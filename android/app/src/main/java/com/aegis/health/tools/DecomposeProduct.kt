package com.aegis.health.tools

import com.aegis.health.db.KBDatabase
import com.aegis.health.models.DrugInfo
import com.aegis.health.models.ProductDecomposition
import kotlinx.serialization.Serializable

@Serializable
data class DecomposeResult(
    val decomposition: ProductDecomposition? = null,
    val error: String? = null,
)

object DecomposeProduct {

    fun decompose(productName: String, db: KBDatabase): DecomposeResult {
        if (productName.isBlank()) {
            return DecomposeResult(error = "Empty product name")
        }

        val rows = db.queryIngredients(productName)
        if (rows.isEmpty()) {
            return DecomposeResult(error = "Product '$productName' not found in knowledge base")
        }

        val ingredients = rows.map { row ->
            DrugInfo(
                generic_name = row["ingredient_name"] ?: "",
                rxcui = row["ingredient_rxcui"] ?: "",
                category = "ingredient",
            )
        }

        return DecomposeResult(
            decomposition = ProductDecomposition(
                product = productName,
                ingredients = ingredients,
                citation = "RxNorm / DailyMed",
            )
        )
    }
}
