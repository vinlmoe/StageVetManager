package fr.vetbrain.stagevetmanager.ui.components

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.CloudUpload
import androidx.compose.material.icons.filled.FileDownload
import androidx.compose.material.icons.filled.GridOn
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.text.font.FontWeight
import fr.vetbrain.stagevetmanager.model.FilterOption
import fr.vetbrain.stagevetmanager.model.LocalFilters
import fr.vetbrain.stagevetmanager.model.ScrapeFilterOptions
import fr.vetbrain.stagevetmanager.model.ScrapeFilters
import fr.vetbrain.stagevetmanager.model.TrackingTarget
import fr.vetbrain.stagevetmanager.model.ViewFilter
import fr.vetbrain.stagevetmanager.scraper.SeleniumScraper

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FilterBar(
    filterText: String,
    activeFilter: ViewFilter,
    count: Int,
    dbCount: Int,
    isLoading: Boolean,
    isLoggedIn: Boolean,
    scrapeFilters: ScrapeFilters,
    localFilters: LocalFilters,
    onTextChange: (String) -> Unit,
    onViewChange: (ViewFilter) -> Unit,
    onScrapeFiltersChange: (ScrapeFilters) -> Unit,
    onLocalFiltersChange: (LocalFilters) -> Unit,
    browserType: SeleniumScraper.BrowserType,
    onBrowserChange: (SeleniumScraper.BrowserType) -> Unit,
    onRequestScrape: () -> Unit,
    onLoadFromDb: () -> Unit,
    onReanalyzeAllPdfs: () -> Unit,
    onExport: () -> Unit,
    onExportVetAgroTice: () -> Unit,
    onExportOneDrive: () -> Unit,
    onExportOneDriveComplement: () -> Unit,
    trackingTargets: List<TrackingTarget>,
    onExportTracking: (List<TrackingTarget>) -> Unit,
    modifier: Modifier = Modifier,
) {
    var showScrapeDialog    by remember { mutableStateOf(false) }
    var showOneDriveDialog  by remember { mutableStateOf(false) }
    var showTrackingDialog  by remember { mutableStateOf(false) }
    var dialogFilters       by remember { mutableStateOf(ScrapeFilters()) }

    // ── Dialog d'extraction ────────────────────────────────────────────────
    if (showScrapeDialog) {
        AlertDialog(
            onDismissRequest = { showScrapeDialog = false },
            title = { Text("Paramètres d'extraction") },
            text = {
                Column(
                    modifier = Modifier.widthIn(min = 520.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Text("Navigateur", fontSize = 12.sp, fontWeight = FontWeight.Medium)
                    SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                        SeleniumScraper.BrowserType.entries.forEachIndexed { index, type ->
                            SegmentedButton(
                                selected = browserType == type,
                                onClick = { onBrowserChange(type) },
                                shape = SegmentedButtonDefaults.itemShape(
                                    index = index,
                                    count = SeleniumScraper.BrowserType.entries.size,
                                ),
                                label = {
                                    Text(type.name.lowercase().replaceFirstChar { it.uppercase() })
                                },
                            )
                        }
                    }
                    HorizontalDivider()
                    Text(
                        "Filtres appliqués sur stagevet.fr avant de récupérer les stages.",
                        fontSize = 12.sp,
                        color = Color.Gray,
                    )
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        FilterDropdown(
                            label = "Période",
                            options = ScrapeFilterOptions.periodes,
                            selected = dialogFilters.periode,
                            onSelect = { dialogFilters = dialogFilters.copy(periode = it) },
                            modifier = Modifier.weight(1f),
                        )
                        FilterDropdown(
                            label = "Année d'étude",
                            options = ScrapeFilterOptions.annees,
                            selected = dialogFilters.anneeEtude,
                            onSelect = { dialogFilters = dialogFilters.copy(anneeEtude = it) },
                            modifier = Modifier.weight(1f),
                        )
                        FilterDropdown(
                            label = "Tri",
                            options = ScrapeFilterOptions.tris,
                            selected = dialogFilters.order,
                            onSelect = { dialogFilters = dialogFilters.copy(order = it) },
                            modifier = Modifier.weight(1f),
                        )
                    }
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        FilterDropdown(
                            label = "Thème de stage",
                            options = ScrapeFilterOptions.themes,
                            selected = dialogFilters.theme,
                            onSelect = { dialogFilters = dialogFilters.copy(theme = it) },
                            modifier = Modifier.weight(1.6f),
                        )
                        FilterDropdown(
                            label = "Statut",
                            options = ScrapeFilterOptions.statuts,
                            selected = dialogFilters.status,
                            onSelect = { dialogFilters = dialogFilters.copy(status = it) },
                            modifier = Modifier.weight(1.3f),
                        )
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        onScrapeFiltersChange(dialogFilters)
                        showScrapeDialog = false
                        onRequestScrape()
                    },
                    enabled = !isLoading,
                ) {
                    Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("Extraire")
                }
            },
            dismissButton = {
                TextButton(onClick = { showScrapeDialog = false }) { Text("Annuler") }
            },
        )
    }

    // ── Dialog mode OneDrive ───────────────────────────────────────────────
    if (showOneDriveDialog) {
        AlertDialog(
            onDismissRequest = { showOneDriveDialog = false },
            title = { Text("Mise à jour OneDrive") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("Comment voulez-vous mettre à jour le fichier ?", fontSize = 13.sp)
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "Compléter — ajoute uniquement les stages absents du tableau (les données existantes sont conservées).",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
                    )
                    Text(
                        "Remplacer — efface tout le contenu et réécrit depuis la base locale.",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
                    )
                }
            },
            confirmButton = {
                Button(onClick = {
                    showOneDriveDialog = false
                    onExportOneDriveComplement()
                }, enabled = !isLoading) {
                    Icon(Icons.Default.CloudUpload, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("Compléter")
                }
            },
            dismissButton = {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(onClick = { showOneDriveDialog = false }) { Text("Annuler") }
                    OutlinedButton(onClick = {
                        showOneDriveDialog = false
                        onExportOneDrive()
                    }, enabled = !isLoading) {
                        Text("Remplacer", color = MaterialTheme.colorScheme.error)
                    }
                }
            },
        )
    }

    // ── Dialogue sélection tableaux de suivi ──────────────────────────────
    if (showTrackingDialog) {
        var selected by remember(trackingTargets) { mutableStateOf(trackingTargets.map { true }) }
        AlertDialog(
            onDismissRequest = { showTrackingDialog = false },
            title = { Text("Mise à jour tableaux de suivi ER") },
            text = {
                if (trackingTargets.isEmpty()) {
                    Text("Aucun tableau de suivi configuré. Ajoutez-en un dans Paramètres.")
                } else {
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        trackingTargets.forEachIndexed { index, target ->
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Checkbox(
                                    checked = selected.getOrElse(index) { false },
                                    onCheckedChange = { checked ->
                                        selected = selected.toMutableList().also { it[index] = checked }
                                    },
                                )
                                Spacer(Modifier.width(4.dp))
                                Column {
                                    Text(
                                        target.yearLabel.ifBlank { "Tous les stages" },
                                        fontWeight = FontWeight.Medium,
                                        fontSize = 13.sp,
                                    )
                                    Text(
                                        target.filePath,
                                        fontSize = 11.sp,
                                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f),
                                    )
                                }
                            }
                        }
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        val chosen = trackingTargets.filterIndexed { i, _ -> selected.getOrElse(i) { false } }
                        if (chosen.isNotEmpty()) {
                            showTrackingDialog = false
                            onExportTracking(chosen)
                        }
                    },
                    enabled = !isLoading && trackingTargets.isNotEmpty() && selected.any { it },
                ) {
                    Icon(Icons.Default.GridOn, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("Mettre à jour")
                }
            },
            dismissButton = {
                TextButton(onClick = { showTrackingDialog = false }) { Text("Annuler") }
            },
        )
    }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
    ) {
        // ── Ligne 1 : recherche + boutons ─────────────────────────────────
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = filterText,
                onValueChange = onTextChange,
                placeholder = { Text("Rechercher étudiant, organisme, thème…", fontSize = 13.sp) },
                leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                trailingIcon = {
                    if (filterText.isNotEmpty()) {
                        IconButton(onClick = { onTextChange("") }) {
                            Icon(Icons.Default.Clear, contentDescription = "Effacer")
                        }
                    }
                },
                singleLine = true,
                modifier = Modifier.weight(1f).height(52.dp),
                shape = RoundedCornerShape(8.dp),
            )
            Spacer(Modifier.width(12.dp))
            Text(
                "$count affiché(s)",
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
            )
            Spacer(Modifier.width(8.dp))
            AssistChip(
                onClick = onLoadFromDb,
                label = { Text("Base : $dbCount", fontSize = 12.sp) },
                leadingIcon = {
                    Icon(Icons.Default.Storage, contentDescription = null, modifier = Modifier.size(14.dp))
                },
                enabled = !isLoading,
            )
            Spacer(Modifier.width(8.dp))
            Button(
                onClick = {
                    dialogFilters = scrapeFilters   // ouvre avec les filtres actuels
                    showScrapeDialog = true
                },
                enabled = !isLoading && isLoggedIn,
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.secondary),
            ) {
                Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(4.dp))
                Text("Extraire", fontSize = 13.sp)
            }
            Spacer(Modifier.width(8.dp))
            OutlinedButton(onClick = onExport, enabled = !isLoading && count > 0) {
                Icon(Icons.Default.FileDownload, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(4.dp))
                Text("Excel", fontSize = 13.sp)
            }
            Spacer(Modifier.width(8.dp))
            OutlinedButton(onClick = onExportVetAgroTice, enabled = !isLoading && count > 0) {
                Icon(Icons.Default.FileDownload, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(4.dp))
                Text("CSV VetAgroTice", fontSize = 13.sp)
            }
            Spacer(Modifier.width(8.dp))
            OutlinedButton(
                onClick = onReanalyzeAllPdfs,
                enabled = !isLoading && isLoggedIn && count > 0,
            ) {
                Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(4.dp))
                Text("Réanalyser les PDF", fontSize = 13.sp)
            }
        }

        Spacer(Modifier.height(8.dp))

        // ── Ligne 2 : filtres locaux ───────────────────────────────────────
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                "Filtres locaux :",
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f),
            )
            FilterDropdown(
                label = "Période",
                options = ScrapeFilterOptions.periodes,
                selected = localFilters.periode,
                onSelect = { onLocalFiltersChange(localFilters.copy(periode = it)) },
                modifier = Modifier.weight(0.8f),
            )
            FilterDropdown(
                label = "Année d'étude",
                options = ScrapeFilterOptions.annees,
                selected = localFilters.anneeEtude,
                onSelect = { onLocalFiltersChange(localFilters.copy(anneeEtude = it)) },
                modifier = Modifier.weight(0.8f),
            )
            FilterDropdown(
                label = "Thème de stage",
                options = ScrapeFilterOptions.themes,
                selected = localFilters.theme,
                onSelect = { onLocalFiltersChange(localFilters.copy(theme = it)) },
                modifier = Modifier.weight(1.4f),
            )
            if (localFilters != LocalFilters()) {
                TextButton(
                    onClick = { onLocalFiltersChange(LocalFilters()) },
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp),
                ) {
                    Icon(Icons.Default.Clear, contentDescription = null, modifier = Modifier.size(13.dp))
                    Spacer(Modifier.width(2.dp))
                    Text("Réinitialiser", fontSize = 11.sp)
                }
            }
        }

        Spacer(Modifier.height(8.dp))

        // ── Ligne 3 : filtres de vue ───────────────────────────────────────
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ViewFilter.entries.forEach { filter ->
                FilterChip(
                    selected = activeFilter == filter,
                    onClick = { onViewChange(filter) },
                    label = { Text(filter.label, fontSize = 12.sp) },
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun FilterDropdown(
    label: String,
    options: List<FilterOption>,
    selected: String,
    onSelect: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    var expanded by remember { mutableStateOf(false) }
    val selectedLabel = options.find { it.value == selected }?.label ?: options.firstOrNull()?.label ?: ""

    ExposedDropdownMenuBox(
        expanded = expanded,
        onExpandedChange = { expanded = it },
        modifier = modifier,
    ) {
        OutlinedTextField(
            value = selectedLabel,
            onValueChange = {},
            readOnly = true,
            label = { Text(label, fontSize = 11.sp) },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded) },
            singleLine = true,
            textStyle = LocalTextStyle.current.copy(fontSize = 12.sp),
            modifier = Modifier.menuAnchor(MenuAnchorType.PrimaryNotEditable).fillMaxWidth(),
        )
        ExposedDropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
        ) {
            options.forEach { opt ->
                DropdownMenuItem(
                    text = { Text(opt.label, fontSize = 12.sp) },
                    onClick = {
                        onSelect(opt.value)
                        expanded = false
                    },
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                )
            }
        }
    }
}
