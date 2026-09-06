package dev.garado.scrobbler

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.lifecycle.viewModelScope
import com.thelightphone.lp3Keyboard.ui.KeyboardOptions
import com.thelightphone.sdk.InitialScreen
import com.thelightphone.sdk.LightQrCodeScanner
import com.thelightphone.sdk.LightScreen
import com.thelightphone.sdk.LightViewModel
import com.thelightphone.sdk.SealedLightActivity
import com.thelightphone.sdk.media.LightMediaSessionAccess
import com.thelightphone.sdk.media.LightNowPlayingTrack
import com.thelightphone.sdk.ui.LightBarButton
import com.thelightphone.sdk.ui.LightBottomBar
import com.thelightphone.sdk.ui.LightIcon
import com.thelightphone.sdk.ui.LightIcons
import com.thelightphone.sdk.ui.LightScrollView
import com.thelightphone.sdk.ui.LightText
import com.thelightphone.sdk.ui.LightTextField
import com.thelightphone.sdk.ui.LightTextInputEditor
import com.thelightphone.sdk.ui.LightTextVariant
import com.thelightphone.sdk.ui.LightTheme
import com.thelightphone.sdk.ui.LightThemeController
import com.thelightphone.sdk.ui.LightThemeTokens
import com.thelightphone.sdk.ui.LightTopBar
import com.thelightphone.sdk.ui.LightTopBarCenter
import com.thelightphone.sdk.ui.gridUnitsAsDp
import com.thelightphone.sdk.ui.lightClickable
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

enum class CredentialField(val label: String, val isSecret: Boolean = false) {
    API_KEY("API Key"),
    API_SECRET("API Secret", isSecret = true),
    USERNAME("Username"),
    PASSWORD("Password", isSecret = true),
}

private val CredentialField.preferenceKey: Preferences.Key<String>
    get() = when (this) {
        CredentialField.API_KEY -> ScrobblerPreferences.API_KEY
        CredentialField.API_SECRET -> ScrobblerPreferences.API_SECRET
        CredentialField.USERNAME -> ScrobblerPreferences.USERNAME
        CredentialField.PASSWORD -> ScrobblerPreferences.PASSWORD
    }

private const val MIN_LISTEN_BEFORE_SCROBBLE_MS = 30_000L

sealed interface LoginState {
    data object LoggedOut : LoginState
    data object LoggingIn : LoginState
    data class LoggedIn(val username: String) : LoginState
    data class Error(val message: String) : LoginState
}

