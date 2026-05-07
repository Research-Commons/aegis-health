package com.aegis.health.inference

import android.util.Log
import com.aegis.health.AegisApp
import com.aegis.health.models.AegisResponse
import com.aegis.health.models.Citation
import com.aegis.health.models.ToolCall
import com.aegis.health.models.ToolResult
import com.aegis.health.tools.CheckWarnings
import com.aegis.health.tools.DecomposeProduct
import com.aegis.health.tools.GetDrugInfo
import com.aegis.health.tools.GetGuideline
import com.aegis.health.tools.LookupTerm
import com.aegis.health.tools.NormalizeDrug
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.int
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Parses Gemma 4 native `<|tool_call>call:name{args}<tool_call|>` blocks,
 * dispatches real Kotlin tool results, and normalizes the model's final
 * AegisResponse JSON for the Android UI.
 */
object ToolDispatcher {

    private const val TAG = "ToolDispatcher"
    private const val MAX_TURNS = 6
    private const val TURN_CLOSE = "<turn|>\n"
    private const val TURN_OPEN_MODEL = "<|turn>model\n"

    private val NATIVE_TOOL_CALL_REGEX = Regex(
        """<\|tool_call>\s*call:\s*(\w+)\s*\{(.*?)\}\s*<tool_call\|>""",
        RegexOption.DOT_MATCHES_ALL,
    )
    private val LEADING_TOOL_RESPONSE_REGEX = Regex(
        """^\s*(?:<\|tool_response>.*?<tool_response\|>\s*)+""",
        RegexOption.DOT_MATCHES_ALL,
    )
    private val TRAILING_MARKERS_REGEX = Regex("""(?:\s*(?:<turn\|>|<eos>))+\s*$""")
    private val LEADING_MODEL_TURN_REGEX = Regex("""^\s*<\|turn>model\s*\n?""")

    private val json = Json { ignoreUnknownKeys = true }

    private data class NativeToolCall(
        val toolCall: ToolCall,
        val matchEnd: Int,
        val rawArgs: String,
    )

    fun dispatch(modelOutput: String): ToolResult? {
        val call = extractNativeToolCalls(modelOutput).firstOrNull() ?: return null
        return dispatchToolCall(call.toolCall, call.rawArgs)
    }

    private fun extractNativeToolCalls(modelOutput: String): List<NativeToolCall> {
        return NATIVE_TOOL_CALL_REGEX.findAll(modelOutput)
            .map { match ->
                val funcName = match.groupValues[1]
                val argsRaw = match.groupValues[2]
                NativeToolCall(
                    toolCall = ToolCall(
                        name = funcName,
                        arguments = parseNativeArgs(argsRaw),
                    ),
                    matchEnd = match.range.last + 1,
                    rawArgs = argsRaw,
                )
            }
            .toList()
    }

    private fun parseNativeArgs(raw: String): Map<String, JsonElement> {
        val s = raw.replace("<|\"|>", "\"")
        val quoted = Regex("""(?<!["\w])(\w+)\s*:""").replace(s) { "\"${it.groupValues[1]}\":" }
        return try {
            json.parseToJsonElement("{$quoted}").jsonObject.toMap()
        } catch (e: Exception) {
            Log.w(TAG, "Failed to parse native args, falling back: $raw", e)
            emptyMap()
        }
    }

    private fun formatNativeValue(element: JsonElement): String {
        return when (element) {
            is JsonPrimitive -> if (element.isString) "<|\"|>${element.content}<|\"|>" else element.content
            is JsonArray -> "[${element.joinToString(",") { formatNativeValue(it) }}]"
            is JsonObject -> {
                val parts = element.entries.sortedBy { it.key }.joinToString(",") { (k, v) ->
                    "$k:${formatNativeValue(v)}"
                }
                "{$parts}"
            }
        }
    }

    fun formatToolResponse(name: String, resultJson: String): String {
        return try {
            val obj = json.parseToJsonElement(resultJson)
            if (obj is JsonObject) {
                val parts = obj.entries.sortedBy { it.key }.joinToString(",") { (k, v) ->
                    "$k:${formatNativeValue(v)}"
                }
                "<|tool_response>response:$name{$parts}<tool_response|>"
            } else {
                "<|tool_response>response:$name{value:${formatNativeValue(obj)}}<tool_response|>"
            }
        } catch (e: Exception) {
            "<|tool_response>response:$name{value:<|\"|>$resultJson<|\"|>}<tool_response|>"
        }
    }

