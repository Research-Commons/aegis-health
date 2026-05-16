package com.aegis.health.inference

import com.aegis.health.models.ToolCall
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Parametrized contract test for [FriendlyToolSummarizer]. Pins one expected
 * sentence per (tool, args) tuple per Plan 05-03 / Phase 5 SC-3.
 *
 * Covers:
 *   - D-01 per-tool args-aware sentences for all 7 tool names (6 real + synthetic
 *     read_lab_report).
 *   - D-02 multi-value truncation (first 2 inline + "+N more" for >2 items).
 *   - D-03 graceful fallback on unknown tool name, malformed args, and empty
 *     args. Never throws.
 *   - D-04 single 7-entry when block (exercised via the per-tool tests).
 *   - D-07 private friendlyToolLabel fallback (exercised via the malformed-args
 *     and empty-args fallback tests).
 *
 * JUnit4 + per-test helpers per the project's only test idiom (no
 * @RunWith(Parameterized::class) — junit:junit:4.13.2 is the only test dep).
 */
class FriendlyToolSummarizerTest {

    // ── Helpers ─────────────────────────────────────────────────────────

    private fun call(name: String, args: Map<String, JsonElement> = emptyMap()): ToolCall =
        ToolCall(name = name, arguments = args)

    private fun str(s: String): JsonElement = JsonPrimitive(s)
    private fun int(i: Int): JsonElement = JsonPrimitive(i)
    private fun bool(b: Boolean): JsonElement = JsonPrimitive(b)
    private fun arr(vararg s: String): JsonArray = buildJsonArray { s.forEach { add(JsonPrimitive(it)) } }

    // ── D-01 + D-04: per-tool args-aware sentences (7 tools) ────────────

    @Test fun summarize_normalizes_drug_with_inline_name() {
        val tc = call("normalize_drug", mapOf("name" to str("Coumadin")))
        assertEquals("Looking up Coumadin → generic name", FriendlyToolSummarizer.summarize(tc))
    }

    @Test fun summarize_decomposes_product_with_inline_name() {
        val tc = call("decompose_product", mapOf("name" to str("Excedrin")))
        assertEquals("Decomposing Excedrin ingredients", FriendlyToolSummarizer.summarize(tc))
    }

    @Test fun summarize_get_drug_info_returns_name_agnostic_label() {
        // rxcui→name resolution is deferred (CONTEXT.md <deferred_ideas>); Phase 5
        // ships the name-agnostic form regardless of args.
        val tc = call("get_drug_info", mapOf("rxcui" to int(11289)))
        assertEquals("Loading drug info", FriendlyToolSummarizer.summarize(tc))
    }

    @Test fun summarize_check_warnings_two_drugs_with_age() {
        val tc = call(
            "check_warnings",
            mapOf(
                "drug_list" to arr("warfarin", "aspirin"),
                "age" to int(72),
                "conditions" to arr(),
            ),
        )
        assertEquals(
            "Checking warfarin + aspirin for a 72-year-old",
            FriendlyToolSummarizer.summarize(tc),
        )
    }

    @Test fun summarize_check_warnings_four_drugs_truncates_to_first_two_plus_count() {
        // D-02: lists >2 truncate as `first, second, +N more`.
        val tc = call(
            "check_warnings",
            mapOf(
                "drug_list" to arr("a", "b", "c", "d"),
                "age" to int(72),
            ),
        )
        assertEquals(
            "Checking a, b, +2 more for a 72-year-old",
            FriendlyToolSummarizer.summarize(tc),
        )
    }

    @Test fun summarize_check_warnings_single_drug_no_age() {
        val tc = call(
            "check_warnings",
            mapOf("drug_list" to arr("warfarin")),
        )
        assertEquals("Checking warfarin", FriendlyToolSummarizer.summarize(tc))
    }

    @Test fun summarize_lookup_term_quotes_the_term() {
        val tc = call("lookup_term", mapOf("term" to str("creatinine")))
        assertEquals("Looking up \"creatinine\"", FriendlyToolSummarizer.summarize(tc))
    }

    @Test fun summarize_get_guideline_includes_age_and_sex() {
        val tc = call(
            "get_guideline",
            mapOf(
                "age" to int(45),
                "sex" to str("male"),
                "conditions" to arr(),
            ),
        )
        assertEquals(
            "Pulling preventive-care checklist for 45-year-old male",
            FriendlyToolSummarizer.summarize(tc),
        )
    }

    @Test fun summarize_read_lab_report_with_outside_range_flag() {
        val tc = call(
            "read_lab_report",
            mapOf(
                "rows" to int(12),
                "outside_range" to bool(true),
            ),
        )
        assertEquals(
            "Reading 12 lab values (some outside range)",
            FriendlyToolSummarizer.summarize(tc),
        )
    }

    @Test fun summarize_read_lab_report_all_in_range() {
        val tc = call(
            "read_lab_report",
            mapOf(
                "rows" to int(8),
                "outside_range" to bool(false),
            ),
        )
        assertEquals("Reading 8 lab values", FriendlyToolSummarizer.summarize(tc))
    }

    // ── D-03 + D-07: graceful fallback (3 cases) ───────────────────────

    @Test fun summarize_graceful_fallback_on_unknown_tool_name() {
        // D-03: unknown tool name → friendlyToolLabel else branch.
        val tc = call("totally_unknown_tool")
        assertEquals("Running totally_unknown_tool", FriendlyToolSummarizer.summarize(tc))
    }

    @Test fun summarize_graceful_fallback_on_malformed_args_known_tool() {
        // D-03: known tool but args malformed (drug_list is JsonNull, not a
        // JsonArray) → falls back to the migrated friendlyToolLabel mapping.
        val tc = call("check_warnings", mapOf("drug_list" to JsonNull))
        assertEquals("Checking interactions", FriendlyToolSummarizer.summarize(tc))
    }

    @Test fun summarize_graceful_fallback_on_empty_args_known_tool() {
        // D-03: known tool, empty args → falls back to the migrated
        // friendlyToolLabel mapping (no exception thrown).
        val tc = call("check_warnings", emptyMap())
        assertEquals("Checking interactions", FriendlyToolSummarizer.summarize(tc))
    }
}
