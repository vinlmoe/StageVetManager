package fr.vetbrain.stagevetmanager.scraper

import fr.vetbrain.stagevetmanager.model.ConventionPdfData
import org.apache.pdfbox.Loader
import org.apache.pdfbox.text.PDFTextStripper

object ConventionPdfParser {

    fun parse(pdfBytes: ByteArray, sourceUrl: String = ""): ConventionPdfData {
        return Loader.loadPDF(pdfBytes).use { doc ->
            val text = PDFTextStripper().apply { sortByPosition = true }.getText(doc)

            val ecoleSection     = section(text, "1 - L'ÉTABLISSEMENT D'ENSEIGNEMENT", "2 - L'ORGANISME D'ACCUEIL")
            val orgSection       = section(text, "2 - L'ORGANISME D'ACCUEIL", "3 - LE STAGIAIRE")
            val stagiaireSection = section(text, "3 - LE STAGIAIRE", "La présente convention")
            val recapSection     = section(text, "La présente convention et ses annexes", "Article 1")
            val encadSection     = section(text, "f- Encadrement pédagogique", "g- Informations complémentaires")
            val modaliteSection  = section(recapSection, "c-", "d-")

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
            val hostOrganization   = lbl(orgSection, "Nom")
            val hostAddress        = lbl(orgSection, "Adresse postale")
            val hostRepresentative = lbl(orgSection, "Représenté par")
            val supervisorQuality  = lbl(orgSection, "Qualité du maître de stage")
            val hostPhone          = lbl(orgSection, "Tel")
            val hostEmail          = lbl(orgSection, "Courriel")

            // — École —
            val schoolContact = lbl(ecoleSection, "Personne contact")

            // — Encadrement —
            val theme         = lbl(encadSection, "Thème du stage")
            val tutorName     = lbl(encadSection, """Nom et prénom de l'enseignant tuteur""")
            val tutorFunction = lbl(encadSection, "Fonction et discipline")
            val tutorSub      = section(encadSection, "Enseignant tuteur", "Maître de stage au sein")
            val tutorPhone    = lbl(tutorSub, "Tel")
            val tutorEmail    = lbl(tutorSub, "Courriel")
            val supervisorName = lbl(encadSection, """Nom et prénom du maître de stage""")
            val supervisorSub  = section(encadSection, "Maître de stage au sein", "")
            val supervisorFunction = lbl(supervisorSub, "Fonction")

            // — Période —
            val academicYear = lbl(recapSection, "Année universitaire").trimEnd()
            val datesMatch = Regex("""Stage se déroulant du\s+(\d{2}/\d{2}/\d{4})\s+au\s+(\d{2}/\d{2}/\d{4})""")
                .find(recapSection)
            val startDate     = datesMatch?.groupValues?.get(1) ?: ""
            val endDate       = datesMatch?.groupValues?.get(2) ?: ""
            val durationLabel = Regex("""durée totale\s*\(art\.\s*3\.1\)\s*de\s+(.+?)\s*;""")
                .find(recapSection)?.groupValues?.get(1)?.trim() ?: ""

            // — Gratification —
            val gratification = when {
                recapSection.contains("sans gratification") -> "sans gratification"
                else -> lbl(recapSection, """La gratification mensuelle s'élève à""")
            }

            // — Modalités particulières (art. 3.2) —
            // Primary: read AcroForm checkbox fields while doc is open
            // Fallback: look for non-whitespace prefix before each item in the text section
            val checkedFields = buildSet<String> {
                doc.documentCatalog.acroForm?.fields?.forEach { field ->
                    val value = runCatching { field.valueAsString }.getOrElse { "Off" }
                    if (value != "Off" && value.isNotBlank()) add(field.fullyQualifiedName.lowercase())
                }
            }
            val useAcroForm = checkedFields.isNotEmpty() || doc.documentCatalog.acroForm?.fields?.isNotEmpty() == true

            fun modalite(acroKeywords: List<String>, textKeyword: String): Boolean =
                if (useAcroForm) checkedFields.any { f -> acroKeywords.any { k -> k in f } }
                else isItemChecked(modaliteSection, textKeyword)

            val nightPresence   = modalite(listOf("nuit", "night"), "nuit")
            val sundayPresence  = modalite(listOf("dimanche", "dim", "sunday"), "dimanche")
            val holidayPresence = modalite(listOf("feri", "holiday", "fér"), "jours f")
            val homePresence    = modalite(listOf("domicile", "home"), "domicile")

            // — Signatures —
            val sigDates = Regex("""Date\s*:\s*(\d{2}-\d{2}-\d{4}\s+à\s+\d{2}:\d{2})""").findAll(text).toList()
            val signingDateStudent = sigDates.getOrNull(0)?.groupValues?.get(1) ?: ""
            val signingDateHost    = sigDates.getOrNull(1)?.groupValues?.get(1) ?: ""

            ConventionPdfData(
                rawText            = text,
                sourceUrl          = sourceUrl,
                schoolContact      = schoolContact,
                tutorName          = tutorName,
                tutorFunction      = tutorFunction,
                tutorPhone         = tutorPhone,
                tutorEmail         = tutorEmail,
                hostOrganization   = hostOrganization,
                hostAddress        = hostAddress,
                hostRepresentative = hostRepresentative,
                supervisorQuality  = supervisorQuality,
                hostPhone          = hostPhone,
                hostEmail          = hostEmail,
                supervisorName     = supervisorName,
                supervisorFunction = supervisorFunction,
                studentLastName    = studentLastName,
                studentFirstName   = studentFirstName,
                studentBirthDate   = studentBirthDate,
                studentStudyYear   = studentStudyYear,
                studentAddress     = studentAddress,
                studentPhone       = studentPhone,
                studentEmail       = studentEmail,
                academicYear       = academicYear,
                startDate          = startDate,
                endDate            = endDate,
                durationLabel      = durationLabel,
                nightPresence      = nightPresence,
                sundayPresence     = sundayPresence,
                holidayPresence    = holidayPresence,
                homePresence       = homePresence,
                theme              = theme,
                gratification      = gratification,
                signingDateStudent = signingDateStudent,
                signingDateHost    = signingDateHost,
            )
        }
    }

    /** Returns substring between [from] (exclusive) and [to] (exclusive, or end if [to] is empty). */
    private fun section(text: String, from: String, to: String): String {
        val start = text.indexOf(from)
        if (start < 0) return ""
        val searchFrom = start + from.length
        val end = if (to.isNotEmpty()) text.indexOf(to, searchFrom) else -1
        return if (end < 0) text.substring(searchFrom) else text.substring(searchFrom, end)
    }

    /** Extracts value after "label : value" on one line; [labelRegex] is a raw regex string. */
    private fun lbl(text: String, labelRegex: String): String =
        Regex("""$labelRegex\s*:\s*(.+)""").find(text)?.groupValues?.get(1)?.trim() ?: ""

    /**
     * Returns true if the line containing [keyword] starts with a non-whitespace,
     * non-empty-box character (heuristic for checked PDF checkboxes in text extraction).
     */
    private fun isItemChecked(section: String, keyword: String): Boolean {
        val idx = section.indexOf(keyword)
        if (idx < 0) return false
        val lineStart = section.lastIndexOf('\n', idx).let { if (it < 0) 0 else it + 1 }
        val prefix = section.substring(lineStart, idx)
        val emptyBoxChars = setOf('□', '☐', '◻', '❑')
        return prefix.any { !it.isWhitespace() && it !in emptyBoxChars }
    }
}
