package com.aegis.health.inference

import com.aegis.health.ui.reportreader.AegisResponseBuilder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Contract test for the D-04 four-step sanitization cascade. Drives the
 * production sanitizeExplanation function directly (internal visibility,
 * same module). Every reject code documented in CONTEXT.md D-04 must have
 * at least one positive test; every accept case must round-trip the input.
 *
 * Order matters in D-04: empty → format_leak → diagnostic_phrase → length_cap.
 * Tests assert ordering implicitly by exercising the path each input takes.
 *
 * ToolDispatcher is declared `object` (singleton) so sanitizeExplanation
 * is called as `ToolDispatcher.sanitizeExplanation(input)` — no instantiation.
 */
class SynthesisSanitizerTest {

    private fun sanitize(input: String): Pair<String, String?> =
        ToolDispatcher.sanitizeExplanation(input)

    // ── Step 1: empty / whitespace ──────────────────────────────────────

    @Test fun empty_string_rejects_with_code_empty() {
        val (text, code) = sanitize("")
        assertEquals(AegisResponseBuilder.FIXED_EXPLANATION, text)
        assertEquals("empty", code)
    }

    @Test fun whitespace_only_rejects_with_code_empty() {
        val (text, code) = sanitize("   \n\t  ")
        assertEquals(AegisResponseBuilder.FIXED_EXPLANATION, text)
        assertEquals("empty", code)
    }

    // ── Step 2: structural leak ─────────────────────────────────────────

    @Test fun tool_call_token_rejects_with_format_leak() {
        val (text, code) = sanitize("response: <|tool_call>call:x{}<tool_call|>")
        assertEquals(AegisResponseBuilder.FIXED_EXPLANATION, text)
        assertEquals("format_leak", code)
    }

    @Test fun tool_response_token_rejects_with_format_leak() {
        val (text, code) = sanitize("normal text <|tool_response>foo<tool_response|>")
        assertEquals(AegisResponseBuilder.FIXED_EXPLANATION, text)
        assertEquals("format_leak", code)
    }

    @Test fun balanced_json_object_rejects_with_format_leak() {
        val (text, code) = sanitize("trailing leak {\"foo\":\"bar\"} extra")
        assertEquals(AegisResponseBuilder.FIXED_EXPLANATION, text)
        assertEquals("format_leak", code)
    }

    // ── Step 3: diagnostic phrase (two-leg rule) ────────────────────────

    @Test fun diagnostic_verb_with_disease_noun_rejects() {
        val (text, code) = sanitize("your LDL indicates diabetes")
        assertEquals(AegisResponseBuilder.FIXED_EXPLANATION, text)
        assertEquals("diagnostic_phrase", code)
    }

    @Test fun you_may_have_disease_rejects() {
        val (text, code) = sanitize("you may have hypertension based on these values")
        assertEquals(AegisResponseBuilder.FIXED_EXPLANATION, text)
        assertEquals("diagnostic_phrase", code)
    }

    @Test fun diagnostic_verb_alone_passes() {
        // Verb hits but no disease noun → must pass per D-04 two-leg rule.
        val (text, code) = sanitize("this means your value is elevated.")
        assertEquals("this means your value is elevated.", text)
        assertNull(code)
    }

    @Test fun disease_noun_alone_passes() {
        // Disease noun hits but no diagnostic verb → must pass per D-04 two-leg rule.
        val (text, code) = sanitize("your cholesterol is in the range printed on the report.")
        assertEquals("your cholesterol is in the range printed on the report.", text)
        assertNull(code)
    }

    // ── Step 4: length cap ──────────────────────────────────────────────

    @Test fun length_exactly_280_passes_unchanged() {
        val s = "a".repeat(280)
        val (text, code) = sanitize(s)
        assertEquals(s, text)
        assertNull(code)
    }

    @Test fun length_over_280_no_boundary_hard_cuts_at_280() {
        val s = "a".repeat(300)
        val (text, code) = sanitize(s)
        assertEquals(280, text.length)
        assertEquals("too_long_truncated", code)
        assertFalse(text.endsWith("…"))  // D-04 explicit: "hard-cut at 280 with no ellipsis"
    }

    @Test fun length_over_280_with_boundary_truncates_at_last_sentence() {
        // Construct: "Sentence one. " (14) + "Sentence two. " (14) + 260 'a's = 288 total.
        // First 280 chars contains both sentence boundaries; truncation should land at the
        // last `.` within the first 280, leaving the long padding off.
        val padding = "a".repeat(260)
        val s = "Sentence one. Sentence two. $padding"
        val (text, code) = sanitize(s)
        assertEquals("too_long_truncated", code)
        assertTrue(
            "Truncated text must end with a sentence-boundary character",
            text.endsWith(".") || text.endsWith("!") || text.endsWith("?"),
        )
        assertTrue("Truncated text must be <= 280 chars", text.length <= 280)
    }

    // ── Happy path ──────────────────────────────────────────────────────

    @Test fun normal_one_sentence_summary_passes_unchanged() {
        val s = "Three values are outside the printed range; bring this to your clinician."
        val (text, code) = sanitize(s)
        assertEquals(s, text)
        assertNull(code)
    }

    @Test fun summary_mentioning_lab_count_passes() {
        val s = "Two values were outside the printed range and one could not be evaluated."
        val (text, code) = sanitize(s)
        assertEquals(s, text)
        assertNull(code)
    }
}
