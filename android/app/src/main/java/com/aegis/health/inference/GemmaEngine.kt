package com.aegis.health.inference

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json

/**
 * Singleton wrapper around LiteRT-LM for on-device Gemma 4 inference.
 *
 * The actual LiteRT-LM API surface (LlmInference, LlmInference.Options, etc.)
 * is stubbed behind a thin abstraction so the rest of the app doesn't depend on
 * the exact SDK revision. Replace the TODO blocks with real API calls once the
 * LiteRT-LM SDK is integrated.
 */
object GemmaEngine {

    private const val TAG = "GemmaEngine"
    private const val MODEL_ASSET = "aegis_model.task"

    private val mutex = Mutex()

    @Volatile
    private var initialized = false

    // Placeholder for the LiteRT-LM inference handle
    private var inferenceHandle: Any? = null

    private val json = Json { ignoreUnknownKeys = true }

    private val toolDefinitionsJson: String by lazy { loadToolDefinitions() }

    // ── Initialization ──────────────────────────────────────────────────

    suspend fun initialize(context: Context) = mutex.withLock {
        if (initialized) return@withLock

        withContext(Dispatchers.IO) {
            try {
                val modelPath = context.getFileStreamPath(MODEL_ASSET)

                if (!modelPath.exists()) {
                    context.assets.open(MODEL_ASSET).use { input ->
                        context.openFileOutput(MODEL_ASSET, Context.MODE_PRIVATE).use { output ->
                            input.copyTo(output)
                        }
                    }
                }

                // TODO: Replace with real LiteRT-LM initialization:
                //   val options = LlmInference.Options.builder()
                //       .setModelPath(modelPath.absolutePath)
                //       .setMaxTokens(2048)
                //       .setTemperature(0.2f)
                //       .setTopK(40)
                //       .setRandomSeed(42)
                //       .build()
                //   inferenceHandle = LlmInference.createFromOptions(context, options)
                inferenceHandle = modelPath.absolutePath

                Log.i(TAG, "Model loaded from ${modelPath.absolutePath}")
                initialized = true
            } catch (e: Exception) {
                Log.e(TAG, "Model initialization failed", e)
                throw e
            }
        }
    }

    val isReady: Boolean get() = initialized

    // ── Prompt construction ─────────────────────────────────────────────

    fun buildPrompt(userMessage: String, mode: String): String = buildString {
        append("<start_of_turn>user\n")
        append("[System] You are Aegis Health, an offline medical safety assistant running locally on ")
        append("the user's device. You have NO internet access. You must use your available tools ")
        append("to look up factual information from the local knowledge base. Never fabricate drug ")
        append("information, interactions, or medical advice. If uncertain, set defer_to_professional ")
        append("to true.\n\n")
        append("Mode: $mode\n")
        append("Available tools: $toolDefinitionsJson\n\n")
        append(userMessage)
        append("<end_of_turn>\n")
        append("<start_of_turn>model\n")
    }

    fun appendToolResult(currentPrompt: String, toolResult: String): String = buildString {
        append(currentPrompt)
        append("<end_of_turn>\n")
        append("<start_of_turn>user\n")
        append(toolResult)
        append("<end_of_turn>\n")
        append("<start_of_turn>model\n")
    }

    // ── Inference ───────────────────────────────────────────────────────

    /**
     * Streaming inference. Emits partial tokens as they are generated.
     */
    fun infer(prompt: String): Flow<String> = flow {
        mutex.withLock {
            check(initialized) { "GemmaEngine not initialized — call initialize() first" }

            // TODO: Replace with real LiteRT-LM streaming API:
            //   inferenceHandle.generateResponseAsync(prompt) { partial ->
            //       emit(partial)
            //   }
            //
            // Stub: emit the full response at once
            val response = inferSync(prompt)
            emit(response)
        }
    }.flowOn(Dispatchers.Default)

    /**
     * Blocking inference. Returns the complete model output.
     */
    suspend fun inferSync(prompt: String): String = mutex.withLock {
        check(initialized) { "GemmaEngine not initialized — call initialize() first" }

        return@withLock withContext(Dispatchers.Default) {
            // TODO: Replace with real LiteRT-LM inference:
            //   inferenceHandle.generateResponse(prompt)
            //
            // Stub response for compilation
            "<tool_call>{\"name\": \"check_warnings\", \"arguments\": {}}</tool_call>"
        }
    }

    // ── Helpers ─────────────────────────────────────────────────────────

    private fun loadToolDefinitions(): String {
        return """
        [
          {"type":"function","function":{"name":"normalize_drug","description":"Resolve a drug name to its canonical generic name, RxCUI, and category.","parameters":{"type":"object","properties":{"name":{"type":"string"}},"required":["name"]}}},
          {"type":"function","function":{"name":"decompose_product","description":"Break a combination product into its individual active ingredients.","parameters":{"type":"object","properties":{"product_name":{"type":"string"}},"required":["product_name"]}}},
          {"type":"function","function":{"name":"check_warnings","description":"Analyse drugs for interactions, contraindications, and population risks.","parameters":{"type":"object","properties":{"drug_list":{"type":"array","items":{"type":"string"}},"age":{"type":"integer"},"conditions":{"type":"array","items":{"type":"string"}}},"required":["drug_list"]}}},
          {"type":"function","function":{"name":"lookup_term","description":"Look up a medical term and return a plain-language definition.","parameters":{"type":"object","properties":{"term":{"type":"string"}},"required":["term"]}}},
          {"type":"function","function":{"name":"get_guideline","description":"Retrieve USPSTF preventive-care recommendations for a patient profile.","parameters":{"type":"object","properties":{"age":{"type":"integer"},"sex":{"type":"string"},"conditions":{"type":"array","items":{"type":"string"}}},"required":["age","sex"]}}}
        ]
        """.trimIndent()
    }
}
