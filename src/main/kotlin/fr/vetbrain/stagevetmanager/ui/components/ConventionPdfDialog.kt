package fr.vetbrain.stagevetmanager.ui.components

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import fr.vetbrain.stagevetmanager.model.ConventionPdfData
import java.awt.Desktop
import java.net.URI

@Composable
fun ConventionPdfDialog(
    data: ConventionPdfData,
    isLoading: Boolean,
    onDismiss: () -> Unit,
) {
    var showRawText by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                "Données extraites de la convention",
                fontWeight = FontWeight.Bold,
            )
        },
        text = {
            if (isLoading) {
                Box(Modifier.fillMaxWidth().height(80.dp), contentAlignment = androidx.compose.ui.Alignment.Center) {
                    CircularProgressIndicator()
                }
            } else {
                Column(
                    modifier = Modifier.fillMaxWidth().verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    // — Champs parsés ————————————————————————————————————
                    FieldSection("Stagiaire") {
                        val fullName = listOf(data.studentLastName, data.studentFirstName)
                            .filter { it.isNotBlank() }.joinToString(" ")
                        Field("Nom",              fullName)
                        Field("Naissance",        data.studentBirthDate)
                        Field("Adresse",          data.studentAddress)
                        Field("Tél",              data.studentPhone)
                        Field("Email",            data.studentEmail)
                    }
                    FieldSection("Tuteur école") {
                        Field("Nom",              data.tutorName)
                        Field("Fonction",         data.tutorFunction)
                        Field("Contact",          data.schoolContact)
                    }
                    FieldSection("Organisme d'accueil") {
                        Field("Raison sociale",   data.hostOrganization)
                        Field("Adresse",          data.hostAddress)
                        Field("Représentant",     data.hostRepresentative)
                        Field("Maître de stage",  data.supervisorName)
                        Field("Qualité",          data.supervisorQuality)
                    }
                    FieldSection("Période") {
                        Field("Année univ.",      data.academicYear)
                        Field("Début",            data.startDate)
                        Field("Fin",              data.endDate)
                        Field("Durée",            data.durationLabel)
                    }
                    if (data.signingDateTutor.isNotBlank() || data.signingDateStudent.isNotBlank() || data.signingDateHost.isNotBlank()) {
                        FieldSection("Signatures") {
                            Field("Enseignant tuteur", data.signingDateTutor)
                            Field("Stagiaire",         data.signingDateStudent)
                            Field("Maître de stage",   data.signingDateHost)
                        }
                    }
                    FieldSection("Conditions") {
                        Field("Thème",            data.theme)
                        Field("Gratification",    data.gratification)
                        val modalites = listOfNotNull(
                            "nuit".takeIf { data.nightPresence },
                            "dimanche".takeIf { data.sundayPresence },
                            "jours fériés".takeIf { data.holidayPresence },
                            "domicile".takeIf { data.homePresence },
                        )
                        if (modalites.isNotEmpty()) Field("Modalités", modalites.joinToString(", "))
                    }

                    HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))

                    // — Texte brut (pour affiner les regex) ——————————————
                    TextButton(
                        onClick = { showRawText = !showRawText },
                        contentPadding = PaddingValues(0.dp),
                    ) {
                        Text(
                            if (showRawText) "▲ Masquer le texte brut" else "▼ Afficher le texte brut",
                            fontSize = 12.sp,
                        )
                    }
                    if (showRawText) {
                        Surface(
                            color = MaterialTheme.colorScheme.surfaceVariant,
                            shape = MaterialTheme.shapes.small,
                        ) {
                            Text(
                                data.rawText,
                                modifier = Modifier.padding(8.dp).fillMaxWidth(),
                                fontFamily = FontFamily.Monospace,
                                fontSize = 10.sp,
                                lineHeight = 14.sp,
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (data.sourceUrl.isNotEmpty()) {
                    TextButton(onClick = { openInBrowser(data.sourceUrl) }) {
                        Text("Ouvrir dans le navigateur")
                    }
                }
                TextButton(onClick = onDismiss) { Text("Fermer") }
            }
        },
    )
}

@Composable
private fun FieldSection(title: String, content: @Composable ColumnScope.() -> Unit) {
    val hasContent = true // sections toujours rendues, les champs vides sont masqués
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(title, fontWeight = FontWeight.SemiBold, fontSize = 12.sp,
            color = MaterialTheme.colorScheme.primary)
        content()
    }
}

@Composable
private fun Field(label: String, value: String) {
    if (value.isBlank()) return
    Row(modifier = Modifier.fillMaxWidth()) {
        Text(
            "$label :",
            modifier = Modifier.width(130.dp),
            fontSize = 12.sp,
            color = Color.Gray,
            fontWeight = FontWeight.Medium,
        )
        Text(value, fontSize = 12.sp, modifier = Modifier.weight(1f))
    }
}

private fun openInBrowser(url: String) {
    runCatching {
        val desktop = Desktop.getDesktop()
        if (desktop.isSupported(Desktop.Action.BROWSE)) desktop.browse(URI(url))
    }
}
