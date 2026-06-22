package fr.vetbrain.stagevetmanager.onedrive

import fr.vetbrain.stagevetmanager.model.Internship
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

data class TrackingUpdateResult(
    val matched: Int,
    val warnings: List<String>,
)

/**
 * Updates "Lieu et date" and "Durée" columns in a student×theme tracking grid on OneDrive/SharePoint.
 * Cells that cannot be matched are left untouched; unmatched entries are returned as warnings.
 */
class OneDriveTrackingUpdater(
    private val accessToken: String,
    private val graphBase: String = "https://graph.microsoft.com/v1.0",
) {
    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .build()

    private val JSON = "application/json".toMediaType()

    private data class ThemeGroup(val name: String, val colLieuDate: Int, val colDuree: Int)
    private data class StudentRowInfo(val nom: String, val prenom: String, val rowIndex: Int)

    fun update(internships: List<Internship>, remotePath: String): TrackingUpdateResult {
        val sessionId = createSession(remotePath)
        try {
            val sheetName = findTrackingSheet(remotePath, sessionId)
                ?: throw IOException("Aucune feuille de suivi trouvée dans $remotePath (cherche un onglet contenant « validation » ou « suivi »)")
            return updateWithSession(internships, remotePath, sheetName, sessionId)
        } finally {
            closeSession(remotePath, sessionId)
        }
    }

    private fun findTrackingSheet(remotePath: String, sessionId: String): String? {
        val req = Request.Builder()
            .url("$graphBase/me/drive/root:/${encPath(remotePath)}:/workbook/worksheets")
            .get().auth().session(sessionId).build()
        val body = client.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) return null
            resp.body!!.string()
        }
        val names = Regex(""""name"\s*:\s*"([^"]+)"""")
            .findAll(body).map { it.groupValues[1] }.toList()
        return names.firstOrNull { n ->
            val low = n.lowercase()
            low.contains("validation") || low.contains("suivi")
        } ?: names.firstOrNull()
    }

    private fun updateWithSession(
        internships: List<Internship>,
        remotePath: String,
        sheetName: String,
        sessionId: String,
    ): TrackingUpdateResult {
        val base = "$graphBase/me/drive/root:/${encPath(remotePath)}:/workbook/worksheets('${enc(sheetName)}')"

        val rangeJson = client.newCall(
            Request.Builder()
                .url("$base/usedRange?\$select=values")
                .get().auth().session(sessionId).build()
        ).execute().use { resp ->
            if (!resp.isSuccessful) throw IOException("Lecture de la plage du tableau : ${resp.code} ${resp.body?.string()}")
            resp.body!!.string()
        }

        val values = parseValues2D(rangeJson)
        if (values.size < 3) throw IOException("Le tableau de suivi semble vide ou mal formaté (< 3 lignes)")

        val themeHeaderRow = values[0]
        val subHeaderRow   = values[1]

        // Build theme groups: scan row 0 for non-empty cells at col ≥ 4 (first 4 cols = NOM/Prénom/ER)
        val themeGroups = mutableListOf<ThemeGroup>()
        var col = 4
        while (col < themeHeaderRow.size) {
            val header = themeHeaderRow[col].trim()
            if (header.isNotEmpty()) {
                var colLieu = -1; var colDuree = -1
                for (offset in 0..5) {
                    val subH = subHeaderRow.getOrNull(col + offset)?.trim()?.lowercase() ?: break
                    when {
                        subH.contains("lieu") -> colLieu = col + offset
                        subH.contains("dur")  -> colDuree = col + offset
                    }
                }
                if (colLieu >= 0 && colDuree >= 0) {
                    themeGroups.add(ThemeGroup(header, colLieu, colDuree))
                }
            }
            col++
        }

        if (themeGroups.isEmpty()) throw IOException("Aucun groupe de thème trouvé dans les en-têtes du tableau")

        // Build student row index: rows starting at index 2 (row 3 in Excel)
        val studentRows = (2 until values.size).mapNotNull { rowIdx ->
            val row   = values[rowIdx]
            val nom   = row.getOrNull(0)?.trim() ?: ""
            val prenom = row.getOrNull(1)?.trim() ?: ""
            if (nom.isNotEmpty()) StudentRowInfo(nom, prenom, rowIdx) else null
        }

        val warnings = mutableListOf<String>()
        var matched = 0

        for (s in internships) {
            val studentRow = findStudentRow(s.studentName, studentRows)
            if (studentRow == null) {
                warnings.add("Étudiant introuvable dans le tableau : ${s.studentName}")
                continue
            }
            val themeGroup = findThemeGroup(s.theme, themeGroups)
            if (themeGroup == null) {
                warnings.add("Thème introuvable pour ${s.studentName} : « ${s.theme} »")
                continue
            }

            val excelRow = studentRow.rowIndex + 1  // 1-based Excel row number
            val lieutDate = buildLieuDate(s)

            val ok1 = runCatching { writeCell(remotePath, sessionId, sheetName, themeGroup.colLieuDate, excelRow, lieutDate) }
            val ok2 = if (s.durationLabel.isNotEmpty())
                runCatching { writeCell(remotePath, sessionId, sheetName, themeGroup.colDuree, excelRow, s.durationLabel) }
            else Result.success(Unit)

            val failure = ok1.exceptionOrNull() ?: ok2.exceptionOrNull()
            if (failure != null) {
                warnings.add("Erreur écriture pour ${s.studentName} / ${s.theme} : ${failure.message}")
            } else {
                matched++
            }
        }

        return TrackingUpdateResult(matched, warnings)
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
        return groups.firstOrNull { tg ->
            val groupNorm = tg.name.trim().lowercase()
            themeNorm.contains(groupNorm) || groupNorm.contains(themeNorm)
        }
    }

    private fun writeCell(
        remotePath: String,
        sessionId: String,
        sheetName: String,
        colIndex: Int,
        excelRow: Int,
        value: String,
    ) {
        val addr = "${colIndexToLetter(colIndex)}$excelRow"
        val base = "$graphBase/me/drive/root:/${encPath(remotePath)}:/workbook/worksheets('${enc(sheetName)}')"
        val body = """{"values":[[${value.jsonQuote()}]]}"""
        client.newCall(
            Request.Builder()
                .url("$base/range(address='$addr')")
                .patch(body.toRequestBody(JSON))
                .auth().session(sessionId).build()
        ).execute().use { resp ->
            if (!resp.isSuccessful)
                throw IOException("Écriture cellule $addr : ${resp.code} ${resp.body?.string()}")
        }
    }

    // ── Session ──────────────────────────────────────────────────────────────

    private fun createSession(remotePath: String): String {
        val req = Request.Builder()
            .url("$graphBase/me/drive/root:/${encPath(remotePath)}:/workbook/createSession")
            .post("""{"persistChanges":true}""".toRequestBody(JSON))
            .auth().build()
        return client.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) throw IOException("createSession : ${resp.code} ${resp.body?.string()}")
            Regex(""""id"\s*:\s*"([^"]+)"""").find(resp.body!!.string())?.groupValues?.get(1)
                ?: throw IOException("Pas d'ID de session dans la réponse")
        }
    }

    private fun closeSession(remotePath: String, sessionId: String) {
        runCatching {
            client.newCall(
                Request.Builder()
                    .url("$graphBase/me/drive/root:/${encPath(remotePath)}:/workbook/closeSession")
                    .post("".toRequestBody(JSON)).auth().session(sessionId).build()
            ).execute().use { }
        }
    }

    // ── Utilities ─────────────────────────────────────────────────────────────

    private fun Request.Builder.auth() = header("Authorization", "Bearer $accessToken")
    private fun Request.Builder.session(id: String) = header("workbook-session-id", id)

    internal fun encPath(path: String) = path.split("/").joinToString("/") { enc(it) }
    internal fun enc(s: String) = URLEncoder.encode(s, "UTF-8").replace("+", "%20")

    internal fun colIndexToLetter(col: Int): String {
        var n = col
        val sb = StringBuilder()
        while (n >= 0) {
            sb.insert(0, ('A' + n % 26))
            n = n / 26 - 1
        }
        return sb.toString()
    }

    private fun String.jsonQuote(): String {
        val sb = StringBuilder("\"")
        for (c in this) {
            when (c) {
                '"'  -> sb.append("\\\"")
                '\\' -> sb.append("\\\\")
                '\n' -> sb.append("\\n")
                '\r' -> sb.append("\\r")
                '\t' -> sb.append("\\t")
                else -> sb.append(c)
            }
        }
        return sb.append('"').toString()
    }

    /**
     * Parses the "values" 2-D array from a Microsoft Graph workbook range JSON response.
     * null cells become empty strings; numbers and booleans are stringified.
     */
    internal fun parseValues2D(json: String): List<List<String>> {
        val valuesIdx = json.indexOf("\"values\"")
        if (valuesIdx < 0) return emptyList()
        var i = json.indexOf('[', valuesIdx)
        if (i < 0) return emptyList()
        i++  // skip outer '['

        val result = mutableListOf<List<String>>()
        while (i < json.length) {
            while (i < json.length && json[i].isWhitespace()) i++
            if (i >= json.length || json[i] == ']') break
            if (json[i] == '[') {
                val (row, next) = parseRow(json, i)
                result.add(row)
                i = next
            } else {
                i++
            }
        }
        return result
    }

    private fun parseRow(json: String, start: Int): Pair<List<String>, Int> {
        var i = start + 1
        val cells = mutableListOf<String>()
        while (i < json.length) {
            while (i < json.length && json[i].isWhitespace()) i++
            if (i >= json.length) break
            when {
                json[i] == ']' -> return cells to (i + 1)
                json[i] == ',' -> i++
                json[i] == '"' -> {
                    val sb = StringBuilder()
                    i++
                    while (i < json.length && json[i] != '"') {
                        if (json[i] == '\\' && i + 1 < json.length) {
                            when (json[i + 1]) {
                                '"'  -> sb.append('"')
                                '\\' -> sb.append('\\')
                                'n'  -> sb.append('\n')
                                'r'  -> sb.append('\r')
                                't'  -> sb.append('\t')
                                else -> sb.append(json[i + 1])
                            }
                            i += 2
                        } else {
                            sb.append(json[i])
                            i++
                        }
                    }
                    cells.add(sb.toString())
                    if (i < json.length) i++  // skip closing '"'
                }
                json.startsWith("null",  i) -> { cells.add(""); i += 4 }
                json.startsWith("true",  i) -> { cells.add("true"); i += 4 }
                json.startsWith("false", i) -> { cells.add("false"); i += 5 }
                json[i].isDigit() || json[i] == '-' -> {
                    var end = i + 1
                    while (end < json.length && (json[end].isDigit() || json[end] == '.' ||
                                json[end] == 'e' || json[end] == 'E' ||
                                json[end] == '+' || json[end] == '-')) end++
                    cells.add(json.substring(i, end))
                    i = end
                }
                else -> i++
            }
        }
        return cells to i
    }
}
