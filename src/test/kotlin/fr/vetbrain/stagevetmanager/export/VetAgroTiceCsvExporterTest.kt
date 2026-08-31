package fr.vetbrain.stagevetmanager.export

import fr.vetbrain.stagevetmanager.model.ConventionPdfData
import fr.vetbrain.stagevetmanager.model.Internship
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.time.LocalDate

class VetAgroTiceCsvExporterTest {

    private fun stage(number: String, signingDate: LocalDate?) = Internship(
        studentName = "Dupont; Marie",
        studyYear = "3e",
        organization = "Clinique \"Test\"",
        address = "Lyon",
        conventionNumber = number,
        conventionGenDate = "01/01/2026",
        signingDate = signingDate,
        startDate = LocalDate.of(2026, 2, 1),
        endDate = LocalDate.of(2026, 2, 5),
        rawDateStage = "",
        theme = "Chirurgie",
        conventionPdfUrl = "https://example.test/$number.pdf",
    )

    @Test
    fun `exports only signed stages with all convention data`() {
        val signed = stage("SIGNE", LocalDate.of(2026, 1, 20))
        val unsigned = stage("NON-SIGNE", null)
        val cache = mapOf(
            signed.conventionPdfUrl to ConventionPdfData(
                rawText = "Texte\nsur deux lignes",
                studentEmail = "marie.dupont@example.test",
                hostEmail = "contact@clinique.test",
                signingDateSchool = "20/01/2026",
            )
        )
        val file = Files.createTempFile("vetagrotice-", ".csv")

        val count = VetAgroTiceCsvExporter.export(listOf(signed, unsigned), file, cache)
        val csv = Files.readString(file)

        assertEquals(1, count)
        assertTrue(csv.startsWith("\uFEFF\"Étudiant\";"))
        assertTrue(csv.contains("\"SIGNE\""))
        assertFalse(csv.contains("\"NON-SIGNE\""))
        assertTrue(csv.contains("\"marie.dupont@example.test\""))
        assertTrue(csv.contains("\"Clinique \"\"Test\"\"\""))
        assertTrue(csv.contains("\"Texte\nsur deux lignes\""))
    }
}
