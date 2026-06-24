package fr.vetbrain.stagevetmanager.scraper

import org.apache.pdfbox.Loader
import org.apache.pdfbox.text.PDFTextStripper
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.time.DayOfWeek
import java.time.LocalDate

class ConventionPdfParserTest {

    // Access private helpers via reflection for unit testing
    private fun isFrenchPublicHoliday(date: LocalDate): Boolean {
        val m = ConventionPdfParser::class.java.getDeclaredMethod("isFrenchPublicHoliday", LocalDate::class.java)
        m.isAccessible = true
        return m.invoke(ConventionPdfParser, date) as Boolean
    }

    private fun computeEaster(year: Int): LocalDate {
        val m = ConventionPdfParser::class.java.getDeclaredMethod("computeEaster", Int::class.java)
        m.isAccessible = true
        return m.invoke(ConventionPdfParser, year) as LocalDate
    }

    // ── Easter algorithm ──────────────────────────────────────────────────────

    @Test fun `easter 2024 is March 31`() = assertEquals(LocalDate.of(2024, 3, 31), computeEaster(2024))
    @Test fun `easter 2025 is April 20`() = assertEquals(LocalDate.of(2025, 4, 20), computeEaster(2025))
    @Test fun `easter 2026 is April 5`()  = assertEquals(LocalDate.of(2026,  4,  5), computeEaster(2026))
    @Test fun `easter 2027 is March 28`() = assertEquals(LocalDate.of(2027, 3, 28), computeEaster(2027))

    // ── Fixed public holidays ────────────────────────────────────────────────

    @Test fun `New Year is a public holiday`()     = assertTrue(isFrenchPublicHoliday(LocalDate.of(2027, 1, 1)))
    @Test fun `Labour Day is a public holiday`()   = assertTrue(isFrenchPublicHoliday(LocalDate.of(2027, 5, 1)))
    @Test fun `Bastille Day is a public holiday`() = assertTrue(isFrenchPublicHoliday(LocalDate.of(2027, 7, 14)))
    @Test fun `Christmas is a public holiday`()    = assertTrue(isFrenchPublicHoliday(LocalDate.of(2027, 12, 25)))
    @Test fun `Regular working day is not a holiday`() = assertFalse(isFrenchPublicHoliday(LocalDate.of(2027, 3, 15)))

    // ── Floating public holidays ─────────────────────────────────────────────

    @Test fun `Easter Monday 2026 is public holiday`() {
        // Easter 2026 = April 5 → Monday = April 6
        assertTrue(isFrenchPublicHoliday(LocalDate.of(2026, 4, 6)))
    }

    @Test fun `Ascension 2026 is public holiday`() {
        // Easter 2026 + 39 = May 14
        assertTrue(isFrenchPublicHoliday(LocalDate.of(2026, 5, 14)))
    }

    @Test fun `Pentecost Monday 2026 is public holiday`() {
        // Easter 2026 + 50 = May 25
        assertTrue(isFrenchPublicHoliday(LocalDate.of(2026, 5, 25)))
    }

    // ── Dominguez convention validation ──────────────────────────────────────
    // Dates: 28/12/2026–02/01/2027
    // 28 Dec 2026 = Monday, 29 = Tue, 30 = Wed, 31 = Thu, 01 Jan 2027 = Friday (public holiday), 02 Jan = Saturday

    @Test fun `01-01-2027 is a public holiday`() = assertTrue(isFrenchPublicHoliday(LocalDate.of(2027, 1, 1)))

    @Test fun `no Sunday in Dominguez working dates`() {
        val dates = listOf(
            LocalDate.of(2026, 12, 28),
            LocalDate.of(2026, 12, 29),
            LocalDate.of(2026, 12, 30),
            LocalDate.of(2026, 12, 31),
            LocalDate.of(2027,  1,  1),
            LocalDate.of(2027,  1,  2),
        )
        assertFalse(dates.any { it.dayOfWeek == DayOfWeek.SUNDAY })
    }

    @Test fun `Dominguez holiday presence is true`() {
        val dates = listOf(
            LocalDate.of(2026, 12, 28),
            LocalDate.of(2026, 12, 29),
            LocalDate.of(2026, 12, 30),
            LocalDate.of(2026, 12, 31),
            LocalDate.of(2027,  1,  1),
            LocalDate.of(2027,  1,  2),
        )
        assertTrue(dates.any { isFrenchPublicHoliday(it) })
    }

    // ── Tarbouriech PDF (fixture réelle) ────────────────────────────────────

    private fun loadFixturePdf(): ByteArray =
        javaClass.classLoader.getResourceAsStream("fixtures/Tarbouriech12042027.pdf")!!.readBytes()

