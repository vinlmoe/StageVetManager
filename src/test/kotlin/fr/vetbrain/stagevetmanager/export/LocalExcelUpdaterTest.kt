package fr.vetbrain.stagevetmanager.export

import fr.vetbrain.stagevetmanager.model.Internship
import org.apache.poi.ss.usermodel.WorkbookFactory
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.nio.file.Path
import java.time.LocalDate

class LocalExcelUpdaterTest {

    @TempDir
    lateinit var tempDir: Path

    @Test
    fun `does not duplicate rows whose convention number is stored as a number`() {
        // Cell.toString() renvoie "12345.0" pour une cellule numérique : la
        // déduplication échouait et chaque exécution réajoutait toutes les lignes.
        val file = tempDir.resolve("export.xlsx").toFile()
        val stages = listOf(stage("Dupont Marie", "12345"), stage("Martin Paul", "12346"))

        LocalExcelUpdater.update(stages, file.absolutePath)
        makeConventionNumbersNumeric(file)

        LocalExcelUpdater.complement(stages, file.absolutePath)

        assertEquals(2, dataRowCount(file), "aucune ligne ne doit être réajoutée")
    }

    @Test
    fun `appends only the stages that are not already present`() {
        val file = tempDir.resolve("export.xlsx").toFile()
        LocalExcelUpdater.update(listOf(stage("Dupont Marie", "12345")), file.absolutePath)

        LocalExcelUpdater.complement(
            listOf(stage("Dupont Marie", "12345"), stage("Martin Paul", "12346")),
            file.absolutePath,
        )

        assertEquals(2, dataRowCount(file))
    }

    private fun stage(name: String, conventionNumber: String) = Internship(
        studentName = name,
        studyYear = "4",
        organization = "Clinique du Parc",
        address = "Lyon",
        conventionNumber = conventionNumber,
        conventionGenDate = "",
        signingDate = LocalDate.of(2026, 5, 1),
        startDate = LocalDate.of(2026, 6, 1),
        endDate = LocalDate.of(2026, 6, 30),
        rawDateStage = "01/06/2026 au 30/06/2026",
        theme = "Animaux de compagnie",
    )

    /** Reproduit un classeur où les numéros de convention sont des nombres. */
    private fun makeConventionNumbersNumeric(file: File) {
        val wb = file.inputStream().use { WorkbookFactory.create(it) }
        wb.use {
            val sheet = it.getSheet("Tous les stages")
            for (rowIdx in 1..sheet.lastRowNum) {
                val cell = sheet.getRow(rowIdx)?.getCell(4) ?: continue
                val numeric = cell.stringCellValue.toDoubleOrNull() ?: continue
                cell.setCellValue(numeric)
            }
            SafeFileWrite.replace(file, keepBackup = false) { out -> it.write(out) }
        }
    }

    private fun dataRowCount(file: File): Int =
        file.inputStream().use { WorkbookFactory.create(it) }.use { wb ->
            val sheet = wb.getSheet("Tous les stages")
            (1..sheet.lastRowNum).count { idx ->
                val first = sheet.getRow(idx)?.getCell(0)
                first != null && first.toString().isNotBlank()
            }
        }
}
