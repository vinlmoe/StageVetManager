package fr.vetbrain.stagevetmanager.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import fr.vetbrain.stagevetmanager.model.FilterOption
import fr.vetbrain.stagevetmanager.model.ScrapeFilterOptions
import fr.vetbrain.stagevetmanager.model.ScrapeFilters

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ScrapeFiltersPanel(
    filters: ScrapeFilters,
    onFiltersChange: (ScrapeFilters) -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.25f),
        tonalElevation = 1.dp,
    ) {
        Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp)) {
            Text(
                "Filtres d'extraction (appliqués sur stagevet.fr avant la recherche)",
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.primary,
                style = MaterialTheme.typography.labelSmall,
            )
            Spacer(Modifier.height(8.dp))
            Row(
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                FilterDropdown(
                    label = "Période",
                    options = ScrapeFilterOptions.periodes,
                    selected = filters.periode,
                    onSelect = { onFiltersChange(filters.copy(periode = it)) },
                    modifier = Modifier.weight(1f),
                )
                FilterDropdown(
                    label = "Année d'étude",
                    options = ScrapeFilterOptions.annees,
                    selected = filters.anneeEtude,
                    onSelect = { onFiltersChange(filters.copy(anneeEtude = it)) },
                    modifier = Modifier.weight(1f),
                )
                FilterDropdown(
                    label = "Thème de stage",
                    options = ScrapeFilterOptions.themes,
                    selected = filters.theme,
                    onSelect = { onFiltersChange(filters.copy(theme = it)) },
                    modifier = Modifier.weight(1.6f),
                )
                FilterDropdown(
                    label = "Statut",
                    options = ScrapeFilterOptions.statuts,
                    selected = filters.status,
                    onSelect = { onFiltersChange(filters.copy(status = it)) },
                    modifier = Modifier.weight(1.3f),
                )
                FilterDropdown(
                    label = "Tri",
                    options = ScrapeFilterOptions.tris,
                    selected = filters.order,
                    onSelect = { onFiltersChange(filters.copy(order = it)) },
                    modifier = Modifier.weight(1.3f),
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
