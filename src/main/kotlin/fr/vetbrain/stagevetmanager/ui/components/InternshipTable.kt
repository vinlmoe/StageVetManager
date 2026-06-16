package fr.vetbrain.stagevetmanager.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.UnfoldMore
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import fr.vetbrain.stagevetmanager.model.Internship
import fr.vetbrain.stagevetmanager.viewmodel.SortColumn
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
    ColumnDef("Adresse",    0.15f, null)                    { it.address },
    ColumnDef("Début stage",0.10f, SortColumn.START_DATE)   { it.startDate?.format(DATE_FMT) ?: "" },
    ColumnDef("Fin stage",  0.10f, null)                    { it.endDate?.format(DATE_FMT) ?: "" },
    ColumnDef("Signature",  0.10f, SortColumn.SIGN_DATE)    { it.signingDate?.format(DATE_FMT) ?: "" },
    ColumnDef("Thème",      0.12f, SortColumn.THEME)        { it.theme },
)

@Composable
fun InternshipTable(
    internships: List<Internship>,
    sortColumn: SortColumn,
    sortAscending: Boolean,
    onSort: (SortColumn) -> Unit,
    modifier: Modifier = Modifier,
) {
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
                    }
                    HorizontalDivider(color = Color(0xFFE0E0E0), thickness = 0.5.dp)
                }
            }
        }
    }
}
