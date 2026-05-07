package com.aegis.health.ui.bench

import android.content.Context
import android.util.Log
import com.aegis.health.inference.ToolDispatcher
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File

/**
 * Drives the anchor-case replay loop. State is exposed via a StateFlow for the
 * Compose screen. Loop runs in the caller's coroutine scope so the screen can
 * cancel it.
 *
 * Anchor cases are read from the app's external files dir (push with
 * `adb push eval/eval/anchor_cases.json /sdcard/Android/data/com.aegis.health/files/`).
 * Only id, input, and mode are consumed — the rest of the schema is ignored.
 */
object BatteryBenchViewModel {

    private const val TAG = "BatteryBench"
    private const val ANCHOR_FILE = "anchor_cases.json"

    @Serializable
    private data class AnchorCase(
        val id: String,
        val input: String,
        val mode: String,
    )

    data class BenchState(
        val running: Boolean = false,
        val total: Int = 0,
        val completed: Int = 0,
        val currentCaseId: String? = null,
        val currentMode: String? = null,
        val lastDurationMs: Long? = null,
        val lastError: String? = null,
    )

    private val _state = MutableStateFlow(BenchState())
    val state: StateFlow<BenchState> = _state.asStateFlow()

    private val json = Json { ignoreUnknownKeys = true }

    fun anchorCasesPath(context: Context): String {
        val dir = context.getExternalFilesDir(null) ?: return "(no external storage)"
        return File(dir, ANCHOR_FILE).absolutePath
    }

    fun markStopped() {
        _state.value = _state.value.copy(running = false)
    }

    suspend fun run(context: Context, cooldownSec: Int) {
        val cases = loadAnchorCases(context)
        if (cases.isEmpty()) {
            _state.value = _state.value.copy(
                running = false,
                lastError = "No anchor cases at ${anchorCasesPath(context)}",
            )
            return
        }

        _state.value = BenchState(running = true, total = cases.size, completed = 0)
        try {
            cases.forEachIndexed { idx, case ->
                _state.value = _state.value.copy(
                    currentCaseId = case.id,
                    currentMode = case.mode,
                )
                val t0 = System.currentTimeMillis()
                try {
                    ToolDispatcher.runAgenticLoop(case.input, case.mode)
                } catch (ce: CancellationException) {
                    throw ce
                } catch (t: Throwable) {
                    Log.w(TAG, "Case ${case.id} failed: ${t.message}", t)
                    _state.value = _state.value.copy(lastError = "${case.id}: ${t.message}")
                }
                val durationMs = System.currentTimeMillis() - t0
                _state.value = _state.value.copy(
                    completed = idx + 1,
                    lastDurationMs = durationMs,
                )
                if (idx < cases.size - 1 && cooldownSec > 0) {
                    delay(cooldownSec * 1000L)
                }
            }
        } finally {
            _state.value = _state.value.copy(
                running = false,
                currentCaseId = null,
                currentMode = null,
            )
        }
    }

    private fun loadAnchorCases(context: Context): List<AnchorCase> {
        return try {
            val dir = context.getExternalFilesDir(null) ?: return emptyList()
            val file = File(dir, ANCHOR_FILE)
            if (!file.exists()) return emptyList()
            json.decodeFromString(
                kotlinx.serialization.builtins.ListSerializer(AnchorCase.serializer()),
                file.readText(),
            )
        } catch (t: Throwable) {
            Log.w(TAG, "Failed to load anchor cases", t)
            emptyList()
        }
    }
}
