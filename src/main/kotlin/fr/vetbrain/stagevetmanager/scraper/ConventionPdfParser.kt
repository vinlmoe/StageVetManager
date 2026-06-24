package fr.vetbrain.stagevetmanager.scraper

import fr.vetbrain.stagevetmanager.model.ConventionPdfData
import org.apache.pdfbox.Loader
import org.apache.pdfbox.text.PDFTextStripper
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.format.DateTimeFormatter

object ConventionPdfParser {

    fun parse(pdfBytes: ByteArray, sourceUrl: String = ""): ConventionPdfData {
        return Loader.loadPDF(pdfBytes).use { doc ->
            val rawText = PDFTextStripper().apply { sortByPosition = true }.getText(doc)
            // Normalise les apostrophes typographiques (U+2019, U+02BC…) en apostrophe ASCII
            // afin que indexOf("L'ORGANISME") fonctionne quel que soit le générateur PDF.
            val text = normalizeQuotes(rawText)

            val ecoleSection     = section(text, "1 - L'ÉTABLISSEMENT D'ENSEIGNEMENT", "2 - L'ORGANISME D'ACCUEIL")
            val orgSection       = section(text, "2 - L'ORGANISME D'ACCUEIL", "3 - LE STAGIAIRE")
            val stagiaireSection = section(text, "3 - LE STAGIAIRE", "La présente convention")
            val recapSection     = section(text, "La présente convention et ses annexes", "Article 1")
            val encadSection     = section(text, "f- Encadrement pédagogique", "g- Informations complémentaires")
            val modaliteSection  = section(recapSection, "c-", "d-")
            // Sous-section maître de stage (utilisée tôt comme fallback pour le Tel/Courriel de l'organisme)
            val supervisorSub    = section(encadSection, "Maître de stage au sein", "")

            // — Stagiaire —
            val studentLastName  = lbl(stagiaireSection, "Nom")
            val studentFirstName = lbl(stagiaireSection, "Prénom")
            val studentBirthDate = lbl(stagiaireSection, """Né\(e\) le""")
            val studentStudyYear = Regex("""Etudiant\(e\) de\s+(\S+)\s+ann""").find(stagiaireSection)
                ?.groupValues?.get(1) ?: ""
            val studentAddress = lbl(stagiaireSection, "Adresse postale")
            val studentPhone   = lbl(stagiaireSection, """T[eé]l(?:[eé]phone)?""")
            val studentEmail   = lbl(stagiaireSection, "Courriel|E-?mail")

            // — Organisme —
            val hostOrganization   = lbl(orgSection, "Nom")
            val hostAddress        = lbl(orgSection, "Adresse postale")
            val hostRepresentative = lbl(orgSection, "Représenté par")
            val supervisorQuality  = lbl(orgSection, "Qualité du maître de stage")
            // "Tel" peut être écrit "Tél" ou "Téléphone" selon la version du document ;
            // si absent de la section 2, on le cherche dans la sous-section maître de stage.
            val hostPhone = lbl(orgSection, """T[eé]l(?:[eé]phone)?""")
                .ifBlank { lbl(supervisorSub, """T[eé]l(?:[eé]phone)?""") }
            // findEmail() essaie d'abord le label "Courriel / E-mail", puis un scan regex @.
            // Le dernier recours (scan texte complet) est calculé après tutorEmail.
            val hostEmailPrelim = findEmail(orgSection).ifBlank { findEmail(supervisorSub) }

            // — École —
            val schoolContact = lbl(ecoleSection, "Personne contact")
            val schoolEmail   = lbl(ecoleSection, "Courriel|E-?mail")

            // — Encadrement —
            val theme         = lbl(encadSection, "Thème du stage")
            val tutorName     = lbl(encadSection, """Nom et prénom de l'enseignant tuteur""")
            val tutorFunction = lbl(encadSection, "Fonction et discipline")
            val tutorSub      = section(encadSection, "Enseignant tuteur", "Maître de stage au sein")
            val tutorPhone    = lbl(tutorSub, """T[eé]l(?:[eé]phone)?""")
            val tutorEmail    = lbl(tutorSub, "Courriel|E-?mail")
            val supervisorName     = lbl(encadSection, """Nom et prénom du maître de stage""")
            val supervisorFunction = lbl(supervisorSub, "Fonction")

            // Dernier recours : scan complet — exclut les emails de l'école, du tuteur et de l'étudiant
            val hostEmail = hostEmailPrelim.ifBlank {
                val known = setOf(studentEmail, tutorEmail, schoolEmail).filter { '@' in it }.toSet()
                EMAIL_REGEX.findAll(text).map { it.value }.firstOrNull { it !in known } ?: ""
            }

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

            // — Dates de présence effectives (entre "dates précises" et "c-") —
            val datesPrecisesSection = section(recapSection, "Les dates précises de présence", "c-")
            val workingDates: List<LocalDate> = Regex("""\d{2}/\d{2}/\d{4}""")
                .findAll(datesPrecisesSection)
                .mapNotNull { m -> runCatching {
                    LocalDate.parse(m.value, DateTimeFormatter.ofPattern("dd/MM/yyyy"))
                }.getOrNull() }
                .toList()

            // — Jour de repos hebdomadaire —
            val hasWeeklyRestDay = computeHasWeeklyRestDay(workingDates)

            // — Modalités particulières (art. 3.2) —
            // Priority 1 (most reliable): validate against the explicit working-date list
            // Priority 2: AcroForm checkbox fields (read while doc is still open)
            // Priority 3: text-based heuristic (non-whitespace prefix before keyword)
            val checkedFields = buildSet<String> {
                doc.documentCatalog.acroForm?.fields?.forEach { field ->
                    val value = runCatching { field.valueAsString }.getOrElse { "Off" }
                    if (value != "Off" && value.isNotBlank()) add(field.fullyQualifiedName.lowercase())
                }
            }
            val useAcroForm = checkedFields.isNotEmpty() ||
                doc.documentCatalog.acroForm?.fields?.isNotEmpty() == true

            fun modalite(acroKeywords: List<String>, textKeyword: String): Boolean =
                if (useAcroForm) checkedFields.any { f -> acroKeywords.any { k -> k in f } }
                else isItemChecked(modaliteSection, textKeyword)

            // Si "aucune modalité particulière" est cochée, toutes les autres sont fausses.
            val aucuneModalite = if (useAcroForm)
                checkedFields.any { "aucun" in it || "none" in it || "no_special" in it }
            else isItemChecked(modaliteSection, "aucune modalité")

            val nightPresence = !aucuneModalite && modalite(listOf("nuit", "night"), "nuit")
            val homePresence  = !aucuneModalite && modalite(listOf("domicile", "home"), "domicile")

            // Sunday / holiday: use actual dates when available, otherwise checkbox fallback
            val sundayPresence = if (workingDates.isNotEmpty())
                workingDates.any { it.dayOfWeek == DayOfWeek.SUNDAY }
            else !aucuneModalite && modalite(listOf("dimanche", "dim", "sunday"), "dimanche")

            val holidayPresence = if (workingDates.isNotEmpty())
                workingDates.any { isFrenchPublicHoliday(it) }
            else !aucuneModalite && modalite(listOf("feri", "holiday", "fér"), "jours f")

            // — Signatures (col. gauche = tuteur, milieu = stagiaire, droite = maître) —
            // sortByPosition extrait le texte gauche→droite dans la même bande horizontale,
            // donc le tuteur (col. 1) donne la 1re "Date :", le stagiaire (col. 2) la 2e,
            // le maître de stage (col. 3) la 3e — si elle existe.
            val sigDates = Regex("""Date\s*:\s*(\d{2}-\d{2}-\d{4}\s+à\s+\d{2}:\d{2})""").findAll(text).toList()
            val signingDateTutor   = sigDates.getOrNull(0)?.groupValues?.get(1) ?: ""
            val signingDateStudent = sigDates.getOrNull(1)?.groupValues?.get(1) ?: ""
            val signingDateHost    = sigDates.getOrNull(2)?.groupValues?.get(1) ?: ""
            val signingDateSchool  = sigDates.getOrNull(3)?.groupValues?.get(1) ?: ""

            ConventionPdfData(
                rawText            = rawText,
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
                hasWeeklyRestDay   = hasWeeklyRestDay,
                theme              = theme,
                gratification      = gratification,
                signingDateTutor   = signingDateTutor,
                signingDateStudent = signingDateStudent,
                signingDateHost    = signingDateHost,
                signingDateSchool  = signingDateSchool,
            )
        }
    }

