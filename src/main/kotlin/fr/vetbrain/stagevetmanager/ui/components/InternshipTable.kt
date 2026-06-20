package fr.vetbrain.stagevetmanager.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.Cancel
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.UnfoldMore
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import fr.vetbrain.stagevetmanager.model.Internship
import fr.vetbrain.stagevetmanager.viewmodel.SortColumn
import java.awt.Desktop
import java.net.URI
import java.time.format.DateTimeFormatter

private val DATE_FMT = DateTimeFormatter.ofPattern("dd/MM/yyyy")

data class ColumnDef(
    val label: String,
    val weight: Float,
    val sortCol: SortColumn?,
    val value: (Internship) -> String,
)

private val COLUMNS = listOf(
    ColumnDef("Étudiant",   0.18f, SortColumn.STUDENT)      { it.studentName },
    ColumnDef("Année",      0.07f, SortColumn.YEAR)         { it.studyYear },
    ColumnDef("Organisme",  0.18f, SortColumn.ORGANIZATION) { it.organization },
    ColumnDef("Adresse",    0.12f, null)                    { it.address },
    ColumnDef("Début stage",0.10f, SortColumn.START_DATE)   { it.startDate?.format(DATE_FMT) ?: "" },
    ColumnDef("Fin stage",  0.07f, null)                    { it.endDate?.format(DATE_FMT) ?: "" },
    ColumnDef("Signature",  0.10f, SortColumn.SIGN_DATE)    { it.signingDate?.format(DATE_FMT) ?: "" },
    ColumnDef("Thème",      0.10f, SortColumn.THEME)        { it.theme },
)
// weights above sum to 0.92 — remaining 0.08 goes to the Conv. actions column

@Composable
fun InternshipTable(
    internships: List<Internship>,
    sortColumn: SortColumn,
    sortAscending: Boolean,
    onSort: (SortColumn) -> Unit,
    modifier: Modifier = Modifier,
    onSelectInternship: ((Internship) -> Unit)? = null,
) {
    var cancelInternship by remember { mutableStateOf<Internship?>(null) }
    val cancelInternshipValue = cancelInternship
    if (cancelInternshipValue != null) {
        AlertDialog(
            onDismissRequest = { cancelInternship = null },
            title = { Text("Annuler la convention ?") },
            text  = { Text("Cette action annulera la convention de ${cancelInternshipValue.studentName} sur stagevet.fr.") },
            confirmButton = {
                TextButton(onClick = {
                    openInBrowser(cancelInternshipValue.conventionCancelUrl)
                    cancelInternship = null
                }) { Text("Annuler la convention", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { cancelInternship = null }) { Text("Garder") }
            },
        )
    }

    Column(modifier = modifier.fillMaxSize()) {
        // Header row
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(MaterialTheme.colorScheme.primary)
                .padding(horizontal = 8.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            COLUMNS.forEach { col ->
                Row(
                    modifier = Modifier
                        .weight(col.weight)
                        .then(
                            if (col.sortCol != null)
                                Modifier.clickable { onSort(col.sortCol) }
                            else Modifier
                        )
                        .padding(horizontal = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        col.label,
                        color = Color.White,
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 12.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    if (col.sortCol != null) {
                        val icon = when {
                            sortColumn == col.sortCol && sortAscending  -> Icons.Default.ArrowUpward
                            sortColumn == col.sortCol && !sortAscending -> Icons.Default.ArrowDownward
                            else                                        -> Icons.Default.UnfoldMore
                        }
                        Icon(icon, contentDescription = null, tint = Color.White,
                            modifier = Modifier.size(14.dp))
                    }
                }
            }
            Text(
                "Conv.",
                modifier = Modifier.weight(0.08f).padding(horizontal = 4.dp),
                color = Color.White,
                fontWeight = FontWeight.SemiBold,
                fontSize = 12.sp,
                maxLines = 1,
            )
        }

        if (internships.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("Aucun stage à afficher", color = Color.Gray)
            }
        } else {
            LazyColumn(modifier = Modifier.fillMaxSize()) {
                itemsIndexed(internships) { idx, internship ->
                    val bg = if (idx % 2 == 0) Color.White else Color(0xFFF0F4F8)
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(bg)
                            .then(if (onSelectInternship != null) Modifier.clickable { onSelectInternship(internship) } else Modifier)
                            .padding(horizontal = 8.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        COLUMNS.forEach { col ->
                            Text(
                                col.value(internship),
                                modifier = Modifier
                                    .weight(col.weight)
                                    .padding(horizontal = 4.dp),
                                fontSize = 12.sp,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                        Row(
                            modifier = Modifier.weight(0.08f).padding(horizontal = 2.dp),
                            horizontalArrangement = Arrangement.spacedBy(3.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            if (internship.signingDate != null) {
                                Icon(Icons.Default.CheckCircle,
                                    contentDescription = "Convention signée",
                                    modifier = Modifier.size(14.dp),
                                    tint = Color(0xFF2E7D32))
                            }
                            if (internship.conventionPdfUrl.isNotEmpty()) {
                                Icon(Icons.Default.Description,
                                    contentDescription = "Voir la convention PDF",
                                    modifier = Modifier.size(14.dp).clickable { openInBrowser(internship.conventionPdfUrl) },
                                    tint = MaterialTheme.colorScheme.primary)
                            }
                            // Signe : fallback sur conventionSignUrl (pas de cache PDF ici)
                            if (internship.conventionSignUrl.isNotEmpty() && internship.signingDate == null) {
                                Icon(Icons.Default.Edit,
                                    contentDescription = "Signer la convention",
                                    modifier = Modifier.size(14.dp).clickable { openInBrowser(internship.conventionSignUrl) },
                                    tint = Color(0xFFE65100))
                            }
                            if (internship.conventionCancelUrl.isNotEmpty()) {
                                Icon(Icons.Default.Cancel,
                                    contentDescription = "Annuler la convention",
                                    modifier = Modifier.size(14.dp).clickable { cancelInternship = internship },
                                    tint = MaterialTheme.colorScheme.error)
                            }
                            if (internship.inSuiviTable) {
                                Icon(Icons.Default.CheckCircle,
                                    contentDescription = "Dans le tableau de suivi",
                                    modifier = Modifier.size(14.dp),
                                    tint = Color(0xFF1565C0))
                            }
                        }
                    }
                    HorizontalDivider(color = Color(0xFFE0E0E0), thickness = 0.5.dp)
                }
            }
        }
    }
}

private fun openInBrowser(url: String) {
    runCatching {
        val desktop = Desktop.getDesktop()
        if (desktop.isSupported(Desktop.Action.BROWSE)) desktop.browse(URI(url))
    }
}
