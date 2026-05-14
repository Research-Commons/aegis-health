package com.aegis.health.inference

import android.content.Context
import android.util.Log
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File
import java.io.FileNotFoundException

/**
 * Thin wrapper that initializes the LiteRT-LM engine at app startup.
 *
 * The user sideloads a single ``aegis_model.litertlm`` file to
 * ``/sdcard/Android/data/com.aegis.health/files/``; this object confirms
 * the file is present and delegates initialization to [LiteRtLmEngine].
 *
 * Kept as a level of indirection so callers (AegisApp, ViewModels) can
 * obtain the active engine via [active] without coupling to a specific
 * runtime implementation.
 */
object EngineRouter {

    private const val TAG = "EngineRouter"
    private const val LITERT_MODEL = "aegis_model.litertlm"

    private val mutex = Mutex()

    @Volatile
    private var selected: InferenceEngine? = null

    /** Lazily-resolved active engine. Throws until [initialize] completes. */
    val active: InferenceEngine
        get() = selected ?: error("EngineRouter not initialized — call initialize() first")

    val isReady: Boolean get() = selected?.isReady == true

    /**
     * Verify the LiteRT-LM model is sideloaded and initialize the engine.
     * Safe to call repeatedly; no-op once the engine is ready.
     */
    suspend fun initialize(context: Context) = mutex.withLock {
        if (selected?.isReady == true) return@withLock

        val externalDir = context.getExternalFilesDir(null)
            ?: throw FileNotFoundException("External files dir unavailable")
        val litertModel = File(externalDir, LITERT_MODEL)
        if (!litertModel.exists()) {
            throw FileNotFoundException(
                "No model file found in ${externalDir.absolutePath}. Sideload with:\n" +
                "  huggingface-cli download V1rtucious/gemma4-e4b-toolcalling-litertlm-v2 " +
                "model.litertlm --local-dir ./downloads\n" +
                "  adb push ./downloads/model.litertlm " +
                "/sdcard/Android/data/com.aegis.health/files/$LITERT_MODEL",
            )
        }
        Log.i(TAG, "Selecting LiteRtLmEngine (${litertModel.length()} bytes at ${litertModel.name})")

        val engine = selected ?: LiteRtLmEngine.also { selected = it }
        engine.initialize(context)
    }

    /**
     * Phase 4 D-07 — belt-and-suspenders cold-start warm-up triggered from the
     * HomeScreen ReportReader tile tap. AegisApp.onCreate already runs an eager
     * pre-warm in the background; this is the second trigger that overlaps with
     * the user's "Pick a lab report PDF" reading + SAF picker interaction window
     * on slow cold starts.
     *
     * Idempotent in two ways:
     *   1. If [initialize] hasn't completed yet (selected == null OR not ready),
     *      this is a no-op — the caller is racing AegisApp startup; bail rather
     *      than throw.
     *   2. If the engine is ready, [LiteRtLmEngine.startConversation] forces the
     *      model pages hot by creating a conversation; the engine's own
     *      mutex.withLock + conversation?.close() guarantees repeated calls are
     *      safe.
     *
     * Mode is "reportreader" so the tool catalog (emptyList() per Wave 1's
     * OpenApiToolDefs change) matches what the actual synthesis turn will use —
     * keeps the prefill cache warm for the exact prompt that will run next.
     *
     * Callers should wrap in runCatching — warmUp is an optimization, not a
     * requirement; a failure here must not crash the UI.
     */
    suspend fun warmUp() {
        if (!isReady) return
        active.startConversation("reportreader", includeTools = false)
    }
}
