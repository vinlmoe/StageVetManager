package fr.vetbrain.stagevetmanager.export

import fr.vetbrain.stagevetmanager.model.ConventionPdfData
import fr.vetbrain.stagevetmanager.model.Internship
import org.apache.poi.ss.usermodel.Cell
import org.apache.poi.ss.usermodel.CellType
import org.apache.poi.ss.usermodel.DataFormatter
import org.apache.poi.ss.usermodel.Sheet
import org.apache.poi.ss.usermodel.WorkbookFactory
import java.io.File
import java.nio.file.Paths
import java.time.LocalDate
import java.time.format.DateTimeFormatter

object LocalExcelUpdater {

    private val DATE_FMT = DateTimeFormatter.ofPattern("dd/MM/yyyy")
    private val DATA_FORMATTER = DataFormatter()

    fun update(
        internships: List<Internship>,
        localPath: String,
        pdfDataCache: Map<String, ConventionPdfData> = emptyMap(),
    ) {
        val path = Paths.get(localPath)
        path.parent?.toFile()?.mkdirs()
        ExcelExporter.export(internships, path, pdfDataCache)
    }

    fun complement(
        internships: List<Internship>,
        localPath: String,
        pdfDataCache: Map<String, ConventionPdfData> = emptyMap(),
    ) {
        val file = File(localPath)
        val today = LocalDate.now()

        if (!file.exists()) {
            file.parentFile?.mkdirs()
            ExcelExporter.export(internships, file.toPath(), pdfDataCache)
            return
        }

        val workbook = file.inputStream().use { WorkbookFactory.create(it) }
        try {
            complementMainSheet(workbook.getSheet("Tous les stages") ?: run {
                val s = workbook.createSheet("Tous les stages")
                writeHeaderRow(s)
                s
            }, internships, pdfDataCache)

            for ((name, rows) in rollingSheets(internships, today)) {
                val idx = workbook.getSheetIndex(name)
                if (idx >= 0) workbook.removeSheetAt(idx)
                val sheet = workbook.createSheet(name)
                writeHeaderRow(sheet)
                rows.forEachIndexed { i, s -> appendRow(sheet, i + 1, s, pdfDataCache) }
            }

            SafeFileWrite.replace(file) { out -> workbook.write(out) }
        } finally {
            workbook.close()
        }
    }

    private fun complementMainSheet(
        sheet: Sheet,
        internships: List<Internship>,
        pdfDataCache: Map<String, ConventionPdfData>,
    ) {
        writeHeaderRow(sheet)
        (1..sheet.lastRowNum).forEach { rowIdx ->
            val row = sheet.getRow(rowIdx) ?: return@forEach
            val pdfUrl = cellText(row.getCell(11))
            row.createCell(14).setCellValue(pdfDataCache[pdfUrl]?.studentEmail.orEmpty())
        }
        val existing = (1..sheet.lastRowNum).mapNotNull { rowIdx ->
            cellText(sheet.getRow(rowIdx)?.getCell(4)).ifBlank { null }
        }.toHashSet()

        val toAppend = if (existing.isEmpty()) internships
            else internships.filter { s ->
                s.conventionNumber.isBlank() || normalizeKey(s.conventionNumber) !in existing
            }
        val startIdx = sheet.lastRowNum + 1
        toAppend.forEachIndexed { i, s -> appendRow(sheet, startIdx + i, s, pdfDataCache) }
    }

    /**
     * Lecture textuelle d'une cellule indépendante de son type.
     *
     * `Cell.toString()` renvoie "12345.0" pour une cellule numérique : la
     * déduplication par numéro de convention échouait donc dès que le classeur
     * stockait ces numéros en numérique, et chaque exécution réajoutait toutes
     * les lignes.
     */
    private fun cellText(cell: Cell?): String {
        if (cell == null) return ""
        val raw = when (cell.cellType) {
            CellType.FORMULA -> runCatching { DATA_FORMATTER.formatCellValue(cell) }
                .getOrElse { cell.toString() }
            else -> DATA_FORMATTER.formatCellValue(cell)
        }
        return normalizeKey(raw)
    }

    /** Supprime un éventuel ".0" résiduel et les espaces insécables. */
    private fun normalizeKey(value: String): String =
        value.trim().replace(' ', ' ').trim().removeSuffix(".0")

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
        ExcelExporter.HEADERS.forEachIndexed { i, h -> row.createCell(i).setCellValue(h) }
    }

    private fun appendRow(
        sheet: Sheet,
        rowIdx: Int,
        s: Internship,
        pdfDataCache: Map<String, ConventionPdfData>,
    ) {
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
        row.createCell(14).setCellValue(pdfDataCache[s.conventionPdfUrl]?.studentEmail.orEmpty())
    }
}
