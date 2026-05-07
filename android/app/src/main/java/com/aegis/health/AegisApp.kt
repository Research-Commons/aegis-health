package com.aegis.health

import android.app.Application
import android.util.Log
import com.aegis.health.db.KBDatabase
import com.aegis.health.db.history.HistoryDatabase
import com.aegis.health.inference.EngineRouter
import com.aegis.health.ui.profile.ProfileStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Startup state machine. Observed by MainActivity to show a loading,
 * ready, or error screen instead of crashing when the model isn't
 * sideloaded or the KB copy fails.
 */
sealed interface StartupState {
    data object Initializing : StartupState
    data object Ready : StartupState
    data class Failed(val message: String, val cause: Throwable) : StartupState
}

class AegisApp : Application() {

    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    lateinit var database: KBDatabase
        private set

    lateinit var historyDb: HistoryDatabase
        private set

    private val _startup = MutableStateFlow<StartupState>(StartupState.Initializing)
    val startup: StateFlow<StartupState> = _startup.asStateFlow()

    override fun onCreate() {
        super.onCreate()
        instance = this

        database = KBDatabase(this)
        historyDb = HistoryDatabase.build(this)
        ProfileStore.init(this)

        appScope.launch {
            try {
                database.ensureCopied()
                EngineRouter.initialize(this@AegisApp)
                _startup.value = StartupState.Ready
                Log.i(TAG, "Aegis startup complete: engine ready")
            } catch (t: Throwable) {
                Log.e(TAG, "Aegis startup failed", t)
                _startup.value = StartupState.Failed(
                    message = t.message ?: "Startup failed",
                    cause = t,
                )
            }
        }
    }

    companion object {
        private const val TAG = "AegisApp"

        lateinit var instance: AegisApp
            private set
    }
}
