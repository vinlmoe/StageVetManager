package fr.vetbrain.stagevetmanager.export

import fr.vetbrain.stagevetmanager.model.Internship
import org.apache.poi.ss.usermodel.WorkbookFactory
import org.apache.poi.xssf.usermodel.XSSFWorkbook
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.io.FileOutputStream
import java.nio.file.Path
import java.time.LocalDate

class LocalTrackingUpdaterTest {

    @TempDir
    lateinit var tempDir: Path

    @Test
    fun `writes the stage into the column matching its theme`() {
        val file = trackingWorkbook()

        val result = LocalTrackingUpdater().update(
            listOf(stage(studentName = "Dupont Marie", theme = "Animaux de compagnie")),
            file.absolutePath,
        )

        assertEquals(1, result.matched)
        assertEquals("Clinique du Parc — 01/06/2026 au 30/06/2026", cell(file, row = 2, col = 4))
    }

    @Test
    fun `keeps theme groups separate when their columns are contiguous`() {
        // Le balayage des sous-en-têtes ne s'arrêtait que sur une cellule vide :
        // sans colonne de séparation, chaque groupe héritait des colonnes du
        // dernier groupe scanné et tous les stages atterrissaient au même endroit.
        val file = trackingWorkbook()

        val result = LocalTrackingUpdater().update(
            listOf(stage(studentName = "Dupont Marie", theme = "Animaux de production")),
            file.absolutePath,
        )

        assertEquals(1, result.matched)
        assertNull(cell(file, row = 2, col = 4), "le groupe « compagnie » doit rester vide")
        assertEquals("Clinique du Parc — 01/06/2026 au 30/06/2026", cell(file, row = 2, col = 6))
    }

    @Test
    fun `refuses to place a stage whose theme could not be parsed`() {
        // groupNorm.contains("") est toujours vrai : un thème vide était écrit
        // silencieusement dans le PREMIER groupe de colonnes du tableau de suivi.
        val file = trackingWorkbook()

        val result = LocalTrackingUpdater().update(
            listOf(stage(studentName = "Dupont Marie", theme = "")),
            file.absolutePath,
        )

        assertEquals(0, result.matched)
        assertTrue(
            result.warnings.any { it.contains("Thème introuvable") },
            "un avertissement doit signaler le thème manquant : ${result.warnings}",
        )
        assertNull(cell(file, row = 2, col = 4), "aucune colonne ne doit être remplie")
    }

    @Test
    fun `keeps the original workbook when the theme is unknown`() {
        val file = trackingWorkbook()
        val before = file.readBytes().size

        LocalTrackingUpdater().update(
            listOf(stage(studentName = "Dupont Marie", theme = "Thème inexistant")),
            file.absolutePath,
        )

        assertTrue(file.exists() && file.readBytes().size > 0)
        assertTrue(before > 0)
        assertEquals("Dupont", cell(file, row = 2, col = 0))
    }

    private fun stage(studentName: String, theme: String) = Internship(
        studentName = studentName,
        studyYear = "4",
        organization = "Clinique du Parc",
        address = "Lyon",
        conventionNumber = "C-1",
        conventionGenDate = "",
        signingDate = LocalDate.of(2026, 5, 1),
        startDate = LocalDate.of(2026, 6, 1),
        endDate = LocalDate.of(2026, 6, 30),
        rawDateStage = "01/06/2026 au 30/06/2026",
        theme = theme,
        durationLabel = "4 semaines",
    )

    /** Tableau minimal : 2 lignes d'en-tête, 2 groupes de thèmes, 1 étudiant. */
    private fun trackingWorkbook(): File {
        val file = tempDir.resolve("suivi.xlsx").toFile()
        XSSFWorkbook().use { wb ->
            val sheet = wb.createSheet("Suivi")
            val head0 = sheet.createRow(0)
            head0.createCell(4).setCellValue("Animaux de compagnie")
            head0.createCell(6).setCellValue("Animaux de production")
            val head1 = sheet.createRow(1)
            head1.createCell(4).setCellValue("Lieu et date")
            head1.createCell(5).setCellValue("Durée")
            head1.createCell(6).setCellValue("Lieu et date")
            head1.createCell(7).setCellValue("Durée")
            val student = sheet.createRow(2)
            student.createCell(0).setCellValue("Dupont")
            student.createCell(1).setCellValue("Marie")
            FileOutputStream(file).use { wb.write(it) }
        }
        return file
    }

    private fun cell(file: File, row: Int, col: Int): String? =
        file.inputStream().use { WorkbookFactory.create(it) }.use { wb ->
            wb.getSheetAt(0).getRow(row)?.getCell(col)?.stringCellValue
        }
}
