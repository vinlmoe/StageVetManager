package fr.vetbrain.stagevetmanager.export

import fr.vetbrain.stagevetmanager.model.ConventionPdfData
import fr.vetbrain.stagevetmanager.model.Internship
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.time.format.DateTimeFormatter

object VetAgroTiceCsvExporter {

    private val DATE_FMT = DateTimeFormatter.ofPattern("dd/MM/yyyy")

    val HEADERS = listOf(
        "Étudiant", "Année d'étude", "Organisme", "Adresse organisme",
        "N° convention", "Convention générée le", "Date signature finale",
        "Début stage", "Fin stage", "Dates brutes", "Thème", "Durée",
        "URL convention PDF", "URL signature", "Chemin PDF local",
        "Contact école", "Nom tuteur", "Fonction tuteur", "Téléphone tuteur", "Email tuteur",
        "Organisme (convention)", "Adresse organisme (convention)", "Représentant organisme",
        "Qualité maître de stage", "Téléphone organisme", "Email organisme",
        "Nom maître de stage", "Fonction maître de stage",
        "Nom étudiant", "Prénom étudiant", "Date de naissance étudiant",
        "Année étudiant (convention)", "Adresse étudiant", "Téléphone étudiant", "Email étudiant",
        "Année universitaire", "Début (convention)", "Fin (convention)", "Durée (convention)",
        "Jours déclarés", "Jours effectifs", "Cohérence jours",
        "Présence de nuit", "Présence dimanche", "Présence jour férié", "Présence à domicile",
        "Repos hebdomadaire", "Thème (convention)", "Statut gratification",
        "Montant gratification", "Cohérence gratification", "Gratification",
        "Signature tuteur", "Signature étudiant", "Signature maître de stage", "Signature école",
        "URL source des données", "Texte brut de la convention",
    )

    /** Exporte uniquement les stages dont la signature finale est renseignée. */
    fun export(
        internships: List<Internship>,
        path: Path,
        pdfDataCache: Map<String, ConventionPdfData> = emptyMap(),
    ): Int {
        val signed = internships.filter { it.signingDate != null }
        Files.newBufferedWriter(path, StandardCharsets.UTF_8).use { writer ->
            writer.write('\uFEFF'.code)
            writer.appendLine(HEADERS.joinToString(";") { csv(it) })
            signed.forEach { stage ->
                writer.appendLine(values(stage, pdfDataCache[stage.conventionPdfUrl]).joinToString(";") { csv(it) })
            }
        }
        return signed.size
    }

    private fun values(stage: Internship, pdf: ConventionPdfData?): List<String> = listOf(
        stage.studentName, stage.studyYear, stage.organization, stage.address,
        stage.conventionNumber, stage.conventionGenDate, stage.signingDate?.format(DATE_FMT).orEmpty(),
        stage.startDate?.format(DATE_FMT).orEmpty(), stage.endDate?.format(DATE_FMT).orEmpty(),
        stage.rawDateStage, stage.theme, stage.durationLabel,
        stage.conventionPdfUrl, stage.conventionSignUrl, stage.localPdfPath,
        pdf?.schoolContact.orEmpty(), pdf?.tutorName.orEmpty(), pdf?.tutorFunction.orEmpty(),
        pdf?.tutorPhone.orEmpty(), pdf?.tutorEmail.orEmpty(),
        pdf?.hostOrganization.orEmpty(), pdf?.hostAddress.orEmpty(), pdf?.hostRepresentative.orEmpty(),
        pdf?.supervisorQuality.orEmpty(), pdf?.hostPhone.orEmpty(), pdf?.hostEmail.orEmpty(),
        pdf?.supervisorName.orEmpty(), pdf?.supervisorFunction.orEmpty(),
        pdf?.studentLastName.orEmpty(), pdf?.studentFirstName.orEmpty(), pdf?.studentBirthDate.orEmpty(),
        pdf?.studentStudyYear.orEmpty(), pdf?.studentAddress.orEmpty(), pdf?.studentPhone.orEmpty(),
        pdf?.studentEmail.orEmpty(), pdf?.academicYear.orEmpty(), pdf?.startDate.orEmpty(),
        pdf?.endDate.orEmpty(), pdf?.durationLabel.orEmpty(), pdf?.declaredDaysCount?.toString().orEmpty(),
        pdf?.effectiveDaysCount?.toString().orEmpty(), bool(pdf?.daysCountCoherent),
        bool(pdf?.nightPresence), bool(pdf?.sundayPresence), bool(pdf?.holidayPresence),
        bool(pdf?.homePresence), bool(pdf?.hasWeeklyRestDay), pdf?.theme.orEmpty(),
        pdf?.gratificationStatus.orEmpty(), pdf?.gratificationAmount.orEmpty(),
        bool(pdf?.gratificationCoherent), pdf?.gratification.orEmpty(),
        pdf?.signingDateTutor.orEmpty(), pdf?.signingDateStudent.orEmpty(),
        pdf?.signingDateHost.orEmpty(), pdf?.signingDateSchool.orEmpty(),
        pdf?.sourceUrl.orEmpty(), pdf?.rawText.orEmpty(),
    )

    private fun bool(value: Boolean?): String = when (value) {
        true -> "Oui"
        false -> "Non"
        null -> ""
    }

    private fun csv(value: String): String = "\"${value.replace("\"", "\"\"")}\""
}