    /**
     * Returns true if the sorted list of working dates never contains 7 consecutive calendar days,
     * false if it does (= violation), null if the list is empty (= unable to determine).
     */
    internal fun computeHasWeeklyRestDay(workingDates: List<LocalDate>): Boolean? {
        if (workingDates.isEmpty()) return null
        val sorted = workingDates.distinct().sorted()
        var maxConsec = 1
        var currConsec = 1
        for (i in 1 until sorted.size) {
            currConsec = if (sorted[i] == sorted[i - 1].plusDays(1)) currConsec + 1 else 1
            if (currConsec > maxConsec) maxConsec = currConsec
        }
        return maxConsec < 7
    }

    /** Normalise les variantes d'apostrophes/guillemets pour que indexOf() fonctionne. */
    private fun normalizeQuotes(text: String) = text
        .replace('’', '\'')  // RIGHT SINGLE QUOTATION MARK  (apostrophe typographique)
        .replace('‘', '\'')  // LEFT SINGLE QUOTATION MARK
        .replace('ʼ', '\'')  // MODIFIER LETTER APOSTROPHE
        .replace('′', '\'')  // PRIME
        .replace('`', '\'')  // GRAVE ACCENT
        .replace('“', '"')   // LEFT DOUBLE QUOTATION MARK
        .replace('”', '"')   // RIGHT DOUBLE QUOTATION MARK

