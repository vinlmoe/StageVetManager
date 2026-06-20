package fr.vetbrain.stagevetmanager.scraper

import fr.vetbrain.stagevetmanager.model.Internship
import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException

object DashboardParser {

    private val FMT_SLASH = DateTimeFormatter.ofPattern("dd/MM/yyyy")
    private val FMT_DASH  = DateTimeFormatter.ofPattern("dd-MM-yyyy")

    fun parse(html: String): List<Internship> {
        val doc = Jsoup.parse(html)
        return doc.select("div.card").mapNotNull { parseCard(it) }
    }

    private fun parseCard(card: Element): Internship? {
        val studentName = card.selectFirst(".h4 a")?.text()?.trim() ?: return null

        val studyYear = card.select("ul:nth-of-type(2) li").firstOrNull()
            ?.text()?.trim()
            ?.removePrefix("Année d'étude")
            ?.removePrefix(":")
            ?.trim() ?: ""

        val organization = card.selectFirst("ul:nth-of-type(5) a")?.text()?.trim() ?: ""

        val address = card.selectFirst(".list-inline-item small")?.text()?.trim() ?: ""

        val convention = card.selectFirst(".d-block small:nth-child(1)")?.text()?.trim() ?: ""

        val signingDateStr = card.selectFirst("i.font-size-3.fas.fa-school.text-success")
            ?.attr("data-original-title")
            ?.let { Regex("\\d{2}-\\d{2}-\\d{4}").find(it)?.value }

        val rawDateStage = card.selectFirst("span.font-size-0")?.text()?.trim() ?: ""

        val conventionGenDate = card.selectFirst(".d-block small:nth-child(2)")?.text()?.trim() ?: ""

        val theme = card.select("ul:nth-of-type(3) li").firstOrNull()
            ?.text()?.trim()
            ?.removePrefix("Thème du stage")
            ?.removePrefix(":")
            ?.trim() ?: ""

        val conventionPdfUrl = card.selectFirst("a.btn-success[href*='/convention/pdf/']:not([href*='/signature/'])")
            ?.attr("href") ?: ""
        val conventionSignUrl = card.selectFirst("a.btn-success[href*='/signature/']")
            ?.attr("href") ?: ""
        // Bouton d'annulation : généralement btn-danger ou lien contenant "annul"/"supprimer"
        val conventionCancelUrl = card.selectFirst(
            "a.btn-danger, a[href*='annul'], a[href*='supprimer']"
        )?.attr("href") ?: ""

        val signingDate = signingDateStr?.let { parseDate(it) }
        val (startDate, endDate) = parseDateRange(rawDateStage)

        return Internship(
            studentName = studentName,
            studyYear = studyYear,
            organization = organization,
            address = address,
            conventionNumber = convention,
            conventionGenDate = conventionGenDate,
            signingDate = signingDate,
            startDate = startDate,
            endDate = endDate,
            rawDateStage = rawDateStage,
            theme = theme,
            conventionPdfUrl = conventionPdfUrl,
            conventionSignUrl = conventionSignUrl,
            conventionCancelUrl = conventionCancelUrl,
        )
    }

    private fun parseDate(s: String): LocalDate? {
        val cleaned = s.trim()
        for (fmt in listOf(FMT_SLASH, FMT_DASH)) {
            try { return LocalDate.parse(cleaned, fmt) } catch (_: DateTimeParseException) {}
        }
        return null
    }

    // Extracts start and end dates from strings like "01/06/2025 au 30/06/2025"
    // or "01-06-2025 au 30-06-2025"
    private fun parseDateRange(raw: String): Pair<LocalDate?, LocalDate?> {
        val dates = Regex("\\d{2}[/-]\\d{2}[/-]\\d{4}").findAll(raw).map { it.value }.toList()
        val start = dates.getOrNull(0)?.let { parseDate(it) }
        val end   = dates.getOrNull(1)?.let { parseDate(it) }
        return start to end
    }
}
