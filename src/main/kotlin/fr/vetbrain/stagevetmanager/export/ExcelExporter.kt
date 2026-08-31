package fr.vetbrain.stagevetmanager.export

import fr.vetbrain.stagevetmanager.model.ConventionPdfData
import fr.vetbrain.stagevetmanager.model.Internship
import org.apache.poi.ss.usermodel.*
import org.apache.poi.xssf.usermodel.XSSFWorkbook
import java.io.ByteArrayOutputStream
import java.io.FileOutputStream
import java.nio.file.Path
import java.time.LocalDate
import java.time.format.DateTimeFormatter

object ExcelExporter {

    private val DATE_FMT = DateTimeFormatter.ofPattern("dd/MM/yyyy")
    val HEADERS = listOf(
        "Étudiant", "Année", "Organisme", "Adresse",
        "Convention n°", "Conv. générée le", "Date signature",
        "Début stage", "Fin stage", "Dates brutes", "Thème",
        "URL Convention PDF", "URL Signature", "Durée", "Email étudiant"
    )

    val CLINIC_HEADERS = listOf(
        "Organisme", "Adresse", "Nb stages", "Email(s)", "Étudiants"
    )

    fun export(internships: List<Internship>, path: Path, pdfDataCache: Map<String, ConventionPdfData> = emptyMap()) {
        val wb = buildWorkbook(internships, pdfDataCache)
        FileOutputStream(path.toFile()).use { wb.write(it) }
        wb.close()
    }

    fun exportToBytes(internships: List<Internship>, pdfDataCache: Map<String, ConventionPdfData> = emptyMap()): ByteArray {
        val wb = buildWorkbook(internships, pdfDataCache)
        val out = ByteArrayOutputStream()
        wb.write(out)
        wb.close()
        return out.toByteArray()
    }

    private fun buildWorkbook(internships: List<Internship>, pdfDataCache: Map<String, ConventionPdfData> = emptyMap()): XSSFWorkbook {
        val wb = XSSFWorkbook()
        val today = LocalDate.now()

        val startingSoon = internships.filter {
            it.startDate != null && !it.startDate.isBefore(today) && !it.startDate.isAfter(today.plusDays(15))
        }
        val recentlySigned = internships.filter {
            it.signingDate != null && !it.signingDate.isBefore(today.minusDays(15)) && !it.signingDate.isAfter(today)
        }

        writeSheet(wb, "Tous les stages", internships, pdfDataCache)
        writeSheet(wb, "Débuts 15 prochains jours", startingSoon, pdfDataCache)
        writeSheet(wb, "Signés 15 derniers jours", recentlySigned, pdfDataCache)
        writeClinicSheet(wb, internships, pdfDataCache)
        return wb
    }

    private fun writeSheet(
        wb: XSSFWorkbook,
        name: String,
        rows: List<Internship>,
        pdfDataCache: Map<String, ConventionPdfData>,
    ) {
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

        val headers = HEADERS

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
            row.createCell(11).setCellValue(s.conventionPdfUrl)
            row.createCell(12).setCellValue(s.conventionSignUrl)
            row.createCell(13).setCellValue(s.durationLabel)
            row.createCell(14).setCellValue(pdfDataCache[s.conventionPdfUrl]?.studentEmail.orEmpty())
        }

        headers.indices.forEach { sheet.autoSizeColumn(it) }
    }

    private fun writeClinicSheet(
        wb: XSSFWorkbook,
        internships: List<Internship>,
        pdfDataCache: Map<String, ConventionPdfData>,
    ) {
        val sheet = wb.createSheet("Cliniques")

        val headerStyle = wb.createCellStyle().apply {
            fillForegroundColor = IndexedColors.CORNFLOWER_BLUE.index
            fillPattern = FillPatternType.SOLID_FOREGROUND
            setFont(wb.createFont().apply {
                bold = true
                color = IndexedColors.WHITE.index
            })
        }

        val headerRow = sheet.createRow(0)
        CLINIC_HEADERS.forEachIndexed { i, h ->
            headerRow.createCell(i).apply {
                setCellValue(h)
                cellStyle = headerStyle
            }
        }

        val clinics = internships
            .filter { it.organization.isNotBlank() }
            .groupBy { it.organization.trim() }
            .entries
            .sortedBy { it.key }

        clinics.forEachIndexed { rowIdx, (org, stages) ->
            val address = stages.firstOrNull { it.address.isNotBlank() }?.address ?: ""
            val emails = stages
                .mapNotNull { pdfDataCache[it.conventionPdfUrl]?.hostEmail }
                .filter { it.isNotBlank() }
                .distinct()
                .joinToString(", ")
            val students = stages.map { it.studentName }.distinct().sorted().joinToString(", ")

            val row = sheet.createRow(rowIdx + 1)
            row.createCell(0).setCellValue(org)
            row.createCell(1).setCellValue(address)
            row.createCell(2).setCellValue(stages.size.toDouble())
            row.createCell(3).setCellValue(emails)
            row.createCell(4).setCellValue(students)
        }

        CLINIC_HEADERS.indices.forEach { sheet.autoSizeColumn(it) }
    }
}
