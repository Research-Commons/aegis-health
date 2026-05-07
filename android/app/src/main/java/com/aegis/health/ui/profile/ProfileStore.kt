package com.aegis.health.ui.profile

import android.content.Context
import com.aegis.health.models.HealthProfile
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.SerializationException
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * Single source of truth for the on-device health profile.
 * Disk-backed via private SharedPreferences; in-memory state via StateFlow so
 * Compose screens recompose automatically on save/delete.
 *
 * AegisApp.onCreate() calls [init] once at app start.
 */
object ProfileStore {
    private const val PREFS = "aegis_profile"
    private const val KEY = "health_profile"

    private val _state = MutableStateFlow<HealthProfile?>(null)
    val state: StateFlow<HealthProfile?> = _state.asStateFlow()

    fun init(context: Context) {
        _state.value = readFromPrefs(context.applicationContext)
    }

    fun current(): HealthProfile? = _state.value

    fun save(context: Context, profile: HealthProfile) {
        writeToPrefs(context.applicationContext, profile)
        _state.value = profile
    }

    fun delete(context: Context) {
        context.applicationContext
            .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .remove(KEY)
            .apply()
        _state.value = null
    }

    private fun readFromPrefs(context: Context): HealthProfile? {
        val raw = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY, null) ?: return null
        return try {
            Json.decodeFromString<HealthProfile>(raw)
        } catch (_: SerializationException) {
            null
        }
    }

    private fun writeToPrefs(context: Context, profile: HealthProfile) {
        val json = Json.encodeToString(profile)
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY, json)
            .apply()
    }
}
