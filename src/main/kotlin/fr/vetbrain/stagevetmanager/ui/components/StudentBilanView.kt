package fr.vetbrain.stagevetmanager.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.FindInPage
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import fr.vetbrain.stagevetmanager.model.ConventionPdfData
import fr.vetbrain.stagevetmanager.model.Internship
import java.awt.Desktop
import java.net.URI
import java.time.LocalDate
import java.time.format.DateTimeFormatter

private val DATE_FMT = DateTimeFormatter.ofPattern("dd/MM/yyyy")

private data class StudentBilan(
    val studentName: String,
    val studyYear: String,
    val stages: List<Internship>,
    val orgsSummary: String,
    val themesSummary: String,
    val firstStart: LocalDate?,
    val lastEnd: LocalDate?,
)

@Composable
fun StudentBilanView(
    internships: List<Internship>,
    modifier: Modifier = Modifier,
    pdfDataCache: Map<String, ConventionPdfData> = emptyMap(),
    onSelectInternship: ((Internship) -> Unit)? = null,
) {
    val bilans = remember(internships) {
        internships.groupBy { it.studentName.trim() }
            .map { (name, list) ->
                StudentBilan(
                    studentName = name,
                    studyYear = list.firstOrNull()?.studyYear ?: "",
                    stages = list.sortedWith(compareBy(nullsLast()) { it.startDate }),
                    orgsSummary = list.map { it.organization }.filter { it.isNotBlank() }
                        .distinct().joinToString(" · "),
                    themesSummary = list.map { it.theme }.filter { it.isNotBlank() }
                        .distinct().joinToString(" · "),
                    firstStart = list.mapNotNull { it.startDate }.minOrNull(),
                    lastEnd = list.mapNotNull { it.endDate }.maxOrNull(),
                )
            }
            .sortedBy { it.studentName }
    }

    val expanded = remember { mutableStateMapOf<String, Boolean>() }
    // Stage en attente de signature avec incohérence détectée
    var signAlertStage by remember { mutableStateOf<Internship?>(null) }
    val signAlertStageValue = signAlertStage
    if (signAlertStageValue != null) {
        val pdf = pdfDataCache[signAlertStageValue.conventionPdfUrl]
        SignInconsistencyDialog(
            sundayPresence = pdf?.sundayPresence == true,
            holidayPresence = pdf?.holidayPresence == true,
            onConfirm = {
                openInBrowser(signAlertStageValue.conventionSignUrl)
                signAlertStage = null
            },
            onDismiss = { signAlertStage = null },
        )
    }

    Column(modifier = modifier.fillMaxSize()) {
        // ── En-tête ──────────────────────────────────────────────────────────
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(MaterialTheme.colorScheme.primary)
                .padding(horizontal = 8.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Spacer(Modifier.width(20.dp)) // aligne avec l'icône d'expansion
            HeaderCell("Étudiant",   0.18f)
            HeaderCell("Année",      0.07f)
            HeaderCell("Stages",     0.05f)
            HeaderCell("Organismes", 0.22f)
            HeaderCell("Thèmes",     0.20f)
            HeaderCell("Période",    0.28f)
        }

        if (bilans.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("Aucun stage à afficher", color = Color.Gray)
            }
            return@Column
        }

        // ── Lignes ───────────────────────────────────────────────────────────
        LazyColumn(Modifier.fillMaxSize()) {
            itemsIndexed(bilans) { idx, bilan ->
                val isExpanded = expanded[bilan.studentName] == true
                val rowBg = if (idx % 2 == 0) Color.White else Color(0xFFF0F4F8)

                Column {
                    // Ligne de résumé (cliquable)
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(rowBg)
                            .clickable { expanded[bilan.studentName] = !isExpanded }
                            .padding(horizontal = 8.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(
                            imageVector = if (isExpanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                            contentDescription = if (isExpanded) "Réduire" else "Développer",
                            modifier = Modifier.size(16.dp),
                            tint = MaterialTheme.colorScheme.primary,
                        )
                        Spacer(Modifier.width(4.dp))

                        Text(
                            bilan.studentName,
                            modifier = Modifier.weight(0.18f).padding(end = 4.dp),
                            fontWeight = FontWeight.SemiBold,
                            fontSize = 12.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            bilan.studyYear,
                            modifier = Modifier.weight(0.07f).padding(end = 4.dp),
                            fontSize = 12.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            "${bilan.stages.size}",
                            modifier = Modifier.weight(0.05f).padding(end = 4.dp),
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.primary,
                        )
                        Text(
                            bilan.orgsSummary,
                            modifier = Modifier.weight(0.22f).padding(end = 4.dp),
                            fontSize = 11.sp,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            bilan.themesSummary,
                            modifier = Modifier.weight(0.20f).padding(end = 4.dp),
                            fontSize = 11.sp,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            when {
                                bilan.firstStart != null && bilan.lastEnd != null ->
                                    "${bilan.firstStart.format(DATE_FMT)} → ${bilan.lastEnd.format(DATE_FMT)}"
                                bilan.firstStart != null ->
                                    "depuis ${bilan.firstStart.format(DATE_FMT)}"
                                else -> ""
                            },
                            modifier = Modifier.weight(0.28f),
                            fontSize = 11.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }

                    // Sous-lignes de détail (visibles uniquement si développé)
                    if (isExpanded) {
                        bilan.stages.forEach { stage ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f))
                                    .padding(start = 36.dp, end = 8.dp, top = 3.dp, bottom = 3.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(
                                    stage.organization,
                                    modifier = Modifier.weight(0.20f).padding(end = 4.dp),
                                    fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                                )
                                Text(
                                    when {
                                        stage.startDate != null && stage.endDate != null ->
                                            "${stage.startDate.format(DATE_FMT)} – ${stage.endDate.format(DATE_FMT)}"
                                        stage.rawDateStage.isNotBlank() -> stage.rawDateStage
                                        else -> "—"
                                    },
                                    modifier = Modifier.weight(0.20f).padding(end = 4.dp),
                                    fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                                )
                                Text(
                                    stage.signingDate?.format(DATE_FMT) ?: "—",
                                    modifier = Modifier.weight(0.12f).padding(end = 4.dp),
                                    fontSize = 11.sp, maxLines = 1,
                                    color = if (stage.signingDate != null)
                                        MaterialTheme.colorScheme.onSurface
                                    else Color.Gray,
                                )
                                Text(
                                    stage.theme,
                                    modifier = Modifier.weight(0.22f).padding(end = 4.dp),
                                    fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                                )
                                Text(
                                    stage.conventionNumber,
                                    modifier = Modifier.weight(0.16f).padding(end = 4.dp),
                                    fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f),
                                )
                                // Convention actions: signed indicator + PDF link + sign link
                                Row(
                                    modifier = Modifier.weight(0.10f),
                                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    if (stage.signingDate != null) {
                                        Icon(
                                            Icons.Default.CheckCircle,
                                            contentDescription = "Convention signée le ${stage.signingDate.format(DATE_FMT)}",
                                            modifier = Modifier.size(14.dp),
                                            tint = Color(0xFF2E7D32),
                                        )
                                    }
                                    if (stage.conventionPdfUrl.isNotEmpty()) {
                                        Icon(
                                            Icons.Default.Description,
                                            contentDescription = "Voir la convention PDF",
                                            modifier = Modifier.size(14.dp).clickable { openInBrowser(stage.conventionPdfUrl) },
                                            tint = MaterialTheme.colorScheme.primary,
                                        )
                                    }
                                    if (stage.conventionPdfUrl.isNotEmpty() && onSelectInternship != null) {
                                        Icon(
                                            Icons.Default.FindInPage,
                                            contentDescription = "Voir le détail du stage",
                                            modifier = Modifier.size(14.dp).clickable { onSelectInternship(stage) },
                                            tint = Color(0xFF6A1B9A),
                                        )
                                    }
                                    if (stage.conventionSignUrl.isNotEmpty()) {
                                        val pdf = pdfDataCache[stage.conventionPdfUrl]
                                        val hasInconsistency = pdf != null &&
                                            (pdf.sundayPresence || pdf.holidayPresence)
                                        Icon(
                                            Icons.Default.Edit,
                                            contentDescription = "Signer la convention",
                                            modifier = Modifier.size(14.dp).clickable {
                                                if (hasInconsistency) signAlertStage = stage
                                                else openInBrowser(stage.conventionSignUrl)
                                            },
                                            tint = if (hasInconsistency) Color(0xFFB71C1C)
                                                   else Color(0xFFE65100),
                                        )
                                    }
                                }
                            }
                            HorizontalDivider(color = Color(0xFFE0E0E0), thickness = 0.5.dp)
                        }
                    }

                    HorizontalDivider(color = Color(0xFFE0E0E0), thickness = 0.5.dp)
                }
            }
        }
    }
}