    /** Affiche la section modalités ET les champs AcroForm — diagnostic des cases à cocher. */
    @Test fun `debug - modalite section raw text`() {
        val bytes = loadFixturePdf()
        Loader.loadPDF(bytes).use { doc ->
            val rawText = PDFTextStripper().apply { sortByPosition = true }.getText(doc)
            val start = rawText.indexOf("c-")
            val end   = rawText.indexOf("d-", start)
            val section = if (start >= 0 && end > start) rawText.substring(start, end) else "(section non trouvée)"
            println("=== Section modalités (texte brut PDFBox) ===")
            section.lines().forEach { line ->
                val codes = line.take(5).map { "U+%04X".format(it.code) }.joinToString(" ")
                println("[$codes] $line")
            }
            println("=== Champs AcroForm ===")
            val acroForm = doc.documentCatalog.acroForm
            if (acroForm == null) { println("  (aucun AcroForm dans ce PDF)") }
            else {
                println("  Nombre de champs top-level : ${acroForm.fields?.size ?: 0}")
                fun dumpFields(fields: List<org.apache.pdfbox.pdmodel.interactive.form.PDField>?, indent: String) {
                    fields?.forEach { f ->
                        val value = runCatching { f.valueAsString }.getOrElse { "?" }
                        println("  $indent[${f.javaClass.simpleName}] '${f.fullyQualifiedName}' = '$value'")
                        if (f is org.apache.pdfbox.pdmodel.interactive.form.PDNonTerminalField)
                            dumpFields(f.children, "$indent  ")
                    }
                }
                dumpFields(acroForm.fields, "")
            }
            println("=== Fin debug ===")
        }
        // Test purement diagnostic, aucune assertion.
    }

    @Test fun `Tarbouriech - aucune modalite particuliere is checked`() {
        val data = ConventionPdfParser.parse(loadFixturePdf())
        println("nightPresence   = ${data.nightPresence}")
        println("sundayPresence  = ${data.sundayPresence}")
        println("holidayPresence = ${data.holidayPresence}")
        println("homePresence    = ${data.homePresence}")
        // La convention a "aucune modalité particulière" cochée → toutes les autres fausses
        assertFalse(data.nightPresence,   "nightPresence devrait être false")
        assertFalse(data.homePresence,    "homePresence devrait être false")
    }

    @Test fun `Tarbouriech - host email extracted`() {
        val data = ConventionPdfParser.parse(loadFixturePdf())
        println("hostEmail = '${data.hostEmail}'")
        assertEquals("cliniqueveterinairedulude@orange.fr", data.hostEmail)
    }

    @Test fun `Tarbouriech - host phone extracted`() {
        val data = ConventionPdfParser.parse(loadFixturePdf())
        println("hostPhone = '${data.hostPhone}'")
        assertEquals("0243949131", data.hostPhone)
    }

    @Test fun `Tarbouriech - dates extracted correctly`() {
        val data = ConventionPdfParser.parse(loadFixturePdf())
        println("startDate = '${data.startDate}' endDate = '${data.endDate}'")
        assertEquals("12/04/2027", data.startDate)
        assertEquals("23/04/2027", data.endDate)
    }

    // ── computeHasWeeklyRestDay ──────────────────────────────────────────────

    @Test fun `empty date list returns null`() {
        assertNull(ConventionPdfParser.computeHasWeeklyRestDay(emptyList()))
    }

    @Test fun `single date returns true (has rest day)`() {
        assertTrue(ConventionPdfParser.computeHasWeeklyRestDay(listOf(LocalDate.of(2026, 6, 1)))!!)
    }

    @Test fun `six consecutive days returns true (ok)`() {
        val dates = (0L..5L).map { LocalDate.of(2026, 6, 1).plusDays(it) }
        assertTrue(ConventionPdfParser.computeHasWeeklyRestDay(dates)!!)
    }

    @Test fun `exactly seven consecutive days returns false (violation)`() {
        val dates = (0L..6L).map { LocalDate.of(2026, 6, 1).plusDays(it) }
        assertFalse(ConventionPdfParser.computeHasWeeklyRestDay(dates)!!)
    }

    @Test fun `eight consecutive days returns false (violation)`() {
        val dates = (0L..7L).map { LocalDate.of(2026, 6, 1).plusDays(it) }
        assertFalse(ConventionPdfParser.computeHasWeeklyRestDay(dates)!!)
    }

    @Test fun `non-consecutive dates with gap return true`() {
        // 6 consecutive, then a gap, then 6 more — no 7-day streak
        val block1 = (0L..5L).map { LocalDate.of(2026, 6, 1).plusDays(it) }
        val block2 = (0L..5L).map { LocalDate.of(2026, 6, 10).plusDays(it) }
        assertTrue(ConventionPdfParser.computeHasWeeklyRestDay(block1 + block2)!!)
    }

    @Test fun `duplicates are ignored in consecutive count`() {
        // 6 unique consecutive days with one duplicate — still only 6 in a row
        val dates = (0L..5L).map { LocalDate.of(2026, 6, 1).plusDays(it) } +
            listOf(LocalDate.of(2026, 6, 3))
        assertTrue(ConventionPdfParser.computeHasWeeklyRestDay(dates)!!)
    }
}
