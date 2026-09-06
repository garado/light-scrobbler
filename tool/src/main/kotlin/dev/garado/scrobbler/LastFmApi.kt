package dev.garado.scrobbler

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.forms.submitForm
import io.ktor.client.statement.bodyAsText
import io.ktor.http.Parameters
import io.ktor.http.isSuccess
import io.ktor.serialization.kotlinx.json.json
import java.security.MessageDigest
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

private const val LAST_FM_API_BASE = "https://ws.audioscrobbler.com/2.0/"

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
        val params = sortedMapOf(
            "method" to "auth.getMobileSession",
            "api_key" to apiKey,
            "username" to username,
            "password" to password,
        )
        val signature = signParams(params, apiSecret)

        val response = client.submitForm(
            url = LAST_FM_API_BASE,
            formParameters = Parameters.build {
                params.forEach { (key, value) -> append(key, value) }
                append("api_sig", signature)
                append("format", "json")
            },
        )

        val body = response.bodyAsText()
        if (!response.status.isSuccess()) {
            val parsed = runCatching { json.decodeFromString<MobileSessionResponse>(body) }.getOrNull()
            throw LastFmAuthException(parsed?.errorMessage ?: "Login failed (HTTP ${response.status.value}).")
        }

        val parsed = json.decodeFromString<MobileSessionResponse>(body)
        parsed.session ?: throw LastFmAuthException(parsed.errorMessage ?: "Login failed.")
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
