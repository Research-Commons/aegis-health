package com.aegis.health.tools

import com.aegis.health.db.KBDatabase
import com.aegis.health.models.TermDefinition
import kotlinx.serialization.Serializable

@Serializable
data class LookupTermResult(
    val definition: TermDefinition? = null,
    val error: String? = null,
)

object LookupTerm {

    fun lookup(term: String, db: KBDatabase): LookupTermResult {
        if (term.isBlank()) {
            return LookupTermResult(error = "Empty term")
        }

        val row = db.queryTerm(term)
            ?: return LookupTermResult(error = "Term '$term' not found in knowledge base")

        return LookupTermResult(
            definition = TermDefinition(
                term = row["term"] ?: term,
                plain_language_definition = row["plain_language_definition"] ?: "",
                citation = row["citation"] ?: "NLM MedlinePlus",
            )
        )
    }
}
