package fr.vetbrain.stagevetmanager.persistence

import fr.vetbrain.stagevetmanager.model.Internship
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path
import java.time.LocalDate

class LocalDatabaseTest {

    private lateinit var db: LocalDatabase
    private lateinit var tempFile: Path

    @BeforeEach
    fun setUp() {
        tempFile = Files.createTempFile("stagevet_test", ".db")
        db = LocalDatabase(tempFile)
        db.init()
    }

    @AfterEach
    fun tearDown() {
        Files.deleteIfExists(tempFile)
    }

    private fun internship(
        studentName: String = "Dupont Marie",
        organization: String = "Clinique du Lac",
        rawDateStage: String = "01/06/2025 au 30/06/2025",
        startDate: LocalDate? = LocalDate.of(2025, 6, 1),
        signingDate: LocalDate? = null,
    ) = Internship(
        studentName = studentName,
        studyYear = "3ème année",
        organization = organization,
        address = "Lyon",
        conventionNumber = "2025-001",
        conventionGenDate = "15/01/2025",
        signingDate = signingDate,
        startDate = startDate,
        endDate = LocalDate.of(2025, 6, 30),
        rawDateStage = rawDateStage,
        theme = "Chirurgie",
    )

    // ── localId ───────────────────────────────────────────────────────────────

    @Test
    fun `localId is deterministic`() {
        val s = internship()
        assertEquals(s.localId(), s.localId())
    }

    @Test
    fun `localId changes when organization changes`() {
        assertNotEquals(
            internship(organization = "Clinique A").localId(),
            internship(organization = "Clinique B").localId()
        )
    }

    @Test
    fun `localId changes when rawDateStage changes`() {
        assertNotEquals(
            internship(rawDateStage = "01/06/2025 au 30/06/2025").localId(),
            internship(rawDateStage = "01/07/2025 au 31/07/2025").localId()
        )
    }

    // ── upsertAll ─────────────────────────────────────────────────────────────

    @Test
    fun `upsertAll on empty list returns zero stats`() {
        assertEquals(UpsertStats(0, 0), db.upsertAll(emptyList()))
    }

    @Test
    fun `upsertAll with new internship returns added=1 updated=0`() {
        val stats = db.upsertAll(listOf(internship()))
        assertEquals(1, stats.added)
        assertEquals(0, stats.updated)
    }

    @Test
    fun `upsertAll same internship twice deduplicates and counts correctly`() {
        val s = internship()
        val stats1 = db.upsertAll(listOf(s))
        val stats2 = db.upsertAll(listOf(s))
        assertEquals(UpsertStats(1, 0), stats1)
        assertEquals(UpsertStats(0, 1), stats2)
        assertEquals(1, db.count())
    }

    @Test
    fun `upsertAll updates signingDate from null to a real date`() {
        db.upsertAll(listOf(internship(signingDate = null)))
        db.upsertAll(listOf(internship(signingDate = LocalDate.of(2025, 6, 1))))
        val loaded = db.loadAll()
        assertEquals(LocalDate.of(2025, 6, 1), loaded[0].signingDate)
    }

    // ── loadAll ───────────────────────────────────────────────────────────────

    @Test
    fun `loadAll returns internships sorted by startDate ascending`() {
        val earlier = internship(
            studentName = "Dupont Marie",
            rawDateStage = "01/06/2025 au 30/06/2025",
            startDate = LocalDate.of(2025, 6, 1),
        )
        val later = internship(
            studentName = "Martin Luc",
            rawDateStage = "01/07/2025 au 31/07/2025",
            startDate = LocalDate.of(2025, 7, 1),
        )
        db.upsertAll(listOf(later, earlier))
        val loaded = db.loadAll()
        assertEquals(2, loaded.size)
        assertEquals("Dupont Marie", loaded[0].studentName)
        assertEquals("Martin Luc", loaded[1].studentName)
    }

    // ── clear / count ─────────────────────────────────────────────────────────

    @Test
    fun `upsertAll persists and loadAll restores conventionPdfUrl and conventionSignUrl`() {
        val s = internship().copy(
            conventionPdfUrl = "https://www.stagevet.fr/convention/pdf/abc123",
            conventionSignUrl = "https://www.stagevet.fr/convention/pdf/abc123/signature/xyz789",
        )
        db.upsertAll(listOf(s))
        val loaded = db.loadAll()[0]
        assertEquals(s.conventionPdfUrl, loaded.conventionPdfUrl)
        assertEquals(s.conventionSignUrl, loaded.conventionSignUrl)
    }

    @Test
    fun `clear empties the database and count returns zero`() {
        db.upsertAll(listOf(internship()))
        assertEquals(1, db.count())
        db.clear()
        assertEquals(0, db.count())
        assertTrue(db.loadAll().isEmpty())
    }

    // ── checkpoints de scraping ──────────────────────────────────────────────

    @Test
    fun `incomplete scrape resumes with completed page counts`() {
        val first = db.beginOrResumeScrape("3|2|21||1")
        assertFalse(first.resumed)
        db.markScrapePageCompleted(first.runId, 1, 10)
        db.markScrapePageCompleted(first.runId, 3, 7)

        val resumed = db.beginOrResumeScrape("3|2|21||1")

        assertTrue(resumed.resumed)
        assertEquals(first.runId, resumed.runId)
        assertEquals(mapOf(1 to 10, 3 to 7), resumed.completedPages)
    }

    @Test
    fun `completed scrape starts a fresh checkpoint`() {
        val first = db.beginOrResumeScrape("all")
        db.markScrapePageCompleted(first.runId, 1, 10)
        db.finishScrapeRun(first.runId, success = true, totalPages = 1, totalItems = 10)

        val next = db.beginOrResumeScrape("all")

        assertFalse(next.resumed)
        assertNotEquals(first.runId, next.runId)
        assertTrue(next.completedPages.isEmpty())
    }
}
