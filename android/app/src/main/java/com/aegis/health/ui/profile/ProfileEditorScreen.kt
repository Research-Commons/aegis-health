package com.aegis.health.ui.profile

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.aegis.health.models.HealthProfile
import com.aegis.health.ui.common.AegisTextField
import com.aegis.health.ui.common.GhostButton
import com.aegis.health.ui.common.PrimaryButton
import com.aegis.health.ui.common.ScreenHeader
import com.aegis.health.ui.common.SectionLabel
import com.aegis.health.ui.theme.AegisSpacing
import com.aegis.health.ui.theme.LocalAegisColors

@Composable
fun ProfileEditorScreen(
    onBack: () -> Unit,
    onSaved: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = LocalAegisColors.current
    val context = LocalContext.current
    val existing = remember { ProfileStore.current() }

    var name by remember { mutableStateOf(existing?.name.orEmpty()) }
    var ageText by remember { mutableStateOf(existing?.age?.toString().orEmpty()) }
    var sex by remember {
        mutableStateOf(existing?.sex?.replaceFirstChar { it.uppercaseChar() }.orEmpty())
    }
    var conditions by remember { mutableStateOf(existing?.conditions?.joinToString(", ").orEmpty()) }
    var medications by remember { mutableStateOf(existing?.medications?.joinToString(", ").orEmpty()) }
    var familyHistory by remember {
        mutableStateOf(existing?.familyHistory?.joinToString(", ").orEmpty())
    }

    val canSave = name.isNotBlank() && ageText.toIntOrNull() != null && sex.isNotBlank()

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(colors.canvas)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = AegisSpacing.xl, vertical = AegisSpacing.xl),
    ) {
        ScreenHeader(
            title = if (existing == null) "New profile" else "Edit profile",
            subtitle = "Stored only on this device.",
            onBack = onBack,
        )
        Spacer(Modifier.height(22.dp))

        SectionLabel("About you")
        Spacer(Modifier.height(12.dp))
        AegisTextField(
            value = name,
            onValueChange = { name = it },
            label = "Name",
            placeholder = "e.g. Sara Kim",
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(14.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            AegisTextField(
                value = ageText,
                onValueChange = { ageText = it.filter { c -> c.isDigit() } },
                label = "Age",
                placeholder = "45",
                keyboardType = KeyboardType.Number,
                modifier = Modifier.weight(1f),
            )
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    "SEX",
                    style = MaterialTheme.typography.labelMedium,
                    color = colors.onSurfaceMuted,
                )
                Spacer(Modifier.height(8.dp))
                SexSegmented(value = sex, onChange = { sex = it })
            }
        }

        Spacer(Modifier.height(20.dp))
        SectionLabel("Health context")
        Spacer(Modifier.height(12.dp))
        AegisTextField(
            value = conditions,
            onValueChange = { conditions = it },
            label = "Conditions",
            placeholder = "diabetes, hypertension",
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(14.dp))
        AegisTextField(
            value = medications,
            onValueChange = { medications = it },
            label = "Current medications",
            placeholder = "metformin, lisinopril",
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(14.dp))
        AegisTextField(
            value = familyHistory,
            onValueChange = { familyHistory = it },
            label = "Family history",
            placeholder = "heart disease, breast cancer",
            modifier = Modifier.fillMaxWidth(),
        )

        Spacer(Modifier.height(24.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            GhostButton(
                text = "Cancel",
                onClick = onBack,
                modifier = Modifier.weight(1f),
            )
            PrimaryButton(
                text = "Save",
                onClick = {
                    val profile = HealthProfile(
                        name = name.trim(),
                        age = ageText.toIntOrNull(),
                        sex = sex.lowercase(),
                        conditions = conditions.splitTrim(),
                        medications = medications.splitTrim(),
                        familyHistory = familyHistory.splitTrim(),
                    )
                    ProfileStore.save(context, profile)
                    onSaved()
                },
                enabled = canSave,
                modifier = Modifier.weight(1f),
            )
        }
        if (!canSave) {
            Spacer(Modifier.height(8.dp))
            Text(
                "Name, age, and sex are required.",
                style = MaterialTheme.typography.bodySmall,
                color = colors.onSurfaceMuted,
                modifier = Modifier.fillMaxWidth(),
                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
            )
        }
    }
}

@Composable
private fun SexSegmented(value: String, onChange: (String) -> Unit) {
    val colors = LocalAegisColors.current
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        listOf("Female", "Male").forEach { option ->
            val selected = value == option
            Row(
                modifier = Modifier
                    .weight(1f)
                    .height(52.dp)
                    .background(
                        color = if (selected) colors.accent else colors.surface,
                        shape = RoundedCornerShape(16.dp),
                    )
                    .border(
                        width = 1.dp,
                        color = if (selected) colors.accent else colors.hairline,
                        shape = RoundedCornerShape(16.dp),
                    )
                    .clickable { onChange(option) },
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    option,
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (selected) colors.accentInk else colors.onSurface,
                )
            }
        }
    }
}

private fun String.splitTrim(): List<String> =
    split(",").map { it.trim() }.filter { it.isNotBlank() }
