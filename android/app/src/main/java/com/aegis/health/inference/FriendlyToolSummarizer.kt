package com.aegis.health.inference

import android.util.Log
import com.aegis.health.models.ToolCall
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive

/**
 * Args-aware human-readable label for a tool call. See
 * `.planning/phases/05-stepper-streaming-infrastructure/05-CONTEXT.md` D-01..D-04
 * and D-07 for the contract.
 *
 * Public API: [summarize] takes a [ToolCall] and returns a demo-readable sentence
 * with a ~60-char soft cap. Args-aware: pulls the most demo-relevant arg per tool
 * (drug names for check_warnings / normalize_drug, term for lookup_term, age + row
 * count for read_lab_report, etc.).
 *
 * Graceful fallback (D-03): returns [friendlyToolLabel] on any exception, never
 * throws. Logs the failure at `Log.w(TAG, ...)`.
 *
 * D-02 multi-value truncation: lists of >2 items render `first, second, +N more`
 * to keep the demo-value of seeing real drug names without overflowing the
 * ~60-char soft cap.
 *
 * D-07 invariant: the private [friendlyToolLabel] fallback lives ONLY in this
 * file. No caller outside this file references it — see SC-4 phase-close grep
 * gate.
 */
object FriendlyToolSummarizer {

    private const val TAG = "FriendlyToolSummarizer"

    /**
     * Returns an args-aware human-readable sentence for [toolCall]. Single
     * 7-entry `when (toolCall.name)` per D-04 covering the 6 real tools plus the
     * synthetic `read_lab_report`. On any exception, falls back to
     * [friendlyToolLabel] for known names or `"Running $name"` for unknowns.
     */
    fun summarize(toolCall: ToolCall): String {
        return try {
            when (toolCall.name) {
                "normalize_drug" -> {
                    val name = toolCall.arguments["name"]?.jsonPrimitive?.contentOrNull
                    if (name.isNullOrBlank()) friendlyToolLabel(toolCall.name)
                    else "Looking up $name → generic name"
                }
                "decompose_product" -> {
                    val name = toolCall.arguments["name"]?.jsonPrimitive?.contentOrNull
                    if (name.isNullOrBlank()) friendlyToolLabel(toolCall.name)
                    else "Decomposing $name ingredients"
                }
                "get_drug_info" -> {
                    // rxcui→name resolution is a deferred stretch per CONTEXT.md
                    // <deferred_ideas>; Phase 5 keeps the name-agnostic form.
                    "Loading drug info"
                }
                "check_warnings" -> summarizeCheckWarnings(toolCall.arguments)
                "lookup_term" -> {
                    val term = toolCall.arguments["term"]?.jsonPrimitive?.contentOrNull
                    if (term.isNullOrBlank()) friendlyToolLabel(toolCall.name)
                    else "Looking up \"$term\""
                }
                "get_guideline" -> summarizeGetGuideline(toolCall.arguments)
                "read_lab_report" -> summarizeReadLabReport(toolCall.arguments)
                else -> friendlyToolLabel(toolCall.name)
            }
        } catch (e: Exception) {
            Log.w(TAG, "summarize failed for ${toolCall.name}: ${toolCall.arguments}", e)
            friendlyToolLabel(toolCall.name)
        }
    }

    private fun summarizeCheckWarnings(args: Map<String, JsonElement>): String {
        val drugList = args["drug_list"]?.takeIf { it is JsonArray }?.jsonArray
            ?.mapNotNull { it.jsonPrimitive.contentOrNull?.takeIf { s -> s.isNotBlank() } }
            ?: emptyList()
        if (drugList.isEmpty()) {
            // Known tool with bad / empty drug_list → fall back to name-only label.
            return friendlyToolLabel("check_warnings")
        }
        val drugsPart = truncateList(drugList, joinerForTwo = " + ")
        val age = args["age"]?.jsonPrimitive?.intOrNull
        return if (age != null) {
            "Checking $drugsPart for a $age-year-old"
        } else {
            "Checking $drugsPart"
        }
    }

    private fun summarizeGetGuideline(args: Map<String, JsonElement>): String {
        val age = args["age"]?.jsonPrimitive?.intOrNull
        val sex = args["sex"]?.jsonPrimitive?.contentOrNull
        if (age == null || sex.isNullOrBlank()) {
            return friendlyToolLabel("get_guideline")
        }
        return "Pulling preventive-care checklist for $age-year-old $sex"
    }

    private fun summarizeReadLabReport(args: Map<String, JsonElement>): String {
        val rows = args["rows"]?.jsonPrimitive?.intOrNull
        if (rows == null) {
            return friendlyToolLabel("read_lab_report")
        }
        val outsideRange = args["outside_range"]?.jsonPrimitive?.booleanOrNull ?: false
        return if (outsideRange) {
            "Reading $rows lab values (some outside range)"
        } else {
            "Reading $rows lab values"
        }
    }

    /**
     * Truncation helper per D-02. Returns:
     *   - `items[0]` if size == 1
     *   - `"${items[0]}{joinerForTwo}${items[1]}"` if size == 2
     *   - `"${items[0]}, ${items[1]}, +${items.size - 2} more"` if size >= 3
     *
     * For 3+ items the joiner is hardcoded `", "` because mixed-joiner truncation
     * reads better as `"a, b, +2 more"` than `"a + b + 2 more"`.
     */
    private fun truncateList(items: List<String>, joinerForTwo: String): String {
        return when {
            items.size == 1 -> items[0]
            items.size == 2 -> "${items[0]}$joinerForTwo${items[1]}"
            else -> "${items[0]}, ${items[1]}, +${items.size - 2} more"
        }
    }

    /**
     * Migrated copy of the old `ToolDispatcher.friendlyToolLabel(name)` body
     * (per D-07). The same 6-entry mapping survives here as a private fallback,
     * called only on graceful-fallback paths and the `else ->` branch of the
     * public [summarize] when block.
     */
    private fun friendlyToolLabel(name: String): String = when (name) {
        "normalize_drug" -> "Normalizing drug names"
        "decompose_product" -> "Decomposing products"
        "check_warnings" -> "Checking interactions"
        "lookup_term" -> "Looking up term"
        "get_drug_info" -> "Loading drug info"
        "get_guideline" -> "Pulling USPSTF guidelines"
        else -> "Running $name"
    }
}
