package fr.vetbrain.stagevetmanager.export

import fr.vetbrain.stagevetmanager.model.Internship
import fr.vetbrain.stagevetmanager.model.TrackingUpdateResult
import org.apache.poi.ss.usermodel.Row
import org.apache.poi.ss.usermodel.WorkbookFactory
import java.io.File
import java.io.FileNotFoundException

class LocalTrackingUpdater {

    private data class ThemeGroup(val name: String, val colLieuDate: Int, val colDuree: Int)
    private data class StudentRowInfo(val nom: String, val prenom: String, val rowIndex: Int)

    fun update(internships: List<Internship>, localPath: String): TrackingUpdateResult {
        val file = File(localPath)
        if (!file.exists()) throw FileNotFoundException("Fichier introuvable : $localPath")

        val workbook = file.inputStream().use { WorkbookFactory.create(it) }
        try {
            val sheet = (0 until workbook.numberOfSheets)
                .map { workbook.getSheetAt(it) }
                .firstOrNull { s ->
                    val name = s.sheetName.lowercase()
                    name.contains("validation") || name.contains("suivi")
                } ?: workbook.getSheetAt(0)

            if (sheet.lastRowNum < 2)
                throw IllegalStateException("Le tableau de suivi semble vide (< 3 lignes) dans ${file.name}")

            val headerRow0 = sheet.getRow(0)
                ?: throw IllegalStateException("Pas de ligne d'en-tête dans ${sheet.sheetName}")
            val headerRow1 = sheet.getRow(1)

            fun cellStr(row: Row?, col: Int) = row?.getCell(col)?.toString()?.trim() ?: ""

            val themeGroups = mutableListOf<ThemeGroup>()
            var col = 4
            val lastCol = headerRow0.lastCellNum.toInt()
            while (col < lastCol) {
                val header = cellStr(headerRow0, col)
                if (header.isNotEmpty()) {
                    var colLieu = -1; var colDuree = -1
                    for (offset in 0..5) {
                        // Le balayage s'arrêtait uniquement sur un sous-en-tête vide :
                        // avec des groupes contigus il débordait sur le thème suivant et
                        // tous les groupes finissaient par pointer les mêmes colonnes.
                        if (offset > 0 && cellStr(headerRow0, col + offset).isNotEmpty()) break
                        val subH = cellStr(headerRow1, col + offset).lowercase()
                        if (subH.isEmpty()) break
                        when {
                            subH.contains("lieu") -> if (colLieu < 0) colLieu = col + offset
                            subH.contains("dur")  -> if (colDuree < 0) colDuree = col + offset
                        }
                    }
                    if (colLieu >= 0 && colDuree >= 0)
                        themeGroups.add(ThemeGroup(header, colLieu, colDuree))
                }
                col++
            }

            if (themeGroups.isEmpty())
                throw IllegalStateException("Aucun groupe thème/sous-en-tête (Lieu+Durée) trouvé dans ${sheet.sheetName}")

            val studentRows = (2..sheet.lastRowNum).mapNotNull { rowIdx ->
                val row = sheet.getRow(rowIdx) ?: return@mapNotNull null
                val nom = cellStr(row, 0)
                val prenom = cellStr(row, 1)
                if (nom.isNotEmpty()) StudentRowInfo(nom, prenom, rowIdx) else null
            }

            val warnings = mutableListOf<String>()
            var matched = 0

            for (s in internships) {
                val studentRow = findStudentRow(s.studentName, studentRows)
                if (studentRow == null) {
                    warnings.add("Étudiant introuvable : ${s.studentName}")
                    continue
                }
                val themeGroup = findThemeGroup(s.theme, themeGroups)
                if (themeGroup == null) {
                    warnings.add("Thème introuvable pour ${s.studentName} : « ${s.theme} »")
                    continue
                }

                val row = sheet.getRow(studentRow.rowIndex) ?: sheet.createRow(studentRow.rowIndex)
                val lieuDate = buildLieuDate(s)
                (row.getCell(themeGroup.colLieuDate) ?: row.createCell(themeGroup.colLieuDate))
                    .setCellValue(lieuDate)
                if (s.durationLabel.isNotEmpty()) {
                    (row.getCell(themeGroup.colDuree) ?: row.createCell(themeGroup.colDuree))
                        .setCellValue(s.durationLabel)
                }
                matched++
            }

            SafeFileWrite.replace(file) { out -> workbook.write(out) }
            return TrackingUpdateResult(matched, warnings)
        } finally {
            workbook.close()
        }
    }

    private fun buildLieuDate(s: Internship): String {
        val dates = s.rawDateStage.trim()
        return if (dates.isNotEmpty()) "${s.organization} — $dates" else s.organization
    }

    private fun findStudentRow(studentName: String, rows: List<StudentRowInfo>): StudentRowInfo? {
        val nameNorm = studentName.trim().lowercase()
        return rows.firstOrNull { sr ->
            val combined = "${sr.nom} ${sr.prenom}".trim().lowercase()
            combined == nameNorm ||
                (nameNorm.contains(sr.nom.trim().lowercase()) &&
                 sr.nom.trim().length >= 2 &&
                 nameNorm.contains(sr.prenom.trim().lowercase()) &&
                 sr.prenom.trim().length >= 2)
        }
    }

    private fun findThemeGroup(theme: String, groups: List<ThemeGroup>): ThemeGroup? {
        val themeNorm = theme.trim().lowercase()
        // Sans ce garde, `groupNorm.contains("")` est toujours vrai : un stage dont le
        // thème n'a pas pu être parsé était écrit silencieusement dans le premier
        // groupe de colonnes du tableau. Mieux vaut un warning « thème introuvable ».
        if (themeNorm.isEmpty()) return null
        return groups.firstOrNull { tg ->
            val groupNorm = tg.name.trim().lowercase()
            if (groupNorm.isEmpty()) return@firstOrNull false
            themeNorm.contains(groupNorm) || groupNorm.contains(themeNorm)
        }
    }
}
