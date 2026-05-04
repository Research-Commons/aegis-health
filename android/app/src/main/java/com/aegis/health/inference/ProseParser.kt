package com.aegis.health.inference

import com.aegis.health.models.AegisResponse
import com.aegis.health.models.Citation
import com.aegis.health.models.Flag

/**
 * Extracts an AegisResponse from unstructured prose when the model
 * does not emit valid JSON. Mirrors the NLP/regex logic in baseline_scorer.py.
 *
 * Confidence is always ≤ 0.5 to signal that no KB grounding occurred.
 */
object ProseParser {

    private val DEFER_PATTERNS = listOf(
        Regex("""consult\s+(a\s+)?(doctor|physician|pharmacist|healthcare|professional)""", RegexOption.IGNORE_CASE),
        Regex("""seek\s+medical""", RegexOption.IGNORE_CASE),
        Regex("""talk\s+to\s+(your\s+)?(doctor|pharmacist)""", RegexOption.IGNORE_CASE),
        Regex("""do\s+not\s+(take|use|combine)\s+without""", RegexOption.IGNORE_CASE),
        Regex("""immediately\s+(contact|call|see)\s+""", RegexOption.IGNORE_CASE),
        Regex("""emergency|call\s+911|go\s+to\s+(the\s+)?er""", RegexOption.IGNORE_CASE),
    )

    private val SEVERITY_SIGNALS = listOf(
        5 to Regex("""life.?threatening|fatal|death|emergency|call\s+911""", RegexOption.IGNORE_CASE),
        4 to Regex("""serious|major|severe|dangerous|significant\s+risk|avoid""", RegexOption.IGNORE_CASE),
        3 to Regex("""moderate|caution|monitor|carefully|increase\s+risk""", RegexOption.IGNORE_CASE),
        2 to Regex("""mild|minor|small\s+risk|low\s+risk""", RegexOption.IGNORE_CASE),
    )

    private val INTERACTION_SENTENCE = Regex(
        """[^.!?]*(?:interact|combination|together|concurrent|co-administer|bleeding|toxicity|overdose)[^.!?]*[.!?]""",
        RegexOption.IGNORE_CASE,
    )

    fun parse(text: String, mode: String = "drugsafe"): AegisResponse {
        // Smart pre-pass: when the model emitted JSON-shaped output that failed
        // to deserialize (malformed keys, wrong flag schema, missing braces,
        // etc.), pull the legible explanation field out via regex rather than
        // dumping the raw JSON literal as the explanation text. Mirrors what
        // the user sees on screen.
        val jsonExplanation = extractJsonStringField(text, "explanation")
        val jsonConfidence = extractJsonNumberField(text, "confidence")
        val jsonDefer = extractJsonBooleanField(text, "defer_to_professional")

        if (jsonExplanation != null) {
            val severity = detectSeverity(jsonExplanation)
            val deferFromExplanation = DEFER_PATTERNS.any { it.containsMatchIn(jsonExplanation) }
            return AegisResponse(
                flags = emptyList(),
                citations = emptyList(),
                confidence = (jsonConfidence ?: 0.5).coerceIn(0.0, 1.0),
                defer_to_professional = jsonDefer ?: (deferFromExplanation || severity >= 4),
                explanation = jsonExplanation,
            )
        }

        val defer = DEFER_PATTERNS.any { it.containsMatchIn(text) }
        val severity = detectSeverity(text)
        val flags = extractFlags(text, severity)
        val confidence = when {
            severity >= 4 -> 0.45
            severity >= 3 -> 0.40
            else -> 0.30
        }

        val explanation = text.trim().take(600).let {
            if (it.length == 600) "$it…" else it
        }

        return AegisResponse(
            flags = flags,
            citations = listOf(
                Citation(
                    source = "AI Assessment (Unverified)",
                    text = "This response was generated without KB grounding. No citations available.",
                )
            ),
            confidence = confidence,
            defer_to_professional = defer || severity >= 4,
            explanation = explanation,
        )
    }

    private fun extractJsonStringField(text: String, key: String): String? {
        val pattern = Regex(""""${Regex.escape(key)}"\s*:\s*"((?:\\.|[^"\\])*)"""")
        val match = pattern.find(text) ?: return null
        return unescapeJsonString(match.groupValues[1]).takeIf { it.isNotBlank() }
    }

    private fun extractJsonNumberField(text: String, key: String): Double? {
        val pattern = Regex(""""${Regex.escape(key)}"\s*:\s*(-?\d+(?:\.\d+)?)""")
        return pattern.find(text)?.groupValues?.get(1)?.toDoubleOrNull()
    }

    private fun extractJsonBooleanField(text: String, key: String): Boolean? {
        val pattern = Regex(""""${Regex.escape(key)}"\s*:\s*(true|false)""")
        return pattern.find(text)?.groupValues?.get(1)?.toBooleanStrictOrNull()
    }

    private fun unescapeJsonString(raw: String): String {
        return buildString {
            var i = 0
            while (i < raw.length) {
                val c = raw[i]
                if (c == '\\' && i + 1 < raw.length) {
                    when (raw[i + 1]) {
                        'n' -> append('\n')
                        't' -> append('\t')
                        'r' -> append('\r')
                        '"' -> append('"')
                        '\\' -> append('\\')
                        '/' -> append('/')
                        else -> { append(c); append(raw[i + 1]) }
                    }
                    i += 2
                } else {
                    append(c)
                    i++
                }
            }
        }
    }

    private fun detectSeverity(text: String): Int {
        for ((level, pattern) in SEVERITY_SIGNALS) {
            if (pattern.containsMatchIn(text)) return level
        }
        return 1
    }

    private fun extractFlags(text: String, topSeverity: Int): List<Flag> {
        if (topSeverity < 2) return emptyList()

        val sentences = INTERACTION_SENTENCE.findAll(text)
            .map { it.value.trim() }
            .filter { it.length in 20..300 }
            .take(3)
            .toList()

        return sentences.map { sentence ->
            val sev = SEVERITY_SIGNALS.firstOrNull { (_, pat) -> pat.containsMatchIn(sentence) }?.first ?: topSeverity
            Flag(
                severity = sev,
                description = sentence,
                citation = "AI Assessment (Unverified — no KB lookup performed)",
            )
        }
    }
}
