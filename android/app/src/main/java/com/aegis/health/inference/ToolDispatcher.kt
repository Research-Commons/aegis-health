package com.aegis.health.inference

import android.util.Log
import com.aegis.health.AegisApp
import com.aegis.health.camera.DrugNameExtractor
import com.aegis.health.models.AegisResponse
import com.aegis.health.models.Citation
import com.aegis.health.models.EvaluatedRow
import com.aegis.health.models.Flag
import com.aegis.health.models.PreparsedReport
import com.aegis.health.models.ToolCall
import com.aegis.health.models.ToolResult
import com.aegis.health.tools.CheckWarnings
import com.aegis.health.tools.DecomposeProduct
import com.aegis.health.tools.GetDrugInfo
import com.aegis.health.tools.GetGuideline
import com.aegis.health.tools.GetGuidelineResult
import com.aegis.health.tools.LookupTerm
import com.aegis.health.tools.NormalizeDrug
import com.aegis.health.ui.reportreader.AegisResponseBuilder
import com.aegis.health.ui.reportreader.DeferReasonCopy
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
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
    private const val TURN_OPEN_USER = "<|turn>user\n"

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

    private fun compactToolResultForModel(toolResult: ToolResult): String {
        return try {
            val element = json.parseToJsonElement(toolResult.result)
            val compact = when (toolResult.name) {
                "check_warnings" -> compactAegisResponse(element)
                "get_guideline" -> compactGuidelineResult(element)
                "lookup_term" -> compactLookupTermResult(element)
                "normalize_drug" -> compactNormalizeDrugResult(element)
                "decompose_product" -> compactDecomposeProductResult(element)
                "get_drug_info" -> compactDrugInfoResult(element)
                "read_lab_report" -> compactReadLabReportResult(element)
                else -> compactGenericJson(element)
            }
            compact.toString()
        } catch (e: Exception) {
            Log.w(TAG, "Failed to compact tool result for ${toolResult.name}; using full result", e)
            toolResult.result
        }
    }

    private fun compactAegisResponse(element: JsonElement): JsonElement {
        val obj = element as? JsonObject ?: return element
        return obj.select(
            "confidence",
            "defer_to_professional",
            "flags",
            "explanation",
            "error",
            transform = mapOf("flags" to ::compactFlags),
        )
    }

    private fun compactGuidelineResult(element: JsonElement): JsonElement {
        val obj = element as? JsonObject ?: return element
        return obj.select(
            "recommendations",
            "gaps",
            "error",
            transform = mapOf("recommendations" to ::compactGuidelineRecommendations),
        )
    }

    private fun compactLookupTermResult(element: JsonElement): JsonElement {
        val obj = element as? JsonObject ?: return element
        return obj.select(
            "definition",
            "error",
            transform = mapOf("definition" to { it.selectObject("term", "plain_language_definition", "citation") }),
        )
    }

    private fun compactNormalizeDrugResult(element: JsonElement): JsonElement {
        val obj = element as? JsonObject ?: return element
        return obj.select(
            "drug",
            "error",
            transform = mapOf("drug" to { it.selectObject("generic_name", "rxcui", "category") }),
        )
    }

    private fun compactDecomposeProductResult(element: JsonElement): JsonElement {
        val obj = element as? JsonObject ?: return element
        return obj.select(
            "decomposition",
            "error",
            transform = mapOf(
                "decomposition" to { value ->
                    val decomp = value as? JsonObject
                    if (decomp == null) {
                        value
                    } else {
                        decomp.select(
                            "product",
                            "ingredients",
                            "citation",
                            transform = mapOf("ingredients" to ::compactDrugInfoList),
                        )
                    }
                },
            ),
        )
    }

    private fun compactDrugInfoResult(element: JsonElement): JsonElement {
        val obj = element as? JsonObject ?: return element
        return obj.select("name", "drug_class", "category", "warnings_summary", "citation", "error")
    }

    private fun compactGenericJson(element: JsonElement): JsonElement {
        return when (element) {
            is JsonObject -> JsonObject(
                element.mapValuesNotNull { (_, value) -> compactGenericJson(value).takeUnless { it.isEmptyPayload() } },
            )
            is JsonArray -> JsonArray(element.map { compactGenericJson(it) })
            else -> element
        }
    }

    private fun compactFlags(element: JsonElement): JsonElement {
        val arr = element as? JsonArray ?: return element
        return JsonArray(arr.map { it.selectObject("severity", "description", "citation") })
    }

    private fun compactCitations(element: JsonElement): JsonElement {
        val arr = element as? JsonArray ?: return element
        return JsonArray(arr.map { it.selectObject("source", "text") })
    }

    private fun compactGuidelineRecommendations(element: JsonElement): JsonElement {
        val arr = element as? JsonArray ?: return element
        return JsonArray(arr.map { it.selectObject("title", "grade", "description", "population", "citation") })
    }

    /**
     * Compacts a `read_lab_report` synthetic tool_result for the model context.
     * Per CONTEXT.md D-03 Claude's Discretion (lines 259-278): keep `rows`,
     * `has_outside_range`, `has_unknown`, `profile_used`, `citations`; DROP
     * `report_status` (synthesis only fires when code == "OK"). Each row is
     * compacted in turn — definition + definition_citation are dropped for
     * IN_RANGE rows (the model never narrates them) and kept for every other
     * status so the model has plain-language grounding for flagged values.
     * Implements the compaction half of SAFETY-02 (model sees only structured
     * PreparsedReport, never raw PDF text) + EXPLAIN-01 (per-row MedlinePlus
     * citation retention for non-IN_RANGE rows).
     */
    private fun compactReadLabReportResult(element: JsonElement): JsonElement {
        val obj = element as? JsonObject ?: return element
        return obj.select(
            "rows",
            "has_outside_range",
            "has_unknown",
            "profile_used",
            "citations",
            transform = mapOf(
                "rows" to ::compactEvaluatedRows,
                // PreparsedReport.citations is List<LabCitation> with {label, url}
                // shape — distinct from AegisResponse.Citation's {source, text}
                // shape compactCitations targets. Use the dedicated LabCitation
                // compactor below so the model actually sees the citation data
                // (Rule 1: reusing compactCitations here would silently drop every
                // citation because the keys don't match).
                "citations" to ::compactLabCitations,
                "profile_used" to ::compactProfileUsed,
            ),
        )
    }

    private fun compactLabCitations(element: JsonElement): JsonElement {
        val arr = element as? JsonArray ?: return element
        return JsonArray(arr.map { it.selectObject("label", "url") })
    }

    private fun compactEvaluatedRows(element: JsonElement): JsonElement {
        val arr = element as? JsonArray ?: return element
        return JsonArray(arr.map { compactEvaluatedRow(it) })
    }

    private fun compactEvaluatedRow(element: JsonElement): JsonElement {
        val obj = element as? JsonObject ?: return element
        val status = (obj["status"] as? JsonPrimitive)?.contentOrNull
        // Always keep: canonical_name, value, units, ref_low, ref_high, status,
        // ref_source, defer_reason. Definition fields kept only for non-IN_RANGE
        // rows (CONTEXT.md D-03 Discretion lines 259-266) — the model uses them
        // to narrate flagged values without hallucinating definitions.
        val keepDefinitions = status != "IN_RANGE"
        val keys = buildList {
            add("canonical_name")
            add("value")
            add("units")
            add("ref_low")
            add("ref_high")
            add("status")
            add("ref_source")
            add("defer_reason")
            if (keepDefinitions) {
                add("definition")
                add("definition_citation")
            }
        }
        return obj.select(*keys.toTypedArray())
    }

    private fun compactProfileUsed(element: JsonElement): JsonElement {
        val obj = element as? JsonObject ?: return element
        return obj.select("age", "sex")
    }

    private fun compactDrugInfoList(element: JsonElement): JsonElement {
        val arr = element as? JsonArray ?: return element
        return JsonArray(arr.map { it.selectObject("generic_name", "rxcui", "category") })
    }

    private fun JsonElement.selectObject(vararg keys: String): JsonElement {
        val obj = this as? JsonObject ?: return this
        return obj.select(*keys)
    }

    private fun JsonObject.select(
        vararg keys: String,
        transform: Map<String, (JsonElement) -> JsonElement> = emptyMap(),
    ): JsonObject {
        val out = linkedMapOf<String, JsonElement>()
        for (key in keys) {
            val value = this[key] ?: continue
            if (value == JsonNull) continue
            val compact = transform[key]?.invoke(value) ?: value
            if (!compact.isNullOrBlankPayload()) out[key] = compact
        }
        return JsonObject(out)
    }

    private fun JsonElement.isNullOrBlankPayload(): Boolean {
        return when (this) {
            JsonNull -> true
            is JsonPrimitive -> isString && content.isBlank()
            else -> false
        }
    }

    private fun JsonElement.isEmptyPayload(): Boolean {
        return when (this) {
            is JsonPrimitive -> isNullOrBlankPayload()
            is JsonArray -> isEmpty()
            is JsonObject -> isEmpty()
        }
    }

    private inline fun JsonObject.mapValuesNotNull(transform: (Map.Entry<String, JsonElement>) -> JsonElement?): Map<String, JsonElement> {
        val out = linkedMapOf<String, JsonElement>()
        for (entry in entries) {
            val value = transform(entry)
            if (value != null) out[entry.key] = value
        }
        return out
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

        /**
         * Emitted as each flag object closes during the synthesis stream.
         * Carries enough info to render a preview SeverityCard before the
         * full AegisResponse arrives. UI tracks these in a separate list
         * (not the steps list) so [applyTo] is a no-op here.
         */
        data class FlagPreview(
            val severity: Int,
            val description: String,
            val citation: String,
        ) : ProgressEvent() {
            override fun applyTo(steps: MutableList<String>) {
                // Routed to a separate preview-list state on the UI side.
            }
        }
    }

    /**
     * Fast path for the DrugSafe screen. The UI field is already a medication
     * list, so for simple list-like inputs we can call the same local
     * check_warnings tool directly and spend model time only on final synthesis.
     * Complex/free-form prompts fall back to the normal agentic loop.
     */
    suspend fun runDrugSafeFastPath(
        userInput: String,
        onProgress: (ProgressEvent) -> Unit = {},
    ): AegisResponse {
        val parsed = parseDrugSafeFastPathInput(userInput)
            ?: return runAgenticLoop(
                userInput = "Check these drugs for interactions and safety: $userInput",
                mode = "drugsafe",
                onProgress = onProgress,
            )

        onProgress(ProgressEvent.Step("Checking interactions"))
        val result = ToolResult(
            name = "check_warnings",
            result = json.encodeToString(
                CheckWarnings.check(
                    drugList = parsed.drugs,
                    age = parsed.age,
                    conditions = parsed.conditions,
                    db = AegisApp.instance.database,
                ),
            ),
        )
        emitFlagPreviewsFromToolResult(result, onProgress)

        return runPrecomputedToolSynthesis(
            mode = "drugsafe",
            userInput = "Check these drugs for interactions and safety: $userInput",
            nativeToolCall = formatCheckWarningsCall(parsed),
            toolResult = result,
            onProgress = onProgress,
        )
    }

    /**
     * Fast path for HealthPartner's structured profile form. Age, sex, and
     * conditions are already validated by the UI, so the model does not need
     * to spend a first turn extracting get_guideline arguments.
     */
    data class HealthPartnerResult(
        val response: AegisResponse,
        val guidelines: GetGuidelineResult,
    )

    suspend fun runHealthPartnerFastPath(
        age: Int,
        sex: String,
        conditions: List<String>,
        userInput: String,
        onProgress: (ProgressEvent) -> Unit = {},
    ): HealthPartnerResult {
        onProgress(ProgressEvent.Step("Pulling USPSTF guidelines"))
        val guidelines = GetGuideline.getGuidelines(
            age = age,
            sex = sex,
            conditions = conditions,
            db = AegisApp.instance.database,
        )
        val result = ToolResult(
            name = "get_guideline",
            result = json.encodeToString(guidelines),
        )

        val response = runPrecomputedToolSynthesis(
            mode = "healthpartner",
            userInput = "Get preventive care recommendations for this patient: $userInput",
            nativeToolCall = formatGetGuidelineCall(age, sex, conditions),
            toolResult = result,
            onProgress = onProgress,
        )
        return HealthPartnerResult(response = response, guidelines = guidelines)
    }

    /**
     * Fast path for the ReportReader screen. [PreparsedReport] is already
     * validated upstream by Phase 2's `ReportReaderPipeline` + `RangeEvaluator`;
     * the model never sees raw PDF text. Per SAFETY-02, this function builds a
     * synthetic `<|tool_call>call:read_lab_report{...}<tool_call|>` /
     * `<|tool_response>response:read_lab_report{...}<tool_response|>` turn from
     * the structured PreparsedReport and feeds it through the same
     * [runPrecomputedToolSynthesis] helper DrugSafe / HealthPartner use.
     *
     * The model owns only the `explanation` field of the returned [AegisResponse]
     * — every other slot is re-emitted by [enforceReportReaderContract] from
     * the PreparsedReport (D-03). On sanitization reject the explanation falls
     * back to [AegisResponseBuilder.FIXED_EXPLANATION] and the reject code
     * lands on the BatteryProbe span (D-04 telemetry).
     *
     * Wrapped in `BatteryProbe.around("reportreader_fastpath", ...)` so the
     * synthesis turn's per-call charge / voltage / temperature delta records
     * with `parent_span_id` chaining through to the inner
     * `precomputed_tool_synthesis` span (BatteryProbe.SpanElement in
     * CoroutineContext makes the chain automatic).
     *
     * Latency target: under 180000 ms (3 min) for a 12-row lipid+CMP on
     * SD8G2 / CPU(threads=5) per ROADMAP Phase 4 SC-3.
     */
    suspend fun runReportReaderFastPath(
        report: PreparsedReport,
        onProgress: (ProgressEvent) -> Unit = {},
    ): AegisResponse = BatteryProbe.around(
        label = "reportreader_fastpath",
        initialMetadata = mapOf(
            "rows" to JsonPrimitive(report.rows.size),
            "has_outside_range" to JsonPrimitive(report.has_outside_range),
            "has_unknown" to JsonPrimitive(report.has_unknown),
        ),
    ) { _ ->
        onProgress(ProgressEvent.Step("Reading report"))
        val toolResult = ToolResult(
            name = "read_lab_report",
            result = json.encodeToString(report),
        )
        val nativeToolCall = formatReadLabReportCall(report)
        runPrecomputedToolSynthesis(
            mode = "reportreader",
            userInput = "Summarize the flagged values in this lab report:",
            nativeToolCall = nativeToolCall,
            toolResult = toolResult,
            onProgress = onProgress,
            report = report,
        )
    }

    private data class DrugSafeFastPathArgs(
        val drugs: List<String>,
        val age: Int?,
        val conditions: List<String>,
    )

    private fun parseDrugSafeFastPathInput(input: String): DrugSafeFastPathArgs? {
        val trimmed = input.trim()
        if (trimmed.isBlank()) return null

        val lower = trimmed.lowercase()
        val age = extractAge(lower)
        if (age == null && lower.containsAny("pediatric", "child", "children", "infant", "baby", "elderly", "senior")) {
            return null
        }
        if (lower.containsAny("dose", "dosage", "side effect", "symptom", "fever", "allergy", "allergic")) {
            return null
        }

        val extracted = DrugNameExtractor.extract(trimmed, AegisApp.instance.database)
        val drugs = extracted.canonical.distinct()
        if (drugs.isEmpty()) return null

        val looksLikeList = drugs.size >= 2 ||
            Regex("""[,;+&/]|\s\+\s|\band\b|\bwith\b""", RegexOption.IGNORE_CASE).containsMatchIn(trimmed) ||
            Regex("""^[A-Za-z][A-Za-z -]{1,40}$""").matches(trimmed)
        if (!looksLikeList) return null

        return DrugSafeFastPathArgs(
            drugs = drugs,
            age = age,
            conditions = extractDrugSafeConditions(lower),
        )
    }

    private fun extractAge(lowerInput: String): Int? {
        val match = Regex("""(?:\bage\s*[:=]?\s*(\d{1,3})\b|\b(\d{1,3})\s*(?:years?\s*old|year-old|yo|y/o|yrs?)\b)""")
            .find(lowerInput)
            ?: return null
        val value = match.groupValues.drop(1).firstOrNull { it.isNotBlank() }
        return value?.toIntOrNull()?.takeIf { it in 0..120 }
    }

    private fun extractDrugSafeConditions(lowerInput: String): List<String> {
        val out = linkedSetOf<String>()
        if (lowerInput.containsAny("pregnant", "pregnancy")) out += "pregnancy"
        if (lowerInput.containsAny("breastfeeding", "lactation", "nursing")) out += "lactation"
        if (lowerInput.containsAny("liver disease", "hepatic")) out += "liver disease"
        if (lowerInput.containsAny("kidney disease", "renal")) out += "kidney disease"
        return out.toList()
    }

    private suspend fun runPrecomputedToolSynthesis(
        mode: String,
        userInput: String,
        nativeToolCall: String,
        toolResult: ToolResult,
        onProgress: (ProgressEvent) -> Unit,
        report: PreparsedReport? = null,
    ): AegisResponse = BatteryProbe.around(
        label = "precomputed_tool_synthesis",
        initialMetadata = mapOf(
            "mode" to JsonPrimitive(mode),
            "tool" to JsonPrimitive(toolResult.name),
            "user_input_chars" to JsonPrimitive(userInput.length),
        ),
    ) { span ->
        val engine = EngineRouter.active
        engine.startConversation(mode, includeTools = false)
        val backfillCitations = extractCitationsFromToolResult(toolResult).dedupBySource()
        val syntheticTurn = TURN_OPEN_USER +
            userInput +
            TURN_CLOSE +
            TURN_OPEN_MODEL +
            nativeToolCall +
            formatToolResponse(toolResult.name, compactToolResultForModel(toolResult)) +
            TURN_CLOSE +
            TURN_OPEN_MODEL

        onProgress(ProgressEvent.Step(turnLabelForMode(mode)))
        var lastEmittedCount = 0
        val streamBuffer = StringBuilder()
        var lastFlagCount = 0
        val flagsParser = FlagsStreamParser()
        val modelOutput = try {
            engine.inferSync(syntheticTurn) { piece, count ->
                var flagCountChanged = false
                streamBuffer.append(piece)
                val seen = countOccurrences(streamBuffer, "\"severity\":")
                if (seen > lastFlagCount) {
                    lastFlagCount = seen
                    flagCountChanged = true
                }
                for (preview in flagsParser.extractNewFlags(streamBuffer)) {
                    onProgress(preview)
                }
                if (count == 1 || count - lastEmittedCount >= 4 || flagCountChanged) {
                    lastEmittedCount = count
                    val tokenWord = if (count == 1) "token" else "tokens"
                    val label = if (lastFlagCount > 0) {
                        val flagWord = if (lastFlagCount == 1) "flag" else "flags"
                        "Generating response ($count $tokenWord, $lastFlagCount $flagWord found)â€¦"
                    } else {
                        "Generating response ($count $tokenWord)â€¦"
                    }
                    onProgress(ProgressEvent.Update(label))
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Fast-path synthesis failed", e)
            span.put("inference_error", e.javaClass.simpleName)
            span.put("inference_error_message", e.message ?: "")
            return@around runAgenticLoop(userInput, mode, onProgress)
        }

        span.put("model_turns", 1)
        span.put("tool_calls_total", 1)
        span.put("terminated_at_max", false)
        parseAegisResponse(modelOutput, mode, backfillCitations, report, span)
    }

    private fun formatCheckWarningsCall(args: DrugSafeFastPathArgs): String {
        val parts = mutableListOf<String>()
        parts += "drug_list:${formatNativeStringList(args.drugs)}"
        args.age?.let { parts += "age:$it" }
        if (args.conditions.isNotEmpty()) {
            parts += "conditions:${formatNativeStringList(args.conditions)}"
        }
        return "<|tool_call>call:check_warnings{${parts.joinToString(",")}}<tool_call|>"
    }

    private fun formatGetGuidelineCall(age: Int, sex: String, conditions: List<String>): String {
        val parts = mutableListOf("age:$age", "sex:${formatNativeString(sex)}")
        if (conditions.isNotEmpty()) {
            parts += "conditions:${formatNativeStringList(conditions)}"
        }
        return "<|tool_call>call:get_guideline{${parts.joinToString(",")}}<tool_call|>"
    }

    /**
     * Builds the synthetic `<|tool_call>call:read_lab_report{...}<tool_call|>`
     * header for the ReportReader fast path. Keys off `profile_used` when
     * present so the model sees demographic context (per PATTERNS.md §A1
     * delta #4); always carries the three summary counts so the model knows
     * the report size before reading the tool-response body.
     *
     * `read_lab_report` is a SYNTHETIC virtual tool name — it is NOT in the
     * registry, and `OpenApiToolDefs.forMode("reportreader") = emptyList()`
     * (Plan 04-01) ensures the model has no live tool to actually call.
     * The header exists only inside the synthesis-turn transcript so the
     * model recognizes the canonical "after-tool-result synthesis" shape.
     */
    private fun formatReadLabReportCall(report: PreparsedReport): String {
        val parts = mutableListOf<String>()
        val profile = report.profile_used
        profile.age?.let { parts += "age:$it" }
        profile.sex?.takeIf { it.isNotBlank() }?.let { parts += "sex:${formatNativeString(it)}" }
        parts += "rows:${report.rows.size}"
        parts += "outside_range:${report.has_outside_range}"
        parts += "has_unknown:${report.has_unknown}"
        return "<|tool_call>call:read_lab_report{${parts.joinToString(",")}}<tool_call|>"
    }

    private fun formatNativeStringList(values: List<String>): String =
        values.joinToString(prefix = "[", postfix = "]") { formatNativeString(it) }

    private fun formatNativeString(value: String): String =
        "<|\"|>${value.replace("<|\"|>", "").replace("<tool_call|>", "").replace("<|tool_call>", "")}<|\"|>"

    private fun String.containsAny(vararg needles: String): Boolean =
        needles.any { contains(it, ignoreCase = true) }

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
            // Turn 0 is the genuine "read user input + tool catalog" phase.
            // Turn 1+ is the synthesis prefill — re-prefilling system + tools
            // + history + tool result on CPU eats 30–45s on SD8G2, and showing
            // "Reading prompt…" again makes it look like we've gone back to
            // square one. Use a phase-appropriate label so the user can tell
            // we're now in synthesis, not re-reading.
            val turnLabel = if (turn == 0) "Reading prompt…" else turnLabelForMode(mode)
            onProgress(ProgressEvent.Step(turnLabel))
            var lastEmittedCount = 0
            // Streaming buffer + flag counter for the synthesis turn (turn > 0).
            // Each AegisResponse flag emits its own `"severity":` key, so the
            // count of that substring in the running buffer = number of
            // safety flags the model has finished severity-tagging so far.
            // Cheap O(buffer) scan per piece; buffer maxes at ~1.5k chars.
            // Falls back silently to the token-count label on tool-call turns
            // (turn 0) and on synthesis turns that emit zero flags.
            val streamBuffer = StringBuilder()
            var lastFlagCount = 0
            // Phase B: as each flag object closes, push a typed preview event
            // so the UI can render a SeverityCard before the full response
            // arrives. Parser is reset per turn (only synthesis turns produce
            // AegisResponse JSON; tool-call turns never reach `"flags":[`).
            val flagsParser = FlagsStreamParser()
            val modelOutput = try {
                engine.inferSync(nextTurn) { piece, count ->
                    var flagCountChanged = false
                    if (turn > 0) {
                        streamBuffer.append(piece)
                        val seen = countOccurrences(streamBuffer, "\"severity\":")
                        if (seen > lastFlagCount) {
                            lastFlagCount = seen
                            flagCountChanged = true
                        }
                        // Drain any newly-completed flag objects. Each call
                        // returns 0..N flags depending on how the streamed
                        // piece moved us across `}` boundaries.
                        for (preview in flagsParser.extractNewFlags(streamBuffer)) {
                            onProgress(preview)
                        }
                    }
                    if (count == 1 || count - lastEmittedCount >= 4 || flagCountChanged) {
                        lastEmittedCount = count
                        val tokenWord = if (count == 1) "token" else "tokens"
                        val label = if (lastFlagCount > 0) {
                            val flagWord = if (lastFlagCount == 1) "flag" else "flags"
                            "Generating response ($count $tokenWord, $lastFlagCount $flagWord found)…"
                        } else {
                            "Generating response ($count $tokenWord)…"
                        }
                        onProgress(ProgressEvent.Update(label))
                    }
                }
            } catch (e: Exception) {
                // Engine errors (most commonly LiteRtLmJniException for context
                // overflow at 4096 tokens) must NOT crash the app. The screen
                // launches us from rememberCoroutineScope, which has no
                // exception handler. Degrade to a deferral message and let the
                // user see real KB citations harvested so far.
                Log.e(TAG, "Inference failed on turn $turn", e)
                span.put("model_turns", turn + 1)
                span.put("tool_calls_total", totalToolCalls)
                span.put("inference_error", e.javaClass.simpleName)
                span.put("inference_error_message", e.message ?: "")
                return@around invalidFinalResponse(
                    message = "On-device inference hit a limit. " +
                        "Please consult a healthcare professional with the findings below.",
                    mode = mode,
                    backfillCitations = accumulatedCitations.dedupBySource(),
                )
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
                    emitFlagPreviewsFromToolResult(result, onProgress)
                    accumulatedCitations += extractCitationsFromToolResult(result)
                    append(formatToolResponse(result.name, compactToolResultForModel(result)))
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
        report: PreparsedReport? = null,
        span: BatteryProbe.SpanContext? = null,
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
                return enforceModeContract(
                    json.decodeFromString<AegisResponse>(jsonStr),
                    mode,
                    backfillCitations,
                    report,
                    span,
                )
            } catch (e: Exception) {
                Log.d(TAG, "JSON parse failed, attempting repair before ProseParser fallback", e)
                val repaired = repairCommonJsonMalformations(jsonStr)
                if (repaired != jsonStr) {
                    try {
                        return enforceModeContract(
                            json.decodeFromString<AegisResponse>(repaired),
                            mode,
                            backfillCitations,
                            report,
                            span,
                        )
                    } catch (e2: Exception) {
                        Log.w(TAG, "Repaired JSON parse also failed, falling back to ProseParser", e2)
                    }
                }
            }
        }

        Log.d(TAG, "Routing non-JSON output through ProseParser")
        return enforceModeContract(
            ProseParser.parse(cleaned.ifBlank { modelOutput }, mode),
            mode,
            backfillCitations,
            report,
            span,
        )
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

    /**
     * Dispatches the response through a mode-specific contract.
     *
     * - `reportreader` → [enforceReportReaderContract]: DISCARDS the model's
     *   `flags[]`, `citations[]`, `defer_to_professional`, and `confidence`
     *   (D-03 strict override; SAFETY-01). Only the sanitized `explanation`
     *   field survives. Requires the [report] argument to be non-null in
     *   production — a null report routes through the FIXED_EXPLANATION
     *   fail-safe.
     * - `consentreader` / `consent` / `healthpartner` → pass-through (the
     *   model's response shape is already authoritative for those modes).
     * - All other modes (default DrugSafe) → [enforceDrugSafeContract]: the
     *   original flag/citation cleanup + backfill logic unchanged.
     *
     * `report` and `span` default to null so DrugSafe / HealthPartner /
     * ConsentReader / agentic-loop callers are unaffected. They're populated
     * only by [runReportReaderFastPath] → [runPrecomputedToolSynthesis] →
     * [parseAegisResponse].
     */
    private fun enforceModeContract(
        response: AegisResponse,
        mode: String,
        backfillCitations: List<Citation> = emptyList(),
        report: PreparsedReport? = null,
        span: BatteryProbe.SpanContext? = null,
    ): AegisResponse {
        return when (mode.lowercase()) {
            "reportreader" -> enforceReportReaderContract(response, report, span)
            "consentreader", "consent", "healthpartner" -> response
            else -> enforceDrugSafeContract(response, backfillCitations)
        }
    }

    /**
     * Preserves the Phase 1-3 DrugSafe override block verbatim. Factored out of
     * the original [enforceModeContract] early-return so the new when-switch
     * leaves DrugSafe behavior byte-for-byte identical (existing unit tests
     * still pass).
     */
    private fun enforceDrugSafeContract(
        response: AegisResponse,
        backfillCitations: List<Citation>,
    ): AegisResponse {
        val flags = response.flags.map { flag ->
            val cleaned = humanizeCitationSource(flag.citation)
            if (cleaned.isBlank()) {
                flag.copy(citation = "Aegis local safety KB")
            } else {
                flag.copy(citation = cleaned)
            }
        }

        val modelCitations = response.citations
            .filter { it.source.isNotBlank() || it.text.isNotBlank() }
            .map { citation ->
                Citation(
                    source = humanizeCitationSource(citation.source).ifBlank { "Aegis local safety KB" },
                    text = citation.text.ifBlank { "Medication safety result from the bundled on-device knowledge base." },
                )
            }

        // Mine per-flag citations into the Sources list. The model often copies
        // the source code (e.g. "FDA-WFN §7.1; Shorr...") from the tool result
        // into each flag.citation but emits an empty top-level citations array.
        // Without this, the user sees "Aegis local safety KB" as the only
        // source even though every flag carries a precise FDA reference.
        val flagCitations = response.flags
            .filter { it.citation.isNotBlank() }
            .map { flag ->
                Citation(
                    source = humanizeCitationSource(flag.citation),
                    text = flag.description,
                )
            }

        val humanizedBackfill = backfillCitations.map { c ->
            c.copy(source = humanizeCitationSource(c.source))
        }

        // Combine model-emitted citations + per-flag citations + tool-result
        // backfill. Dedup by source so the same FDA reference appearing on
        // multiple flags collapses to one row. Real sources only fall back to
        // the generic "Aegis local safety KB" when literally nothing else is
        // present (rare — usually means the model emitted no flags either).
        val combined = (modelCitations + flagCitations + humanizedBackfill).dedupBySource()
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

    /**
     * D-03 strict override for ReportReader synthesis. The model owns ONLY the
     * `explanation` field. Every other AegisResponse slot is re-emitted from
     * the [report] (which the Kotlin pipeline already validated upstream) so
     * the user can never see a model hallucination on `flags`, `citations`,
     * `defer_to_professional`, or `confidence` (SAFETY-01).
     *
     * The flag severity + description formulas mirror
     * `AegisResponseBuilder.severityForStatus` + `AegisResponseBuilder.flagMessage`
     * verbatim. Those are private to AegisResponseBuilder; we replicate the
     * formulas here rather than widening visibility because the production
     * fallback path (D-05) also reuses `AegisResponseBuilder.build(report)` —
     * keeping both in sync is the contract.
     *
     * The model's `explanation` is piped through [sanitizeExplanation] (D-04
     * four-step cascade). On reject we emit `synthesis_sanitization_reason=<code>`
     * to the active [BatteryProbe.SpanContext] so Phase 5 EVAL-04 can grep the
     * telemetry log for which class of model output was suppressed.
     *
     * If [report] is null (caller bug — runReportReaderFastPath should always
     * pass it), we return the FIXED_EXPLANATION envelope as a fail-safe so
     * the user never sees a model-emitted hallucination.
     */
    internal fun enforceReportReaderContract(
        response: AegisResponse,
        report: PreparsedReport?,
        span: BatteryProbe.SpanContext?,
    ): AegisResponse {
        if (report == null) {
            Log.w(TAG, "enforceReportReaderContract called without report; falling back to FIXED_EXPLANATION envelope")
            span?.put("synthesis_sanitization_reason", "missing_report")
            return AegisResponse(
                confidence = 0.6,
                defer_to_professional = true,
                flags = emptyList(),
                citations = emptyList(),
                explanation = AegisResponseBuilder.FIXED_EXPLANATION,
            )
        }

        // D-03: flags re-emitted from PreparsedReport.rows filtered by status;
        // model flags[] DISCARDED entirely.
        val flags = report.rows
            .filter { it.status != "IN_RANGE" }
            .map { row ->
                Flag(
                    severity = when (row.status) {
                        "OUTSIDE_RANGE" -> 4
                        "BORDERLINE" -> 3
                        "unknown" -> 2
                        else -> 1
                    },
                    description = flagDescriptionForRow(row),
                    citation = row.definition_citation.orEmpty(),
                )
            }

        // D-03: citations backfilled from PreparsedReport.citations; model
        // citations[] DISCARDED — citation surface is the highest-risk
        // hallucination vector and gets zero opportunity.
        val citations = report.citations.map { lc ->
            Citation(source = lc.label, text = lc.url)
        }

        // Phase 4.1 D-07: GENERIC_FALLBACK sub-clause.
        // When the underlying PreparsedReport carries the catch-all
        // GENERIC_FALLBACK status code, extraction provenance is uncertain
        // (permissive regex behind a 3-layer defense: per-row units-or-range
        // gate, aggregate ≥3 floor, alias-map silent-drop). Force clinician
        // handoff and lower the confidence floor regardless of model output.
        // All other D-03 overrides (flags, citations, sanitization) above
        // and below this block continue to apply unchanged.
        val isGenericFallback = report.report_status.code == "GENERIC_FALLBACK"

        // D-03 / D-07: defer flag = Kotlin-computed; model value DISCARDED.
        val deferToProfessional = if (isGenericFallback) {
            true                                              // D-07: unconditional defer
        } else {
            report.has_outside_range || report.has_unknown    // D-03 baseline
        }

        // D-03 / D-07: confidence floor = Kotlin-computed; model value DISCARDED.
        val confidence: Double = if (isGenericFallback) {
            0.4                                               // D-07: lowered floor
        } else {
            0.6                                               // D-03 baseline
        }

        // D-04: sanitize the model explanation; fall back to FIXED_EXPLANATION
        // on reject. The reject reason lands on the active BatteryProbe span
        // for Phase 5 EVAL-04 telemetry audit.
        val (explanation, rejectReason) = sanitizeExplanation(response.explanation)
        if (rejectReason != null) {
            span?.put("synthesis_sanitization_reason", rejectReason)
        }

        return AegisResponse(
            confidence = confidence,
            defer_to_professional = deferToProfessional,
            flags = flags,
            citations = citations,
            explanation = explanation,
        )
    }

    /**
     * Mirror of `AegisResponseBuilder.flagMessage` (private there). Phase 4
     * replicates the formula rather than widening visibility on the UI-layer
     * builder so the two implementations stay self-contained. Keep this in
     * sync with `AegisResponseBuilder.flagMessage` — any future change must
     * touch both (Phase 5's regression eval would catch drift between the
     * production prose and the D-05 fallback prose).
     */
    private fun flagDescriptionForRow(row: EvaluatedRow): String {
        val valueText = (row.value as? JsonPrimitive)?.contentOrNull
        val units = row.units?.takeIf { it.isNotBlank() } ?: ""
        return when (row.status) {
            "unknown" -> {
                val reasonText = row.defer_reason?.let { DeferReasonCopy.lookup(it) }
                    ?: "A clinician can review this result."
                "${row.canonical_name} — $reasonText"
            }
            else -> {
                val tail = listOfNotNull(valueText, units.takeIf { it.isNotEmpty() })
                    .joinToString(" ")
                if (tail.isNotEmpty()) {
                    "${row.canonical_name}: $tail — outside printed range"
                } else {
                    "${row.canonical_name} — outside printed range"
                }
            }
        }
    }

    /**
     * D-04 explanation sanitization. Order matters — empty / structural-leak /
     * diagnostic-phrase / length-cap. Don't reorder: the length-cap step is
     * allowed to mutate the string and we want rejects to fire before
     * truncation has a chance to mask them.
     *
     * Returns a [Pair] of `(explanation_to_use, reject_code_or_null)`. Reject
     * codes are exactly four literal strings — `"empty"`, `"format_leak"`,
     * `"diagnostic_phrase"`, `"too_long_truncated"` — because Phase 5 EVAL-04
     * and REGULATORY.md telemetry grep on them. Any variation breaks the audit.
     *
     * Marked `internal` (not `private`) so JVM tests in the same module can
     * invoke it directly without reflection.
     */
    internal fun sanitizeExplanation(raw: String): Pair<String, String?> {
        val trimmed = raw.trim()

        // 1. Empty / whitespace-only.
        if (trimmed.isEmpty()) {
            return AegisResponseBuilder.FIXED_EXPLANATION to "empty"
        }

        // 2. Structural-token leak. SFT v4 has no ReportReader training, so
        // format leaks are a known risk on this novel-mode prompt.
        for (token in SafetyBoundaryPhrases.STRUCTURAL_LEAK_TOKENS) {
            if (trimmed.contains(token)) {
                return AegisResponseBuilder.FIXED_EXPLANATION to "format_leak"
            }
        }
        if (containsBalancedJsonObject(trimmed)) {
            return AegisResponseBuilder.FIXED_EXPLANATION to "format_leak"
        }

        // 3. Diagnostic phrase — both legs must match on the same string per
        // D-04. A diagnostic verb alone (e.g. "this means your LDL is high")
        // is allowed; a disease noun alone (some lab analytes legitimately
        // appear in description text) is allowed. Together they signal a
        // declarative-diagnosis claim, which is out of envelope.
        val verbHit = SafetyBoundaryPhrases.DIAGNOSTIC_VERB_REGEX.containsMatchIn(trimmed)
        val nounHit = SafetyBoundaryPhrases.DISEASE_NOUN_REGEX.containsMatchIn(trimmed)
        if (verbHit && nounHit) {
            return AegisResponseBuilder.FIXED_EXPLANATION to "diagnostic_phrase"
        }

        // 4. Length cap. Truncate at the last sentence boundary within bound;
        // if none, hard-cut at 280 with no ellipsis (D-04 step 1).
        if (trimmed.length > 280) {
            val window = trimmed.substring(0, 280)
            val lastBoundary = maxOf(
                window.lastIndexOf('.'),
                window.lastIndexOf('!'),
                window.lastIndexOf('?'),
            )
            val truncated = if (lastBoundary >= 0) {
                window.substring(0, lastBoundary + 1)
            } else {
                window
            }
            return truncated to "too_long_truncated"
        }

        return trimmed to null
    }

    /**
     * Detects a balanced `{...}` substring anywhere in [text]. Used by
     * [sanitizeExplanation] step 2 to catch the model leaking a partial JSON
     * envelope into the explanation field. A simple depth-counter is
     * sufficient — we only need ONE balanced pair, not full JSON parsing,
     * and this is cheap enough to run on every synthesis turn.
     */
    private fun containsBalancedJsonObject(text: String): Boolean {
        var start = -1
        var depth = 0
        for (i in text.indices) {
            when (text[i]) {
                '{' -> {
                    if (depth == 0) start = i
                    depth++
                }
                '}' -> {
                    if (depth > 0) {
                        depth--
                        if (depth == 0 && start in 0..i) return true
                    }
                }
            }
        }
        return false
    }

    private fun isDrugSafeMode(mode: String): Boolean {
        return mode.lowercase() !in setOf("consentreader", "consent", "healthpartner", "reportreader")
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

    /**
     * Curated DDI source codes used in kb/kb/sources/curated_ddi.py expand to
     * full human labels for display. The table column is `source` (aliased to
     * `citation` in the SQL query) and ships strings like
     *   "FDA-WFN §7.1; Shorr RI et al., Arch Intern Med 1993"
     * which is precise but cryptic. We rewrite the leading code in each
     * semicolon-delimited segment so the user sees
     *   "FDA Warfarin Prescribing Information §7.1; Shorr RI et al., …"
     * Keep this map in sync with the legend at the top of curated_ddi.py.
     */
    private val FDA_CODE_LABELS = mapOf(
        "FDA-WFN" to "FDA Warfarin Prescribing Information",
        "FDA-MTX" to "FDA Methotrexate Prescribing Information",
        "FDA-DSC" to "FDA Drug Safety Communication (opioids + benzodiazepines, 2016)",
        "FDA-SIM" to "FDA Simvastatin Label Update (2011)",
        "FDA-DIG" to "FDA Digoxin Prescribing Information",
        "FDA-LIT" to "FDA Lithium Label",
        "FDA-SSRI" to "FDA SSRI Labels",
        "FDA-CLO" to "FDA Clopidogrel Label + Drug Safety Communication (2007)",
        "FDA-DOAC" to "FDA DOAC Labels (rivaroxaban / apixaban)",
        "FDA-ACE" to "FDA Lisinopril Label + ACC/AHA Heart Failure Guidelines",
        "FDA-QT" to "FDA Hydroxychloroquine + Ondansetron Labels (QT)",
        "FDA-BUP" to "FDA Bupropion Label",
        // Generic source labels emitted by check_warnings tool results.
        // Bare "openfda" lands on screen as a too-terse citation next to fully
        // expanded FDA labels and looks unprofessional. Resolves regardless of
        // case via the IGNORE_CASE flag in humanizeCitationSource.
        "openfda" to "openFDA Drug Labels (FDA)",
    )

    private fun humanizeCitationSource(source: String): String {
        if (source.isBlank()) return source
        var result = source
        for ((code, label) in FDA_CODE_LABELS) {
            // Word-boundary match avoids replacing a code inside an
            // already-expanded label or partial substring. IGNORE_CASE so
            // "openfda", "OpenFDA", and "OPENFDA" all resolve to the same
            // canonical label.
            result = Regex("\\b" + Regex.escape(code) + "\\b", RegexOption.IGNORE_CASE)
                .replace(result, label)
        }
        return result
    }

    /**
     * Step label shown at the start of a synthesis turn (turn > 0). Lives
     * during the long CPU prefill that re-renders system + tools + history
     * + tool result before the model produces its first output token. Gets
     * replaced by the streaming "Generating response (N tokens)…" label as
     * soon as decode begins.
     *
     * Mode-specialised so the user knows what kind of artifact is being
     * built — DrugSafe is composing a safety report, HealthPartner a
     * checklist, ConsentReader a simplification, etc. The fallback string
     * is generic enough to cover any future mode without code changes.
     */
    private fun turnLabelForMode(mode: String): String = when (mode.lowercase()) {
        "drugsafe" -> "Composing safety report…"
        "healthpartner" -> "Building care checklist…"
        "consentreader", "consent" -> "Simplifying clauses…"
        "reportreader" -> "Composing lab summary…"
        else -> "Composing answer…"
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
     * Walks the streaming synthesis buffer and emits [ProgressEvent.FlagPreview]
     * events as each flag object inside `"flags":[ ... ]` closes. Lets the UI
     * render preview SeverityCards before the full AegisResponse arrives —
     * the model emits flags early in the JSON (after `confidence` and
     * `defer_to_professional`), so the first preview typically lands within
     * the first ~30s of the synthesis decode (vs ~80–95s end-to-end).
     *
     * State is per-call (one parser per turn). Tracks:
     *   - `flagsStart` — position right after `"flags":[` once seen
     *   - `cursor`     — position past the last flag we've already reported
     *   - `done`       — set once the closing `]` of the flags array arrives,
     *                    so we stop scanning even if the buffer keeps growing
     *
     * Each call to [extractNewFlags] is incremental: scans from `cursor` to
     * the end of the buffer, returns 0..N completed flags (may be 0 if the
     * latest piece didn't close any object). Re-entrant — safe to call after
     * every streamed piece. Total scan work across the turn is bounded by
     * O(buffer length) because `cursor` only moves forward.
     *
     * Robust to:
     *   - braces inside string values (string- and escape-aware)
     *   - flags array spanning many pieces
     *   - the enclosing AegisResponse `}` arriving before the array's `]`
     *     (rare; we handle it by stopping at `]` first)
     *
     * Does NOT try to parse the `flags` value as a streaming JSON parser
     * end-to-end. It just finds balanced `{...}` substrings inside the
     * array and hands each one to kotlinx Json for full parsing — keeps
     * this code small and reuses the validated parser path.
     */
    private class FlagsStreamParser {
        private var flagsStart = -1
        private var cursor = -1
        private var done = false

        fun extractNewFlags(buffer: CharSequence): List<ProgressEvent.FlagPreview> {
            if (done) return emptyList()
            if (flagsStart < 0) {
                val idx = buffer.indexOf("\"flags\":")
                if (idx < 0) return emptyList()
                // Skip over `"flags":` then any whitespace then the opening `[`.
                var i = idx + "\"flags\":".length
                while (i < buffer.length && buffer[i].isWhitespace()) i++
                if (i >= buffer.length) return emptyList()
                if (buffer[i] != '[') {
                    // Not a flags array (`"flags": null` or similar). Disable.
                    done = true
                    return emptyList()
                }
                flagsStart = i + 1
                cursor = flagsStart
            }

            val out = mutableListOf<ProgressEvent.FlagPreview>()
            while (cursor < buffer.length) {
                when (val next = findNextBalancedObject(buffer, cursor)) {
                    is ScanResult.Found -> {
                        val raw = buffer.substring(next.start, next.end + 1)
                        val preview = parseFlagPreview(raw)
                        if (preview != null) out += preview
                        cursor = next.end + 1
                    }
                    is ScanResult.ArrayClosed -> {
                        done = true
                        cursor = buffer.length
                        return out
                    }
                    is ScanResult.Pending -> {
                        // Buffer ends mid-object — wait for more pieces.
                        return out
                    }
                }
            }
            return out
        }

        /**
         * Scan from [from] for the next balanced `{...}` inside the flags
         * array. Returns Pending if no full object yet, ArrayClosed when we
         * hit the closing `]` first, Found(start, end) on success.
         */
        private fun findNextBalancedObject(buffer: CharSequence, from: Int): ScanResult {
            var depth = 0
            var inString = false
            var escaped = false
            var startIdx = -1
            var i = from
            while (i < buffer.length) {
                val ch = buffer[i]
                if (inString) {
                    when {
                        escaped -> escaped = false
                        ch == '\\' -> escaped = true
                        ch == '"' -> inString = false
                    }
                    i++
                    continue
                }
                when (ch) {
                    '"' -> inString = true
                    '{' -> {
                        if (depth == 0) startIdx = i
                        depth++
                    }
                    '}' -> {
                        depth--
                        if (depth == 0 && startIdx >= 0) return ScanResult.Found(startIdx, i)
                    }
                    ']' -> if (depth == 0) return ScanResult.ArrayClosed
                }
                i++
            }
            return ScanResult.Pending
        }

        private fun parseFlagPreview(rawJson: String): ProgressEvent.FlagPreview? {
            return try {
                val obj = json.parseToJsonElement(rawJson) as? JsonObject ?: return null
                val severity = obj["severity"]?.jsonPrimitive?.intOrNull ?: 0
                val description = (obj["description"] as? JsonPrimitive)?.contentOrEmpty().orEmpty()
                val citation = (obj["citation"] as? JsonPrimitive)?.contentOrEmpty().orEmpty()
                if (description.isBlank() && severity == 0) null
                else ProgressEvent.FlagPreview(
                    severity = severity,
                    description = description,
                    citation = humanizeCitationSource(citation),
                )
            } catch (e: Exception) {
                Log.d(TAG, "Skipping malformed flag preview: ${e.message}")
                null
            }
        }

        private fun JsonPrimitive.contentOrEmpty(): String =
            if (isString) content else content
    }

    private sealed class ScanResult {
        data class Found(val start: Int, val end: Int) : ScanResult()
        object ArrayClosed : ScanResult()
        object Pending : ScanResult()
    }

    /**
     * Counts non-overlapping occurrences of [needle] in [haystack]. Used by
     * the streaming progress label to report how many flags the model has
     * written so far (each flag emits a `"severity":` key). Cheap because
     * the synthesis buffer maxes at ~1.5k chars and we only scan after each
     * decoded piece.
     */
    private fun countOccurrences(haystack: CharSequence, needle: String): Int {
        if (needle.isEmpty()) return 0
        var count = 0
        var idx = 0
        while (true) {
            idx = haystack.indexOf(needle, idx)
            if (idx < 0) break
            count++
            idx += needle.length
        }
        return count
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

    private fun emitFlagPreviewsFromToolResult(
        toolResult: ToolResult,
        onProgress: (ProgressEvent) -> Unit,
    ) {
        if (toolResult.name != "check_warnings") return
        try {
            val element = json.parseToJsonElement(toolResult.result) as? JsonObject ?: return
            val flags = element["flags"] as? JsonArray ?: return
            for (flagElement in flags) {
                val flag = flagElement as? JsonObject ?: continue
                val severity = flag["severity"]?.jsonPrimitive?.intOrNull ?: 0
                val description = flag["description"].asNonBlankString().orEmpty()
                val citation = flag["citation"].asNonBlankString().orEmpty()
                if (description.isNotBlank() || severity > 0) {
                    onProgress(
                        ProgressEvent.FlagPreview(
                            severity = severity,
                            description = compactPreviewText(description),
                            citation = humanizeCitationSource(citation),
                        ),
                    )
                }
            }
        } catch (e: Exception) {
            Log.d(TAG, "Skipping immediate flag previews: ${e.message}")
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

    private fun compactPreviewText(text: String, maxChars: Int = 320): String {
        val compact = text.replace(Regex("""\s+"""), " ").trim()
        return if (compact.length <= maxChars) {
            compact
        } else {
            compact.take(maxChars).trimEnd() + "..."
        }
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
