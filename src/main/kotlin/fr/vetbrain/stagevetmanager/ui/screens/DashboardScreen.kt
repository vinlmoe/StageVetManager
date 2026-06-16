package fr.vetbrain.stagevetmanager.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ExitToApp
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import fr.vetbrain.stagevetmanager.model.ViewFilter
import fr.vetbrain.stagevetmanager.ui.components.FilterBar
import fr.vetbrain.stagevetmanager.ui.components.InternshipTable
import fr.vetbrain.stagevetmanager.ui.components.StatusBar
import fr.vetbrain.stagevetmanager.viewmodel.DashboardViewModel
import fr.vetbrain.stagevetmanager.viewmodel.SortColumn
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
) {
    val displayed  by vm.displayed.collectAsState()
    val filterText by vm.filterText.collectAsState()
    val activeFilter by vm.activeFilter.collectAsState()
    val isLoading  by vm.isLoading.collectAsState()
    val status     by vm.statusMessage.collectAsState()
    val error      by vm.errorMessage.collectAsState()
    val sortCol    by vm.sortColumn.collectAsState()
    val sortAsc    by vm.sortAscending.collectAsState()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("StageVet Manager — Tableau de bord") },
                actions = {
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
            FilterBar(
                filterText = filterText,
                activeFilter = activeFilter,
                count = displayed.size,
                isLoading = isLoading,
                onTextChange = vm::setFilter,
                onViewChange = vm::setView,
                onRefresh = onRequestScrape,
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
                            val path = Paths.get(chosenDir, chosen)
                            vm.exportToExcel(path)
                        }
                    } catch (_: Exception) {
                        val path = Paths.get(dir, filename)
                        vm.exportToExcel(path)
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
