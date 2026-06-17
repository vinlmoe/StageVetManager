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
        private val LAST_COL = ('A' + HEADERS.size - 1).toString() // "K"
    }

    /** Met à jour les 3 feuilles du classeur OneDrive existant via une session Graph. */
    fun update(internships: List<Internship>, remotePath: String) {
        val today = LocalDate.now()
        val sheetData = linkedMapOf(
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

    // ── Gestion du fichier ───────────────────────────────────────────────────

    private fun ensureFileExists(remotePath: String) {
        val req = Request.Builder()
            .url("$graphBase/me/drive/root:/${encPath(remotePath)}")
            .get()
            .auth()
            .build()
        val exists = client.newCall(req).execute().use { it.code != 404 }
        if (!exists) {
            // Créer un classeur vide avec les bonnes feuilles pour la première fois
            val bytes = ExcelExporter.exportToBytes(emptyList())
            OneDriveUploader.upload(bytes, remotePath, accessToken)
        }
    }

    // ── Session ──────────────────────────────────────────────────────────────

    private fun createSession(remotePath: String): String {
        val req = Request.Builder()
            .url("$graphBase/me/drive/root:/${encPath(remotePath)}:/workbook/createSession")
            .post("""{"persistChanges":true}""".toRequestBody(JSON))
            .auth()
            .build()
        return client.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) throw IOException("createSession: ${resp.code} ${resp.body?.string()}")
            extractJsonString(resp.body!!.string(), "id")
                ?: throw IOException("Pas d'ID de session dans la réponse")
        }
    }

    private fun closeSession(remotePath: String, sessionId: String) {
        val req = Request.Builder()
            .url("$graphBase/me/drive/root:/${encPath(remotePath)}:/workbook/closeSession")
            .post("".toRequestBody(JSON))
            .auth()
            .session(sessionId)
            .build()
        runCatching { client.newCall(req).execute().use { } }
    }

    // ── Feuilles ─────────────────────────────────────────────────────────────

    private fun listWorksheets(remotePath: String, sessionId: String): Set<String> {
        val req = Request.Builder()
            .url("$graphBase/me/drive/root:/${encPath(remotePath)}:/workbook/worksheets")
            .get()
            .auth()
            .session(sessionId)
            .build()
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
            .auth()
            .session(sessionId)
            .build()
        client.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful)
                throw IOException("addWorksheet '$name': ${resp.code} ${resp.body?.string()}")
        }
    }

    // ── Données ──────────────────────────────────────────────────────────────

    private fun clearAndWrite(
        remotePath: String,
        sessionId: String,
        sheetName: String,
        internships: List<Internship>,
    ) {
        val base = "$graphBase/me/drive/root:/${encPath(remotePath)}:/workbook/worksheets('${enc(sheetName)}')"

        // 1. Effacer les données existantes (large range pour supprimer les anciennes lignes)
        val clearReq = Request.Builder()
            .url("$base/range(address='A1:${LAST_COL}5000')/clear")
            .post("""{"applyTo":"All"}""".toRequestBody(JSON))
            .auth().session(sessionId).build()
        runCatching { client.newCall(clearReq).execute().use { } }

        // 2. Construire les lignes : en-tête + données
        val rows = buildList {
            add(HEADERS)
            internships.forEach { s ->
                add(listOf(
                    s.studentName, s.studyYear, s.organization, s.address,
                    s.conventionNumber, s.conventionGenDate,
                    s.signingDate?.format(DATE_FMT) ?: "",
                    s.startDate?.format(DATE_FMT) ?: "",
                    s.endDate?.format(DATE_FMT) ?: "",
                    s.rawDateStage, s.theme,
                    s.conventionPdfUrl, s.conventionSignUrl,
                ))
            }
        }

        // 3. Écrire dans la plage exacte
        val rangeAddr = "A1:${LAST_COL}${rows.size}"
        val writeReq = Request.Builder()
            .url("$base/range(address='$rangeAddr')")
            .patch(buildValuesBody(rows).toRequestBody(JSON))
            .auth().session(sessionId).build()

        client.newCall(writeReq).execute().use { resp ->
            if (!resp.isSuccessful)
                throw IOException("write '$sheetName': ${resp.code} ${resp.body?.string()}")
        }
    }

    // ── Utilitaires ──────────────────────────────────────────────────────────

    private fun Request.Builder.auth() = header("Authorization", "Bearer $accessToken")
    private fun Request.Builder.session(id: String) = header("workbook-session-id", id)

    /** Encode un chemin en préservant les '/' comme séparateurs. */
    internal fun encPath(path: String) = path.split("/").joinToString("/") { enc(it) }

    /** Encode un segment d'URL (espace → %20). */
    internal fun enc(s: String) = URLEncoder.encode(s, "UTF-8").replace("+", "%20")

    /** Échappe les caractères spéciaux JSON dans une chaîne. */
    private fun String.jsonEscape() = replace("\\", "\\\\").replace("\"", "\\\"")
        .replace("\n", " ").replace("\r", "")

    /** Extrait la valeur d'une clé string dans un JSON simple (regex légère). */
    internal fun extractJsonString(json: String, key: String): String? =
        Regex(""""$key"\s*:\s*"([^"]+)"""").find(json)?.groupValues?.get(1)

    /** Construit le corps JSON {"values":[[...],[...],...]}. */
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
