package fr.vetbrain.stagevetmanager.ui

import androidx.compose.runtime.*
import fr.vetbrain.stagevetmanager.scraper.SeleniumScraper
import fr.vetbrain.stagevetmanager.ui.screens.DashboardScreen
import fr.vetbrain.stagevetmanager.ui.screens.LoginScreen
import fr.vetbrain.stagevetmanager.ui.screens.SettingsScreen
import fr.vetbrain.stagevetmanager.ui.theme.AppTheme
import fr.vetbrain.stagevetmanager.viewmodel.DashboardViewModel
import java.util.prefs.Preferences

private enum class Screen { LOGIN, DASHBOARD, SETTINGS }

@Composable
fun App() {
    val prefs = remember { Preferences.userRoot().node("fr/vetbrain/stagevetmanager") }

    var screen by remember { mutableStateOf(Screen.LOGIN) }
    var savedUsername by remember { mutableStateOf(prefs.get("username", "")) }
    var savedPassword by remember { mutableStateOf("") } // jamais stocké
    var browserType  by remember {
        mutableStateOf(
            SeleniumScraper.BrowserType.valueOf(
                prefs.get("browser", SeleniumScraper.BrowserType.CHROME.name)
            )
        )
    }
    var headless    by remember { mutableStateOf(prefs.getBoolean("headless", false)) }
    var exportDir   by remember { mutableStateOf(prefs.get("exportDir", "")) }

    val vm = remember { DashboardViewModel() }
    DisposableEffect(Unit) { onDispose { vm.dispose() } }

    AppTheme {
        when (screen) {
            Screen.LOGIN -> LoginScreen(
                initialUsername = savedUsername,
                initialPassword = savedPassword,
                onLogin = { user, pass ->
                    savedUsername = user
                    savedPassword = pass
                    prefs.put("username", user)
                    // Mot de passe non persisté : ressaisi à chaque session
                    screen = Screen.DASHBOARD
                    vm.scrape(user, pass, browserType, headless)
                },
                onOpenSettings = { screen = Screen.SETTINGS },
            )

            Screen.DASHBOARD -> DashboardScreen(
                vm = vm,
                exportDir = exportDir,
                onOpenSettings = { screen = Screen.SETTINGS },
                onLogout = {
                    vm.allInternships.value = emptyList()
                    screen = Screen.LOGIN
                },
                onRequestScrape = {
                    vm.scrape(savedUsername, savedPassword, browserType, headless)
                },
            )

            Screen.SETTINGS -> SettingsScreen(
                browserType = browserType,
                headless = headless,
                exportDir = exportDir,
                onBrowserChange = {
                    browserType = it
                    prefs.put("browser", it.name)
                },
                onHeadlessChange = {
                    headless = it
                    prefs.putBoolean("headless", it)
                },
                onExportDirChange = {
                    exportDir = it
                    prefs.put("exportDir", it)
                },
                onBack = { screen = if (vm.allInternships.value.isEmpty()) Screen.LOGIN else Screen.DASHBOARD },
            )
        }
    }
}
