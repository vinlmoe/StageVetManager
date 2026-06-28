package fr.vetbrain.stagevetmanager.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Cancel
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.FindInPage
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import fr.vetbrain.stagevetmanager.model.ClinicStatus
import fr.vetbrain.stagevetmanager.model.ConventionPdfData
import fr.vetbrain.stagevetmanager.model.Internship
import java.awt.Desktop
import java.net.URI
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit

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
    clinicStatuses: Map<String, ClinicStatus> = emptyMap(),
    isLoggedIn: Boolean = true,
    conventionDir: String = "",
    onDownloadSignedPdf: ((Internship) -> Unit)? = null,
    onOpenLocalPdf: ((String) -> Unit)? = null,
    onSelectInternship: ((Internship) -> Unit)? = null,
    onToggleSuivi: ((Internship, Boolean) -> Unit)? = null,
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
    var signAlertStage            by remember { mutableStateOf<Internship?>(null) }
    var cancelAlertStage          by remember { mutableStateOf<Internship?>(null) }
    var clinicBlacklistAlertStage by remember { mutableStateOf<Internship?>(null) }
    var clinicWatchAlertStage     by remember { mutableStateOf<Internship?>(null) }

    val cancelAlertStageValue = cancelAlertStage
    if (cancelAlertStageValue != null) {
        AlertDialog(
            onDismissRequest = { cancelAlertStage = null },
            title = { Text("Annuler la convention ?") },
            text  = { Text("Cette action annulera la convention de ${cancelAlertStageValue.studentName} sur stagevet.fr.") },
            confirmButton = {
                TextButton(onClick = {
                    openInBrowser(cancelAlertStageValue.conventionCancelUrl)
                    cancelAlertStage = null
                }) { Text("Annuler la convention", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { cancelAlertStage = null }) { Text("Garder") }
            },
        )
    }
    val clinicBlacklistAlertStageValue = clinicBlacklistAlertStage
    if (clinicBlacklistAlertStageValue != null) {
        AlertDialog(
            onDismissRequest = { clinicBlacklistAlertStage = null },
            title = { Text("Clinique — Ne plus envoyer") },
            text  = { Text("L'organisme « ${clinicBlacklistAlertStageValue.organization} » est marqué « Ne plus envoyer ».\n\nVoulez-vous quand même signer cette convention ?") },
            confirmButton = {
                TextButton(onClick = {
                    val pdf = pdfDataCache[clinicBlacklistAlertStageValue.conventionPdfUrl]
                    val days = stageDurationDays(clinicBlacklistAlertStageValue, pdf)
                    val hasInconsistency = (days != null && days > 30) ||
                        (pdf != null && (pdf.sundayPresence || pdf.holidayPresence || pdf.hasWeeklyRestDay == false))
                    if (hasInconsistency) signAlertStage = clinicBlacklistAlertStageValue
                    else openInBrowser(clinicBlacklistAlertStageValue.conventionSignUrl)
                    clinicBlacklistAlertStage = null
                }) { Text("Signer quand même", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { clinicBlacklistAlertStage = null }) { Text("Annuler") }
            },
        )
    }

    val clinicWatchAlertStageValue = clinicWatchAlertStage
    if (clinicWatchAlertStageValue != null) {
        AlertDialog(
            onDismissRequest = { clinicWatchAlertStage = null },
            title = { Text("Clinique — À surveiller") },
            text  = { Text("L'organisme « ${clinicWatchAlertStageValue.organization} » est marqué « À surveiller ».\n\nVérifiez les conditions de stage avant de signer.") },
            confirmButton = {
                TextButton(onClick = {
                    val pdf = pdfDataCache[clinicWatchAlertStageValue.conventionPdfUrl]
                    val days = stageDurationDays(clinicWatchAlertStageValue, pdf)
                    val hasInconsistency = (days != null && days > 30) ||
                        (pdf != null && (pdf.sundayPresence || pdf.holidayPresence || pdf.hasWeeklyRestDay == false))
                    if (hasInconsistency) signAlertStage = clinicWatchAlertStageValue
                    else openInBrowser(clinicWatchAlertStageValue.conventionSignUrl)
                    clinicWatchAlertStage = null
                }) { Text("Continuer", color = Color(0xFFF57F17)) }
            },
            dismissButton = {
                TextButton(onClick = { clinicWatchAlertStage = null }) { Text("Annuler") }
            },
        )
    }

    val signAlertStageValue = signAlertStage
    if (signAlertStageValue != null) {
        val pdf = pdfDataCache[signAlertStageValue.conventionPdfUrl]
        SignInconsistencyDialog(
            sundayPresence = pdf?.sundayPresence == true,
            holidayPresence = pdf?.holidayPresence == true,
            hasWeeklyRestDay = pdf?.hasWeeklyRestDay,
            longDurationDays = stageDurationDays(signAlertStageValue, pdf)?.takeIf { it > 30 },
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
                            val stageClinicStatus = clinicStatuses[stage.organization] ?: ClinicStatus.OK
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f))
                                    .padding(start = 36.dp, end = 8.dp, top = 3.dp, bottom = 3.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Row(
                                    modifier = Modifier.weight(0.20f).padding(end = 4.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    ClinicStatusDot(stageClinicStatus)
                                    if (stageClinicStatus != ClinicStatus.OK) Spacer(Modifier.width(4.dp))
                                    Text(
                                        stage.organization,
                                        fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                                    )
                                }
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
                                // Convention actions
                                Row(
                                    modifier = Modifier.weight(0.10f),
                                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    if (stage.signingDate != null) {
                                        TipIcon(
                                            tip = "Convention signée le ${stage.signingDate.format(DATE_FMT)}",
                                            imageVector = Icons.Default.CheckCircle,
                                            tint = Color(0xFF2E7D32),
                                        )
                                    }
                                    if (stage.conventionPdfUrl.isNotEmpty()) {
                                        TipIcon(
                                            tip = "Voir la convention PDF",
                                            imageVector = Icons.Default.Description,
                                            tint = MaterialTheme.colorScheme.primary,
                                            modifier = Modifier.size(14.dp)
                                                .clickable { openInBrowser(stage.conventionPdfUrl) },
                                        )
                                    }
                                    if (stage.conventionPdfUrl.isNotEmpty() && onSelectInternship != null) {
                                        TipIcon(
                                            tip = "Voir le détail du stage",
                                            imageVector = Icons.Default.FindInPage,
                                            tint = Color(0xFF6A1B9A),
                                            modifier = Modifier.size(14.dp)
                                                .clickable { onSelectInternship(stage) },
                                        )
                                    }
                                    val pdf = pdfDataCache[stage.conventionPdfUrl]
                                    val schoolNotSigned = stage.signingDate == null
                                    val preSignaturesDone = if (pdf != null)
                                        pdf.allPreSignaturesDone && pdf.signingDateSchool.isBlank()
                                    else
                                        stage.conventionSignUrl.isNotEmpty()
                                    if (isLoggedIn && schoolNotSigned && preSignaturesDone) {
                                        val days = stageDurationDays(stage, pdf)
                                        val isLongDuration = days != null && days > 30
                                        val hasInconsistency = isLongDuration ||
                                            (pdf != null && (pdf.sundayPresence || pdf.holidayPresence || pdf.hasWeeklyRestDay == false))
                                        TipIcon(
                                            tip = when {
                                                stageClinicStatus == ClinicStatus.BLACKLISTED -> "Signer (clinique : ne plus envoyer !)"
                                                stageClinicStatus == ClinicStatus.WATCH       -> "Signer (clinique : à surveiller)"
                                                hasInconsistency                              -> "Signer la convention (avertissement)"
                                                else                                          -> "Signer la convention"
                                            },
                                            imageVector = Icons.Default.Edit,
                                            tint = when {
                                                stageClinicStatus == ClinicStatus.BLACKLISTED -> Color(0xFFB71C1C)
                                                stageClinicStatus == ClinicStatus.WATCH       -> Color(0xFFF57F17)
                                                hasInconsistency                              -> Color(0xFFB71C1C)
                                                else                                          -> Color(0xFFE65100)
                                            },
                                            modifier = Modifier.size(14.dp).clickable {
                                                when (stageClinicStatus) {
                                                    ClinicStatus.BLACKLISTED -> clinicBlacklistAlertStage = stage
                                                    ClinicStatus.WATCH       -> clinicWatchAlertStage = stage
                                                    else -> if (hasInconsistency) signAlertStage = stage
                                                            else openInBrowser(stage.conventionSignUrl)
                                                }
                                            },
                                        )
                                        SignUrgencyBadge(stage.startDate, iconSize = 13)
                                    }
                                    if (stage.conventionCancelUrl.isNotEmpty()) {
                                        TipIcon(
                                            tip = "Annuler la convention",
                                            imageVector = Icons.Default.Cancel,
                                            tint = MaterialTheme.colorScheme.error,
                                            modifier = Modifier.size(14.dp)
                                                .clickable { cancelAlertStage = stage },
                                        )
                                    }
                                    // Bouton téléchargement/ouverture locale de la convention signée
                                    if (stage.signingDate != null && stage.conventionPdfUrl.isNotEmpty() && isLoggedIn) {
                                        if (stage.localPdfPath.isNotEmpty()) {
                                            TipIcon(
                                                tip = "Ouvrir la convention locale (${stage.localPdfPath})",
                                                imageVector = Icons.Default.FolderOpen,
                                                tint = Color(0xFF2E7D32),
                                                modifier = Modifier.size(14.dp)
                                                    .clickable { onOpenLocalPdf?.invoke(stage.localPdfPath) },
                                            )
                                        } else {
                                            TipIcon(
                                                tip = if (conventionDir.isNotBlank()) "Télécharger la convention signée"
                                                      else "Configurez le dossier conventions dans Paramètres",
                                                imageVector = Icons.Default.Download,
                                                tint = if (conventionDir.isNotBlank()) Color(0xFF1565C0) else Color.LightGray,
                                                modifier = Modifier.size(14.dp).let { m ->
                                                    if (conventionDir.isNotBlank()) m.clickable { onDownloadSignedPdf?.invoke(stage) }
                                                    else m
                                                },
                                            )
                                        }
                                    }
                                    if (stage.inSuiviTable) {
                                        TipIcon(
                                            tip = "Dans le tableau de suivi — cliquer pour retirer",
                                            imageVector = Icons.Default.CheckCircle,
                                            tint = Color(0xFF1565C0),
                                            modifier = Modifier.size(14.dp)
                                                .clickable { onToggleSuivi?.invoke(stage, false) },
                                        )
                                    } else if (onToggleSuivi != null) {
                                        TipIcon(
                                            tip = "Marquer dans le tableau de suivi",
                                            imageVector = Icons.Default.CheckCircle,
                                            tint = Color.LightGray,
                                            modifier = Modifier.size(14.dp)
                                                .clickable { onToggleSuivi.invoke(stage, true) },
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TipIcon(
    tip: String,
    imageVector: ImageVector,
    tint: Color,
    modifier: Modifier = Modifier.size(14.dp),
) {
    TooltipBox(
        positionProvider = TooltipDefaults.rememberPlainTooltipPositionProvider(),
        tooltip = { PlainTooltip { Text(tip, fontSize = 11.sp) } },
        state = rememberTooltipState(),
    ) {
        Icon(imageVector, contentDescription = tip, modifier = modifier, tint = tint)
    }
}

@Composable
internal fun SignInconsistencyDialog(
    sundayPresence: Boolean,
    holidayPresence: Boolean,
    hasWeeklyRestDay: Boolean? = null,
    longDurationDays: Int? = null,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    val warnings = listOfNotNull(
        "durée de ${longDurationDays} jours effectifs (> 30 jours)".takeIf { longDurationDays != null },
        "présence le dimanche".takeIf { sundayPresence },
        "présence un jour férié".takeIf { holidayPresence },
        "absence de jour de repos hebdomadaire".takeIf { hasWeeklyRestDay == false },
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

/** Jours effectifs : declaredDaysCount du PDF si disponible, sinon jours calendaires inclusifs. */
private fun stageDurationDays(stage: Internship, pdf: ConventionPdfData?): Int? {
    if (pdf?.declaredDaysCount != null) return pdf.declaredDaysCount
    val s = stage.startDate ?: return null
    val e = stage.endDate   ?: return null
    return (ChronoUnit.DAYS.between(s, e) + 1).toInt()
}

private fun openInBrowser(url: String) {
    runCatching {
        val desktop = Desktop.getDesktop()
        if (desktop.isSupported(Desktop.Action.BROWSE)) desktop.browse(URI(url))
    }
}