    /**
     * Live progress signal for the agent loop. `Step` appends a new entry to
     * the visible step list (treat the previous entry as Done). `Update`
     * replaces the last entry's text in place — used to refresh the token
     * counter during the long decode phase without spamming the list.
     */
    sealed class ProgressEvent {
        abstract fun applyTo(steps: MutableList<String>)

        data class Step(val label: String) : ProgressEvent() {
            override fun applyTo(steps: MutableList<String>) {
                steps.add(label)
            }
        }

        data class Update(val label: String) : ProgressEvent() {
            override fun applyTo(steps: MutableList<String>) {
                if (steps.isEmpty()) steps.add(label) else steps[steps.lastIndex] = label
            }
        }
    }

    /**
     * Runs the agent loop:
     *   model tool_call -> real tool_response -> model final JSON
     *
     * The loop matches eval behavior by dispatching every native tool_call in a
     * turn, truncating hallucinated text after the final tool_call marker, and
     * capping the interaction at six model turns.
     *
     * `onProgress` receives ProgressEvents the screen layer surfaces in
     * LoadingPanel — Step for phase transitions ("Reading prompt…",
     * "Checking interactions"), Update for in-phase refreshes (token
     * count during decode). The decode phase is the long one, so we emit a
     * token-count Update every 4 pieces to give the user concrete proof of
     * life instead of a static "Thinking…" label.
     */
    suspend fun runAgenticLoop(
        userInput: String,
        mode: String,
        onProgress: (ProgressEvent) -> Unit = {},
    ): AegisResponse = BatteryProbe.around(
        label = "agentic_loop",
        initialMetadata = mapOf(
            "mode" to JsonPrimitive(mode),
            "user_input_chars" to JsonPrimitive(userInput.length),
        ),
    ) { span ->
        val engine = EngineRouter.active
        engine.startConversation(mode)
        var nextTurn = userInput
        val seenGetDrugInfoCalls = mutableSetOf<String>()
        // Citations harvested from each dispatched tool result. Used to backfill
        // the final response when the model leaves the citations array empty —
        // see enforceModeContract's combined-citation path.
        val accumulatedCitations = mutableListOf<Citation>()
        var totalToolCalls = 0
        var modelTurns = 0

        repeat(MAX_TURNS) { turn ->
            onProgress(ProgressEvent.Step("Reading prompt…"))
            var lastEmittedCount = 0
            val modelOutput = engine.inferSync(nextTurn) { _, count ->
                if (count == 1 || count - lastEmittedCount >= 4) {
                    lastEmittedCount = count
                    val noun = if (count == 1) "token" else "tokens"
                    onProgress(ProgressEvent.Update("Generating response ($count $noun)…"))
                }
            }
            modelTurns = turn + 1
            Log.d(TAG, "Turn $turn model output: ${modelOutput.take(200)}...")

            val toolCalls = extractNativeToolCalls(modelOutput)
            if (toolCalls.isEmpty()) {
                onProgress(ProgressEvent.Step("Composing answer…"))
                span.put("model_turns", modelTurns)
                span.put("tool_calls_total", totalToolCalls)
                span.put("terminated_at_max", false)
                return@around parseAegisResponse(modelOutput, mode, accumulatedCitations.dedupBySource())
            }
            totalToolCalls += toolCalls.size

            val toolResponses = buildString {
                for (call in toolCalls) {
                    onProgress(ProgressEvent.Step(friendlyToolLabel(call.toolCall.name)))
                    val toolCall = call.toolCall
                    val result = if (toolCall.name == "get_drug_info") {
                        val key = toolCall.stableKey()
                        if (!seenGetDrugInfoCalls.add(key)) {
                            ToolResult(
                                name = toolCall.name,
                                result = errorJson("Repeated get_drug_info call suppressed; use the existing tool result and produce final JSON."),
                            )
                        } else {
                            dispatchToolCall(toolCall, call.rawArgs)
                        }
                    } else {
                        dispatchToolCall(toolCall, call.rawArgs)
                    }
                    accumulatedCitations += extractCitationsFromToolResult(result)
                    append(formatToolResponse(result.name, result.result))
                }
            }

            val truncatedModelTurn = modelOutput.substring(0, toolCalls.last().matchEnd)
            nextTurn = truncatedModelTurn + toolResponses + TURN_CLOSE + TURN_OPEN_MODEL
            Log.d(TAG, "Turn $turn dispatched ${toolCalls.size} tool_call(s)")
        }

        span.put("model_turns", modelTurns)
        span.put("tool_calls_total", totalToolCalls)
        span.put("terminated_at_max", true)
        invalidFinalResponse(
            message = "Maximum tool-call turns exceeded. Please consult a healthcare professional.",
            mode = mode,
            backfillCitations = accumulatedCitations.dedupBySource(),
        )
    }

