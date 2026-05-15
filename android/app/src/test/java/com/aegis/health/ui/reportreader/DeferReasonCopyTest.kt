package com.aegis.health.ui.reportreader

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Phase 3 D-12 contract test — [DeferReasonCopy].ENTRIES must mirror the Phase 2
 * EXTRACTION-SPEC.md 9-entry `defer_reason` vocabulary byte-for-byte on the key
 * side. Drift surfaces as a missing key in production (the `lookup()` fallback
 * masks it) — this test catches it before merge.
 *
 * Also enforces calm-by-default tone (UI-03):
 *   - every value recommends clinician follow-up
 *   - no "good"/"bad"/"abnormal"/"critical"/"warning"/"alert"/"danger" framing
 *   - no exclamation points
 */
class DeferReasonCopyTest {

    /** Canonical 9 keys from .planning/specs/EXTRACTION-SPEC.md / 02-CONTEXT.md D-12. */
    private val expectedKeys = setOf(
        "missing_units",
        "mismatched_units",
        "non_numeric_result",
        "range_unavailable",
        "kb_no_pediatric",
        "kb_no_pregnancy",
        "auto_defer:tumor_marker",
        "auto_defer:genetic",
        "auto_defer:pathology",
    )

    @Test
    fun entries_has_exactly_nine_keys() {
        assertEquals(9, DeferReasonCopy.ENTRIES.size)
    }

    @Test
    fun entries_keys_match_extraction_spec_verbatim() {
        assertEquals(expectedKeys, DeferReasonCopy.ENTRIES.keys)
    }

    @Test
    fun every_value_is_nonblank_and_recommends_clinician() {
        DeferReasonCopy.ENTRIES.forEach { (key, copy) ->
            assertTrue("$key value must not be blank", copy.isNotBlank())
            val lower = copy.lowercase()
            assertTrue(
                "$key value must recommend clinician follow-up (contains 'clinician')",
                lower.contains("clinician"),
            )
        }
    }

    @Test
    fun no_entry_uses_good_bad_or_panic_framing() {
        val forbidden = listOf("good", "bad", "abnormal", "critical", "warning", "alert", "danger")
        DeferReasonCopy.ENTRIES.forEach { (key, copy) ->
            val lower = copy.lowercase()
            forbidden.forEach { word ->
                assertFalse(
                    "$key copy contains forbidden word '$word' — UI-03 calm-by-default violation",
                    lower.contains(word),
                )
            }
            assertFalse(
                "$key copy must not contain exclamation points — UI-03 calm tone",
                copy.contains("!"),
            )
        }
    }

    @Test
    fun lookup_returns_calm_fallback_for_unknown_code() {
        val result = DeferReasonCopy.lookup("definitely_not_a_real_code")
        assertNotNull(result)
        assertTrue(result.isNotBlank())
        // Fallback must also be calm-tone.
        val lower = result.lowercase()
        assertFalse(
            "Fallback must not use 'good'/'bad' framing",
            lower.contains("good") || lower.contains("bad"),
        )
        assertFalse("Fallback must not use exclamation", result.contains("!"))
        // WR-04: fallback must also recommend clinician follow-up to honour
        // the file-level KDoc contract and stay consistent with ENTRIES.
        assertTrue(
            "Fallback must recommend clinician follow-up",
            lower.contains("clinician"),
        )
    }

    @Test
    fun lookup_returns_mapped_value_for_known_code() {
        // Spot-check entries against the Map directly to confirm lookup() routes through ENTRIES.
        assertEquals(
            DeferReasonCopy.ENTRIES["mismatched_units"],
            DeferReasonCopy.lookup("mismatched_units"),
        )
        assertEquals(
            DeferReasonCopy.ENTRIES["auto_defer:tumor_marker"],
            DeferReasonCopy.lookup("auto_defer:tumor_marker"),
        )
    }

    /**
     * Phase 4.1 D-06 + RESEARCH.md Pitfall 4 — GenericFallbackBanner copy must
     * satisfy the same UI-03 calm-tone invariant that [DeferReasonCopy.ENTRIES]
     * satisfies, plus a positive "verify"-affordance requirement (the banner
     * exists to tell the user the rows below are best-effort and need PDF
     * verification, so the verb "verify" MUST appear).
     *
     * The constant is hoisted as a top-level `const val` at the bottom of
     * [GenericFallbackBanner.kt] so future grep audits can locate it; this
     * test scans the same hoisted constant.
     */
    @Test
    fun banner_copy_uses_calm_vocabulary() {
        val copy = GENERIC_FALLBACK_BANNER_COPY
        // Test 4: non-blank.
        assertTrue("GENERIC_FALLBACK_BANNER_COPY must not be blank", copy.isNotBlank())

        // Test 1: forbidden-word list mirrors no_entry_uses_good_bad_or_panic_framing.
        val forbidden = listOf("good", "bad", "abnormal", "critical", "warning", "alert", "danger")
        val lower = copy.lowercase()
        forbidden.forEach { word ->
            assertFalse(
                "GENERIC_FALLBACK_BANNER_COPY contains forbidden word '$word' — UI-03 calm-by-default violation",
                lower.contains(word),
            )
        }

        // Test 2: no exclamation marks — UI-03 calm tone.
        assertFalse(
            "GENERIC_FALLBACK_BANNER_COPY must not contain exclamation points — UI-03 calm tone",
            copy.contains("!"),
        )

        // Test 3: positive verify-affordance assertion (D-06 + CONTEXT.md
        // Claude Discretion: must include "verify against your PDF" affordance).
        assertTrue(
            "GENERIC_FALLBACK_BANNER_COPY must contain the verify-affordance substring 'verify'",
            lower.contains("verify"),
        )
    }
}
