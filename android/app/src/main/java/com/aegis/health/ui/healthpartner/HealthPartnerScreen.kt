package com.aegis.health.ui.healthpartner

import android.content.Context
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.HealthAndSafety
import androidx.compose.material.icons.filled.HelpOutline
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.aegis.health.inference.ToolDispatcher
import com.aegis.health.models.AegisResponse
import com.aegis.health.models.GuidelineRecommendation
import com.aegis.health.models.HealthProfile
import com.aegis.health.render.ChecklistItem
import com.aegis.health.render.DeferralCard
import com.aegis.health.ui.theme.AegisTeal
import com.aegis.health.ui.theme.SeverityAmber
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.encodeToString

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HealthPartnerScreen(modifier: Modifier = Modifier) {
    var ageText by remember { mutableStateOf("") }
    var sex by remember { mutableStateOf("") }
    var sexExpanded by remember { mutableStateOf(false) }
    var conditions by remember { mutableStateOf("") }
    var medications by remember { mutableStateOf("") }
    var familyHistory by remember { mutableStateOf("") }

    var isLoading by remember { mutableStateOf(false) }
    var response by remember { mutableStateOf<AegisResponse?>(null) }
    var recommendations by remember { mutableStateOf<List<GuidelineRecommendation>>(emptyList()) }
    var gaps by remember { mutableStateOf<List<String>>(emptyList()) }
    val checkedItems = remember { mutableStateMapOf<Int, Boolean>() }

    val scope = rememberCoroutineScope()
    val context = LocalContext.current

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 16.dp),
    ) {
        // Header
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                Icons.Default.HealthAndSafety,
                contentDescription = null,
                tint = AegisTeal,
                modifier = Modifier.size(32.dp),
            )
            Spacer(Modifier.width(10.dp))
            Text(
                text = "HealthPartner",
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold,
                color = AegisTeal,
            )
        }
        Spacer(Modifier.height(4.dp))
        Text(
            text = "Personalized prevention checklist based on USPSTF guidelines",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Spacer(Modifier.height(20.dp))

        // Profile form
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            OutlinedTextField(
                value = ageText,
                onValueChange = { ageText = it.filter { c -> c.isDigit() } },
                modifier = Modifier.weight(1f),
                label = { Text("Age") },
                placeholder = { Text("e.g. 45") },
                singleLine = true,
            )

            ExposedDropdownMenuBox(
                expanded = sexExpanded,
                onExpandedChange = { sexExpanded = it },
                modifier = Modifier.weight(1f),
            ) {
                OutlinedTextField(
                    value = sex,
                    onValueChange = {},
                    readOnly = true,
                    label = { Text("Sex") },
                    trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = sexExpanded) },
                    modifier = Modifier.menuAnchor(),
                )
                ExposedDropdownMenu(
                    expanded = sexExpanded,
                    onDismissRequest = { sexExpanded = false },
                ) {
                    listOf("Male", "Female").forEach { option ->
                        DropdownMenuItem(
                            text = { Text(option) },
                            onClick = {
                                sex = option
                                sexExpanded = false
                            },
                        )
                    }
                }
            }
        }

        Spacer(Modifier.height(12.dp))

        OutlinedTextField(
            value = conditions,
            onValueChange = { conditions = it },
            modifier = Modifier.fillMaxWidth(),
            label = { Text("Conditions") },
            placeholder = { Text("e.g. diabetes, hypertension") },
            singleLine = true,
        )

        Spacer(Modifier.height(12.dp))

        OutlinedTextField(
            value = medications,
            onValueChange = { medications = it },
            modifier = Modifier.fillMaxWidth(),
            label = { Text("Current medications") },
            placeholder = { Text("e.g. metformin, lisinopril") },
            singleLine = true,
        )

        Spacer(Modifier.height(12.dp))

        OutlinedTextField(
            value = familyHistory,
            onValueChange = { familyHistory = it },
            modifier = Modifier.fillMaxWidth(),
            label = { Text("Family history") },
            placeholder = { Text("e.g. heart disease, cancer") },
            singleLine = true,
        )

        Spacer(Modifier.height(16.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            OutlinedButton(
                onClick = { saveProfile(context, ageText, sex, conditions, medications, familyHistory) },
                modifier = Modifier.weight(1f),
            ) {
                Icon(Icons.Default.Save, contentDescription = null)
                Spacer(Modifier.width(6.dp))
                Text("Save Profile")
            }

            Button(
                onClick = {
                    val age = ageText.toIntOrNull()
                    if (age != null && sex.isNotBlank()) {
                        scope.launch {
                            isLoading = true
                            response = null
                            recommendations = emptyList()
                            gaps = emptyList()
                            checkedItems.clear()

                            val condList = conditions.split(",")
                                .map { it.trim() }
                                .filter { it.isNotBlank() }
                            val medList = medications.split(",")
                                .map { it.trim() }
                                .filter { it.isNotBlank() }

                            val profileDesc = buildString {
                                append("Age: $age, Sex: $sex")
                                if (condList.isNotEmpty()) append(", Conditions: ${condList.joinToString()}")
                                if (medList.isNotEmpty()) append(", Medications: ${medList.joinToString()}")
                                if (familyHistory.isNotBlank()) append(", Family history: $familyHistory")
                            }

                            val result = ToolDispatcher.runAgenticLoop(
                                userInput = "Get preventive care recommendations for this patient: $profileDesc",
                                mode = "healthpartner",
                            )
                            response = result

                            // Parse recommendations from response flags
                            recommendations = result.flags.map { flag ->
                                GuidelineRecommendation(
                                    title = flag.description.substringBefore(":").take(80),
                                    grade = if (flag.severity <= 2) "A" else "B",
                                    description = flag.description,
                                    population = "Age $age, ${sex}",
                                    citation = flag.citation,
                                )
                            }

                            gaps = buildList {
                                if (condList.isEmpty()) add("No conditions provided — condition-specific screenings may be missing.")
                                if (familyHistory.isBlank()) add("No family history provided — genetic risk factors not assessed.")
                            }

                            isLoading = false
                        }
                    }
                },
                modifier = Modifier.weight(1f),
                enabled = ageText.isNotBlank() && sex.isNotBlank() && !isLoading,
            ) {
                Icon(Icons.Default.Search, contentDescription = null)
                Spacer(Modifier.width(6.dp))
                Text("Get Plan")
            }
        }

        Spacer(Modifier.height(20.dp))

        // Loading
        if (isLoading) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 32.dp),
                contentAlignment = Alignment.Center,
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(48.dp),
                        color = AegisTeal,
                    )
                    Spacer(Modifier.height(12.dp))
                    Text(
                        text = "Building your prevention plan…",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }

        // Results
        AnimatedVisibility(
            visible = !isLoading && (recommendations.isNotEmpty() || response != null),
            enter = fadeIn(),
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                if (response?.defer_to_professional == true) {
                    DeferralCard()
                }

                if (recommendations.isNotEmpty()) {
                    Text(
                        text = "Your Prevention Checklist",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                    )

                    recommendations.forEachIndexed { index, rec ->
                        ChecklistItem(
                            recommendation = rec,
                            checked = checkedItems[index] == true,
                            onCheckedChange = { checkedItems[index] = it },
                        )
                    }
                }

                // "What we don't know" section
                if (gaps.isNotEmpty()) {
                    Spacer(Modifier.height(8.dp))
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(
                            containerColor = SeverityAmber.copy(alpha = 0.08f),
                        ),
                        shape = MaterialTheme.shapes.medium,
                    ) {
                        Column(modifier = Modifier.padding(16.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(
                                    Icons.Default.HelpOutline,
                                    contentDescription = null,
                                    tint = SeverityAmber,
                                    modifier = Modifier.size(20.dp),
                                )
                                Spacer(Modifier.width(8.dp))
                                Text(
                                    text = "What We Don't Know",
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.SemiBold,
                                    color = SeverityAmber,
                                )
                            }
                            Spacer(Modifier.height(8.dp))
                            gaps.forEach { gap ->
                                Text(
                                    text = "• $gap",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.padding(vertical = 2.dp),
                                )
                            }
                        }
                    }
                }

                response?.let { resp ->
                    if (resp.explanation.isNotBlank()) {
                        Text(
                            text = resp.explanation,
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.padding(top = 8.dp),
                        )
                    }
                }
            }
        }
    }
}

private fun saveProfile(
    context: Context,
    age: String,
    sex: String,
    conditions: String,
    medications: String,
    familyHistory: String,
) {
    val profile = HealthProfile(
        age = age.toIntOrNull(),
        sex = sex.lowercase(),
        conditions = conditions.split(",").map { it.trim() }.filter { it.isNotBlank() },
        medications = medications.split(",").map { it.trim() }.filter { it.isNotBlank() },
        familyHistory = familyHistory.split(",").map { it.trim() }.filter { it.isNotBlank() },
    )
    val json = Json.encodeToString(profile)
    context.getSharedPreferences("aegis_profile", Context.MODE_PRIVATE)
        .edit()
        .putString("health_profile", json)
        .apply()
}
