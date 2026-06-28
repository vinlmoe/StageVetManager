package fr.vetbrain.stagevetmanager.ui

import androidx.compose.runtime.*
import fr.vetbrain.stagevetmanager.model.ScrapeFilters
import fr.vetbrain.stagevetmanager.model.TrackingTarget
import fr.vetbrain.stagevetmanager.model.deserializeTrackingTargets
import fr.vetbrain.stagevetmanager.model.serializeTrackingTargets
import fr.vetbrain.stagevetmanager.persistence.LocalDatabase
import fr.vetbrain.stagevetmanager.scraper.SeleniumScraper
import fr.vetbrain.stagevetmanager.ui.screens.DashboardScreen
import fr.vetbrain.stagevetmanager.ui.screens.LoginScreen
import fr.vetbrain.stagevetmanager.ui.screens.SettingsScreen
import fr.vetbrain.stagevetmanager.ui.theme.AppTheme
import fr.vetbrain.stagevetmanager.viewmodel.DashboardViewModel
import java.nio.file.Paths
import java.util.prefs.Preferences

private enum class Screen { LOGIN, DASHBOARD, SETTINGS }

@Composable
fun App() {
    val prefs = remember { Preferences.userRoot().node("fr/vetbrain/stagevetmanager") }

    var screen        by remember { mutableStateOf(Screen.LOGIN) }
    var savedUsername by remember { mutableStateOf(prefs.get("username", "")) }
    var savedPassword by remember { mutableStateOf("") } // jamais stocké
    var browserType   by remember {
        mutableStateOf(
            SeleniumScraper.BrowserType.valueOf(
                prefs.get("browser", SeleniumScraper.BrowserType.CHROME.name)
            )
        )
    }
    var headless         by remember { mutableStateOf(prefs.getBoolean("headless", true)) }
    var chromeDriverPath by remember { mutableStateOf(prefs.get("chromeDriverPath", "")) }
    var exportDir        by remember { mutableStateOf(prefs.get("exportDir", "")) }
    var oneDrivePath  by remember {
        mutableStateOf(prefs.get("oneDrivePath", "Documents/StageVet/export_stagevet.xlsx"))
    }
    var conventionDir by remember { mutableStateOf(prefs.get("conventionDir", "")) }
    // Migration : ancien paramètre unique → liste de cibles
    var dbDir by remember { mutableStateOf(prefs.get("dbDir", "")) }

    // Configurer le chemin de la base avant la création du ViewModel
    remember(dbDir) {
        val path = if (dbDir.isBlank()) LocalDatabase.defaultDbPath
                   else Paths.get(dbDir, "internships.db")
        if (path != LocalDatabase.instance.dbPath) {
            LocalDatabase.instance = LocalDatabase(path)
        }
    }

    var trackingTargets by remember {
        val stored = prefs.get("trackingTargets", "")
        val legacy  = prefs.get("trackingFilePath", "")
        mutableStateOf(
            when {
                stored.isNotBlank() -> deserializeTrackingTargets(stored)
                legacy.isNotBlank() -> listOf(TrackingTarget("", legacy))
                else                -> emptyList()
            }
        )
    }

    val vm = remember { DashboardViewModel() }
    DisposableEffect(Unit) { onDispose { vm.dispose() } }

    // Restore persisted scrape filters
    remember {
        vm.setScrapeFilters(ScrapeFilters(
            periode    = prefs.get("scrapeFilter.periode", ""),
            anneeEtude = prefs.get("scrapeFilter.anneeEtude", ""),
            theme      = prefs.get("scrapeFilter.theme", ""),
            status     = prefs.get("scrapeFilter.status", ""),
            order      = prefs.get("scrapeFilter.order", "1"),
        ))
    }

    AppTheme {
        when (screen) {
            Screen.LOGIN -> LoginScreen(
                initialUsername = savedUsername,
                initialPassword = savedPassword,
                onLogin = { user, pass ->
                    savedUsername = user
                    savedPassword = pass
                    prefs.put("username", user)
                    screen = Screen.DASHBOARD
                },
                onContinueOffline = { screen = Screen.DASHBOARD },
                onOpenSettings = { screen = Screen.SETTINGS },
            )

            Screen.DASHBOARD -> DashboardScreen(
                vm = vm,
                exportDir = exportDir,
                conventionDir = conventionDir,
                trackingTargets = trackingTargets,
                browserType = browserType,
                isLoggedIn = savedPassword.isNotBlank(),
                onBrowserChange = {
                    browserType = it
                    prefs.put("browser", it.name)
                },
                onOpenSettings = { screen = Screen.SETTINGS },
                onLogout = {
                    vm.allInternships.value = emptyList()
                    screen = Screen.LOGIN
                },
                onRequestScrape = {
                    vm.scrape(savedUsername, savedPassword, browserType, headless, chromeDriverPath, conventionDir)
                },
                onExportOneDrive = {
                    vm.exportToOneDrive(oneDrivePath)
                },
                onExportOneDriveComplement = {
                    vm.exportToOneDriveComplement(oneDrivePath)
                },
                onExportTracking = { targets ->
                    vm.exportToOneDriveTracking(targets)
                },
                onScrapeFiltersChange = { f ->
                    prefs.put("scrapeFilter.periode",    f.periode)
                    prefs.put("scrapeFilter.anneeEtude", f.anneeEtude)
                    prefs.put("scrapeFilter.theme",      f.theme)
                    prefs.put("scrapeFilter.status",     f.status)
                    prefs.put("scrapeFilter.order",      f.order)
                },
            )

            Screen.SETTINGS -> SettingsScreen(
                onBackupDatabase = { onSuccess, onError -> vm.backupDatabase(onSuccess, onError) },
                dbCount = vm.dbCount.collectAsState().value,
                onClearDatabase = { vm.clearDatabase() },
                conventionDir = conventionDir,
                onConventionDirChange = { conventionDir = it; prefs.put("conventionDir", it) },
                browserType = browserType,
                headless = headless,
                chromeDriverPath = chromeDriverPath,
                exportDir = exportDir,
                dbDir = dbDir,
                onDbDirChange = { newDir ->
                    dbDir = newDir
                    prefs.put("dbDir", newDir)
                    val path = if (newDir.isBlank()) LocalDatabase.defaultDbPath
                               else Paths.get(newDir, "internships.db")
                    LocalDatabase.instance = LocalDatabase(path)
                    LocalDatabase.instance.init()
                    vm.reloadAll()
                },
                onBrowserChange = {
                    browserType = it
                    prefs.put("browser", it.name)
                },
                onHeadlessChange = {
                    headless = it
                    prefs.putBoolean("headless", it)
                },
                onChromeDriverPathChange = {
                    chromeDriverPath = it
                    prefs.put("chromeDriverPath", it)
                },
                onExportDirChange = {
                    exportDir = it
                    prefs.put("exportDir", it)
                },
                oneDrivePath = oneDrivePath,
                onOneDrivePathChange = {
                    oneDrivePath = it
                    prefs.put("oneDrivePath", it)
                },
                trackingTargets = trackingTargets,
                onTrackingTargetsChange = { targets ->
                    trackingTargets = targets
                    prefs.put("trackingTargets", serializeTrackingTargets(targets))
                },
                onBack = { screen = if (vm.allInternships.value.isEmpty()) Screen.LOGIN else Screen.DASHBOARD },
            )
        }
    }
}
