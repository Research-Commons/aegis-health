package com.aegis.health

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
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
import androidx.compose.foundation.clickable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Person
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.aegis.health.ui.OnboardingPrefs
import com.aegis.health.ui.bench.BatteryBenchScreen
import com.aegis.health.ui.consentreader.ConsentReaderScreen
import com.aegis.health.ui.deferral.DeferralScreen
import com.aegis.health.ui.deferral.DeferralStore
import com.aegis.health.ui.drugsafe.DrugSafeScreen
import com.aegis.health.ui.healthpartner.HealthPartnerScreen
import com.aegis.health.ui.history.HistoryScreen
import com.aegis.health.ui.home.HomeScreen
import com.aegis.health.ui.onboarding.OnboardingScreen
import com.aegis.health.ui.profile.ProfileEditorScreen
import com.aegis.health.ui.profile.ProfileScreen
import com.aegis.health.ui.startup.StartupErrorScreen
import com.aegis.health.ui.startup.StartupLoadingScreen
import com.aegis.health.ui.theme.AegisHealthTheme
import com.aegis.health.ui.theme.LocalAegisColors

private object Routes {
    const val Home = "home"
    const val History = "history"
    const val Profile = "profile"
    const val ProfileEditor = "profile_editor"
    const val DrugSafe = "drugsafe"
    const val Consent = "consent"
    const val Partner = "partner"
    const val Bench = "bench"
    const val Deferral = "deferral"
}

private data class TabItem(val route: String, val label: String, val icon: ImageVector)
private val TopLevelTabs = listOf(
    TabItem(Routes.Home, "Home", Icons.Default.Home),
    TabItem(Routes.History, "History", Icons.Default.History),
    TabItem(Routes.Profile, "Profile", Icons.Default.Person),
)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            AegisHealthTheme {
                StartupGate { AegisRoot() }
            }
        }
    }
}

@Composable
fun StartupGate(readyContent: @Composable () -> Unit) {
    val state by AegisApp.instance.startup.collectAsState()
    when (val s = state) {
        StartupState.Initializing -> StartupLoadingScreen()
        StartupState.Ready -> readyContent()
        is StartupState.Failed -> StartupErrorScreen(message = s.message)
    }
}

@Composable
private fun AegisRoot() {
    val context = LocalContext.current
    var showOnboarding by rememberSaveable {
        mutableStateOf(OnboardingPrefs.isFirstRun(context))
    }
    if (showOnboarding) {
        OnboardingScreen(onDone = {
            OnboardingPrefs.markComplete(context)
            showOnboarding = false
        })
    } else {
        AegisNavHost()
    }
}

@Composable
fun AegisNavHost() {
    val navController = rememberNavController()
    val backStackEntry by navController.currentBackStackEntryAsState()
    val current = backStackEntry?.destination

    val showTabs = current?.route in setOf(Routes.Home, Routes.History, Routes.Profile)

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        bottomBar = {
            if (showTabs) AegisTabBar(currentRoute = current?.route, onTab = { route ->
                navController.navigate(route) {
                    popUpTo(navController.graph.findStartDestination().id) { saveState = true }
                    launchSingleTop = true
                    restoreState = true
                }
            })
        },
    ) { innerPadding ->
        NavHost(
            navController = navController,
            startDestination = Routes.Home,
            modifier = Modifier.padding(innerPadding),
        ) {
            composable(Routes.Home) {
                HomeScreen(
                    onOpen = { navController.navigate(it) },
                    onSettings = { navController.navigate(Routes.Profile) },
                )
            }
            composable(Routes.History) { HistoryScreen() }
            composable(Routes.Profile) {
                ProfileScreen(
                    onOpenBench = { navController.navigate(Routes.Bench) },
                    onEditProfile = { navController.navigate(Routes.ProfileEditor) },
                )
            }
            composable(Routes.DrugSafe) {
                DrugSafeScreen(
                    onBack = { navController.popBackStack() },
                    onDefer = { navController.navigate(Routes.Deferral) },
                )
            }
            composable(Routes.Consent) {
                ConsentReaderScreen(onBack = { navController.popBackStack() })
            }
            composable(Routes.Partner) {
                HealthPartnerScreen(
                    onBack = { navController.popBackStack() },
                    onDefer = { navController.navigate(Routes.Deferral) },
                )
            }
            composable(Routes.Bench) { BatteryBenchScreen() }
            composable(Routes.ProfileEditor) {
                ProfileEditorScreen(
                    onBack = { navController.popBackStack() },
                    onSaved = { navController.popBackStack() },
                )
            }
            composable(Routes.Deferral) {
                val staged = remember { DeferralStore.consume() }
                DeferralScreen(
                    onBack = { navController.popBackStack() },
                    response = staged,
                )
            }
        }
    }
}

@Composable
private fun AegisTabBar(currentRoute: String?, onTab: (String) -> Unit) {
    val colors = LocalAegisColors.current
    Column {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(1.dp)
                .background(colors.hairline),
        )
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(colors.surface)
                .padding(vertical = 6.dp),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TopLevelTabs.forEach { tab ->
                val active = currentRoute == tab.route
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier
                        .clickable { onTab(tab.route) }
                        .padding(horizontal = 24.dp, vertical = 8.dp),
                ) {
                    Icon(
                        tab.icon,
                        contentDescription = tab.label,
                        tint = if (active) colors.accent else colors.onSurfaceMuted,
                        modifier = Modifier.size(if (active) 22.dp else 20.dp),
                    )
                    Spacer(Modifier.height(2.dp))
                    Text(
                        tab.label,
                        style = if (active) MaterialTheme.typography.labelLarge
                        else MaterialTheme.typography.labelMedium,
                        color = if (active) colors.accent else colors.onSurfaceMuted,
                    )
                }
            }
        }
    }
}
