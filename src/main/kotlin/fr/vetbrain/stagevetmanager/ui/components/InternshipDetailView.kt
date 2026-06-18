package fr.vetbrain.stagevetmanager.ui.components

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.FindInPage
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import fr.vetbrain.stagevetmanager.model.ConventionPdfData
import fr.vetbrain.stagevetmanager.model.Internship
import java.awt.Desktop
import java.net.URI
import java.time.format.DateTimeFormatter

private val DATE_FMT = DateTimeFormatter.ofPattern("dd/MM/yyyy")

@Composable
fun InternshipDetailView(
    internship: Internship,
    pdfData: ConventionPdfData?,
    isPdfLoading: Boolean,
    canDownloadPdf: Boolean,
    onDownloadPdf: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var showSignAlert by remember { mutableStateOf(false) }
    if (showSignAlert && pdfData != null) {
        SignInconsistencyDialog(
            sundayPresence = pdfData.sundayPresence,
            holidayPresence = pdfData.holidayPresence,
            onConfirm = {
                showSignAlert = false
                openUrl(internship.conventionSignUrl)
            },
            onDismiss = { showSignAlert = false },
        )
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        // ── Informations générales ────────────────────────────────────────
        DetailCard(title = "Informations générales") {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                DetailField("Étudiant",      internship.studentName)
                DetailField("Année",         internship.studyYear)
                DetailField("Organisme",     internship.organization)
                DetailField("Adresse",       internship.address)
                DetailField("Thème",         internship.theme)
                DetailField("Début",         internship.startDate?.format(DATE_FMT) ?: "—")
                DetailField("Fin",           internship.endDate?.format(DATE_FMT) ?: "—")
                DetailField("Convention n°", internship.conventionNumber)
                if (internship.signingDate != null) {
                    DetailField("Signé le",  internship.signingDate.format(DATE_FMT))
                }
            }
        }

        // ── Convention ────────────────────────────────────────────────────
        DetailCard(title = "Convention") {
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (internship.signingDate != null) {
                    Icon(Icons.Default.CheckCircle, null,
                        modifier = Modifier.size(18.dp), tint = Color(0xFF2E7D32))
                    Text("Signée le ${internship.signingDate.format(DATE_FMT)}",
                        fontSize = 12.sp, color = Color(0xFF2E7D32))
                    Spacer(Modifier.width(8.dp))
                }
                if (internship.conventionPdfUrl.isNotEmpty()) {
                    OutlinedButton(
                        onClick = { openUrl(internship.conventionPdfUrl) },
                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                    ) {
                        Icon(Icons.Default.Description, null, Modifier.size(16.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("Ouvrir PDF", fontSize = 12.sp)
                    }
                }
                if (internship.conventionSignUrl.isNotEmpty()) {
                    val hasInconsistency = pdfData != null &&
                        (pdfData.sundayPresence || pdfData.holidayPresence)
                    val signColor = if (hasInconsistency) Color(0xFFB71C1C) else Color(0xFFE65100)
                    OutlinedButton(
                        onClick = {
                            if (hasInconsistency) showSignAlert = true
                            else openUrl(internship.conventionSignUrl)
                        },
                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                    ) {
                        Icon(Icons.Default.Edit, null, Modifier.size(16.dp), tint = signColor)
                        Spacer(Modifier.width(4.dp))
                        Text("Signer", fontSize = 12.sp, color = signColor)
                    }
                }
                if (internship.conventionPdfUrl.isNotEmpty() && pdfData == null && !isPdfLoading) {
                    val enabled = canDownloadPdf
                    OutlinedButton(
                        onClick = onDownloadPdf,
                        enabled = enabled,
                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                    ) {
                        Icon(Icons.Default.FindInPage, null, Modifier.size(16.dp),
                            tint = if (enabled) Color(0xFF6A1B9A) else Color.Gray)
                        Spacer(Modifier.width(4.dp))
                        Text(
                            if (enabled) "Analyser le PDF" else "Analyser (scraping requis)",
                            fontSize = 12.sp,
                            color = if (enabled) Color(0xFF6A1B9A) else Color.Gray,
                        )
                    }
                }
                if (isPdfLoading) {
                    CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                    Text("Téléchargement…", fontSize = 12.sp, color = Color.Gray)
                }
            }
        }

        // ── Données extraites du PDF ──────────────────────────────────────
        if (pdfData != null && !isPdfLoading) {
            DetailCard(title = "Données extraites de la convention") {
                // — Stagiaire & École ——
                Row(modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    Column(modifier = Modifier.weight(1f),
                        verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        PdfSectionTitle("Stagiaire")
                        val fullName = listOf(pdfData.studentLastName, pdfData.studentFirstName)
                            .filter { it.isNotBlank() }.joinToString(" ")
                        PdfField("Nom",        fullName)
                        PdfField("Naissance",  pdfData.studentBirthDate)
                        PdfField("Adresse",    pdfData.studentAddress)
                        PdfField("Tél",        pdfData.studentPhone)
                        PdfField("Email",      pdfData.studentEmail)
                    }
                    Column(modifier = Modifier.weight(1f),
                        verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        PdfSectionTitle("Tuteur VetAgro Sup")
                        PdfField("Nom",        pdfData.tutorName)
                        PdfField("Fonction",   pdfData.tutorFunction)
                        PdfField("Tél",        pdfData.tutorPhone)
                        PdfField("Email",      pdfData.tutorEmail)
                        PdfField("Contact",    pdfData.schoolContact)
                    }
                }

                HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))

                // — Organisme & Maître de stage ——
                Row(modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    Column(modifier = Modifier.weight(1f),
                        verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        PdfSectionTitle("Organisme d'accueil")
                        PdfField("Nom",           pdfData.hostOrganization)
                        PdfField("Adresse",        pdfData.hostAddress)
                        PdfField("Représentant",   pdfData.hostRepresentative)
                        PdfField("Tél",            pdfData.hostPhone)
                        PdfField("Email",          pdfData.hostEmail)
                    }
                    Column(modifier = Modifier.weight(1f),
                        verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        PdfSectionTitle("Maître de stage")
                        PdfField("Nom",            pdfData.supervisorName)
                        PdfField("Qualité",        pdfData.supervisorQuality)
                        PdfField("Fonction",       pdfData.supervisorFunction)
                    }
                }

                HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))

                // — Période & Conditions ——
                Row(modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    Column(modifier = Modifier.weight(1f),
                        verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        PdfSectionTitle("Période")
                        PdfField("Année univ.",   pdfData.academicYear)
                        PdfField("Début",          pdfData.startDate)
                        PdfField("Fin",            pdfData.endDate)
                        PdfField("Durée",          pdfData.durationLabel)
                    }
                    Column(modifier = Modifier.weight(1f),
                        verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        PdfSectionTitle("Conditions")
                        PdfField("Thème",          pdfData.theme)
                        PdfField("Gratification",  pdfData.gratification)
                        val modalites = listOfNotNull(
                            "nuit".takeIf { pdfData.nightPresence },
                            "dimanche".takeIf { pdfData.sundayPresence },
                            "jours fériés".takeIf { pdfData.holidayPresence },
                            "domicile".takeIf { pdfData.homePresence },
                        )
                        if (modalites.isNotEmpty()) PdfField("Modalités", modalites.joinToString(", "))
                        if (pdfData.signingDateStudent.isNotBlank()) {
                            PdfSectionTitle("Signatures")
                            PdfField("Stagiaire",  pdfData.signingDateStudent)
                            PdfField("Organisme",  pdfData.signingDateHost)
                        }
                    }
                }

                // — Texte brut (pliable) ——
                var showRaw by remember { mutableStateOf(false) }
                HorizontalDivider(modifier = Modifier.padding(top = 8.dp))
                TextButton(
                    onClick = { showRaw = !showRaw },
                    contentPadding = PaddingValues(0.dp),
                ) {
                    Text(if (showRaw) "▲ Masquer le texte brut" else "▼ Afficher le texte brut",
                        fontSize = 11.sp)
                }
                if (showRaw) {
                    Surface(
                        color = MaterialTheme.colorScheme.surfaceVariant,
                        shape = MaterialTheme.shapes.small,
                    ) {
                        Text(pdfData.rawText,
                            modifier = Modifier.padding(8.dp).fillMaxWidth(),
                            fontFamily = FontFamily.Monospace,
                            fontSize = 9.sp, lineHeight = 13.sp)
                    }
                }
            }
        }
    }
}

