package fr.vetbrain.stagevetmanager.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import fr.vetbrain.stagevetmanager.model.ClinicStatus
import fr.vetbrain.stagevetmanager.model.Internship
import java.time.format.DateTimeFormatter

private val DATE_FMT = DateTimeFormatter.ofPattern("dd/MM/yyyy")

private data class ClinicSummary(
    val organization: String,
    val address: String,
    val stages: List<Internship>,
    val status: ClinicStatus,
)

@Composable
fun ClinicView(
    internships: List<Internship>,
    clinicStatuses: Map<String, ClinicStatus>,
    onSetClinicStatus: (String, ClinicStatus) -> Unit,
    searchText: String = "",
    modifier: Modifier = Modifier,
) {
    val clinics = remember(internships, clinicStatuses, searchText) {
        internships.groupBy { it.organization.trim() }
            .filter { it.key.isNotBlank() }
            .map { (org, list) ->
                ClinicSummary(
                    organization = org,
                    address = list.firstOrNull { it.address.isNotBlank() }?.address ?: "",
                    stages = list.sortedWith(compareBy(nullsLast()) { it.startDate }),
                    status = clinicStatuses[org] ?: ClinicStatus.OK,
                )
            }
            .filter { clinic ->
                searchText.isBlank() ||
                    clinic.stages.any { it.matchesText(searchText) }
            }
            .sortedWith(compareBy({ it.status.ordinal }, { it.organization }))
    }

    val expanded = remember { mutableStateMapOf<String, Boolean>() }
    var statusMenuFor by remember { mutableStateOf<String?>(null) }

    Column(modifier = modifier.fillMaxSize()) {
        // En-tête
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(MaterialTheme.colorScheme.primary)
                .padding(horizontal = 8.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Spacer(Modifier.width(20.dp))
            ClinicHeaderCell("Organisme",  0.30f)
            ClinicHeaderCell("Adresse",    0.26f)
            ClinicHeaderCell("Statut",     0.16f)
            ClinicHeaderCell("Nb stages",  0.10f)
            ClinicHeaderCell("Étudiants",  0.18f)
        }

        if (clinics.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("Aucune clinique à afficher", color = Color.Gray)
            }
            return@Column
        }

        LazyColumn(modifier = Modifier.fillMaxSize()) {
            itemsIndexed(clinics, key = { _, c -> c.organization }) { idx, clinic ->
                val isExpanded = expanded[clinic.organization] == true
                val rowBg = if (idx % 2 == 0) Color.White
                            else Color(0xFFF0F4F8)

                // Ligne résumé
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(rowBg)
                        .clickable { expanded[clinic.organization] = !isExpanded }
                        .padding(horizontal = 8.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        if (isExpanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.width(4.dp))
                    // Org : dot + nom
                    Row(
                        modifier = Modifier.weight(0.30f).padding(end = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        ClinicStatusDot(clinic.status)
                        if (clinic.status != ClinicStatus.OK) Spacer(Modifier.width(5.dp))
                        Text(
                            clinic.organization,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Medium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    Text(
                        clinic.address,
                        modifier = Modifier.weight(0.26f).padding(end = 4.dp),
                        fontSize = 11.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    // Chip statut cliquable
                    Box(modifier = Modifier.weight(0.16f).padding(end = 4.dp)) {
                        val color = STATUS_COLORS[clinic.status] ?: Color.Gray
                        Surface(
                            shape = MaterialTheme.shapes.small,
                            color = color.copy(alpha = 0.15f),
                            modifier = Modifier.clickable { statusMenuFor = clinic.organization },
                        ) {
                            Text(
                                clinic.status.label,
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                                fontSize = 10.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = color,
                                maxLines = 1,
                            )
                        }
                        DropdownMenu(
                            expanded = statusMenuFor == clinic.organization,
                            onDismissRequest = { statusMenuFor = null },
                        ) {
                            ClinicStatus.values().forEach { s ->
                                DropdownMenuItem(
                                    text = {
                                        Text(
                                            s.label,
                                            color = STATUS_COLORS[s] ?: Color.Unspecified,
                                            fontWeight = if (s == clinic.status) FontWeight.Bold else FontWeight.Normal,
                                        )
                                    },
                                    onClick = {
                                        onSetClinicStatus(clinic.organization, s)
                                        statusMenuFor = null
                                    },
                                )
                            }
                        }
                    }
                    Text(
                        "${clinic.stages.size}",
                        modifier = Modifier.weight(0.10f).padding(end = 4.dp),
                        fontSize = 12.sp,
                    )
                    val students = clinic.stages.map { it.studentName }.distinct()
                    Text(
                        students.joinToString(", "),
                        modifier = Modifier.weight(0.18f),
                        fontSize = 10.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                // Lignes de détail
                if (isExpanded) {
                    clinic.stages.forEach { stage ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
                                .padding(start = 32.dp, end = 8.dp, top = 3.dp, bottom = 3.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(stage.studentName, modifier = Modifier.weight(0.22f).padding(end = 4.dp), fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(stage.studyYear,   modifier = Modifier.weight(0.08f).padding(end = 4.dp), fontSize = 11.sp)
                            Text(stage.startDate?.format(DATE_FMT) ?: "", modifier = Modifier.weight(0.13f).padding(end = 4.dp), fontSize = 11.sp)
                            Text(stage.endDate?.format(DATE_FMT) ?: "",   modifier = Modifier.weight(0.13f).padding(end = 4.dp), fontSize = 11.sp)
                            Text(stage.theme, modifier = Modifier.weight(0.27f).padding(end = 4.dp), fontSize = 10.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            val signed = stage.signingDate != null
                            Text(
                                if (signed) "Signé ✓" else "Non signé",
                                modifier = Modifier.weight(0.17f),
                                fontSize = 10.sp,
                                color = if (signed) Color(0xFF2E7D32) else Color(0xFFB71C1C),
                            )
                        }
                    }
                }

                HorizontalDivider(thickness = 0.5.dp, color = MaterialTheme.colorScheme.outlineVariant)
            }
        }
    }
}

@Composable
private fun RowScope.ClinicHeaderCell(label: String, weight: Float) {
    Text(
        label,
        modifier = Modifier.weight(weight).padding(end = 4.dp),
        fontSize = 11.sp,
        fontWeight = FontWeight.Bold,
        color = Color.White,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
    )
}
