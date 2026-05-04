package com.aegis.health.tools

import com.aegis.health.db.KBDatabase
import kotlinx.serialization.Serializable

@Serializable
data class DrugInfoResult(
    val name: String? = null,
    val drug_class: String? = null,
    val category: String? = null,
    val warnings_summary: String? = null,
    val citation: String? = null,
    val error: String? = null,
)

object GetDrugInfo {

    fun get(rxcui: String, db: KBDatabase): DrugInfoResult {
        if (rxcui.isBlank()) {
            return DrugInfoResult(error = "Empty RxCUI provided")
        }

        val row = db.queryDrugByRxcui(rxcui.trim())
            ?: return DrugInfoResult(error = "Drug with RxCUI '$rxcui' not found")

        return DrugInfoResult(
            name = row["name"],
            drug_class = "",
            category = row["category"] ?: "Rx",
            warnings_summary = row["warnings_summary"],
            citation = row["citation"],
        )
    }
}