    private fun dispatchToolCall(toolCall: ToolCall, rawArgs: String): ToolResult {
        return try {
            val result = executeToolCall(toolCall)
            ToolResult(name = toolCall.name, result = result)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to execute tool_call: ${toolCall.name}($rawArgs)", e)
            ToolResult(name = toolCall.name, result = errorJson(e.message ?: "Tool execution failed"))
        }
    }

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

            "get_drug_info" -> {
                val rxcui = args["rxcui"]?.jsonPrimitive?.content ?: ""
                json.encodeToString(GetDrugInfo.get(rxcui, db))
            }

            "get_guideline" -> {
                val age = args["age"]?.jsonPrimitive?.int ?: 0
                val sex = args["sex"]?.jsonPrimitive?.content ?: ""
                val conditions = (args["conditions"] as? JsonArray)
                    ?.map { it.jsonPrimitive.content }
                json.encodeToString(GetGuideline.getGuidelines(age, sex, conditions, db))
            }

            else -> errorJson("Unknown tool: ${toolCall.name}")
        }
    }

    private fun parseAegisResponse(
        modelOutput: String,
        mode: String = "drugsafe",
        backfillCitations: List<Citation> = emptyList(),
    ): AegisResponse {
        val cleaned = cleanFinalResponse(modelOutput)
        if (containsToolCallFragment(cleaned)) {
            Log.w(TAG, "Rejecting final answer with leftover tool_call fragment")
            return invalidFinalResponse(
                message = "The model returned an unfinished tool call instead of a final answer. Please try again.",
                mode = mode,
                backfillCitations = backfillCitations,
            )
        }

        val jsonStr = extractFirstBalancedJsonObject(cleaned)
        if (jsonStr != null) {
            try {
                return enforceModeContract(json.decodeFromString<AegisResponse>(jsonStr), mode, backfillCitations)
            } catch (e: Exception) {
                Log.d(TAG, "JSON parse failed, attempting repair before ProseParser fallback", e)
                val repaired = repairCommonJsonMalformations(jsonStr)
                if (repaired != jsonStr) {
                    try {
                        return enforceModeContract(
                            json.decodeFromString<AegisResponse>(repaired),
                            mode,
                            backfillCitations,
                        )
                    } catch (e2: Exception) {
                        Log.w(TAG, "Repaired JSON parse also failed, falling back to ProseParser", e2)
                    }
                }
            }
        }

        Log.d(TAG, "Routing non-JSON output through ProseParser")
        return enforceModeContract(ProseParser.parse(cleaned.ifBlank { modelOutput }, mode), mode, backfillCitations)
    }

    private fun cleanFinalResponse(modelOutput: String): String {
        val withoutLeadingToolResponses = LEADING_TOOL_RESPONSE_REGEX.replace(modelOutput, "")
        val withoutLeadingTurn = LEADING_MODEL_TURN_REGEX.replace(withoutLeadingToolResponses, "")
        return TRAILING_MARKERS_REGEX.replace(withoutLeadingTurn, "").trim()
    }

    private fun containsToolCallFragment(text: String): Boolean {
        return text.contains("<|tool_call>") || text.contains("<tool_call|>")
    }

    private fun extractFirstBalancedJsonObject(text: String): String? {
        val start = text.indexOf('{')
        if (start < 0) return null

        var depth = 0
        var inString = false
        var escaped = false

        for (i in start until text.length) {
            val ch = text[i]
            if (inString) {
                when {
                    escaped -> escaped = false
                    ch == '\\' -> escaped = true
                    ch == '"' -> inString = false
                }
                continue
            }

            when (ch) {
                '"' -> inString = true
                '{' -> depth += 1
                '}' -> {
                    depth -= 1
                    if (depth == 0) return text.substring(start, i + 1)
                }
            }
        }

        return null
    }

    private fun enforceModeContract(
        response: AegisResponse,
        mode: String,
        backfillCitations: List<Citation> = emptyList(),
    ): AegisResponse {
        if (!isDrugSafeMode(mode)) return response

        val flags = response.flags.map { flag ->
            if (flag.citation.isBlank()) {
                flag.copy(citation = "Aegis local safety KB")
            } else {
                flag
            }
        }

        val modelCitations = response.citations
            .filter { it.source.isNotBlank() || it.text.isNotBlank() }
            .map { citation ->
                citation.copy(
                    source = citation.source.ifBlank { "Aegis local safety KB" },
                    text = citation.text.ifBlank { "Medication safety result from the bundled on-device knowledge base." },
                )
            }

        // Tier A backfill: combine model-emitted citations with citations
        // harvested from the dispatched tool results, dedup by source. If the
        // model emitted nothing and tools were called (e.g. check_warnings),
        // the user now sees the real KB sources instead of a generic fallback.
        val combined = (modelCitations + backfillCitations).dedupBySource()
        val citations = combined.ifEmpty {
            listOf(
                Citation(
                    source = "Aegis local safety KB",
                    text = "Medication safety result from the bundled on-device knowledge base.",
                ),
            )
        }

        return response.copy(flags = flags, citations = citations)
    }

    private fun isDrugSafeMode(mode: String): Boolean {
        return mode.lowercase() !in setOf("consentreader", "consent", "healthpartner")
    }

    private fun invalidFinalResponse(
        message: String,
        mode: String,
        backfillCitations: List<Citation> = emptyList(),
    ): AegisResponse {
        return enforceModeContract(
            AegisResponse(
                confidence = 0.0,
                defer_to_professional = true,
                explanation = message,
            ),
            mode,
            backfillCitations,
        )
    }

    private fun friendlyToolLabel(name: String): String = when (name) {
        "normalize_drug" -> "Normalizing drug names"
        "decompose_product" -> "Decomposing products"
        "check_warnings" -> "Checking interactions"
        "lookup_term" -> "Looking up term"
        "get_drug_info" -> "Loading drug info"
        "get_guideline" -> "Pulling USPSTF guidelines"
        else -> "Running $name"
    }

    private fun ToolCall.stableKey(): String {
        return arguments.entries.sortedBy { it.key }.joinToString(prefix = "$name:", separator = "&") { (key, value) ->
            "$key=$value"
        }
    }

    private fun errorJson(message: String): String {
        return """{"error":${JsonPrimitive(message)}}"""
    }

    /**
     * Pulls real citation data out of a tool result that was already dispatched
     * during the agent loop. Reads two places per result:
     *   - the top-level `citations` array (e.g. CheckWarnings explicit Beers entry)
     *   - each flag's `citation` source string (mapped to a Citation with the
     *     flag's description as the text)
     *
     * Skips error results and any non-AegisResponse-shaped tool output. Caller
     * is responsible for dedup — accumulated citations across multiple tool
     * calls in a turn (or multiple turns) need dedupBySource() before use.
     */
    private fun extractCitationsFromToolResult(toolResult: ToolResult): List<Citation> {
        return try {
            val element = json.parseToJsonElement(toolResult.result)
            if (element !is JsonObject) return emptyList()
            if (element.containsKey("error")) return emptyList()

            val out = mutableListOf<Citation>()

            (element["citations"] as? JsonArray)?.forEach { citElement ->
                val cit = citElement as? JsonObject ?: return@forEach
                val source = cit["source"].asNonBlankString() ?: return@forEach
                val text = cit["text"].asNonBlankString().orEmpty()
                out += Citation(source = source, text = text)
            }

            (element["flags"] as? JsonArray)?.forEach { flagElement ->
                val flag = flagElement as? JsonObject ?: return@forEach
                val source = flag["citation"].asNonBlankString() ?: return@forEach
                val description = flag["description"].asNonBlankString().orEmpty()
                out += Citation(source = source, text = description)
            }

            out
        } catch (e: Exception) {
            Log.w(TAG, "Failed to extract citations from tool result for ${toolResult.name}", e)
            emptyList()
        }
    }

    private fun List<Citation>.dedupBySource(): List<Citation> {
        val seen = mutableSetOf<String>()
        return filter { seen.add(it.source.lowercase().trim()) }
    }

    private fun JsonElement?.asNonBlankString(): String? {
        val prim = this as? JsonPrimitive ?: return null
        if (!prim.isString) return null
        return prim.content.takeIf { it.isNotBlank() }
    }

    /**
     * Conservative repairs for the malformations the SFT model occasionally
     * emits in ConsentReader and HealthPartner outputs. Only fixes shapes that
     * cannot legitimately appear in valid JSON, so applying this to a
     * well-formed object is a no-op.
     *   - Single-quoted keys: `'key":` → `"key":`
     *   - Empty leading element in arrays: `[,` → `[`
     *   - Trailing commas in arrays/objects: `,]` → `]`, `,}` → `}`
     */
    private fun repairCommonJsonMalformations(input: String): String {
        var s = input
        s = Regex("""'(\w+)"\s*:""").replace(s) { "\"${it.groupValues[1]}\":" }
        s = Regex("""\[\s*,""").replace(s, "[")
        s = Regex(""",\s*\]""").replace(s, "]")
        s = Regex(""",\s*\}""").replace(s, "}")
        return s
    }
}
