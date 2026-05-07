package com.aegis.health.models

import kotlinx.serialization.Serializable

@Serializable
data class Flag(
    val severity: Int,
    val description: String,
    val citation: String,
)

@Serializable
data class Citation(
    val source: String,
    val text: String,
)

@Serializable
data class AegisResponse(
    val confidence: Double = 0.0,
    val defer_to_professional: Boolean = false,
    val flags: List<Flag> = emptyList(),
    val citations: List<Citation> = emptyList(),
    val explanation: String = "",
)

@Serializable
data class DrugInfo(
    val generic_name: String,
    val rxcui: String,
    val category: String,
)

@Serializable
data class ProductDecomposition(
    val product: String,
    val ingredients: List<DrugInfo>,
    val citation: String,
)

@Serializable
data class TermDefinition(
    val term: String,
    val plain_language_definition: String,
    val citation: String,
)

@Serializable
data class GuidelineRecommendation(
    val title: String,
    val grade: String,
    val description: String,
    val population: String,
    val citation: String,
)

@Serializable
data class ToolCall(
    val name: String,
    val arguments: Map<String, kotlinx.serialization.json.JsonElement> = emptyMap(),
)

@Serializable
data class ToolResult(
    val name: String,
    val result: String,
)

@Serializable
data class HealthProfile(
    val name: String? = null,
    val age: Int? = null,
    val sex: String? = null,
    val conditions: List<String> = emptyList(),
    val medications: List<String> = emptyList(),
    val familyHistory: List<String> = emptyList(),
)