    private val EMAIL_REGEX = Regex("""[a-zA-Z0-9._%+\-]+@[a-zA-Z0-9.\-]+\.[a-zA-Z]{2,}""")

    /**
     * Cherche un email dans [text] :
     * 1) via le label "Courriel / E-mail" → valide si contient '@'
     * 2) via regex directe sur '@' (cas où le label est absent ou mal formaté)
     */
    private fun findEmail(text: String): String {
        val byLabel = lbl(text, "Courriel|E-?mail")
        if (byLabel.contains('@')) return byLabel
        return EMAIL_REGEX.find(text)?.value ?: ""
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
     * Retourne true si la ligne contenant [keyword] est précédée d'un symbole coché.
     *
     * Logique :
     *  1. Si un symbole "cochée" explicite est trouvé avant le mot-clé → true
     *  2. Si un symbole "non-cochée" explicite (case vide, croix ✗) → false
     *  3. Sinon tout caractère non-blanc → true (heuristique de repli)
     *
     * Ce format correspond aux conventions StageVet qui utilisent ✓ (cochée) et ✗ (non-cochée).
     */
    private fun isItemChecked(section: String, keyword: String): Boolean {
        val idx = section.indexOf(keyword)
        if (idx < 0) return false
        val lineStart = section.lastIndexOf('\n', idx).let { if (it < 0) 0 else it + 1 }
        val prefix = section.substring(lineStart, idx)
        val checkedChars    = setOf('✓', '✔', '☑', '●', '◉')
        val notCheckedChars = setOf(
            '□', '☐', '◻', '❑',          // cases vides
            '✗', '✘', '✕', '✖', '×', '☓', // croix / X (non-coché dans le format StageVet)
        )
        return when {
            prefix.any { it in checkedChars }    -> true
            prefix.any { it in notCheckedChars } -> false
            else -> prefix.any { !it.isWhitespace() }
        }
    }

    /** Returns true if [date] is a French public holiday (métropole). */
    private fun isFrenchPublicHoliday(date: LocalDate): Boolean {
        val y = date.year
        val fixed = setOf(
            LocalDate.of(y,  1,  1),  // Jour de l'An
            LocalDate.of(y,  5,  1),  // Fête du Travail
            LocalDate.of(y,  5,  8),  // Victoire 1945
            LocalDate.of(y,  7, 14),  // Fête Nationale
            LocalDate.of(y,  8, 15),  // Assomption
            LocalDate.of(y, 11,  1),  // Toussaint
            LocalDate.of(y, 11, 11),  // Armistice
            LocalDate.of(y, 12, 25),  // Noël
        )
        if (date in fixed) return true
        val easter = computeEaster(y)
        return date == easter.plusDays(1)   // Lundi de Pâques
            || date == easter.plusDays(39)  // Ascension
            || date == easter.plusDays(50)  // Lundi de Pentecôte
    }

    /** Gregorian (Anonymous) Easter algorithm. */
    private fun computeEaster(year: Int): LocalDate {
        val a = year % 19
        val b = year / 100;  val c = year % 100
        val d = b / 4;       val e = b % 4
        val f = (b + 8) / 25
        val g = (b - f + 1) / 3
        val h = (19 * a + b - d - g + 15) % 30
        val i = c / 4;       val k = c % 4
        val l = (32 + 2 * e + 2 * i - h - k) % 7
        val m = (a + 11 * h + 22 * l) / 451
        val month = (h + l - 7 * m + 114) / 31
        val day   = (h + l - 7 * m + 114) % 31 + 1
        return LocalDate.of(year, month, day)
    }
}
