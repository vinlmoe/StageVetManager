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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import fr.vetbrain.stagevetmanager.model.ClinicStatus
import fr.vetbrain.stagevetmanager.model.ConventionPdfData
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
    ColumnDef("Étudiant",    0.18f, SortColumn.STUDENT)      { it.studentName },
    ColumnDef("Année",       0.07f, SortColumn.YEAR)         { it.studyYear },
    ColumnDef("Organisme",   0.18f, SortColumn.ORGANIZATION) { it.organization },
    ColumnDef("Adresse",     0.12f, null)                    { it.address },
    ColumnDef("Début stage", 0.10f, SortColumn.START_DATE)   { it.startDate?.format(DATE_FMT) ?: "" },
    ColumnDef("Fin stage",   0.07f, null)                    { it.endDate?.format(DATE_FMT) ?: "" },
    ColumnDef("Signature",   0.10f, SortColumn.SIGN_DATE)    { it.signingDate?.format(DATE_FMT) ?: "" },
    ColumnDef("Thème",       0.10f, SortColumn.THEME)        { it.theme },
)
// weights above sum to 0.92 — remaining 0.08 goes to the Conv. actions column

@Composable
fun InternshipTable(
    internships: List<Internship>,
    sortColumn: SortColumn,
    sortAscending: Boolean,
    onSort: (SortColumn) -> Unit,
    modifier: Modifier = Modifier,
    pdfDataCache: Map<String, ConventionPdfData> = emptyMap(),
    clinicStatuses: Map<String, ClinicStatus> = emptyMap(),
    onSelectInternship: ((Internship) -> Unit)? = null,
    onToggleSuivi: ((Internship, Boolean) -> Unit)? = null,
) {
    var cancelInternship      by remember { mutableStateOf<Internship?>(null) }
    var signAlertInternship   by remember { mutableStateOf<Internship?>(null) }
    var clinicBlacklistAlert  by remember { mutableStateOf<Internship?>(null) }
    var clinicWatchAlert      by remember { mutableStateOf<Internship?>(null) }

    // — Dialogs —————————————————————————————————————————————————————————————
    val cancelValue = cancelInternship
    if (cancelValue != null) {
        AlertDialog(
            onDismissRequest = { cancelInternship = null },
            title = { Text("Annuler la convention ?") },
            text  = { Text("Cette action annulera la convention de ${cancelValue.studentName} sur stagevet.fr.") },
            confirmButton = {
                TextButton(onClick = {
                    openInBrowser(cancelValue.conventionCancelUrl)
                    cancelInternship = null
                }) { Text("Annuler la convention", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { cancelInternship = null }) { Text("Garder") }
            },
        )
    }

    val blacklistValue = clinicBlacklistAlert
    if (blacklistValue != null) {
        AlertDialog(
            onDismissRequest = { clinicBlacklistAlert = null },
            title = { Text("Clinique — Ne plus envoyer") },
            text  = { Text("L'organisme « ${blacklistValue.organization} » est marqué « Ne plus envoyer ».\n\nVoulez-vous quand même signer cette convention ?") },
            confirmButton = {
                TextButton(onClick = {
                    val pdf = pdfDataCache[blacklistValue.conventionPdfUrl]
                    val hasInconsistency = pdf != null && (pdf.sundayPresence || pdf.holidayPresence || pdf.hasWeeklyRestDay == false)
                    if (hasInconsistency) signAlertInternship = blacklistValue
                    else openInBrowser(blacklistValue.conventionSignUrl)
                    clinicBlacklistAlert = null
                }) { Text("Signer quand même", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { clinicBlacklistAlert = null }) { Text("Annuler") }
            },
        )
    }

    val watchValue = clinicWatchAlert
    if (watchValue != null) {
        AlertDialog(
            onDismissRequest = { clinicWatchAlert = null },
            title = { Text("Clinique — À surveiller") },
            text  = { Text("L'organisme « ${watchValue.organization} » est marqué « À surveiller ».\n\nVérifiez les conditions de stage avant de signer.") },
            confirmButton = {
                TextButton(onClick = {
                    val pdf = pdfDataCache[watchValue.conventionPdfUrl]
                    val hasInconsistency = pdf != null && (pdf.sundayPresence || pdf.holidayPresence || pdf.hasWeeklyRestDay == false)
                    if (hasInconsistency) signAlertInternship = watchValue
                    else openInBrowser(watchValue.conventionSignUrl)
                    clinicWatchAlert = null
                }) { Text("Continuer", color = Color(0xFFF57F17)) }
            },
            dismissButton = {
                TextButton(onClick = { clinicWatchAlert = null }) { Text("Annuler") }
            },
        )
    }

    val signValue = signAlertInternship
    if (signValue != null) {
        val pdf = pdfDataCache[signValue.conventionPdfUrl]
        SignInconsistencyDialog(
            sundayPresence = pdf?.sundayPresence == true,
            holidayPresence = pdf?.holidayPresence == true,
            hasWeeklyRestDay = pdf?.hasWeeklyRestDay,
            sundayDates = pdf?.sundayDates ?: emptyList(),
            holidayDates = pdf?.holidayDates ?: emptyList(),
            onConfirm = {
                openInBrowser(signValue.conventionSignUrl)
                signAlertInternship = null
            },
            onDismiss = { signAlertInternship = null },
        )
    }

    // — Layout ——————————————————————————————————————————————————————————————
    Column(modifier = modifier.fillMaxSize()) {
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
                        .then(if (col.sortCol != null) Modifier.clickable { onSort(col.sortCol) } else Modifier)
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
                        Icon(icon, contentDescription = null, tint = Color.White, modifier = Modifier.size(14.dp))
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
                    val clinicStatus = clinicStatuses[internship.organization] ?: ClinicStatus.OK
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
                            if (col.sortCol == SortColumn.ORGANIZATION) {
                                // Colonne organisme : dot statut + texte
                                Row(
                                    modifier = Modifier.weight(col.weight).padding(horizontal = 4.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    ClinicStatusDot(clinicStatus)
                                    if (clinicStatus != ClinicStatus.OK) Spacer(Modifier.width(4.dp))
                                    Text(
                                        col.value(internship),
                                        fontSize = 12.sp,
                                        maxLines = 2,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                }
                            } else {
                                Text(
                                    col.value(internship),
                                    modifier = Modifier.weight(col.weight).padding(horizontal = 4.dp),
                                    fontSize = 12.sp,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                        }
                        // Actions convention
                        Row(
                            modifier = Modifier.weight(0.08f).padding(horizontal = 2.dp),
                            horizontalArrangement = Arrangement.spacedBy(3.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            if (internship.signingDate != null) {
                                TipIcon("Convention signée le ${internship.signingDate.format(DATE_FMT)}", Icons.Default.CheckCircle, Color(0xFF2E7D32))
                            }
                            if (internship.conventionPdfUrl.isNotEmpty()) {
                                TipIcon(
                                    "Voir la convention PDF", Icons.Default.Description,
                                    MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(14.dp).clickable { openInBrowser(internship.conventionPdfUrl) },
                                )
                            }
                            val pdf = pdfDataCache[internship.conventionPdfUrl]
                            val schoolNotSigned = internship.signingDate == null
                            val preSignaturesDone = if (pdf != null)
                                pdf.allPreSignaturesDone && pdf.signingDateSchool.isBlank()
                            else
                                internship.conventionSignUrl.isNotEmpty()
                            if (schoolNotSigned && preSignaturesDone) {
                                val hasInconsistency = pdf != null &&
                                    (pdf.sundayPresence || pdf.holidayPresence || pdf.hasWeeklyRestDay == false)
                                TipIcon(
                                    tip = when {
                                        clinicStatus == ClinicStatus.BLACKLISTED -> "Signer (clinique : ne plus envoyer !)"
                                        clinicStatus == ClinicStatus.WATCH       -> "Signer (clinique : à surveiller)"
                                        hasInconsistency                         -> "Signer (incohérence détectée)"
                                        else                                     -> "Signer la convention"
                                    },
                                    imageVector = Icons.Default.Edit,
                                    tint = when {
                                        clinicStatus == ClinicStatus.BLACKLISTED -> Color(0xFFB71C1C)
                                        clinicStatus == ClinicStatus.WATCH       -> Color(0xFFF57F17)
                                        hasInconsistency                         -> Color(0xFFB71C1C)
                                        else                                     -> Color(0xFFE65100)
                                    },
                                    modifier = Modifier.size(14.dp).clickable {
                                        when (clinicStatus) {
                                            ClinicStatus.BLACKLISTED -> clinicBlacklistAlert = internship
                                            ClinicStatus.WATCH       -> clinicWatchAlert = internship
                                            else -> if (hasInconsistency) signAlertInternship = internship
                                                    else openInBrowser(internship.conventionSignUrl)
                                        }
                                    },
                                )
                                SignUrgencyBadge(internship.startDate, iconSize = 13)
                            }
                            if (internship.conventionCancelUrl.isNotEmpty()) {
                                TipIcon(
                                    "Annuler la convention", Icons.Default.Cancel,
                                    MaterialTheme.colorScheme.error,
                                    modifier = Modifier.size(14.dp).clickable { cancelInternship = internship },
                                )
                            }
                            if (internship.inSuiviTable) {
                                TipIcon(
                                    "Dans le tableau de suivi — cliquer pour retirer",
                                    Icons.Default.CheckCircle, Color(0xFF1565C0),
                                    modifier = Modifier.size(14.dp).clickable { onToggleSuivi?.invoke(internship, false) },
                                )
                            } else if (onToggleSuivi != null) {
                                TipIcon(
                                    "Marquer dans le tableau de suivi",
                                    Icons.Default.CheckCircle, Color.LightGray,
                                    modifier = Modifier.size(14.dp).clickable { onToggleSuivi.invoke(internship, true) },
                                )
                            }
                        }
                    }
                    HorizontalDivider(color = Color(0xFFE0E0E0), thickness = 0.5.dp)
                }
            }
        }
    }
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

private fun openInBrowser(url: String) {
    runCatching {
        val desktop = Desktop.getDesktop()
        if (desktop.isSupported(Desktop.Action.BROWSE)) desktop.browse(URI(url))
    }
}
