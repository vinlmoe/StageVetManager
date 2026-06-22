package fr.vetbrain.stagevetmanager.onedrive

import fr.vetbrain.stagevetmanager.export.ExcelExporter
import fr.vetbrain.stagevetmanager.model.Internship
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.net.URLEncoder
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.concurrent.TimeUnit

class OneDriveExcelUpdater(
    private val accessToken: String,
    private val graphBase: String = GRAPH_BASE,
) {

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .build()

    companion object {
        private const val GRAPH_BASE = "https://graph.microsoft.com/v1.0"
        private val JSON = "application/json".toMediaType()
        private val DATE_FMT = DateTimeFormatter.ofPattern("dd/MM/yyyy")
        private val HEADERS = listOf(
            "Étudiant", "Année", "Organisme", "Adresse",
            "Convention n°", "Conv. générée le", "Date signature",
            "Début stage", "Fin stage", "Dates brutes", "Thème",
            "URL Convention PDF", "URL Signature"
        )
        private val LAST_COL = ('A' + HEADERS.size - 1).toString()
    }

    /** Remplace intégralement les 3 feuilles du classeur. */
    fun update(internships: List<Internship>, remotePath: String) {
        val today = LocalDate.now()
        val sheetData = buildSheetData(internships, today)
        ensureFileExists(remotePath)
        val sessionId = createSession(remotePath)
        try {
            val existingSheets = listWorksheets(remotePath, sessionId)
            for ((name, rows) in sheetData) {
                if (name !in existingSheets) addWorksheet(remotePath, sessionId, name)
                clearAndWrite(remotePath, sessionId, name, rows)
            }
        } finally {
            closeSession(remotePath, sessionId)
        }
    }

    /**
     * Complète le classeur : ajoute uniquement les stages absents dans "Tous les stages"
     * (détection par numéro de convention, colonne E). Les feuilles glissantes sont
     * rafraîchies normalement car elles dépendent d'une fenêtre temporelle.
     */
    fun complement(internships: List<Internship>, remotePath: String) {
        val today = LocalDate.now()
        ensureFileExists(remotePath)
        val sessionId = createSession(remotePath)
        try {
            val existingSheets = listWorksheets(remotePath, sessionId)

            // Feuille principale : ajouter seulement les nouvelles lignes
            if ("Tous les stages" !in existingSheets) {
                addWorksheet(remotePath, sessionId, "Tous les stages")
                clearAndWrite(remotePath, sessionId, "Tous les stages", internships)
            } else {
                appendNewRows(remotePath, sessionId, "Tous les stages", internships)
            }

            // Feuilles glissantes : rafraîchissement complet (fenêtres temporelles)
            val rolling = linkedMapOf(
                "Débuts 15 prochains jours" to internships.filter {
                    it.startDate != null &&
                        !it.startDate.isBefore(today) &&
                        !it.startDate.isAfter(today.plusDays(15))
                },
                "Signés 15 derniers jours" to internships.filter {
                    it.signingDate != null &&
                        !it.signingDate.isBefore(today.minusDays(15)) &&
                        !it.signingDate.isAfter(today)
                },
            )
            for ((name, rows) in rolling) {
                if (name !in existingSheets) addWorksheet(remotePath, sessionId, name)
                clearAndWrite(remotePath, sessionId, name, rows)
            }
        } finally {
            closeSession(remotePath, sessionId)
        }
    }

    // ── Gestion du fichier ───────────────────────────────────────────────────

    private fun ensureFileExists(remotePath: String) {
        val req = Request.Builder()
            .url("$graphBase/me/drive/root:/${encPath(remotePath)}")
            .get().auth().build()
        val exists = client.newCall(req).execute().use { it.code != 404 }
        if (!exists) {
            val bytes = ExcelExporter.exportToBytes(emptyList())
            OneDriveUploader.upload(bytes, remotePath, accessToken)
        }
    }

    // ── Session ──────────────────────────────────────────────────────────────

    private fun createSession(remotePath: String): String {
        val req = Request.Builder()
            .url("$graphBase/me/drive/root:/${encPath(remotePath)}:/workbook/createSession")
            .post("""{"persistChanges":true}""".toRequestBody(JSON))
            .auth().build()
        return client.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) throw IOException("createSession: ${resp.code} ${resp.body?.string()}")
            extractJsonString(resp.body!!.string(), "id")
                ?: throw IOException("Pas d'ID de session dans la réponse")
        }
    }

    private fun closeSession(remotePath: String, sessionId: String) {
        val req = Request.Builder()
            .url("$graphBase/me/drive/root:/${encPath(remotePath)}:/workbook/closeSession")
            .post("".toRequestBody(JSON)).auth().session(sessionId).build()
        runCatching { client.newCall(req).execute().use { } }
    }

    // ── Feuilles ─────────────────────────────────────────────────────────────

    private fun listWorksheets(remotePath: String, sessionId: String): Set<String> {
        val req = Request.Builder()
            .url("$graphBase/me/drive/root:/${encPath(remotePath)}:/workbook/worksheets")
            .get().auth().session(sessionId).build()
        return client.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) return emptySet()
            Regex(""""name"\s*:\s*"([^"]+)"""")
                .findAll(resp.body!!.string())
                .map { it.groupValues[1] }
                .toSet()
        }
    }

    private fun addWorksheet(remotePath: String, sessionId: String, name: String) {
        val req = Request.Builder()
            .url("$graphBase/me/drive/root:/${encPath(remotePath)}:/workbook/worksheets/add")
            .post("""{"name":"${name.jsonEscape()}"}""".toRequestBody(JSON))
            .auth().session(sessionId).build()
        client.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful)
                throw IOException("addWorksheet '$name': ${resp.code} ${resp.body?.string()}")
        }
    }

    // ── Données : remplacement complet ───────────────────────────────────────

    private fun clearAndWrite(
        remotePath: String,
        sessionId: String,
        sheetName: String,
        internships: List<Internship>,
    ) {
        val base = "$graphBase/me/drive/root:/${encPath(remotePath)}:/workbook/worksheets('${enc(sheetName)}')"

        val clearReq = Request.Builder()
            .url("$base/range(address='A1:${LAST_COL}5000')/clear")
            .post("""{"applyTo":"All"}""".toRequestBody(JSON))
            .auth().session(sessionId).build()
        runCatching { client.newCall(clearReq).execute().use { } }

        val rows = buildList {
            add(HEADERS)
            internships.forEach { s -> add(internshipRow(s)) }
        }
        val writeReq = Request.Builder()
            .url("$base/range(address='A1:${LAST_COL}${rows.size}')")
            .patch(buildValuesBody(rows).toRequestBody(JSON))
            .auth().session(sessionId).build()
        client.newCall(writeReq).execute().use { resp ->
            if (!resp.isSuccessful)
                throw IOException("write '$sheetName': ${resp.code} ${resp.body?.string()}")
        }
    }

    // ── Données : ajout incrémental ──────────────────────────────────────────

    private fun appendNewRows(
        remotePath: String,
        sessionId: String,
        sheetName: String,
        internships: List<Internship>,
    ) {
        val base = "$graphBase/me/drive/root:/${encPath(remotePath)}:/workbook/worksheets('${enc(sheetName)}')"

        // 1. Nombre de lignes actuellement utilisées
        val rowCount = runCatching {
            client.newCall(
                Request.Builder().url("$base/usedRange?\$select=rowCount")
                    .get().auth().session(sessionId).build()
            ).execute().use { r ->
                if (!r.isSuccessful) null
                else Regex(""""rowCount"\s*:\s*(\d+)""")
                    .find(r.body!!.string())?.groupValues?.get(1)?.toIntOrNull()
            }
        }.getOrNull()

        // Feuille vide ou inaccessible → écriture complète avec en-tête
        if (rowCount == null || rowCount == 0) {
            clearAndWrite(remotePath, sessionId, sheetName, internships)
            return
        }

        // 2. Lire les numéros de convention existants (colonne E = "Convention n°")
        //    Regex sur tableaux mono-cellule : [\"val\"]
        val existingKeys = runCatching {
            client.newCall(
                Request.Builder().url("$base/range(address='E1:E$rowCount')")
                    .get().auth().session(sessionId).build()
            ).execute().use { r ->
                if (!r.isSuccessful) emptySet()
                else Regex("""\["([^"]*?)"\]""")
                    .findAll(r.body!!.string())
                    .map { it.groupValues[1] }
                    .filter { it.isNotBlank() && it != "Convention n°" }
                    .toHashSet()
            }
        }.getOrDefault(emptySet<String>())

        // 3. Filtrer : garder uniquement les stages absents du tableau
        val newStages = internships.filter { s ->
            // Si on n'a pas pu lire les clés existantes → on ajoute tout (safe)
            if (existingKeys.isEmpty()) return@filter true
            // Convention n° vide → pas de clé fiable, on l'ajoute
            if (s.conventionNumber.isBlank()) return@filter true
            s.conventionNumber !in existingKeys
        }
        if (newStages.isEmpty()) return

        // 4. Écrire après la dernière ligne utilisée
        val startRow = rowCount + 1
        val endRow   = startRow + newStages.size - 1
        client.newCall(
            Request.Builder()
                .url("$base/range(address='A$startRow:${LAST_COL}$endRow')")
                .patch(buildValuesBody(newStages.map { internshipRow(it) }).toRequestBody(JSON))
                .auth().session(sessionId).build()
        ).execute().use { resp ->
            if (!resp.isSuccessful)
                throw IOException("appendNewRows '$sheetName': ${resp.code} ${resp.body?.string()}")
        }
    }

    // ── Utilitaires ──────────────────────────────────────────────────────────

    private fun buildSheetData(
        internships: List<Internship>,
        today: LocalDate,
    ) = linkedMapOf(
        "Tous les stages" to internships,
        "Débuts 15 prochains jours" to internships.filter {
            it.startDate != null &&
                !it.startDate.isBefore(today) &&
                !it.startDate.isAfter(today.plusDays(15))
        },
        "Signés 15 derniers jours" to internships.filter {
            it.signingDate != null &&
                !it.signingDate.isBefore(today.minusDays(15)) &&
                !it.signingDate.isAfter(today)
        },
    )

    private fun internshipRow(s: Internship) = listOf(
        s.studentName, s.studyYear, s.organization, s.address,
        s.conventionNumber, s.conventionGenDate,
        s.signingDate?.format(DATE_FMT) ?: "",
        s.startDate?.format(DATE_FMT) ?: "",
        s.endDate?.format(DATE_FMT) ?: "",
        s.rawDateStage, s.theme,
        s.conventionPdfUrl, s.conventionSignUrl,
    )

    private fun Request.Builder.auth() = header("Authorization", "Bearer $accessToken")
    private fun Request.Builder.session(id: String) = header("workbook-session-id", id)

    internal fun encPath(path: String) = path.split("/").joinToString("/") { enc(it) }
    internal fun enc(s: String) = URLEncoder.encode(s, "UTF-8").replace("+", "%20")
    private fun String.jsonEscape() = replace("\\", "\\\\").replace("\"", "\\\"")
        .replace("\n", " ").replace("\r", "")

    internal fun extractJsonString(json: String, key: String): String? =
        Regex(""""$key"\s*:\s*"([^"]+)"""").find(json)?.groupValues?.get(1)

    internal fun buildValuesBody(rows: List<List<String>>): String {
        val sb = StringBuilder("""{"values":[""")
        rows.forEachIndexed { i, row ->
            if (i > 0) sb.append(',')
            sb.append('[')
            row.forEachIndexed { j, cell ->
                if (j > 0) sb.append(',')
                sb.append('"').append(cell.jsonEscape()).append('"')
            }
            sb.append(']')
        }
        return sb.append("]}").toString()
    }
}
