package dev.garado.scrobbler

import androidx.datastore.preferences.core.stringPreferencesKey

internal object ScrobblerPreferences {
    val API_KEY = stringPreferencesKey("api_key")
    val API_SECRET = stringPreferencesKey("api_secret")
    val USERNAME = stringPreferencesKey("username")
    val PASSWORD = stringPreferencesKey("password")
    val SESSION_KEY = stringPreferencesKey("session_key")
}
