package com.aegis.health.tools

import com.aegis.health.db.KBDatabase
import com.aegis.health.models.DrugInfo
import kotlinx.serialization.Serializable

@Serializable
data class NormalizeDrugResult(
    val drug: DrugInfo? = null,
    val error: String? = null,
)

object NormalizeDrug {

    private val COMMON_MISSPELLINGS = mapOf(
        "tylenol" to "acetaminophen",
        "advil" to "ibuprofen",
        "motrin" to "ibuprofen",
        "aleve" to "naproxen",
        "benadryl" to "diphenhydramine",
        "zyrtec" to "cetirizine",
        "claritin" to "loratadine",
        "pepcid" to "famotidine",
        "prilosec" to "omeprazole",
        "nexium" to "esomeprazole",
        "lipitor" to "atorvastatin",
        "zocor" to "simvastatin",
        "plavix" to "clopidogrel",
        "xanax" to "alprazolam",
        "ambien" to "zolpidem",
        "prozac" to "fluoxetine",
        "zoloft" to "sertraline",
        "metformin" to "metformin",
        "lisinopril" to "lisinopril",
    )

    fun normalize(name: String, db: KBDatabase): NormalizeDrugResult {
        if (name.isBlank()) {
            return NormalizeDrugResult(error = "Empty drug name")
        }

        val cleaned = name.trim().lowercase()

        // Check common misspellings / brand-to-generic map
        val corrected = COMMON_MISSPELLINGS[cleaned] ?: cleaned

        // Try direct drug lookup
        val drugRow = db.queryDrugByName(corrected)
        if (drugRow != null) {
            return NormalizeDrugResult(
                drug = DrugInfo(
                    generic_name = drugRow["name"] ?: corrected,
                    rxcui = drugRow["rxcui"] ?: "",
                    category = drugRow["category"] ?: "Unknown",
                )
            )
        }

        // Fallback to rxnorm_lookup
        val rxRow = db.queryRxNormLookup(corrected)
            ?: db.queryRxNormLookup(cleaned)
        if (rxRow != null) {
            return NormalizeDrugResult(
                drug = DrugInfo(
                    generic_name = rxRow["generic_name"] ?: corrected,
                    rxcui = rxRow["rxcui"] ?: "",
                    category = rxRow["category"] ?: "Unknown",
                )
            )
        }

        return NormalizeDrugResult(error = "Drug '$name' not found in knowledge base")
    }
}