class HomeScreenViewModel(
    private val dataStore: DataStore<Preferences>,
) : LightViewModel<Unit>() {
    val nowPlaying: StateFlow<LightNowPlayingTrack?> = LightMediaSessionAccess.nowPlaying
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    private val api = LastFmApi()

    private val _credentials = MutableStateFlow(CredentialField.entries.associateWith { "" })
    val credentials: StateFlow<Map<CredentialField, String>> = _credentials.asStateFlow()

    private val _loginState = MutableStateFlow<LoginState>(LoginState.LoggedOut)
    val loginState: StateFlow<LoginState> = _loginState.asStateFlow()

    private var sessionKey: String? = null
    private var scrobbledTrack: TrackKey? = null
    private var scrobbleJob: Job? = null

    init {
        viewModelScope.launch(Dispatchers.IO) {
            val prefs = dataStore.data.first()
            _credentials.value = CredentialField.entries.associateWith { field ->
                prefs[field.preferenceKey].orEmpty()
            }
            val storedSessionKey = prefs[ScrobblerPreferences.SESSION_KEY]
            val username = prefs[CredentialField.USERNAME.preferenceKey]
            if (!storedSessionKey.isNullOrEmpty() && !username.isNullOrEmpty()) {
                sessionKey = storedSessionKey
                _loginState.value = LoginState.LoggedIn(username)
            }
        }

        viewModelScope.launch {
            nowPlaying.collect { onNowPlayingChanged(it) }
        }
    }

    private data class TrackKey(val artist: String, val title: String, val album: String?)

    private fun onNowPlayingChanged(track: LightNowPlayingTrack?) {
        val key = track?.takeIf { it.isPlaying }
            ?.let { t ->
                val artist = t.artist?.takeIf { it.isNotBlank() }
                val title = t.title?.takeIf { it.isNotBlank() }
                if (artist == null || title == null) null else TrackKey(artist, title, t.album)
            }

        if (key == scrobbledTrack) return
        scrobbleJob?.cancel()
        scrobbledTrack = key
        if (key == null) return

        val sk = sessionKey ?: return
        val apiKey = _credentials.value[CredentialField.API_KEY].orEmpty()
        val apiSecret = _credentials.value[CredentialField.API_SECRET].orEmpty()
        if (apiKey.isEmpty() || apiSecret.isEmpty()) return

        val startedAtEpochSeconds = System.currentTimeMillis() / 1000
        scrobbleJob = viewModelScope.launch(Dispatchers.IO) {
            api.updateNowPlaying(apiKey, apiSecret, sk, key.artist, key.title, key.album)

            // Last.fm requires the client to know track duration to apply the real
            // scrobble rule (50% played or 4 minutes, whichever is less). The SDK's
            // now-playing metadata doesn't expose duration, so approximate with a
            // fixed minimum listen time instead.
            delay(MIN_LISTEN_BEFORE_SCROBBLE_MS)
            api.scrobble(apiKey, apiSecret, sk, key.artist, key.title, key.album, startedAtEpochSeconds)
        }
    }

    override fun onCleared() {
        super.onCleared()
        api.close()
    }

    private val _editingField = MutableStateFlow<CredentialField?>(null)
    val editingField: StateFlow<CredentialField?> = _editingField.asStateFlow()

    private val _isScanning = MutableStateFlow(false)
    val isScanning: StateFlow<Boolean> = _isScanning.asStateFlow()

    fun startScan() {
        _isScanning.value = true
    }

    fun cancelScan() {
        _isScanning.value = false
    }

    fun onScanned(value: String) {
        submitEdit(value)
        _isScanning.value = false
    }

    // LightTextInputEditor caches its embedded keyboard's ViewModel by editorKey;
    // bump this each time editing starts so a stale keyboard/TextFieldState pairing
    // from a previous session isn't reused
    private val _editSessionId = MutableStateFlow(0)
    val editSessionId: StateFlow<Int> = _editSessionId.asStateFlow()

    fun startEditing(field: CredentialField) {
        _editSessionId.value += 1
        _editingField.value = field
    }

    fun submitEdit(value: CharSequence) {
        val field = _editingField.value ?: return
        val text = value.toString()
        _credentials.value = _credentials.value + (field to text)
        _editingField.value = null
        viewModelScope.launch(Dispatchers.IO) {
            dataStore.edit { prefs -> prefs[field.preferenceKey] = text }
        }
    }

    fun cancelEditing() {
        _editingField.value = null
    }

    fun logIn() {
        val creds = _credentials.value
        val apiKey = creds[CredentialField.API_KEY].orEmpty()
        val apiSecret = creds[CredentialField.API_SECRET].orEmpty()
        val username = creds[CredentialField.USERNAME].orEmpty()
        val password = creds[CredentialField.PASSWORD].orEmpty()

        if (apiKey.isEmpty() || apiSecret.isEmpty() || username.isEmpty() || password.isEmpty()) {
            _loginState.value = LoginState.Error("Fill in all fields before logging in.")
            return
        }

        _loginState.value = LoginState.LoggingIn
        viewModelScope.launch(Dispatchers.IO) {
            api.getMobileSession(apiKey, apiSecret, username, password).fold(
                onSuccess = { session ->
                    dataStore.edit { prefs -> prefs[ScrobblerPreferences.SESSION_KEY] = session.key }
                    sessionKey = session.key
                    _loginState.value = LoginState.LoggedIn(session.name)
                },
                onFailure = { error ->
                    _loginState.value = LoginState.Error(error.message ?: "Login failed.")
                },
            )
        }
    }

    fun logOut() {
        sessionKey = null
        scrobbleJob?.cancel()
        scrobbledTrack = null
        _loginState.value = LoginState.LoggedOut
        viewModelScope.launch(Dispatchers.IO) {
            dataStore.edit { prefs -> prefs.remove(ScrobblerPreferences.SESSION_KEY) }
        }
    }
}

@InitialScreen
class HomeScreen(sealedActivity: SealedLightActivity) : LightScreen<Unit, HomeScreenViewModel>(sealedActivity) {

    override val viewModelClass: Class<HomeScreenViewModel>
        get() = HomeScreenViewModel::class.java

    override fun createViewModel(): HomeScreenViewModel {
        return HomeScreenViewModel(lightContext.dataStore)
    }

    private var accessGranted by mutableStateOf(LightMediaSessionAccess.isGranted(lightContext))

    override fun willShow() {
        super.willShow()
        accessGranted = LightMediaSessionAccess.isGranted(lightContext)
    }

