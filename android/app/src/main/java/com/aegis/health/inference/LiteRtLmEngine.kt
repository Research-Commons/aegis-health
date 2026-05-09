package com.aegis.health.inference

import android.content.Context
import android.os.Build
import android.util.Log
import com.aegis.health.tools.AegisToolDefs
import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.Contents
import com.google.ai.edge.litertlm.Conversation
import com.google.ai.edge.litertlm.ConversationConfig
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import com.google.ai.edge.litertlm.Message
import com.google.ai.edge.litertlm.MessageCallback
import com.google.ai.edge.litertlm.SamplerConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonPrimitive
import java.io.File
import java.io.FileNotFoundException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Primary on-device inference backend. Runs the Gemma 4 E4B tool-calling
 * LiteRT-LM artifact through LiteRT-LM 0.10.0.
 *
 * Selected by EngineRouter when `aegis_model.litertlm` is sideloaded.
 * Tool calling is manual: the model emits <|tool_call>call:name{args}<tool_call|>
 * blocks and ToolDispatcher parses them. We register OpenApiTool providers
 * with ConversationConfig.tools so the SDK renders the trained
 * `<|tool>declaration:..<tool|>` blocks into the system turn, but we set
 * automaticToolCalling = false so the SDK never invokes them — every dispatch
 * still flows through ToolDispatcher.
 */
object LiteRtLmEngine : InferenceEngine {

    private const val TAG = "LiteRtLmEngine"
    private const val MODEL_FILE = "aegis_model.litertlm"
    // 2048 was the old default; we hit it on the synthesis turn for any DrugSafe
    // case with a multi-flag check_warnings result (system prompt ~1000 + tool
    // declarations + verbose tool_response can already cross 2048 before any
    // output is generated). Gemma 4 E4B trains at 32K and the litert-torch
    // export bundle accepts 4K+; this size only affects KV-cache memory
    // (~8 MB on E4B at 4096), well within Snapdragon 8 Gen 2 headroom.
    private const val MAX_TOKENS = 4096

    // LiteRT-LM 0.10.0 crashes natively on S23/Adreno when max_top_k is 1.
    // Keep topK above 1 and use temperature/topP for a deterministic-as-
    // practical decode path.
    private const val TOP_P = 1.0
    private const val TEMPERATURE = 0.0

    /**
     * Per-device tuning. Resolved once at object init via [detectProfile].
     *
     * Picked at runtime from [Build] flags so a single APK can ship the
     * Snapdragon-tuned config without breaking on emulators or other SoCs.
     * Conservative fallback (`default`) is used when detection cannot place
     * the device in a known profile.
     */
    private data class DeviceProfile(
        val name: String,
        val numThreads: Int,
        val topK: Int,
    )

    private val profile: DeviceProfile = detectProfile()

    private fun detectProfile(): DeviceProfile {
        val isEmulator = Build.HARDWARE.contains("ranchu") ||
            Build.HARDWARE.contains("goldfish") ||
            Build.FINGERPRINT.contains("generic") ||
            Build.MODEL.contains("sdk_gphone")
        if (isEmulator) {
            // Host has 14 physical cores; emulator AVD provisioned with 10
            // homogeneous cores (no big.LITTLE penalty). 8 threads stays
            // below QEMU's overhead ceiling and leaves headroom for the
            // host IDE / browser / gradle daemon. topK 4 cuts per-token
            // sort cost and stays argmax-identical at temp=0.
            return DeviceProfile(name = "emulator", numThreads = 8, topK = 4)
        }

        // Snapdragon 8 Gen 2 (1 X3 + 4 A715 + 3 A510; SM8550, Galaxy S23):
        // 5 threads pins work to 1 big + 4 mid cores and excludes the
        // A510 small cores whose lower throughput makes them stragglers
        // at layer sync points and hurts perf-per-watt under sustained
        // decode. topK 8 reduces per-token sort cost at temp=0 (argmax).
        val socModel = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) Build.SOC_MODEL else ""
        if (socModel == "SM8550") {
            return DeviceProfile(name = "snapdragon_8gen2", numThreads = 5, topK = 8)
        }

