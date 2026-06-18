package fr.vetbrain.stagevetmanager.scraper

import fr.vetbrain.stagevetmanager.model.ConventionPdfData
import org.apache.pdfbox.Loader
import org.apache.pdfbox.text.PDFTextStripper

object ConventionPdfParser {

    fun parse(pdfBytes: ByteArray, sourceUrl: String = ""): ConventionPdfData {
        val text = extractText(pdfBytes)

        val ecoleSection    = section(text, "1 - L'ÉTABLISSEMENT D'ENSEIGNEMENT", "2 - L'ORGANISME D'ACCUEIL")
        val orgSection      = section(text, "2 - L'ORGANISME D'ACCUEIL", "3 - LE STAGIAIRE")
        val stagiaireSection = section(text, "3 - LE STAGIAIRE", "La présente convention")
        val recapSection    = section(text, "La présente convention et ses annexes", "Article 1")
        val encadSection    = section(text, "f- Encadrement pédagogique", "g- Informations complémentaires")

        // — Stagiaire —
        val studentLastName  = lbl(stagiaireSection, "Nom")
        val studentFirstName = lbl(stagiaireSection, "Prénom")
        val studentBirthDate = lbl(stagiaireSection, """Né\(e\) le""")
        val studentStudyYear = Regex("""Etudiant\(e\) de\s+(\S+)\s+ann""").find(stagiaireSection)
            ?.groupValues?.get(1) ?: ""
        val studentAddress = lbl(stagiaireSection, "Adresse postale")
        val studentPhone   = lbl(stagiaireSection, "Tel")
        val studentEmail   = lbl(stagiaireSection, "Courriel")

        // — Organisme —
        val hostOrganization = lbl(orgSection, "Nom")
        val hostAddress      = lbl(orgSection, "Adresse postale")
        val hostRepresentative = lbl(orgSection, "Représenté par")
        val supervisorQuality  = lbl(orgSection, "Qualité du maître de stage")
        val hostPhone = lbl(orgSection, "Tel")
        val hostEmail = lbl(orgSection, "Courriel")

        // — École —
        val schoolContact = lbl(ecoleSection, "Personne contact")

        // — Encadrement —
        val theme        = lbl(encadSection, "Thème du stage")
        val tutorName    = lbl(encadSection, """Nom et prénom de l'enseignant tuteur""")
        val tutorFunction = lbl(encadSection, "Fonction et discipline")
        // tutor phone/email: first Tel/Courriel in encadSection (before "Maître de stage")
        val tutorSubSection = section(encadSection, "Enseignant tuteur", "Maître de stage au sein")
        val tutorPhone = lbl(tutorSubSection, "Tel")
        val tutorEmail = lbl(tutorSubSection, "Courriel")
        val supervisorName = lbl(encadSection, """Nom et prénom du maître de stage""")
        // "Fonction :" in the supervisor subsection only (after "Maître de stage au sein")
        val supervisorSubSection = section(encadSection, "Maître de stage au sein", "")
        val supervisorFunction = lbl(supervisorSubSection, "Fonction")

        // — Période —
        val academicYear = lbl(recapSection, "Année universitaire").trimEnd()
        val datesMatch = Regex("""Stage se déroulant du\s+(\d{2}/\d{2}/\d{4})\s+au\s+(\d{2}/\d{2}/\d{4})""")
            .find(recapSection)
        val startDate = datesMatch?.groupValues?.get(1) ?: ""
        val endDate   = datesMatch?.groupValues?.get(2) ?: ""
        val durationLabel = Regex("""durée totale\s*\(art\.\s*3\.1\)\s*de\s+(.+?)\s*;""")
            .find(recapSection)?.groupValues?.get(1)?.trim() ?: ""

        // — Gratification —
        val gratification = when {
            recapSection.contains("sans gratification") -> "sans gratification"
            else -> lbl(recapSection, """La gratification mensuelle s'élève à""")
                .let { if (it.isNotEmpty()) it else "" }
        }

        // — Signatures (pattern: "Date : DD-MM-YYYY à HH:MM") —
        val sigDates = Regex("""Date\s*:\s*(\d{2}-\d{2}-\d{4}\s+à\s+\d{2}:\d{2})""").findAll(text).toList()
        val signingDateStudent = sigDates.getOrNull(0)?.groupValues?.get(1) ?: ""
        val signingDateHost    = sigDates.getOrNull(1)?.groupValues?.get(1) ?: ""

        return ConventionPdfData(
            rawText           = text,
            sourceUrl         = sourceUrl,
            schoolContact     = schoolContact,
            tutorName         = tutorName,
            tutorFunction     = tutorFunction,
            tutorPhone        = tutorPhone,
            tutorEmail        = tutorEmail,
            hostOrganization  = hostOrganization,
            hostAddress       = hostAddress,
            hostRepresentative = hostRepresentative,
            supervisorQuality  = supervisorQuality,
            hostPhone         = hostPhone,
            hostEmail         = hostEmail,
            supervisorName    = supervisorName,
            supervisorFunction = supervisorFunction,
            studentLastName   = studentLastName,
            studentFirstName  = studentFirstName,
            studentBirthDate  = studentBirthDate,
            studentStudyYear  = studentStudyYear,
            studentAddress    = studentAddress,
            studentPhone      = studentPhone,
            studentEmail      = studentEmail,
            academicYear      = academicYear,
            startDate         = startDate,
            endDate           = endDate,
            durationLabel     = durationLabel,
            theme             = theme,
            gratification     = gratification,
            signingDateStudent = signingDateStudent,
            signingDateHost    = signingDateHost,
        )
    }

    /** Returns substring between [from] (exclusive) and [to] (exclusive, or end of text). */
    private fun section(text: String, from: String, to: String): String {
        val start = text.indexOf(from)
        if (start < 0) return ""
        val searchFrom = start + from.length
        val end = if (to.isNotEmpty()) text.indexOf(to, searchFrom) else -1
        return if (end < 0) text.substring(searchFrom) else text.substring(searchFrom, end)
    }

    /** Extracts the value after "label : value" on one line. labelRegex is a raw regex string. */
    private fun lbl(text: String, labelRegex: String): String =
        Regex("""$labelRegex\s*:\s*(.+)""").find(text)?.groupValues?.get(1)?.trim() ?: ""

    private fun extractText(bytes: ByteArray): String =
        Loader.loadPDF(bytes).use { doc ->
            PDFTextStripper().apply { sortByPosition = true }.getText(doc)
        }
}
