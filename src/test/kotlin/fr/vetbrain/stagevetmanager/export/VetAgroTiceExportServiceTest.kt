package fr.vetbrain.stagevetmanager.export

import fr.vetbrain.stagevetmanager.model.ConventionPdfData
import fr.vetbrain.stagevetmanager.model.Internship
import fr.vetbrain.stagevetmanager.persistence.LocalDatabase
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.time.LocalDate

class VetAgroTiceExportServiceTest {
    @TempDir lateinit var dir: Path

    private fun database() = LocalDatabase(dir.resolve("test.db")).also { it.init() }
    private fun stage(name: String = "Étudiant A", year: String = "5e") = Internship(
        studentName = name, studyYear = year, organization = "Clinique test", address = "Lyon",
        conventionNumber = "", conventionGenDate = "", signingDate = LocalDate.of(2026, 1, 1),
        startDate = LocalDate.of(2026, 2, 1), endDate = LocalDate.of(2026, 2, 5),
        rawDateStage = "01/02/2026 au 05/02/2026", theme = "Chirurgie",
        conventionPdfUrl = "https://example.invalid/$name.pdf",
        supervisorEvaluation = "Ponctualité : 3/5\nCommentaire\nInitial",
    )
    private fun export(db: LocalDatabase, stages: List<Internship>, name: String = "out.csv",
                       cache: Map<String, ConventionPdfData> = emptyMap(), full: Boolean = false,
                       year: String = "5e") =
        VetAgroTiceExportService.export(db, stages, dir.resolve(name), year, cache, full)

    @Test
    fun `first export excludes unsigned and other years and survives reopening`() {
        val db = database()
        val rows = listOf(stage(), stage("Non signé").copy(signingDate = null), stage("Autre", "4e"))
        assertEquals(1, export(db, rows))
        val reopened = database()
        assertEquals(0, export(reopened, rows, "unchanged.csv"))
        assertFalse(Files.exists(dir.resolve("unchanged.csv")))
        val history = reopened.loadVetAgroTiceExports().single()
        assertEquals(1, history.stageCount)
        assertEquals("5e", history.studyYear)
        assertFalse(history.fullExport)
        assertEquals(dir.resolve("out.csv").toString(), history.filePath)
        assertEquals(1, export(reopened, rows, "year4.csv", year = "4e"))
        assertEquals(2, reopened.loadVetAgroTiceExports().size)
    }

    @Test
    fun `new stages and evaluation changes are exported once with blank convention numbers`() {
        val db = database()
        val first = stage()
        val second = stage("Étudiant B")
        assertEquals(2, export(db, listOf(first, second)))
        val updated = first.copy(studentEvaluation = "Accueil : 5/5\nTrès bon stage")
        val third = stage("Étudiant C")
        assertEquals(2, export(db, listOf(updated, second, third), "changes.csv"))
        val csv = Files.readString(dir.resolve("changes.csv"))
        assertTrue(csv.contains("Étudiant A"))
        assertTrue(csv.contains("Étudiant C"))
        assertFalse(csv.contains("Étudiant B"))
        assertEquals(0, export(db, listOf(updated, second, third), "same.csv"))
        assertEquals(1, export(db, listOf(updated.copy(supervisorEvaluation = "Ponctualité : 4/5"), second, third)))
    }

    @Test
    fun `PDF data and exported fields trigger changes but local tracking and evaluation links do not`() {
        val db = database()
        val first = stage()
        export(db, listOf(first))
        assertEquals(0, export(db, listOf(first.copy(inSuiviTable = true, supervisorEvaluationUrl = "new-link"))))
        val pdf = ConventionPdfData(rawText = "Convention", studentEmail = "etu@example.invalid")
        val cache = mapOf(first.conventionPdfUrl to pdf)
        assertEquals(1, export(db, listOf(first), cache = cache))
        assertEquals(0, export(db, listOf(first), cache = cache))
        assertEquals(1, export(db, listOf(first), cache = mapOf(first.conventionPdfUrl to pdf.copy(rawText = "Convention actualisée"))))
        assertEquals(1, export(db, listOf(first.copy(address = "Paris")), cache = cache))
    }

    @Test
    fun `full export includes unchanged stages and resets their comparison baseline`() {
        val db = database()
        val rows = listOf(stage(), stage("Étudiant B"))
        export(db, rows)
        assertEquals(2, export(db, rows, "full.csv", full = true))
        assertTrue(db.loadVetAgroTiceExports().first().fullExport)
        assertEquals(0, export(db, rows, "no-change.csv"))
    }

    @Test
    fun `empty incremental export leaves prior file and history intact`() {
        val db = database()
        export(db, listOf(stage()))
        val bytes = Files.readAllBytes(dir.resolve("out.csv"))
        assertEquals(0, export(db, listOf(stage())))
        assertArrayEquals(bytes, Files.readAllBytes(dir.resolve("out.csv")))
        assertEquals(1, db.loadVetAgroTiceExports().size)
    }

    @Test
    fun `failed file write never advances fingerprints or history`() {
        val db = database()
        export(db, listOf(stage()))
        val before = db.loadVetAgroTiceFingerprints()
        Files.writeString(dir.resolve("not-a-directory"), "blocked")
        assertThrows(Exception::class.java) {
            export(db, listOf(stage().copy(studentEvaluation = "Accueil : 4/5")), "not-a-directory/export.csv")
        }
        assertEquals(before, db.loadVetAgroTiceFingerprints())
        assertEquals(1, db.loadVetAgroTiceExports().size)
        assertEquals(1, export(db, listOf(stage().copy(studentEvaluation = "Accueil : 4/5")), "retry.csv"))
    }

    @Test
    fun `clearing stages resets comparison but retains export history`() {
        val db = database()
        db.upsertAll(listOf(stage()))
        export(db, listOf(stage()))
        db.clear()
        assertTrue(db.loadVetAgroTiceFingerprints().isEmpty())
        assertEquals(1, db.loadVetAgroTiceExports().size)
        assertEquals(1, export(db, listOf(stage())))
    }
}
