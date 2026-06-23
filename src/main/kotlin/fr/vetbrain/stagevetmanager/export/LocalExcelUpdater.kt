package fr.vetbrain.stagevetmanager.export

import fr.vetbrain.stagevetmanager.model.Internship
import org.apache.poi.ss.usermodel.Sheet
import org.apache.poi.ss.usermodel.WorkbookFactory
import java.io.File
import java.io.FileOutputStream
import java.nio.file.Paths
import java.time.LocalDate
import java.time.format.DateTimeFormatter

object LocalExcelUpdater {

    private val DATE_FMT = DateTimeFormatter.ofPattern("dd/MM/yyyy")
    private val HEADERS = listOf(
        "Étudiant", "Année", "Organisme", "Adresse",
        "Convention n°", "Conv. générée le", "Date signature",
        "Début stage", "Fin stage", "Dates brutes", "Thème",
        "URL Convention PDF", "URL Signature", "Durée"
    )

    fun update(internships: List<Internship>, localPath: String) {
        val path = Paths.get(localPath)
        path.parent?.toFile()?.mkdirs()
        ExcelExporter.export(internships, path)
    }

    fun complement(internships: List<Internship>, localPath: String) {
        val file = File(localPath)
        val today = LocalDate.now()

        if (!file.exists()) {
            file.parentFile?.mkdirs()
            ExcelExporter.export(internships, file.toPath())
            return
        }

        val workbook = file.inputStream().use { WorkbookFactory.create(it) }
        try {
            complementMainSheet(workbook.getSheet("Tous les stages") ?: run {
                val s = workbook.createSheet("Tous les stages")
                writeHeaderRow(s)
                s
            }, internships)

            for ((name, rows) in rollingSheets(internships, today)) {
                val idx = workbook.getSheetIndex(name)
                if (idx >= 0) workbook.removeSheetAt(idx)
                val sheet = workbook.createSheet(name)
                writeHeaderRow(sheet)
                rows.forEachIndexed { i, s -> appendRow(sheet, i + 1, s) }
            }

            FileOutputStream(file).use { workbook.write(it) }
        } finally {
            workbook.close()
        }
    }

    private fun complementMainSheet(sheet: Sheet, internships: List<Internship>) {
        val existing = (1..sheet.lastRowNum).mapNotNull { rowIdx ->
            sheet.getRow(rowIdx)?.getCell(4)?.toString()?.trim()?.ifBlank { null }
        }.toHashSet()

        val toAppend = if (existing.isEmpty()) internships
            else internships.filter { s -> s.conventionNumber.isBlank() || s.conventionNumber !in existing }
        val startIdx = if (sheet.lastRowNum == 0 && sheet.getRow(0) == null) {
            writeHeaderRow(sheet); 1
        } else sheet.lastRowNum + 1
        toAppend.forEachIndexed { i, s -> appendRow(sheet, startIdx + i, s) }
    }

    private fun rollingSheets(internships: List<Internship>, today: LocalDate) = listOf(
        "Débuts 15 prochains jours" to internships.filter {
            it.startDate != null && !it.startDate.isBefore(today) && !it.startDate.isAfter(today.plusDays(15))
        },
        "Signés 15 derniers jours" to internships.filter {
            it.signingDate != null && !it.signingDate.isBefore(today.minusDays(15)) && !it.signingDate.isAfter(today)
        },
    )

    private fun writeHeaderRow(sheet: Sheet) {
        val row = sheet.createRow(0)
        HEADERS.forEachIndexed { i, h -> row.createCell(i).setCellValue(h) }
    }

    private fun appendRow(sheet: Sheet, rowIdx: Int, s: Internship) {
        val row = sheet.createRow(rowIdx)
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
        row.createCell(11).setCellValue(s.conventionPdfUrl)
        row.createCell(12).setCellValue(s.conventionSignUrl)
        row.createCell(13).setCellValue(s.durationLabel)
    }
}
