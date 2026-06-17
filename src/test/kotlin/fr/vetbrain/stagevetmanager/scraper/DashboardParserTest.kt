package fr.vetbrain.stagevetmanager.scraper

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.time.LocalDate

class DashboardParserTest {

    private val fixtureHtml: String =
        DashboardParserTest::class.java.getResourceAsStream("/fixtures/sample_card.html")!!
            .bufferedReader().readText()

    @Test
    fun `parse empty HTML returns empty list`() {
        assertTrue(DashboardParser.parse("").isEmpty())
    }

    @Test
    fun `parse valid card maps all fields correctly`() {
        val results = DashboardParser.parse(fixtureHtml)
        assertEquals(1, results.size)
        with(results[0]) {
            assertEquals("Dupont Marie", studentName)
            assertEquals("3ème année", studyYear)
            assertEquals("Clinique Vétérinaire du Lac", organization)
            assertEquals("Lyon, 69000", address)
            assertEquals("Conv. n° 2025-001", conventionNumber)
            assertEquals("Générée le 15/01/2025", conventionGenDate)
            assertEquals(LocalDate.of(2025, 6, 1), signingDate)
            assertEquals(LocalDate.of(2025, 6, 1), startDate)
            assertEquals(LocalDate.of(2025, 6, 30), endDate)
            assertEquals("01/06/2025 au 30/06/2025", rawDateStage)
            assertEquals("Chirurgie générale", theme)
        }
    }

    @Test
    fun `parse card without student anchor returns empty list`() {
        val html = fixtureHtml.replace(
            """<h4 class="h4"><a href="#">Dupont Marie</a></h4>""",
            """<h4 class="h4">Dupont Marie</h4>"""
        )
        assertTrue(DashboardParser.parse(html).isEmpty())
    }

    @Test
    fun `signingDate parsed from dd-MM-yyyy in data-original-title`() {
        val results = DashboardParser.parse(fixtureHtml)
        assertEquals(LocalDate.of(2025, 6, 1), results[0].signingDate)
    }

    @Test
    fun `signingDate is null when data-original-title contains no date pattern`() {
        val html = fixtureHtml.replace("Signé le 01-06-2025", "Pas encore signé")
        val results = DashboardParser.parse(html)
        assertNull(results[0].signingDate)
    }

    @Test
    fun `parseDateRange extracts start and end from dd slash MM slash yyyy`() {
        val results = DashboardParser.parse(fixtureHtml)
        assertEquals(LocalDate.of(2025, 6, 1), results[0].startDate)
        assertEquals(LocalDate.of(2025, 6, 30), results[0].endDate)
    }

    @Test
    fun `parseDateRange extracts start and end from dd dash MM dash yyyy`() {
        val html = fixtureHtml.replace("01/06/2025 au 30/06/2025", "01-06-2025 au 30-06-2025")
        val results = DashboardParser.parse(html)
        assertEquals(LocalDate.of(2025, 6, 1), results[0].startDate)
        assertEquals(LocalDate.of(2025, 6, 30), results[0].endDate)
    }

    @Test
    fun `parseDateRange returns null null when rawDateStage has no dates`() {
        val html = fixtureHtml.replace("01/06/2025 au 30/06/2025", "Dates non renseignées")
        val results = DashboardParser.parse(html)
        assertNull(results[0].startDate)
        assertNull(results[0].endDate)
    }

    @Test
    fun `study year prefix is stripped`() {
        val results = DashboardParser.parse(fixtureHtml)
        assertFalse(results[0].studyYear.contains("Année d'étude"))
    }

    @Test
    fun `theme prefix is stripped`() {
        val results = DashboardParser.parse(fixtureHtml)
        assertFalse(results[0].theme.contains("Thème du stage"))
    }
}
