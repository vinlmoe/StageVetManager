package fr.vetbrain.stagevetmanager.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ExitToApp
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.Group
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import fr.vetbrain.stagevetmanager.ui.components.FilterBar
import fr.vetbrain.stagevetmanager.ui.components.InternshipDetailView
import fr.vetbrain.stagevetmanager.ui.components.InternshipTable
import fr.vetbrain.stagevetmanager.ui.components.ScrapeFiltersPanel
import fr.vetbrain.stagevetmanager.ui.components.StatusBar
import fr.vetbrain.stagevetmanager.ui.components.StudentBilanView
import fr.vetbrain.stagevetmanager.viewmodel.DashboardViewModel
import java.awt.FileDialog
import java.awt.Frame
import java.nio.file.Paths

private enum class DisplayMode { INTERNSHIPS, BILAN, DETAIL }

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
    val isPdfLoading  by vm.isPdfLoading.collectAsState()
    val selectedInternship by vm.selectedInternship.collectAsState()
    val pdfDataCache       by vm.pdfDataCache.collectAsState()
    var previousMode       by remember { mutableStateOf(DisplayMode.INTERNSHIPS) }

    var showClearDialog by remember { mutableStateOf(false) }
    var displayMode     by remember { mutableStateOf(DisplayMode.INTERNSHIPS) }

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
                title = {
                    if (displayMode == DisplayMode.DETAIL && selectedInternship != null) {
                        Text(selectedInternship!!.studentName)
                    } else {
                        Text("StageVet Manager — Tableau de bord")
                    }
                },
                navigationIcon = {
                    if (displayMode == DisplayMode.DETAIL) {
                        IconButton(onClick = {
                            displayMode = previousMode
                            vm.selectInternship(null)
                        }) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Retour")
                        }
                    }
                },
                actions = {
                    if (displayMode != DisplayMode.DETAIL) {
                        // Bascule liste ↔ bilan
                        IconButton(onClick = {
                            displayMode = if (displayMode == DisplayMode.INTERNSHIPS)
                                DisplayMode.BILAN else DisplayMode.INTERNSHIPS
                        }) {
                            if (displayMode == DisplayMode.INTERNSHIPS) {
                                Icon(Icons.Default.Group, contentDescription = "Bilan par étudiant")
                            } else {
                                Icon(Icons.AutoMirrored.Filled.List, contentDescription = "Liste des stages")
                            }
                        }
                        IconButton(onClick = { showClearDialog = true }, enabled = dbCount > 0) {
                            Icon(Icons.Default.DeleteSweep, contentDescription = "Vider la base locale")
                        }
                        IconButton(onClick = onOpenSettings) {
                            Icon(Icons.Default.Settings, contentDescription = "Paramètres")
                        }
                        IconButton(onClick = onLogout) {
                            Icon(Icons.AutoMirrored.Filled.ExitToApp, contentDescription = "Déconnexion")
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primary,
                    titleContentColor = androidx.compose.ui.graphics.Color.White,
                    actionIconContentColor = androidx.compose.ui.graphics.Color.White,
                    navigationIconContentColor = androidx.compose.ui.graphics.Color.White,
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

            when (displayMode) {
                DisplayMode.INTERNSHIPS -> InternshipTable(
                    internships = displayed,
                    sortColumn = sortCol,
                    sortAscending = sortAsc,
                    onSort = vm::toggleSort,
                    modifier = Modifier.weight(1f),
                    onSelectInternship = { internship ->
                        previousMode = DisplayMode.INTERNSHIPS
                        vm.selectInternship(internship)
                        displayMode = DisplayMode.DETAIL
                    },
                )
                DisplayMode.BILAN -> StudentBilanView(
                    internships = displayed,
                    modifier = Modifier.weight(1f),
                    onSelectInternship = { internship ->
                        previousMode = DisplayMode.BILAN
                        vm.selectInternship(internship)
                        displayMode = DisplayMode.DETAIL
                    },
                )
                DisplayMode.DETAIL -> {
                    val internship = selectedInternship
                    if (internship != null) {
                        InternshipDetailView(
                            internship = internship,
                            pdfData = pdfDataCache[internship.conventionPdfUrl],
                            isPdfLoading = isPdfLoading,
                            canDownloadPdf = vm.hasSessionCookies,
                            onDownloadPdf = { vm.downloadConventionPdf(internship.conventionPdfUrl) },
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
            }
        }
    }
}
