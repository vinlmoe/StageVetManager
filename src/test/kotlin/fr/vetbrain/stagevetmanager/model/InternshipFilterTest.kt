package fr.vetbrain.stagevetmanager.model

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.time.LocalDate

class InternshipFilterTest {

    @Test
    fun `unsigned conventions are refreshed until the internship ends`() {
        val today = LocalDate.of(2026, 9, 13)
        val pending = internship(
            startDate = today.minusDays(10), endDate = today,
            conventionSignUrl = "https://stagevet.fr/signature/abc",
        )
        assertTrue(pending.needsSignatureRefresh(today))
        assertFalse(pending.copy(endDate = today.minusDays(1)).needsSignatureRefresh(today))
        assertFalse(pending.copy(signingDate = today).needsSignatureRefresh(today))
        assertFalse(pending.copy(conventionSignUrl = "").needsSignatureRefresh(today))
    }

    private fun internship(
        studentName: String = "Dupont Marie",
        organization: String = "Clinique du Lac",
        theme: String = "Chirurgie",
        address: String = "Lyon",
        studyYear: String = "3ème année",
        startDate: LocalDate? = null,
        endDate: LocalDate? = null,
        signingDate: LocalDate? = null,
        conventionSignUrl: String = "",
        inSuiviTable: Boolean = false,
    ) = Internship(
        studentName = studentName,
        studyYear = studyYear,
        organization = organization,
        address = address,
        conventionNumber = "",
        conventionGenDate = "",
        signingDate = signingDate,
        startDate = startDate,
        endDate = endDate,
        rawDateStage = "",
        theme = theme,
        conventionSignUrl = conventionSignUrl,
        inSuiviTable = inSuiviTable,
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

    @Test
    fun `UNSIGNED_STARTING_SOON matches an unsigned stage starting in 15 days`() {
        assertTrue(
            ViewFilter.UNSIGNED_STARTING_SOON.predicate(
                internship(startDate = LocalDate.now().plusDays(15))
            )
        )
    }

    @Test
    fun `UNSIGNED_STARTING_SOON rejects a signed stage`() {
        assertFalse(
            ViewFilter.UNSIGNED_STARTING_SOON.predicate(
                internship(startDate = LocalDate.now().plusDays(5), signingDate = LocalDate.now())
            )
        )
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

    // ── ViewFilter.PENDING_SCHOOL_SIGNATURE ──────────────────────────────────

    @Test
    fun `PENDING_SCHOOL_SIGNATURE matches a stage started 30 days ago`() {
        assertTrue(
            ViewFilter.PENDING_SCHOOL_SIGNATURE.predicate(
                internship(
                    startDate = LocalDate.now().minusDays(30),
                    conventionSignUrl = "https://stagevet.fr/signature/abc",
                )
            )
        )
    }

    @Test
    fun `PENDING_SCHOOL_SIGNATURE rejects a stage started more than 30 days ago`() {
        assertFalse(
            ViewFilter.PENDING_SCHOOL_SIGNATURE.predicate(
                internship(
                    startDate = LocalDate.now().minusDays(31),
                    conventionSignUrl = "https://stagevet.fr/signature/abc",
                )
            )
        )
    }

    @Test
    fun `COMPLETED_NOT_IN_TRACKING matches an ended stage not selected for tracking`() {
        assertTrue(
            ViewFilter.COMPLETED_NOT_IN_TRACKING.predicate(
                internship(endDate = LocalDate.now().minusDays(1))
            )
        )
    }

    @Test
    fun `COMPLETED_NOT_IN_TRACKING rejects a stage already selected for tracking`() {
        assertFalse(
            ViewFilter.COMPLETED_NOT_IN_TRACKING.predicate(
                internship(endDate = LocalDate.now().minusDays(1), inSuiviTable = true)
            )
        )
    }
}
