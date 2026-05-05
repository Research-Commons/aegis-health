package com.aegis.health.camera

import com.aegis.health.db.KBDatabase
import kotlin.math.abs
import kotlin.math.min

/**
 * Filters raw OCR text from a drug-bottle photo down to canonical drug
 * names that exist in the local KB. Used so packaging copy, manufacturer
 * names, dosage instructions, and barcodes do not corrupt the downstream
 * `check_warnings` query.
 *
 * Strategy:
 *  1. Tokenize the OCR blob.
 *  2. Greedy longest-first 1–3-gram match against the KB dictionary
 *     (rxnorm_lookup brand + generic, drug_ingredients).
 *  3. Levenshtein ≤ 2 fuzzy fallback against the same allow-list for
 *     tokens that did not match exactly (handles OCR errors like
 *     `MetfOrmin` → `metformin`). Bucketed by first character to keep
 *     the search bounded.
 */
object DrugNameExtractor {

    private const val MAX_NGRAM = 3
    private const val FUZZY_MIN_LEN = 5
    private const val FUZZY_MAX_DISTANCE = 2

    data class Result(
        val canonical: List<String>,
        val unmatched: Boolean,
    )

    @Volatile
    private var fuzzyIndex: Map<Char, List<String>>? = null

    fun extract(rawText: String, db: KBDatabase): Result {
        if (rawText.isBlank()) return Result(emptyList(), unmatched = true)

        val dict = db.loadDrugDictionary()
        val tokens = tokenize(rawText)
        if (tokens.isEmpty()) return Result(emptyList(), unmatched = true)

        val matched = LinkedHashSet<String>()
        val consumed = BooleanArray(tokens.size)

        // Greedy longest-first n-gram match against the allow-list.
        for (n in MAX_NGRAM downTo 1) {
            var i = 0
            while (i <= tokens.size - n) {
                if (anyConsumed(consumed, i, n)) { i++; continue }
                val phrase = buildPhrase(tokens, i, n)
                val canon = dict[phrase]
                if (canon != null) {
                    matched += canon
                    for (k in i until i + n) consumed[k] = true
                    i += n
                } else {
                    i++
                }
            }
        }

        // Fuzzy fallback for unmatched tokens that look drug-like.
        val byFirstChar = fuzzyIndex(dict)
        for (i in tokens.indices) {
            if (consumed[i]) continue
            val tok = tokens[i]
            if (tok.length < FUZZY_MIN_LEN) continue
            val candidates = byFirstChar[tok[0]] ?: continue

            var bestKey: String? = null
            var bestDist = FUZZY_MAX_DISTANCE + 1
            for (cand in candidates) {
                if (abs(cand.length - tok.length) > FUZZY_MAX_DISTANCE) continue
                val d = levenshtein(tok, cand, FUZZY_MAX_DISTANCE)
                if (d < bestDist) {
                    bestDist = d
                    bestKey = cand
                    if (d == 0) break
                }
            }
            if (bestKey != null) {
                matched += dict[bestKey]!!
                consumed[i] = true
            }
        }

        val list = matched.toList()
        return Result(canonical = list, unmatched = list.isEmpty())
    }

    // ── Internals ───────────────────────────────────────────────────────

    private fun anyConsumed(flags: BooleanArray, start: Int, len: Int): Boolean {
        for (k in start until start + len) if (flags[k]) return true
        return false
    }

    private fun buildPhrase(tokens: List<String>, start: Int, len: Int): String {
        if (len == 1) return tokens[start]
        val sb = StringBuilder()
        for (k in start until start + len) {
            if (k > start) sb.append(' ')
            sb.append(tokens[k])
        }
        return sb.toString()
    }

    private fun tokenize(s: String): List<String> {
        val out = mutableListOf<String>()
        val sb = StringBuilder()
        for (rawCh in s) {
            val ch = rawCh.lowercaseChar()
            if (ch.isLetter() || (ch == '-' && sb.isNotEmpty())) {
                sb.append(ch)
            } else if (sb.isNotEmpty()) {
                out += sb.toString()
                sb.setLength(0)
            }
        }
        if (sb.isNotEmpty()) out += sb.toString()
        return out
    }

    private fun fuzzyIndex(dict: Map<String, String>): Map<Char, List<String>> {
        fuzzyIndex?.let { return it }
        synchronized(this) {
            fuzzyIndex?.let { return it }
            // Single-token entries only — multi-word phrases are handled
            // exclusively by the exact n-gram pass.
            val built = dict.keys
                .asSequence()
                .filter { it.isNotEmpty() && !it.contains(' ') }
                .groupBy { it[0] }
            fuzzyIndex = built
            return built
        }
    }

    /** Levenshtein with early-out once every cell in a row exceeds maxDist. */
    private fun levenshtein(a: String, b: String, maxDist: Int): Int {
        val n = a.length
        val m = b.length
        if (abs(n - m) > maxDist) return maxDist + 1
        if (n == 0) return m
        if (m == 0) return n

        var prev = IntArray(m + 1) { it }
        var cur = IntArray(m + 1)
        for (i in 1..n) {
            cur[0] = i
            var rowMin = cur[0]
            val ai = a[i - 1]
            for (j in 1..m) {
                val cost = if (ai == b[j - 1]) 0 else 1
                cur[j] = min(min(cur[j - 1] + 1, prev[j] + 1), prev[j - 1] + cost)
                if (cur[j] < rowMin) rowMin = cur[j]
            }
            if (rowMin > maxDist) return maxDist + 1
            val tmp = prev; prev = cur; cur = tmp
        }
        return prev[m]
    }
}
