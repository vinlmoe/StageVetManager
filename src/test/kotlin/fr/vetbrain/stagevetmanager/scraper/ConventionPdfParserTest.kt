package fr.vetbrain.stagevetmanager.scraper

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
}
