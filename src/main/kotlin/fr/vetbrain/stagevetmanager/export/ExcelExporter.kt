package fr.vetbrain.stagevetmanager.export

import fr.vetbrain.stagevetmanager.model.Internship
import org.apache.poi.ss.usermodel.*
import org.apache.poi.xssf.usermodel.XSSFWorkbook
import java.io.FileOutputStream
import java.nio.file.Path
import java.time.LocalDate
import java.time.format.DateTimeFormatter

object ExcelExporter {

    private val DATE_FMT = DateTimeFormatter.ofPattern("dd/MM/yyyy")

    fun export(internships: List<Internship>, path: Path) {
        val wb = XSSFWorkbook()
        val today = LocalDate.now()

        val startingSoon = internships.filter {
            it.startDate != null && !it.startDate.isBefore(today) && !it.startDate.isAfter(today.plusDays(15))
        }
        val recentlySigned = internships.filter {
            it.signingDate != null && !it.signingDate.isBefore(today.minusDays(15)) && !it.signingDate.isAfter(today)
        }

        writeSheet(wb, "Tous les stages", internships)
        writeSheet(wb, "Débuts 15 prochains jours", startingSoon)
        writeSheet(wb, "Signés 15 derniers jours", recentlySigned)

        FileOutputStream(path.toFile()).use { wb.write(it) }
        wb.close()
    }

    private fun writeSheet(wb: XSSFWorkbook, name: String, rows: List<Internship>) {
        val sheet = wb.createSheet(name)

        val headerStyle = wb.createCellStyle().apply {
            fillForegroundColor = IndexedColors.CORNFLOWER_BLUE.index
            fillPattern = FillPatternType.SOLID_FOREGROUND
            setFont(wb.createFont().apply {
                bold = true
                color = IndexedColors.WHITE.index
            })
        }

        val dateCellStyle = wb.createCellStyle().apply {
            dataFormat = wb.createDataFormat().getFormat("dd/mm/yyyy")
        }

        val headers = listOf(
            "Étudiant", "Année", "Organisme", "Adresse",
            "Convention n°", "Conv. générée le", "Date signature",
            "Début stage", "Fin stage", "Dates brutes", "Thème"
        )

        val headerRow = sheet.createRow(0)
        headers.forEachIndexed { i, h ->
            headerRow.createCell(i).apply {
                setCellValue(h)
                cellStyle = headerStyle
            }
        }

        rows.forEachIndexed { rowIdx, s ->
            val row = sheet.createRow(rowIdx + 1)
            row.createCell(0).setCellValue(s.studentName)
            row.createCell(1).setCellValue(s.studyYear)
            row.createCell(2).setCellValue(s.organization)
            row.createCell(3).setCellValue(s.address)
            row.createCell(4).setCellValue(s.conventionNumber)
            row.createCell(5).setCellValue(s.conventionGenDate)
            row.createCell(6).setCellValue(s.signingDate?.format(DATE_FMT) ?: "")
            row.createCell(7).setCellValue(s.startDate?.format(DATE_FMT) ?: "")
            row.createCell(8).setCellValue(s.endDate?.format(DATE_FMT) ?: "")
            row.createCell(9).setCellValue(s.rawDateStage)
            row.createCell(10).setCellValue(s.theme)
        }

        headers.indices.forEach { sheet.autoSizeColumn(it) }
    }
}
