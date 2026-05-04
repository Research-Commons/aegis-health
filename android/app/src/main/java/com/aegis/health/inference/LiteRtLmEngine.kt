package com.aegis.health.inference

import android.content.Context
import android.util.Log
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
 * blocks and ToolDispatcher parses them — we do NOT register @Tool handlers
 * with ConversationConfig.tools, so automatic tool calling never fires.
 */
object LiteRtLmEngine : InferenceEngine {

    private const val TAG = "LiteRtLmEngine"
    private const val MODEL_FILE = "aegis_model.litertlm"
    private const val MAX_TOKENS = 2048

    // Snapdragon 8 Gen 2: 1 X3 + 4 A715 + 3 A510. 6 threads pins work to
    // the 1 big + 4 mid cores and one A510. 8 would spill more onto the
    // slower A510 small cores; 4 leaves big+mid headroom on the table.
    // Tune here when measuring with the prefill/decode timing log line.
    private const val NUM_THREADS = 6

    // LiteRT-LM 0.10.0 crashes natively on S23/Adreno when max_top_k is 1.
    // Keep topK at the known-stable value and use temperature/topP for a
    // deterministic-as-practical decode path.
    private const val TOP_K = 40
    private const val TOP_P = 1.0
    private const val TEMPERATURE = 0.0

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
                backend = Backend.CPU(numOfThreads = NUM_THREADS),
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
            Log.i(TAG, "Engine initialized in ${System.currentTimeMillis() - t0} ms (CPU x$NUM_THREADS threads, ctx=$MAX_TOKENS) from $modelPath")
        }
    }

    // ── Conversation lifecycle ──────────────────────────────────────────

    override suspend fun startConversation(mode: String) = mutex.withLock<Unit> {
        val eng = requireReady()
        withContext(Dispatchers.Default) {
            conversation?.close()
            conversation = eng.createConversation(
                ConversationConfig(
                    systemInstruction = Contents.of(SystemPrompts.forMode(mode)),
                    samplerConfig = SamplerConfig(
                        topK = TOP_K,
                        topP = TOP_P,
                        temperature = TEMPERATURE,
                    ),
                ),
            )
            Log.d(TAG, "Conversation started (mode=$mode)")
        }
    }

    // ── Inference ───────────────────────────────────────────────────────

    override suspend fun inferSync(userTurn: String): String = withContext(Dispatchers.Default) {
        val conv = conversation ?: error("Conversation not started — call startConversation() first")
        val sb = StringBuilder()
        val t0 = System.currentTimeMillis()
        val inputChars = userTurn.length
        var tFirstPiece = 0L
        var pieces = 0

        suspendCancellableCoroutine<String> { cont ->
            val callback = object : MessageCallback {
                override fun onMessage(message: Message) {
                    if (pieces == 0) {
                        tFirstPiece = System.currentTimeMillis()
                        Log.i(TAG, "prefill complete in ${tFirstPiece - t0} ms (input=$inputChars chars)")
                    }
                    sb.append(message.toString())
                    pieces++
                    if (pieces % 32 == 0) {
                        val decodeSecs = (System.currentTimeMillis() - tFirstPiece) / 1000.0
                        if (decodeSecs > 0) {
                            Log.d(TAG, "decoded $pieces pieces in ${"%.1f".format(decodeSecs)}s decode-only (${"%.1f".format(pieces / decodeSecs)} p/s)")
                        }
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
                        Log.i(
                            TAG,
                            "inferSync done: total=${totalMs}ms (prefill=${prefillMs}ms, decode=${decodeMs}ms, " +
                                "$pieces pieces @ ${"%.1f".format(tps)} p/s, input=$inputChars chars)",
                        )
                    } else {
                        Log.i(TAG, "inferSync done: total=${totalMs}ms (no pieces emitted, input=$inputChars chars)")
                    }
                    if (cont.isActive) cont.resume(sb.toString())
                }

                override fun onError(throwable: Throwable) {
                    Log.e(TAG, "inferSync error after $pieces pieces", throwable)
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
