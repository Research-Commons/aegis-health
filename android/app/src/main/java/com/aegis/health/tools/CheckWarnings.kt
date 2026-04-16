package com.aegis.health.tools

import com.aegis.health.db.KBDatabase
import com.aegis.health.models.AegisResponse
import com.aegis.health.models.Citation
import com.aegis.health.models.Flag

/**
 * Core safety engine — Kotlin port of the Python check_warnings tool.
 *
 * Evaluates a list of drugs for:
 *   - drug × drug interactions
 *   - drug × condition contraindications
 *   - special population risks (elderly, pregnancy, pediatric)
 *
 * Auto-defers to a healthcare professional when safety thresholds are crossed.
 */
object CheckWarnings {

    private const val DEFER_THRESHOLD_DRUG_COUNT = 5
    private const val ELDERLY_AGE = 65
    private const val PEDIATRIC_AGE = 12

    fun check(
        drugList: List<String>,
        age: Int?,
        conditions: List<String>?,
        db: KBDatabase,
    ): AegisResponse {
        if (drugList.isEmpty()) {
            return errorResponse("No drugs provided for analysis")
        }

        val conds = conditions.orEmpty()
        val flags = mutableListOf<Flag>()
        val citations = mutableListOf<Citation>()
        var defer = false

        // ── Polypharmacy ────────────────────────────────────────────────
        if (drugList.size >= DEFER_THRESHOLD_DRUG_COUNT) {
            defer = true
            flags += Flag(
                severity = 4,
                description = "Polypharmacy detected: ${drugList.size} drugs. Complex regimens require professional review.",
                citation = "Clinical best practice",
            )
        }

        // ── Resolve all drugs ───────────────────────────────────────────
        val drugRecords = mutableMapOf<String, Map<String, String>>()
        val unknownDrugs = mutableListOf<String>()

        for (rawName in drugList) {
            val cleaned = rawName.trim().lowercase()
            if (cleaned.isEmpty()) continue

            val directRow = db.queryDrugByName(cleaned)
            if (directRow != null) {
                drugRecords[cleaned] = directRow
                continue
            }

            val rxRow = db.queryRxNormLookup(cleaned)
            if (rxRow != null) {
                drugRecords[cleaned] = mapOf(
                    "name" to (rxRow["generic_name"] ?: cleaned),
                    "rxcui" to (rxRow["rxcui"] ?: ""),
                    "category" to (rxRow["category"] ?: ""),
                    "drug_class" to "",
                )
                continue
            }

            unknownDrugs += rawName
        }

        // ── Unknown drugs → defer ──────────────────────────────────────
        if (unknownDrugs.isNotEmpty()) {
            defer = true
            flags += Flag(
                severity = 3,
                description = "Unknown drug(s): ${unknownDrugs.joinToString()}. Cannot verify safety without identification.",
                citation = "Aegis safety policy",
            )
        }

        // ── Controlled substances → defer ──────────────────────────────
        for ((_, rec) in drugRecords) {
            if (rec["category"].equals("controlled", ignoreCase = true)) {
                defer = true
                flags += Flag(
                    severity = 4,
                    description = "'${rec["name"]}' is a controlled substance. Use must be supervised by a prescriber.",
                    citation = "DEA Controlled Substances Act",
                )
            }
        }

        // ── Special populations ─────────────────────────────────────────
        val isPregnant = conds.any { it.lowercase() in listOf("pregnancy", "pregnant") }
        val isElderly = age != null && age >= ELDERLY_AGE
        val isPediatric = age != null && age < PEDIATRIC_AGE

        if (isPregnant) {
            defer = true
            flags += Flag(
                severity = 5,
                description = "Pregnancy detected. All medication use during pregnancy requires direct medical supervision.",
                citation = "FDA Pregnancy and Lactation Labeling Rule",
            )
        }

        if (isPediatric) {
            for ((_, rec) in drugRecords) {
                if (rec["category"].equals("rx", ignoreCase = true)) {
                    defer = true
                    flags += Flag(
                        severity = 4,
                        description = "Pediatric patient (age $age) with prescription drug '${rec["name"]}'. Pediatric dosing requires professional guidance.",
                        citation = "AAP Pediatric Prescribing Guidelines",
                    )
                }
            }
        }

        if (isElderly) {
            flags += Flag(
                severity = 3,
                description = "Patient age $age is ≥65. Elderly patients may need dose adjustments and have increased sensitivity to drug effects.",
                citation = "AGS Beers Criteria",
            )
            citations += Citation(
                source = "AGS Beers Criteria",
                text = "American Geriatrics Society Beers Criteria for Potentially Inappropriate Medication Use in Older Adults",
            )
        }

        // ── Drug × Drug interactions ────────────────────────────────────
        val resolvedNames = drugRecords.keys.toList()
        for (i in resolvedNames.indices) {
            for (j in i + 1 until resolvedNames.size) {
                val drugA = resolvedNames[i]
                val drugB = resolvedNames[j]
                val rxcuiA = drugRecords[drugA]?.get("rxcui") ?: ""
                val rxcuiB = drugRecords[drugB]?.get("rxcui") ?: ""

                val interactions = db.queryInteractions(drugA, drugB, rxcuiA, rxcuiB)
                for (row in interactions) {
                    val sev = (row["severity"]?.toIntOrNull() ?: 1).coerceIn(1, 5)
                    flags += Flag(
                        severity = sev,
                        description = row["description"] ?: "Drug interaction between $drugA and $drugB",
                        citation = row["citation"] ?: "Drug interaction database",
                    )
                    if (sev >= 4) defer = true
                }
            }
        }

        // ── Drug × Condition contraindications ──────────────────────────
        for ((dname, rec) in drugRecords) {
            for (cond in conds) {
                val contras = db.queryContraindications(
                    drugName = dname,
                    rxcui = rec["rxcui"] ?: "",
                    condition = cond,
                )
                for (row in contras) {
                    val sev = (row["severity"]?.toIntOrNull() ?: 1).coerceIn(1, 5)
                    flags += Flag(
                        severity = sev,
                        description = row["description"] ?: "Contraindication: $dname with $cond",
                        citation = row["citation"] ?: "FDA label data",
                    )
                    if (sev >= 4) defer = true
                }
            }
        }

        // ── Confidence ──────────────────────────────────────────────────
        val confidence = when {
            unknownDrugs.isNotEmpty() -> 0.3
            flags.isNotEmpty() -> maxOf(0.5, 1.0 - flags.maxOf { it.severity } * 0.1)
            else -> 0.95
        }

        // ── Explanation ─────────────────────────────────────────────────
        val explanation = buildString {
            if (flags.isEmpty()) {
                append("No known interactions or contraindications found for the provided drugs.")
            } else {
                append("Found ${flags.size} warning(s) across the provided drug list.")
            }
            if (defer) {
                append(" This combination should be reviewed by a healthcare professional.")
            }
        }

        return AegisResponse(
            flags = flags,
            citations = citations,
            confidence = confidence,
            defer_to_professional = defer,
            explanation = explanation,
        )
    }

    private fun errorResponse(msg: String) = AegisResponse(
        confidence = 0.0,
        defer_to_professional = true,
        explanation = msg,
    )
}
