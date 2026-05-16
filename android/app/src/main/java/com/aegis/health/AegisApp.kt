package com.aegis.health

import android.app.Application
import android.util.Log
import com.aegis.health.db.KBDatabase
import com.aegis.health.db.history.HistoryDatabase
import com.aegis.health.inference.EngineRouter
import com.aegis.health.ui.profile.ProfileStore
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
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
        // Phase 2 ReportReader: pdfbox-android resource loader must be initialized
        // ONCE per process before any PDDocument.load() call. LM-1 (Phase 1 commit
        // e099aaf): androidTest @Before separately initializes for the test process;
        // production init lives here. Runs before any other startup work so it is
        // ready for both the eager KB/engine path below and any later PDF picker.
        PDFBoxResourceLoader.init(applicationContext)

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

    /**
     * Phase 4 D-07 — belt-and-suspenders cold-start engine warm-up triggered
     * from HomeScreen's ReportReader tile tap. Idempotent in two ways:
     *   1. EngineRouter.warmUp() bails fast if !isReady (no-op while the
     *      eager AegisApp.onCreate startup is still running).
     *   2. LiteRtLmEngine.startConversation uses a mutex + conversation?.close()
     *      so repeated calls collapse safely.
     *
     * Wrapped in runCatching so a warm-up exception cannot tear down sibling
     * coroutines on the SupervisorJob-scoped appScope.
     *
     * Phase 9 D-05a — relocated from HomeScreen.kt:159-161 to keep ui/home/
     * free of EngineRouter symbols (HOME-05 grep gate; PITFALLS C5). UI
     * screens reach engine state ONLY through AegisApp.instance.startup;
     * direct EngineRouter / KBDatabase / LiteRtLmEngine reads under
     * ui/home/ or ui/startup/ are structurally forbidden by
     * HomeScreenStructureTest.noEngineSymbolsLeakIntoHomeOrStartupModules.
     */
    fun warmUpEngine() {
        appScope.launch(Dispatchers.IO) {
            runCatching { EngineRouter.warmUp() }
        }
    }

    companion object {
        private const val TAG = "AegisApp"

        lateinit var instance: AegisApp
            private set
    }
}