@Composable
private fun RowScope.HeaderCell(label: String, weight: Float) {
    Text(
        label,
        modifier = Modifier.weight(weight).padding(end = 4.dp),
        color = Color.White,
        fontWeight = FontWeight.SemiBold,
        fontSize = 12.sp,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
    )
}

@Composable
internal fun SignInconsistencyDialog(
    sundayPresence: Boolean,
    holidayPresence: Boolean,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    val warnings = listOfNotNull(
        "présence le dimanche".takeIf { sundayPresence },
        "présence un jour férié".takeIf { holidayPresence },
    )
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Vérification avant signature") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("Cette convention comporte une incohérence à vérifier :")
                warnings.forEach { w ->
                    Text("• $w", color = MaterialTheme.colorScheme.error,
                        fontWeight = FontWeight.Medium)
                }
                Text(
                    "Assurez-vous que la case correspondante est bien cochée dans la convention avant de valider.",
                    fontSize = 12.sp, color = Color.Gray,
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text("Signer quand même", color = MaterialTheme.colorScheme.error)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Annuler") }
        },
    )
}

private fun openInBrowser(url: String) {
    runCatching {
        val desktop = Desktop.getDesktop()
        if (desktop.isSupported(Desktop.Action.BROWSE)) desktop.browse(URI(url))
    }
}