    @Composable
    override fun Content() {
        val nowPlaying by viewModel.nowPlaying.collectAsState()
        val themeColors by LightThemeController.colors.collectAsState()
        val credentials by viewModel.credentials.collectAsState()
        val editingField by viewModel.editingField.collectAsState()
        val editSessionId by viewModel.editSessionId.collectAsState()
        val isScanning by viewModel.isScanning.collectAsState()
        val loginState by viewModel.loginState.collectAsState()

        LightTheme(colors = themeColors) {
            val fieldBeingEdited = editingField
            if (isScanning) {
                LightQrCodeScanner(
                    title = "Scan ${fieldBeingEdited?.label ?: "Code"}",
                    onScanned = { viewModel.onScanned(it) },
                    onBack = { viewModel.cancelScan() },
                )
            } else if (fieldBeingEdited != null) {
                val fieldState = rememberTextFieldState(credentials[fieldBeingEdited].orEmpty())
                val keyboardOptionsFlow = remember {
                    MutableStateFlow(
                        KeyboardOptions(emptyList(), true, false, true, swipeEnabled = false)
                    )
                }

                Box(modifier = Modifier.fillMaxSize()) {
                    LightTextInputEditor(
                        title = fieldBeingEdited.label,
                        state = fieldState,
                        onSubmit = { viewModel.submitEdit(it) },
                        onBack = { viewModel.cancelEditing() },
                        keyboardOptionsFlow = keyboardOptionsFlow,
                        singleLine = true,
                        editorKey = editSessionId,
                    )

                    if (fieldBeingEdited == CredentialField.API_KEY || fieldBeingEdited == CredentialField.API_SECRET) {
                        LightIcon(
                            icon = LightIcons.CAMERA,
                            contentDescription = "Scan ${fieldBeingEdited.label} QR code",
                            modifier = Modifier
                                .align(Alignment.TopEnd)
                                .padding(top = 0.5f.gridUnitsAsDp(), end = 1f.gridUnitsAsDp())
                                .lightClickable(onClick = { viewModel.startScan() }),
                        )
                    }
                }
            } else {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(LightThemeTokens.colors.background),
                ) {
                    LightTopBar(center = LightTopBarCenter.Text("Scrobbler"))

                    LightScrollView(
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f)
                            .padding(horizontal = 16.dp),
                    ) {
                        LightText(
                            text = "Now playing",
                            variant = LightTextVariant.Detail,
                        )
                        LightText(
                            text = nowPlaying?.let { "${it.artist} - ${it.title}" } ?: "No track detected",
                            variant = LightTextVariant.Copy,
                            modifier = Modifier.padding(top = 2.dp, bottom = 16.dp),
                            maxLines = 2,
                        )

                        LightText(
                            text = "Media access",
                            variant = LightTextVariant.Detail,
                        )
                        LightText(
                            text = if (accessGranted) "Enabled" else "Disabled",
                            variant = LightTextVariant.Copy,
                            modifier = Modifier
                                .padding(top = 2.dp, bottom = 16.dp)
                                .lightClickable(onClick = { LightMediaSessionAccess.requestAccess(lightContext) }),
                        )

                        val loggedInState = loginState as? LoginState.LoggedIn
                        if (loggedInState != null) {
                            LightText(
                                text = "Logged in as",
                                variant = LightTextVariant.Detail,
                            )
                            LightText(
                                text = loggedInState.username,
                                variant = LightTextVariant.Copy,
                                modifier = Modifier.padding(top = 2.dp, bottom = 16.dp),
                            )
                        } else {
                            CredentialField.entries.forEach { field ->
                                val value = credentials[field].orEmpty()
                                LightTextField(
                                    label = field.label,
                                    value = if (field.isSecret) "•".repeat(value.length) else value,
                                    placeholder = "Enter ${field.label.lowercase()}",
                                    onClick = { viewModel.startEditing(field) },
                                    modifier = Modifier.padding(bottom = 16.dp),
                                )
                            }

                            val errorState = loginState as? LoginState.Error
                            if (errorState != null) {
                                LightText(
                                    text = errorState.message,
                                    variant = LightTextVariant.Copy,
                                )
                            }
                        }
                    }

                    LightBottomBar(
                        items = listOf(
                            LightBarButton.Text(
                                text = when (loginState) {
                                    is LoginState.LoggedIn -> "LOG OUT"
                                    is LoginState.LoggingIn -> "LOGGING IN..."
                                    else -> "LOG IN"
                                },
                                onClick = when (loginState) {
                                    is LoginState.LoggedIn -> ({ viewModel.logOut() })
                                    is LoginState.LoggingIn -> null
                                    else -> ({ viewModel.logIn() })
                                },
                            ),
                        ),
                    )
                }
            }
        }
    }
}
