package com.aegis.health

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.HealthAndSafety
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.MedicalServices
import androidx.compose.material.icons.filled.Description
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.aegis.health.ui.consentreader.ConsentReaderScreen
import com.aegis.health.ui.drugsafe.DrugSafeScreen
import com.aegis.health.ui.healthpartner.HealthPartnerScreen
import com.aegis.health.ui.home.HomeScreen
import com.aegis.health.ui.theme.AegisHealthTheme

sealed class Screen(val route: String, val label: String, val icon: ImageVector) {
    data object Home : Screen("home", "Home", Icons.Default.Home)
    data object DrugSafe : Screen("drugsafe", "DrugSafe", Icons.Default.MedicalServices)
    data object ConsentReader : Screen("consent", "Consent", Icons.Default.Description)
    data object HealthPartner : Screen("partner", "Partner", Icons.Default.HealthAndSafety)
}

private val topLevelScreens = listOf(
    Screen.Home,
    Screen.DrugSafe,
    Screen.ConsentReader,
    Screen.HealthPartner,
)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            AegisHealthTheme {
                AegisNavHost()
            }
        }
    }
}

@Composable
fun AegisNavHost() {
    val navController = rememberNavController()
    val navBackStackEntry by navController.currentBackStackEntryAsState()
    val currentDestination = navBackStackEntry?.destination

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        bottomBar = {
            NavigationBar {
                topLevelScreens.forEach { screen ->
                    NavigationBarItem(
                        icon = { Icon(screen.icon, contentDescription = screen.label) },
                        label = { Text(screen.label) },
                        selected = currentDestination?.hierarchy?.any { it.route == screen.route } == true,
                        onClick = {
                            navController.navigate(screen.route) {
                                popUpTo(navController.graph.findStartDestination().id) {
                                    saveState = true
                                }
                                launchSingleTop = true
                                restoreState = true
                            }
                        },
                    )
                }
            }
        },
    ) { innerPadding ->
        NavHost(
            navController = navController,
            startDestination = Screen.Home.route,
            modifier = Modifier.padding(innerPadding),
        ) {
            composable(Screen.Home.route) {
                HomeScreen(onNavigate = { route -> navController.navigate(route) })
            }
            composable(Screen.DrugSafe.route) {
                DrugSafeScreen()
            }
            composable(Screen.ConsentReader.route) {
                ConsentReaderScreen()
            }
            composable(Screen.HealthPartner.route) {
                HealthPartnerScreen()
            }
        }
    }
}