@Composable
private fun DetailCard(title: String, content: @Composable ColumnScope.() -> Unit) {
    ElevatedCard(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(title, fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.primary,
                style = MaterialTheme.typography.titleSmall)
            HorizontalDivider()
            content()
        }
    }
}

@Composable
private fun DetailField(label: String, value: String) {
    if (value.isBlank() || value == "—") return
    Row(modifier = Modifier.fillMaxWidth()) {
        Text("$label :", modifier = Modifier.width(100.dp),
            fontSize = 12.sp, color = Color.Gray, fontWeight = FontWeight.Medium)
        Text(value, fontSize = 12.sp, modifier = Modifier.weight(1f),
            maxLines = 2, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun PdfSectionTitle(title: String) {
    Text(title, fontWeight = FontWeight.SemiBold, fontSize = 11.sp,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(top = 4.dp, bottom = 2.dp))
}

@Composable
private fun PdfField(label: String, value: String) {
    if (value.isBlank() || value == "-") return
    Row {
        Text("$label :", modifier = Modifier.width(90.dp),
            fontSize = 11.sp, color = Color.Gray)
        Text(value, fontSize = 11.sp, modifier = Modifier.weight(1f),
            maxLines = 2, overflow = TextOverflow.Ellipsis)
    }
}

private fun openUrl(url: String) {
    runCatching {
        val d = Desktop.getDesktop()
        if (d.isSupported(Desktop.Action.BROWSE)) d.browse(URI(url))
    }
}