        return DeviceProfile(name = "default", numThreads = 4, topK = 40)
    }

    private val mutex = Mutex()

    @Volatile
    private var initialized = false

    private var engine: Engine? = null
    private var conversation: Conversation? = null

    override val isReady: Boolean get() = initialized

    // ── Initialization ──────────────────────────────────────────────────

    override suspend fun initialize(context: Context) = mutex.withLock<Unit> {
        if (initialized) return@withLock

        withContext(Dispatchers.IO) {
            val modelPath = resolveModelPath(context)

            val cfg = EngineConfig(
                modelPath = modelPath,
                // CPU is the validated path for this artifact. GPU was tried
                // 2026-05-02 and produced corrupted tokens (mixed quotes, garbled
                // special tokens, EOS not honored) — Adreno-side FP16 dequant
                // shifts argmax for tokens with near-equal logits.
                backend = Backend.CPU(numOfThreads = profile.numThreads),
                visionBackend = null,
                audioBackend = null,
                maxNumTokens = MAX_TOKENS,
                cacheDir = context.cacheDir.absolutePath,
            )

            val t0 = System.currentTimeMillis()
            val eng = Engine(cfg)
            eng.initialize()
            engine = eng
            initialized = true
            Log.i(TAG, "Engine initialized in ${System.currentTimeMillis() - t0} ms (profile=${profile.name}, CPU x${profile.numThreads} threads, topK=${profile.topK}, ctx=$MAX_TOKENS) from $modelPath")
        }
    }

    // ── Conversation lifecycle ──────────────────────────────────────────

    override suspend fun startConversation(mode: String) = mutex.withLock<Unit> {
        val eng = requireReady()
        withContext(Dispatchers.Default) {
            conversation?.close()
            // Tools are registered with the SDK so the conversation's system
            // turn renders them as `<|tool>declaration:name{...}<tool|>` blocks
            // — the format Gemma 4 was SFT'd on. Without this, the model never
            // sees a tool catalog in the trained syntax and won't emit
            // `<|tool_call>` markers, so ToolDispatcher's regex never matches.
            // automaticToolCalling = false keeps dispatch in ToolDispatcher;
            // OpenApiTool.execute throws if the SDK ever auto-invokes.
            val toolProviders = if (modeUsesTools(mode)) AegisToolDefs.all else emptyList()
            conversation = eng.createConversation(
                ConversationConfig(
                    systemInstruction = Contents.of(SystemPrompts.forMode(mode)),
                    tools = toolProviders,
                    samplerConfig = SamplerConfig(
                        topK = profile.topK,
                        topP = TOP_P,
                        temperature = TEMPERATURE,
                    ),
                    automaticToolCalling = false,
                ),
            )
            Log.d(TAG, "Conversation started (mode=$mode, tools=${toolProviders.size})")
        }
    }

    private fun modeUsesTools(mode: String): Boolean {
        val normalized = mode.lowercase()
        return normalized != "consentreader" && normalized != "consent"
    }

    // ── Inference ───────────────────────────────────────────────────────

    override suspend fun inferSync(
        userTurn: String,
        onPiece: (piece: String, count: Int) -> Unit,
    ): String = BatteryProbe.around(
        label = "inferSync",
        initialMetadata = mapOf("input_chars" to JsonPrimitive(userTurn.length)),
    ) { span ->
        withContext(Dispatchers.Default) {
            val conv = conversation ?: error("Conversation not started — call startConversation() first")
            val sb = StringBuilder()
            val t0 = System.currentTimeMillis()
            val inputChars = userTurn.length
            var tFirstPiece = 0L
            var pieces = 0

            suspendCancellableCoroutine<String> { cont ->
                // Gemma 4 native tool calling stops at <tool_call|>: the
                // chat template expects the host to inject the real
                // <|tool_response>...<tool_response|> immediately after,
                // inside the same model turn (no <turn|> separator). LiteRT-LM
                // doesn't honor that boundary natively, so the model freely
                // hallucinates a tool_response (and often a second fake
                // tool_call after it). We detect the marker in the streamed
                // text and call cancelProcess() ourselves. Without this:
                // - Turn 0 burns ~100s of decode time on hallucinated content
                // - Turn 1's prefill balloons (truncated turn 0 + huge real
                //   tool_response) and exhausts the 2048-token context, so
                //   the synthesis JSON never gets generated.
                val toolCallStopHit = java.util.concurrent.atomic.AtomicBoolean(false)
                val callback = object : MessageCallback {
                    override fun onMessage(message: Message) {
                        if (pieces == 0) {
                            tFirstPiece = System.currentTimeMillis()
                            Log.i(TAG, "prefill complete in ${tFirstPiece - t0} ms (input=$inputChars chars)")
                        }
                        val piece = message.toString()
                        sb.append(piece)
                        pieces++
                        runCatching { onPiece(piece, pieces) }
                            .onFailure { Log.w(TAG, "onPiece callback threw at piece $pieces", it) }
                        if (pieces % 32 == 0) {
                            val decodeSecs = (System.currentTimeMillis() - tFirstPiece) / 1000.0
                            if (decodeSecs > 0) {
                                Log.d(TAG, "decoded $pieces pieces in ${"%.1f".format(decodeSecs)}s decode-only (${"%.1f".format(pieces / decodeSecs)} p/s)")
                            }
                        }
                        // Boundary check after append — see comment at the top
                        // of suspendCancellableCoroutine. Idempotent via the
                        // atomic CAS. cancelProcess() lands in onError or
                        // onDone (SDK choice); both paths resume the coroutine
                        // with sb.toString().
                        if (!toolCallStopHit.get() &&
                            sb.indexOf("<tool_call|>") >= 0 &&
                            toolCallStopHit.compareAndSet(false, true)
                        ) {
                            Log.i(TAG, "Stopping decode at <tool_call|> boundary after $pieces pieces")
                            runCatching { conv.cancelProcess() }
                        }
                    }

                    override fun onDone() {
                        val tDone = System.currentTimeMillis()
                        val totalMs = tDone - t0
                        if (tFirstPiece > 0L) {
                            val prefillMs = tFirstPiece - t0
                            val decodeMs = tDone - tFirstPiece
                            val decodeSecs = decodeMs / 1000.0
                            val tps = if (decodeSecs > 0) pieces / decodeSecs else 0.0
                            val stopNote = if (toolCallStopHit.get()) ", stopped=tool_call" else ""
                            Log.i(
                                TAG,
                                "inferSync done: total=${totalMs}ms (prefill=${prefillMs}ms, decode=${decodeMs}ms, " +
                                    "$pieces pieces @ ${"%.1f".format(tps)} p/s, input=$inputChars chars$stopNote)",
                            )
                            span.put("prefill_ms", prefillMs)
                            span.put("decode_ms", decodeMs)
                        } else {
                            Log.i(TAG, "inferSync done: total=${totalMs}ms (no pieces emitted, input=$inputChars chars)")
                        }
                        span.put("total_ms", totalMs)
                        span.put("pieces", pieces)
                        span.put("stopped_at_tool_call", toolCallStopHit.get())
                        if (cont.isActive) cont.resume(sb.toString())
                    }

                    override fun onError(throwable: Throwable) {
                        // cancelProcess() at the tool_call boundary may surface
                        // here as a "cancelled" error rather than as onDone.
                        // Treat that path as a clean stop and return the
                        // accumulated text — the agent loop downstream parses
                        // it normally.
                        if (toolCallStopHit.get()) {
                            Log.i(TAG, "tool_call cancellation surfaced as error (${throwable.javaClass.simpleName}); returning $pieces pieces")
                            span.put("pieces", pieces)
                            span.put("stopped_at_tool_call", true)
                            if (cont.isActive) cont.resume(sb.toString())
                            return
                        }
                        Log.e(TAG, "inferSync error after $pieces pieces", throwable)
                        span.put("error", throwable.message ?: throwable.javaClass.simpleName)
                        span.put("pieces", pieces)
                        if (cont.isActive) cont.resumeWithException(throwable)
                    }
                }

                cont.invokeOnCancellation {
                    Log.w(TAG, "inferSync cancelled at $pieces pieces — calling cancelProcess()")
                    runCatching { conv.cancelProcess() }
                }

                conv.sendMessageAsync(Contents.of(userTurn), callback)
            }
        }
    }

    // ── Model file resolution ───────────────────────────────────────────

    /**
     * LiteRT-LM reads the .litertlm bundle directly via mmap, so we don't
     * copy from external to internal storage. The bundle is large, and
     * doubling it would waste internal space. We read straight from
     * getExternalFilesDir().
     */
    private fun resolveModelPath(context: Context): String {
        val externalDir = context.getExternalFilesDir(null)
            ?: throw FileNotFoundException("External files dir unavailable")
        val externalModel = File(externalDir, MODEL_FILE)

        if (!externalModel.exists()) {
            throw FileNotFoundException(
                "Model not found at ${externalModel.absolutePath}. " +
                "Download with: huggingface-cli download " +
                "V1rtucious/gemma4-e4b-toolcalling-litertlm-v2 model.litertlm --local-dir ./downloads. " +
                "Then sideload with: adb push ./downloads/model.litertlm " +
                "/sdcard/Android/data/com.aegis.health/files/$MODEL_FILE",
            )
        }

        return externalModel.absolutePath
    }

    private fun requireReady(): Engine {
        check(initialized) { "LiteRtLmEngine not initialized — call initialize() first" }
        return engine!!
    }
}
