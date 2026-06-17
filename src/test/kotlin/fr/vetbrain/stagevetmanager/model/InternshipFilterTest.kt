package fr.vetbrain.stagevetmanager.model

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.time.LocalDate

class InternshipFilterTest {

    private fun internship(
        studentName: String = "Dupont Marie",
        organization: String = "Clinique du Lac",
        theme: String = "Chirurgie",
        address: String = "Lyon",
        studyYear: String = "3ème année",
        startDate: LocalDate? = null,
        signingDate: LocalDate? = null,
    ) = Internship(
        studentName = studentName,
        studyYear = studyYear,
        organization = organization,
        address = address,
        conventionNumber = "",
        conventionGenDate = "",
        signingDate = signingDate,
        startDate = startDate,
        endDate = null,
        rawDateStage = "",
        theme = theme,
    )

    // ── matchesText ───────────────────────────────────────────────────────────

    @Test
    fun `matchesText with blank query returns true for any internship`() {
        assertTrue(internship().matchesText(""))
        assertTrue(internship().matchesText("   "))
    }

    @Test
    fun `matchesText is case insensitive on studentName`() {
        assertTrue(internship(studentName = "Dupont Marie").matchesText("dupont"))
        assertTrue(internship(studentName = "Dupont Marie").matchesText("DUPONT"))
        assertTrue(internship(studentName = "Dupont Marie").matchesText("MARIE"))
    }

    @Test
    fun `matchesText searches in organization`() {
        assertTrue(internship(organization = "Clinique du Lac").matchesText("clinique"))
    }

    @Test
    fun `matchesText searches in theme`() {
        assertTrue(internship(theme = "Chirurgie générale").matchesText("chirurgie"))
    }

    @Test
    fun `matchesText searches in address`() {
        assertTrue(internship(address = "Lyon, 69000").matchesText("lyon"))
    }

    @Test
    fun `matchesText searches in studyYear`() {
        assertTrue(internship(studyYear = "3ème année").matchesText("3ème"))
    }

    @Test
    fun `matchesText returns false when query absent from all fields`() {
        assertFalse(internship().matchesText("xyz123"))
    }

    // ── ViewFilter.ALL ────────────────────────────────────────────────────────

    @Test
    fun `ViewFilter ALL predicate is always true`() {
        assertTrue(ViewFilter.ALL.predicate(internship()))
        assertTrue(ViewFilter.ALL.predicate(internship(startDate = LocalDate.now())))
        assertTrue(ViewFilter.ALL.predicate(internship(signingDate = LocalDate.now())))
    }

    // ── ViewFilter.STARTING_SOON ──────────────────────────────────────────────

    @Test
    fun `STARTING_SOON matches today`() {
        assertTrue(ViewFilter.STARTING_SOON.predicate(internship(startDate = LocalDate.now())))
    }

    @Test
    fun `STARTING_SOON matches today plus 15 days`() {
        assertTrue(ViewFilter.STARTING_SOON.predicate(internship(startDate = LocalDate.now().plusDays(15))))
    }

    @Test
    fun `STARTING_SOON rejects today plus 16 days`() {
        assertFalse(ViewFilter.STARTING_SOON.predicate(internship(startDate = LocalDate.now().plusDays(16))))
    }

    @Test
    fun `STARTING_SOON rejects yesterday`() {
        assertFalse(ViewFilter.STARTING_SOON.predicate(internship(startDate = LocalDate.now().minusDays(1))))
    }

    @Test
    fun `STARTING_SOON rejects null startDate`() {
        assertFalse(ViewFilter.STARTING_SOON.predicate(internship(startDate = null)))
    }

    // ── ViewFilter.RECENTLY_SIGNED ────────────────────────────────────────────

    @Test
    fun `RECENTLY_SIGNED matches today`() {
        assertTrue(ViewFilter.RECENTLY_SIGNED.predicate(internship(signingDate = LocalDate.now())))
    }

    @Test
    fun `RECENTLY_SIGNED matches today minus 15 days`() {
        assertTrue(ViewFilter.RECENTLY_SIGNED.predicate(internship(signingDate = LocalDate.now().minusDays(15))))
    }

    @Test
    fun `RECENTLY_SIGNED rejects today minus 16 days`() {
        assertFalse(ViewFilter.RECENTLY_SIGNED.predicate(internship(signingDate = LocalDate.now().minusDays(16))))
    }

    @Test
    fun `RECENTLY_SIGNED rejects null signingDate`() {
        assertFalse(ViewFilter.RECENTLY_SIGNED.predicate(internship(signingDate = null)))
    }
}
