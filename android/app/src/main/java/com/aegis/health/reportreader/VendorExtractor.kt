package com.aegis.health.reportreader

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive

/**
 * Phase 2 — Stage 2 vendor strategy.
 *
 * One implementation per vendor (LabCorp, Quest, Mayo, hospital LIS, urgent care).
 * Each implementation is an `object` (no state). VendorRegistry holds the list +
 * fingerprintMatches dispatch. Adding a 6th vendor = new file + register in
 * registry; no `when`-exhaustiveness churn (D-02).
 */
interface VendorExtractor {
    /** Stable short-code; matches Python _detect_vendor return values. */
    val vendorKey: String

    /** Page-1 header fingerprint test (case-insensitive on a lowercased haystack). */
    fun fingerprintMatches(page1Lower: String): Boolean

    /** Extract raw rows from the joined page text. */
    fun extract(pagesText: List<String>): List<ParsedRow>
}

/**
 * Pre-normalization row carrying PDF-native values.
 *
 * LM-3: value, refLow, refHigh are JsonElement (JsonPrimitive int / float / JsonNull)
 * to preserve int/float discrimination per D-07. Widening to Double will collapse
 * `151` → `151.0` and break byte-identity vs the GT JSONs.
 */
data class ParsedRow(
    val rawName: String,
    val value: JsonElement = JsonNull,
    val units: String? = null,
    val refLow: JsonElement = JsonNull,
    val refHigh: JsonElement = JsonNull,
)

/** Standard NUM regex (signed integer or decimal). */
internal const val NUM = """-?\d+(?:\.\d+)?"""

/**
 * LM-3: Build a JsonPrimitive preserving the input token's int-or-float nature.
 *
 * - "151"       → JsonPrimitive(151L)        (integer literal)
 * - "5.0"       → JsonPrimitive(5.0)         (float literal — stays 5.0 in canonicalized JSON)
 * - "5,100"     → JsonPrimitive(5100L)       (thousand-separator stripped; Mayo)
 * - null / ""   → JsonNull
 * - "abc"       → JsonNull                   (unparseable; caller may emit defer_reason)
 *
 * Mirrors `tools/parsers/lab_report_parser.py:_num` (lines 1064-1088) row-for-row:
 * strips commas first, then dispatches on the presence of '.' / 'e' / 'E' to
 * preserve the int-vs-float distinction the GT JSONs depend on.
 */
internal fun numLiteral(token: String?): JsonElement {
    if (token == null) return JsonNull
    val cleaned = token.replace(",", "").trim()
    if (cleaned.isEmpty()) return JsonNull
    return if ("." in cleaned || "e" in cleaned || "E" in cleaned) {
        cleaned.toDoubleOrNull()?.let { JsonPrimitive(it) } ?: JsonNull
    } else {
        cleaned.toLongOrNull()?.let { JsonPrimitive(it) } ?: JsonNull
    }
}
