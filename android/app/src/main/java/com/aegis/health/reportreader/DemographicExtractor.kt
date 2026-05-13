package com.aegis.health.reportreader

import com.aegis.health.models.Profile

/**
 * Phase 2 — INPUT-02: extract age + sex (+ pregnancy markers) from the PDF cover sheet.
 *
 * Mirrors `tools/parsers/lab_report_parser.py:_extract_demographics` (lines
 * 1232-1270) row-for-row so the 5 fixture PDFs produce profile_used values
 * byte-identical to their `*-evaluated.json` ground truths:
 *   - mayo_cbc                → {age: 27, sex: "male"}      ("Age / Sex : 27 YRS / M")
 *   - hospital_lipid          → {age: 40, sex: "male"}      ("Age / Sex : 40 Y / M")
 *   - labcorp_lipid_panel     → {age: null, sex: null}      (blank cover-sheet fields)
 *   - quest_cmp               → {age: null, sex: null}      (blank cover-sheet fields)
 *   - urgent_care_a1c         → {age: null, sex: null}      (blank cover-sheet fields)
 *
 * Pattern precedence (mirrors Python):
 *   1. Mayo:         "Age / Sex : 45 YRS / F"     (YRS marker, slash-form)
 *   2. Hospital LIS: "Age / Sex : 40 Y / M"       (Y marker, slash-form)
 *   3. Fallback:     "AGE: 45" + "GENDER: Male"   (LabCorp / Quest header style)
 *
 * Pregnancy detection is a separate boolean utility (`isPregnant`); the `Profile`
 * wire-format type itself only carries (age, sex) per D-03, so pregnancy state
 * propagates via a callsite query in RangeEvaluator (Plan 02-11) when deciding
 * whether to dispatch to `queryPregnancyRange`. Patterns adapted from
 * `.planning/research/PITFALLS.md` §S4 (pregnancy / pediatric / geriatric
 * demographic handling).
 *
 * Pediatric routing happens at RangeEvaluator (when age < 18). This extractor
 * just surfaces the age; routing logic lives downstream.
 *
 * **Privacy (T-02-09-01):** Demographics are PII. Phase 2 carries them only inside
 * the Profile object that flows through PreparsedReport. Phase 3 UI must NOT
 * persist them. This extractor never logs the raw text or the extracted values.
 *
 * **Bounding (T-02-09-02):** Age outside `0..120` returns null (treated as
 * missing demographic, not as the parsed value) — defends against malformed
 * PDFs that print junk into the AGE field.
 */
object DemographicExtractor {

    /**
     * Pregnancy markers per PITFALLS.md §S4. Adding new patterns is additive.
     * Order does not matter — any match returns true.
     */
    private val PREGNANCY_PATTERNS: List<Regex> = listOf(
        Regex("""prenatal\s+panel""", RegexOption.IGNORE_CASE),
        Regex("""gestational\s+age""", RegexOption.IGNORE_CASE),
        Regex("""\btrimester\b""", RegexOption.IGNORE_CASE),
        Regex("""pregnant\s*:\s*yes""", RegexOption.IGNORE_CASE),
    )

    // -- Demographic regex patterns (mirror Python _extract_demographics) --

    /** Mayo: "Age / Sex : 27 YRS / M" — YRS marker. */
    private val MAYO_AGE_SEX = Regex(
        """Age\s*/\s*Sex\s*:\s*(\d{1,3})\s*YRS?\s*/\s*([MF])""",
        RegexOption.IGNORE_CASE,
    )

    /** Hospital LIS: "Age / Sex : 40 Y / M" — Y marker. */
    private val HOSPITAL_AGE_SEX = Regex(
        """Age\s*/\s*Sex\s*:\s*(\d{1,3})\s*Y\s*/\s*([MF])""",
        RegexOption.IGNORE_CASE,
    )

    /** Generic LabCorp / Quest fallback: "AGE: 45". */
    private val GENERIC_AGE = Regex("""\bAGE[:\s]+(\d{1,3})\b""", RegexOption.IGNORE_CASE)

    /** Generic LabCorp / Quest fallback: "GENDER: Male" — Python uses GENDER not SEX. */
    private val GENERIC_GENDER = Regex(
        """\bGENDER[:\s]+(Male|Female|M|F)\b""",
        RegexOption.IGNORE_CASE,
    )

    /**
     * Extract age + sex from page-1 cover sheet text.
     *
     * Returns `Profile(age=null, sex=null)` when:
     *   - `pages` is empty
     *   - no cover-sheet pattern matched
     *   - the parsed age was outside [0, 120] (treated as malformed)
     */
    fun extract(pages: List<String>): Profile {
        if (pages.isEmpty()) return Profile()
        val text = pages.first()

        // Pattern 1: Mayo slash-form with YRS marker → early return on match.
        MAYO_AGE_SEX.find(text)?.let { m ->
            val age = m.groupValues[1].toIntOrNull()?.takeIf { age -> age in 0..120 }
            val sex = normalizeSex(m.groupValues[2])
            return Profile(age = age, sex = sex)
        }
        // Pattern 2: Hospital LIS slash-form with Y marker → early return on match.
        HOSPITAL_AGE_SEX.find(text)?.let { m ->
            val age = m.groupValues[1].toIntOrNull()?.takeIf { age -> age in 0..120 }
            val sex = normalizeSex(m.groupValues[2])
            return Profile(age = age, sex = sex)
        }
        // Pattern 3: generic AGE: / GENDER: — independent matches; either or both may miss.
        val age = GENERIC_AGE.find(text)?.let { m ->
            m.groupValues[1].toIntOrNull()?.takeIf { age -> age in 0..120 }
        }
        val sex = GENERIC_GENDER.find(text)?.let { m ->
            normalizeSex(m.groupValues[1])
        }
        return Profile(age = age, sex = sex)
    }

    /**
     * Returns true when any pregnancy marker fires on page-1 (or page-2 if
     * available — cover sheets occasionally spill across the page break).
     *
     * Callsite: RangeEvaluator (Plan 02-11) — when true, routes the affected
     * rows to `KBDatabase.queryPregnancyRange` instead of the adult default.
     * When the pregnancy KB has no entry for the analyte, RangeEvaluator emits
     * `defer_reason = "kb_no_pregnancy"` per D-12 / INTERPRET-03.
     */
    fun isPregnant(pages: List<String>): Boolean {
        if (pages.isEmpty()) return false
        val haystack = pages.take(2).joinToString("\n")
        return PREGNANCY_PATTERNS.any { it.containsMatchIn(haystack) }
    }

    /** Normalize a single-letter or full-word sex token to "male" / "female". */
    private fun normalizeSex(token: String): String =
        when (token.lowercase()) {
            "m", "male" -> "male"
            "f", "female" -> "female"
            else -> token.lowercase()
        }
}
