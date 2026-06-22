package fr.vetbrain.stagevetmanager.export

import fr.vetbrain.stagevetmanager.model.Internship
import org.apache.poi.xssf.usermodel.XSSFWorkbook
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.io.ByteArrayInputStream
import java.time.LocalDate

class ExcelExporterTest {

    private fun internship(
        studentName: String = "Dupont Marie",
        startDate: LocalDate? = null,
        signingDate: LocalDate? = null,
    ) = Internship(
        studentName = studentName,
        studyYear = "3ème année",
        organization = "Clinique du Lac",
        address = "Lyon",
        conventionNumber = "2025-001",
        conventionGenDate = "15/01/2025",
        signingDate = signingDate,
        startDate = startDate,
        endDate = null,
        rawDateStage = "",
        theme = "Chirurgie",
    )

    private fun workbook(internships: List<Internship>): XSSFWorkbook =
        XSSFWorkbook(ByteArrayInputStream(ExcelExporter.exportToBytes(internships)))

    @Test
    fun `exportToBytes returns non-empty bytes for empty list`() {
        assertTrue(ExcelExporter.exportToBytes(emptyList()).isNotEmpty())
    }

    @Test
    fun `workbook has exactly three sheets`() {
        workbook(emptyList()).use { assertEquals(3, it.numberOfSheets) }
    }

    @Test
    fun `sheet names are correct`() {
        workbook(emptyList()).use { wb ->
            assertEquals("Tous les stages", wb.getSheetAt(0).sheetName)
            assertEquals("Débuts 15 prochains jours", wb.getSheetAt(1).sheetName)
            assertEquals("Signés 15 derniers jours", wb.getSheetAt(2).sheetName)
        }
    }

    @Test
    fun `header row has 14 columns with correct labels`() {
        val expectedHeaders = listOf(
            "Étudiant", "Année", "Organisme", "Adresse",
            "Convention n°", "Conv. générée le", "Date signature",
            "Début stage", "Fin stage", "Dates brutes", "Thème",
            "URL Convention PDF", "URL Signature", "Durée"
        )
        workbook(emptyList()).use { wb ->
            val header = wb.getSheetAt(0).getRow(0)
            expectedHeaders.forEachIndexed { i, expected ->
                assertEquals(expected, header.getCell(i).stringCellValue)
            }
        }
    }

    @Test
    fun `tous les stages sheet contains header plus all data rows`() {
        val internships = listOf(internship("A"), internship("B"), internship("C"))
        workbook(internships).use { wb ->
            assertEquals(4, wb.getSheetAt(0).physicalNumberOfRows)
        }
    }

    @Test
    fun `debuts 15 prochains jours filters by startDate window`() {
        val today = LocalDate.now()
        val internships = listOf(
            internship(startDate = today),             // inside: today
            internship(startDate = today.plusDays(15)), // inside: edge
            internship(startDate = today.plusDays(16)), // outside
            internship(startDate = null),               // outside: no date
        )
        workbook(internships).use { wb ->
            // 1 header + 2 matching rows
            assertEquals(3, wb.getSheetAt(1).physicalNumberOfRows)
        }
    }

    @Test
    fun `signes 15 derniers jours filters by signingDate window`() {
        val today = LocalDate.now()
        val internships = listOf(
            internship(signingDate = today),               // inside: today
            internship(signingDate = today.minusDays(15)), // inside: edge
            internship(signingDate = today.minusDays(16)), // outside
            internship(signingDate = null),                // outside: no date
        )
        workbook(internships).use { wb ->
            // 1 header + 2 matching rows
            assertEquals(3, wb.getSheetAt(2).physicalNumberOfRows)
        }
    }

    @Test
    fun `null date fields produce empty string cells without NPE`() {
        workbook(listOf(internship(startDate = null, signingDate = null))).use { wb ->
            val dataRow = wb.getSheetAt(0).getRow(1)
            assertEquals("", dataRow.getCell(6).stringCellValue) // signingDate
            assertEquals("", dataRow.getCell(7).stringCellValue) // startDate
            assertEquals("", dataRow.getCell(8).stringCellValue) // endDate
        }
    }
}
