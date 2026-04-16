package com.aegis.health.inference

import android.util.Log
import com.aegis.health.AegisApp
import com.aegis.health.models.AegisResponse
import com.aegis.health.models.ToolCall
import com.aegis.health.models.ToolResult
import com.aegis.health.tools.CheckWarnings
import com.aegis.health.tools.DecomposeProduct
import com.aegis.health.tools.GetGuideline
import com.aegis.health.tools.LookupTerm
import com.aegis.health.tools.NormalizeDrug
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.int
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive

/**
 * Parses `<tool_call>` blocks from model output and routes them
 * to the corresponding Kotlin tool implementation. Manages the
 * agentic loop: model → tool_call → tool_result → model → … → final response.
 */
object ToolDispatcher {

    private const val TAG = "ToolDispatcher"
    private const val MAX_TURNS = 6

    private val TOOL_CALL_REGEX = Regex("""<tool_call>(.*?)</tool_call>""", RegexOption.DOT_MATCHES_ALL)

    private val json = Json { ignoreUnknownKeys = true }

    // ── Single dispatch ─────────────────────────────────────────────────

    fun dispatch(modelOutput: String): ToolResult? {
        val match = TOOL_CALL_REGEX.find(modelOutput) ?: return null
        val callJson = match.groupValues[1].trim()

        return try {
            val toolCall = json.decodeFromString<ToolCall>(callJson)
            val result = executeToolCall(toolCall)
            ToolResult(name = toolCall.name, result = result)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse or execute tool_call: $callJson", e)
            ToolResult(name = "error", result = """{"error": "${e.message}"}""")
        }
    }

    // ── Agentic loop ────────────────────────────────────────────────────

    /**
     * Runs the full agentic loop for a user query. The model may invoke
     * multiple tools in sequence before producing a final response.
     */
    suspend fun runAgenticLoop(userInput: String, mode: String): AegisResponse {
        var prompt = GemmaEngine.buildPrompt(userInput, mode)

        repeat(MAX_TURNS) { turn ->
            val modelOutput = GemmaEngine.inferSync(prompt)
            Log.d(TAG, "Turn $turn model output: ${modelOutput.take(200)}…")

            val toolResult = dispatch(modelOutput)
                ?: return parseAegisResponse(modelOutput)

            val resultEnvelope = """<tool_result>{"name": "${toolResult.name}", "result": ${toolResult.result}}</tool_result>"""
            prompt = GemmaEngine.appendToolResult(prompt + modelOutput, resultEnvelope)
        }

        return AegisResponse(
            defer_to_professional = true,
            confidence = 0.0,
            explanation = "Maximum tool-call turns exceeded. Please consult a healthcare professional.",
        )
    }

    // ── Tool routing ────────────────────────────────────────────────────

    private fun executeToolCall(toolCall: ToolCall): String {
        val db = AegisApp.instance.database
        val args = toolCall.arguments

        return when (toolCall.name) {
            "normalize_drug" -> {
                val name = args["name"]?.jsonPrimitive?.content ?: ""
                json.encodeToString(NormalizeDrug.normalize(name, db))
            }

            "decompose_product" -> {
                val productName = args["product_name"]?.jsonPrimitive?.content ?: ""
                json.encodeToString(DecomposeProduct.decompose(productName, db))
            }

            "check_warnings" -> {
                val drugList = args["drug_list"]?.jsonArray
                    ?.map { it.jsonPrimitive.content }
                    ?: emptyList()
                val age = args["age"]?.jsonPrimitive?.intOrNull
                val conditions = (args["conditions"] as? JsonArray)
                    ?.map { it.jsonPrimitive.content }
                json.encodeToString(CheckWarnings.check(drugList, age, conditions, db))
            }

            "lookup_term" -> {
                val term = args["term"]?.jsonPrimitive?.content ?: ""
                json.encodeToString(LookupTerm.lookup(term, db))
            }

            "get_guideline" -> {
                val age = args["age"]?.jsonPrimitive?.int ?: 0
                val sex = args["sex"]?.jsonPrimitive?.content ?: ""
                val conditions = (args["conditions"] as? JsonArray)
                    ?.map { it.jsonPrimitive.content }
                json.encodeToString(GetGuideline.getGuidelines(age, sex, conditions, db))
            }

            else -> """{"error": "Unknown tool: ${toolCall.name}"}"""
        }
    }

    // ── Response parsing ────────────────────────────────────────────────

    private fun parseAegisResponse(modelOutput: String): AegisResponse {
        // Try to find JSON in the model output
        val jsonStart = modelOutput.indexOf('{')
        val jsonEnd = modelOutput.lastIndexOf('}')
        if (jsonStart >= 0 && jsonEnd > jsonStart) {
            try {
                val jsonStr = modelOutput.substring(jsonStart, jsonEnd + 1)
                return json.decodeFromString<AegisResponse>(jsonStr)
            } catch (e: Exception) {
                Log.w(TAG, "Could not parse AegisResponse from model output", e)
            }
        }

        return AegisResponse(
            explanation = modelOutput.trim(),
            confidence = 0.5,
        )
    }
}
