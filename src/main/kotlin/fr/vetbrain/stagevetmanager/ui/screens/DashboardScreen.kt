package fr.vetbrain.stagevetmanager.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ExitToApp
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import fr.vetbrain.stagevetmanager.ui.components.FilterBar
import fr.vetbrain.stagevetmanager.ui.components.InternshipTable
import fr.vetbrain.stagevetmanager.ui.components.ScrapeFiltersPanel
import fr.vetbrain.stagevetmanager.ui.components.StatusBar
import fr.vetbrain.stagevetmanager.viewmodel.DashboardViewModel
import java.awt.FileDialog
import java.awt.Frame
import java.nio.file.Paths

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DashboardScreen(
    vm: DashboardViewModel,
    exportDir: String,
    onOpenSettings: () -> Unit,
    onLogout: () -> Unit,
    onRequestScrape: () -> Unit,
    onExportOneDrive: () -> Unit,
) {
    val displayed     by vm.displayed.collectAsState()
    val filterText    by vm.filterText.collectAsState()
    val activeFilter  by vm.activeFilter.collectAsState()
    val isLoading     by vm.isLoading.collectAsState()
    val status        by vm.statusMessage.collectAsState()
    val error         by vm.errorMessage.collectAsState()
    val sortCol       by vm.sortColumn.collectAsState()
    val sortAsc       by vm.sortAscending.collectAsState()
    val scrapeFilters by vm.scrapeFilters.collectAsState()
    val dbCount       by vm.dbCount.collectAsState()

    var showClearDialog by remember { mutableStateOf(false) }

    if (showClearDialog) {
        AlertDialog(
            onDismissRequest = { showClearDialog = false },
            title = { Text("Vider la base locale ?") },
            text = { Text("Cette action supprime définitivement les $dbCount stage(s) stockés localement. Elle ne modifie pas les données sur stagevet.fr.") },
            confirmButton = {
                TextButton(onClick = {
                    vm.clearDatabase()
                    showClearDialog = false
                }) { Text("Vider", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { showClearDialog = false }) { Text("Annuler") }
            },
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("StageVet Manager — Tableau de bord") },
                actions = {
                    IconButton(onClick = { showClearDialog = true }, enabled = dbCount > 0) {
                        Icon(Icons.Default.DeleteSweep, contentDescription = "Vider la base locale")
                    }
                    IconButton(onClick = onOpenSettings) {
                        Icon(Icons.Default.Settings, contentDescription = "Paramètres")
                    }
                    IconButton(onClick = onLogout) {
                        Icon(Icons.AutoMirrored.Filled.ExitToApp, contentDescription = "Déconnexion")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primary,
                    titleContentColor = androidx.compose.ui.graphics.Color.White,
                    actionIconContentColor = androidx.compose.ui.graphics.Color.White,
                ),
            )
        },
        bottomBar = {
            StatusBar(
                message = status,
                isLoading = isLoading,
                errorMessage = error,
                onDismissError = { vm.errorMessage.value = null },
            )
        },
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding)) {
            ScrapeFiltersPanel(
                filters = scrapeFilters,
                onFiltersChange = vm::setScrapeFilters,
            )

            HorizontalDivider()

            FilterBar(
                filterText = filterText,
                activeFilter = activeFilter,
                count = displayed.size,
                dbCount = dbCount,
                isLoading = isLoading,
                onTextChange = vm::setFilter,
                onViewChange = vm::setView,
                onRefresh = onRequestScrape,
                onLoadFromDb = vm::loadFromDatabase,
                onExportOneDrive = onExportOneDrive,
                onExport = {
                    val filename = vm.suggestedExportFileName()
                    val dir = exportDir.ifBlank { System.getProperty("user.home") }
                    try {
                        val dialog = FileDialog(null as Frame?, "Enregistrer l'export Excel", FileDialog.SAVE)
                        dialog.directory = dir
                        dialog.file = filename
                        dialog.isVisible = true
                        val chosen = dialog.file
                        val chosenDir = dialog.directory
                        if (chosen != null && chosenDir != null) {
                            vm.exportToExcel(Paths.get(chosenDir, chosen))
                        }
                    } catch (_: Exception) {
                        vm.exportToExcel(Paths.get(dir, filename))
                    }
                },
            )

            HorizontalDivider()

            InternshipTable(
                internships = displayed,
                sortColumn = sortCol,
                sortAscending = sortAsc,
                onSort = vm::toggleSort,
                modifier = Modifier.weight(1f),
            )
        }
    }
}
