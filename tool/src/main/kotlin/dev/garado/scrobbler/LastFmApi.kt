package dev.garado.scrobbler

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.forms.submitForm
import io.ktor.client.request.get
import io.ktor.client.request.parameter
import io.ktor.client.statement.bodyAsText
import io.ktor.http.Parameters
import io.ktor.http.isSuccess
import io.ktor.serialization.kotlinx.json.json
import java.security.MessageDigest
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

private const val LAST_FM_API_BASE = "https://ws.audioscrobbler.com/2.0/"
private const val LAST_FM_ERROR_TRACK_NOT_FOUND = 6

@Serializable
internal data class MobileSessionResponse(
    val session: MobileSession? = null,
    @SerialName("error") val errorCode: Int? = null,
    @SerialName("message") val errorMessage: String? = null,
)

@Serializable
internal data class MobileSession(
    val name: String,
    val key: String,
)

@Serializable
internal data class LastFmErrorResponse(
    @SerialName("error") val errorCode: Int? = null,
    @SerialName("message") val errorMessage: String? = null,
)

internal class LastFmAuthException(message: String) : Exception(message)

internal class LastFmApi {
    private val json = Json { ignoreUnknownKeys = true }

    private val client = HttpClient(OkHttp) {
        install(ContentNegotiation) {
            json(json)
        }
    }

    suspend fun getMobileSession(
        apiKey: String,
        apiSecret: String,
        username: String,
        password: String,
    ): Result<MobileSession> = runCatching {
        val body = call(
            method = "auth.getMobileSession",
            apiKey = apiKey,
            apiSecret = apiSecret,
            params = mapOf("username" to username, "password" to password),
        )
        val parsed = json.decodeFromString<MobileSessionResponse>(body)
        parsed.session ?: throw LastFmAuthException(parsed.errorMessage ?: "Login failed.")
    }

    suspend fun updateNowPlaying(
        apiKey: String,
        apiSecret: String,
        sessionKey: String,
        artist: String,
        track: String,
        album: String?,
    ): Result<Unit> = runCatching {
        call(
            method = "track.updateNowPlaying",
            apiKey = apiKey,
            apiSecret = apiSecret,
            params = buildMap {
                put("sk", sessionKey)
                put("artist", artist)
                put("track", track)
                if (!album.isNullOrBlank()) put("album", album)
            },
        )
        Unit
    }

    suspend fun scrobble(
        apiKey: String,
        apiSecret: String,
        sessionKey: String,
        artist: String,
        track: String,
        album: String?,
        timestampEpochSeconds: Long,
    ): Result<Unit> = runCatching {
        call(
            method = "track.scrobble",
            apiKey = apiKey,
            apiSecret = apiSecret,
            params = buildMap {
                put("sk", sessionKey)
                put("artist[0]", artist)
                put("track[0]", track)
                put("timestamp[0]", timestampEpochSeconds.toString())
                if (!album.isNullOrBlank()) put("album[0]", album)
            },
        )
        Unit
    }

    /** Whether Last.fm recognizes this artist/track pair. */
    suspend fun trackExists(apiKey: String, artist: String, track: String): Boolean {
        return runCatching {
            val response = client.get(LAST_FM_API_BASE) {
                parameter("method", "track.getInfo")
                parameter("api_key", apiKey)
                parameter("artist", artist)
                parameter("track", track)
                parameter("format", "json")
            }
            if (response.status.isSuccess()) return@runCatching true
            val parsed = json.decodeFromString<LastFmErrorResponse>(response.bodyAsText())
            parsed.errorCode != LAST_FM_ERROR_TRACK_NOT_FOUND
        }.getOrDefault(true)
    }

    private suspend fun call(
        method: String,
        apiKey: String,
        apiSecret: String,
        params: Map<String, String>,
    ): String {
        val allParams = params + mapOf("method" to method, "api_key" to apiKey)
        val signature = signParams(allParams, apiSecret)

        val response = client.submitForm(
            url = LAST_FM_API_BASE,
            formParameters = Parameters.build {
                allParams.forEach { (key, value) -> append(key, value) }
                append("api_sig", signature)
                append("format", "json")
            },
        )

        val body = response.bodyAsText()
        if (!response.status.isSuccess()) {
            val parsed = runCatching { json.decodeFromString<LastFmErrorResponse>(body) }.getOrNull()
            throw LastFmAuthException(
                parsed?.errorMessage ?: "$method failed (HTTP ${response.status.value}).",
            )
        }
        return body
    }

    fun close() {
        client.close()
    }
}

// Last.fm requires params (excluding format/callback) sorted by key, concatenated as
// key+value with no separators, suffixed with the shared secret, then MD5-hashed.
private fun signParams(params: Map<String, String>, secret: String): String {
    val raw = buildString {
        params.toSortedMap().forEach { (key, value) -> append(key).append(value) }
        append(secret)
    }
    val digest = MessageDigest.getInstance("MD5").digest(raw.toByteArray(Charsets.UTF_8))
    return digest.joinToString("") { "%02x".format(it) }
}
