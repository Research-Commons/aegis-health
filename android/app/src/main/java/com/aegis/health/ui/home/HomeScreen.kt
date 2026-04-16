package com.aegis.health.ui.home

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
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.HealthAndSafety
import androidx.compose.material.icons.filled.MedicalServices
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.WifiOff
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.aegis.health.ui.theme.AegisTeal
import com.aegis.health.ui.theme.SeverityGreen

@Composable
fun HomeScreen(
    onNavigate: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Spacer(Modifier.height(12.dp))

        // Header
        Icon(
            Icons.Default.Shield,
            contentDescription = null,
            tint = AegisTeal,
            modifier = Modifier.size(56.dp),
        )
        Spacer(Modifier.height(8.dp))
        Text(
            text = "Aegis Health",
            style = MaterialTheme.typography.headlineLarge,
            fontWeight = FontWeight.Bold,
            color = AegisTeal,
        )
        Spacer(Modifier.height(4.dp))
        Text(
            text = "Your on-device medical safety assistant",
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Spacer(Modifier.height(12.dp))

        // Offline badge
        Surface(
            color = SeverityGreen.copy(alpha = 0.12f),
            shape = MaterialTheme.shapes.small,
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    Icons.Default.WifiOff,
                    contentDescription = null,
                    tint = SeverityGreen,
                    modifier = Modifier.size(16.dp),
                )
                Spacer(Modifier.width(6.dp))
                Text(
                    text = "Local · Offline · Private",
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.SemiBold,
                    color = SeverityGreen,
                )
            }
        }

        Spacer(Modifier.height(28.dp))

        // Feature cards
        FeatureCard(
            icon = Icons.Default.MedicalServices,
            title = "DrugSafe",
            subtitle = "Scan a pill bottle or type drug names to check for interactions, warnings, and safety flags.",
            accentColor = MaterialTheme.colorScheme.primary,
            onClick = { onNavigate("drugsafe") },
        )

        Spacer(Modifier.height(14.dp))

        FeatureCard(
            icon = Icons.Default.Description,
            title = "ConsentReader",
            subtitle = "Photograph a medical consent form. Get a plain-language summary with highlighted terms and binding clauses.",
            accentColor = MaterialTheme.colorScheme.secondary,
            onClick = { onNavigate("consent") },
        )

        Spacer(Modifier.height(14.dp))

        FeatureCard(
            icon = Icons.Default.HealthAndSafety,
            title = "HealthPartner",
            subtitle = "Enter your health profile for a personalized prevention checklist grounded in USPSTF guidelines.",
            accentColor = MaterialTheme.colorScheme.tertiary,
            onClick = { onNavigate("partner") },
        )

        Spacer(Modifier.height(24.dp))

        Text(
            text = "Powered by Gemma 4 · Running entirely on your device",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Spacer(Modifier.height(8.dp))
    }
}

@Composable
private fun FeatureCard(
    icon: ImageVector,
    title: String,
    subtitle: String,
    accentColor: Color,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Card(
        onClick = onClick,
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant,
        ),
        shape = MaterialTheme.shapes.large,
    ) {
        Row(
            modifier = Modifier.padding(20.dp),
            verticalAlignment = Alignment.Top,
        ) {
            Box(
                modifier = Modifier
                    .size(48.dp)
                    .padding(4.dp),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = accentColor,
                    modifier = Modifier.size(36.dp),
                )
            }
            Spacer(Modifier.width(16.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.SemiBold,
                    color = accentColor,
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}
