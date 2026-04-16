package com.aegis.health.tools

import com.aegis.health.db.KBDatabase
import com.aegis.health.models.GuidelineRecommendation
import kotlinx.serialization.Serializable

@Serializable
data class GetGuidelineResult(
    val recommendations: List<GuidelineRecommendation> = emptyList(),
    val gaps: List<String> = emptyList(),
    val error: String? = null,
)

object GetGuideline {

    fun getGuidelines(
        age: Int,
        sex: String,
        conditions: List<String>?,
        db: KBDatabase,
    ): GetGuidelineResult {
        if (age <= 0) {
            return GetGuidelineResult(error = "Invalid age")
        }

        val normalizedSex = when (sex.lowercase()) {
            "m", "male" -> "male"
            "f", "female" -> "female"
            else -> "all"
        }

        val rows = db.queryGuidelines(age, normalizedSex, conditions)

        if (rows.isEmpty()) {
            return GetGuidelineResult(
                error = "No USPSTF recommendations found for age=$age, sex=$normalizedSex",
            )
        }

        val recommendations = rows.map { row ->
            GuidelineRecommendation(
                title = row["title"] ?: "",
                grade = row["grade"] ?: "",
                description = row["description"] ?: "",
                population = row["population"] ?: "",
                citation = row["citation"] ?: "USPSTF",
            )
        }

        val gaps = buildGapsList(age, normalizedSex, conditions)

        return GetGuidelineResult(
            recommendations = recommendations,
            gaps = gaps,
        )
    }

    private fun buildGapsList(age: Int, sex: String, conditions: List<String>?): List<String> {
        val gaps = mutableListOf<String>()
        if (conditions.isNullOrEmpty()) {
            gaps += "No conditions provided — condition-specific screenings may be missing."
        }
        if (sex == "all") {
            gaps += "Sex not specified — sex-specific screenings may be missing."
        }
        return gaps
    }
}
