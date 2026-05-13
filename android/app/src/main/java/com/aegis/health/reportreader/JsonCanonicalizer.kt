package com.aegis.health.reportreader

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * D-06 canonicalizer: kotlinx-serialization JsonElement walker.
 *
 * Output convention (matches Python json.dumps(obj, indent=2, sort_keys=True,
 * ensure_ascii=False) + "\n"):
 *   - 2-space indent at each nesting level
 *   - Keys sorted alphabetically at every object
 *   - Trailing newline
 *   - UTF-8, no BOM
 *   - Lowercase booleans
 *   - LM-3 int/float fidelity: integer literals stay integer (151), float
 *     literals stay float (5.0). No collapse, no widening.
 *
 * Round-trip test (JsonCanonicalizerTest): parse a GT JSON -> canonicalize ->
 * assert string-equality with the original GT bytes. If this fails, the
 * canonicalizer is wrong and no fixture parity test will pass.
 *
 * Lives in `main/` (not `androidTest/`) so both JVM unit tests AND androidTest
 * fixture tests can use it. Phase 4 may also need it for the synthesis-turn
 * fake tool_response payload.
 */
object JsonCanonicalizer {

    /** JSON instance configured for parsing -- preserves number types. */
    val Codec: Json = Json {
        ignoreUnknownKeys = false
        prettyPrint = false
        encodeDefaults = true
    }

    /** Convenience: parse a string, then canonicalize. */
    fun parseAndCanonicalize(jsonText: String): String =
        canonicalize(Codec.parseToJsonElement(jsonText))

    /** Canonicalize an in-memory element to the conventional string form. */
    fun canonicalize(element: JsonElement): String = buildString {
        renderInto(element, this, depth = 0)
        append('\n')
    }

    private fun renderInto(el: JsonElement, sb: StringBuilder, depth: Int) {
        when (el) {
            is JsonObject -> {
                if (el.isEmpty()) {
                    sb.append("{}")
                    return
                }
                sb.append("{\n")
                val pad = "  ".repeat(depth + 1)
                val keys = el.keys.sorted()
                for ((i, k) in keys.withIndex()) {
                    sb.append(pad).append('"').append(escape(k)).append("\": ")
                    renderInto(el.getValue(k), sb, depth + 1)
                    if (i < keys.size - 1) sb.append(',')
                    sb.append('\n')
                }
                sb.append("  ".repeat(depth)).append('}')
            }
            is JsonArray -> {
                if (el.isEmpty()) {
                    sb.append("[]")
                    return
                }
                sb.append("[\n")
                val pad = "  ".repeat(depth + 1)
                for ((i, e) in el.withIndex()) {
                    sb.append(pad)
                    renderInto(e, sb, depth + 1)
                    if (i < el.size - 1) sb.append(',')
                    sb.append('\n')
                }
                sb.append("  ".repeat(depth)).append(']')
            }
            is JsonNull -> sb.append("null")
            is JsonPrimitive -> sb.append(formatPrimitive(el))
        }
    }

    /**
     * LM-3 numeric fidelity:
     *   - "151" parses as long -> emit "151" (no .0 added)
     *   - "5.0" parses as double -> emit "5.0" (NOT collapsed to "5")
     *   - "5.10" -> emit "5.1" (trailing zero AFTER decimal stripped)
     *   - "true"/"false" -> lowercase literal
     *
     * Strategy: inspect JsonPrimitive.content raw text. If no "." / "e" / "E",
     * it was an integer in the input -- emit the long form. Otherwise it was a
     * float -- strip trailing zeros after the decimal point but preserve the
     * decimal itself.
     */
    private fun formatPrimitive(p: JsonPrimitive): String {
        if (p.isString) return "\"" + escape(p.content) + "\""
        val raw = p.content
        if (raw == "true" || raw == "false" || raw == "null") return raw
        if ("." !in raw && "e" !in raw && "E" !in raw) {
            return raw.toLongOrNull()?.toString() ?: raw
        }
        // Float path: normalize but preserve int/float boundary
        val d = raw.toDoubleOrNull() ?: return raw
        // Strip trailing zeros after the decimal, keeping at least one digit
        // post-decimal so 5.0 -> "5.0", 5.10 -> "5.1", 5.100 -> "5.1".
        val s = if (d == d.toLong().toDouble()) {
            "${d.toLong()}.0"  // canonical "5.0" form for whole-number floats
        } else {
            d.toString().let { x ->
                if ('.' in x) x.trimEnd('0').trimEnd('.').let { y ->
                    if ('.' in y) y else "$y.0"
                } else x
            }
        }
        return s
    }

    private fun escape(s: String): String = buildString {
        for (c in s) {
            when (c) {
                '"'  -> append("\\\"")
                '\\' -> append("\\\\")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                '\b' -> append("\\b")
                '' -> append("\\f")
                else -> if (c.code < 0x20) append("\\u%04x".format(c.code)) else append(c)
            }
        }
    }
}
