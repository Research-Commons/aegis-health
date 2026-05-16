package com.aegis.health.ui.theme

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Phase 8 POLISH-02 contract test — pins the [tokenForStatus] +
 * [statusLabel] helpers (Theme.kt) against the four canonical
 * ReportReader status codes plus the calm-by-default fall-back.
 *
 * Decisions referenced:
 *   - D-02a: helper split into Pair<Color, Color> (bg, fg) + String label
 *   - D-02b: status is non-null String
 *   - D-02c: unrecognized strings fall back to IN_RANGE tokens (calm)
 *   - D-02d: strict-case match — no case-normalization
 *
 * "unknown" is intentionally lowercase (Phase 3 EvaluatedRow.status
 * schema). The other three canonical codes are UPPER_SNAKE_CASE. The
 * `else` arm covers both the explicit "IN_RANGE" string and any drift.
 *
 * Pure JVM — no Android dependencies. Lives under app/src/test/ to
 * bypass TEST-FRAMEWORK-01 (Compose UI androidTest framework regression
 * on SM-S918B + BOM 2026.05.00, deferred to Phase 10 P1).
 */
class ThemeStatusHelpersTest {

    @Test
    fun tokenForStatus_outsideRange_returnsSevCritPair() {
        assertEquals(
            LightAegisColors.sevCritBg to LightAegisColors.sevCritFg,
            tokenForStatus("OUTSIDE_RANGE", LightAegisColors),
        )
    }

    @Test
    fun tokenForStatus_borderline_returnsSevModPair() {
        assertEquals(
            LightAegisColors.sevModBg to LightAegisColors.sevModFg,
            tokenForStatus("BORDERLINE", LightAegisColors),
        )
    }

    @Test
    fun tokenForStatus_unknown_returnsSevLowPair() {
        // D-02d: "unknown" is lowercase per the Phase 3 schema —
        // strict-case match preserves this asymmetry intentionally.
        assertEquals(
            LightAegisColors.sevLowBg to LightAegisColors.sevLowFg,
            tokenForStatus("unknown", LightAegisColors),
        )
    }

    @Test
    fun tokenForStatus_unrecognized_fallsBackToInRangeTokens() {
        // D-02c: unrecognized strings AND the canonical IN_RANGE both
        // resolve through the else arm to calm surfaceAlt + onSurfaceMuted.
        val inRangeFallback = LightAegisColors.surfaceAlt to LightAegisColors.onSurfaceMuted
        assertEquals(inRangeFallback, tokenForStatus("garbage_value", LightAegisColors))
        assertEquals(inRangeFallback, tokenForStatus("IN_RANGE", LightAegisColors))
    }

    @Test
    fun statusLabel_returnsByteIdenticalLabelsToOldTripleBlock() {
        // Labels MUST match StatusBadge.kt:34-38 byte-identically — TalkBack
        // contentDescription "Status: $label" is asserted by 9 existing
        // androidTests; any drift here breaks them silently.
        assertEquals("Outside range", statusLabel("OUTSIDE_RANGE"))
        assertEquals("Borderline", statusLabel("BORDERLINE"))
        assertEquals("Review", statusLabel("unknown"))
        assertEquals("In range", statusLabel("IN_RANGE"))
        // D-02d strict-case lock: an UPPERCASE_UNKNOWN drift hits the else.
        assertEquals("In range", statusLabel("UPPERCASE_UNKNOWN"))
        // Empty-string defensive — should also fall through to IN_RANGE.
        assertEquals("In range", statusLabel(""))
    }
}
