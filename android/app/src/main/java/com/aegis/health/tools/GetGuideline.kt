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
                population = synthesizePopulation(
                    row["population_age_min"],
                    row["population_age_max"],
                    row["population_sex"],
                ),
                citation = row["source"] ?: "USPSTF",
            )
        }

        val gaps = buildGapsList(age, normalizedSex, conditions)

        return GetGuidelineResult(
            recommendations = recommendations,
            gaps = gaps,
        )
    }

    /**
     * Turn the three population_* columns into a human-readable label such as
     * "Men 65+", "Women 50-74", or "Children 0-5". NULL bounds are treated as
     * "any" on that side.
     */
    private fun synthesizePopulation(ageMin: String?, ageMax: String?, sex: String?): String {
        val min = ageMin?.toIntOrNull()
        val max = ageMax?.toIntOrNull()
        val s = (sex ?: "all").trim().lowercase()
        val pediatric = max != null && max <= 18
        val noun = when {
            s == "male" -> if (pediatric) "Boys" else "Men"
            s == "female" -> if (pediatric) "Girls" else "Women"
            else -> if (pediatric) "Children" else "Adults"
        }
        return when {
            min != null && max != null -> "$noun $min-$max"
            min != null -> "$noun $min+"
            max != null -> "$noun under ${max + 1}"
            else -> "All ${noun.lowercase()}"
        }
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
