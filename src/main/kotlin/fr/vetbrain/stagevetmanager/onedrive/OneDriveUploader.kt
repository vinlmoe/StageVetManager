package fr.vetbrain.stagevetmanager.onedrive

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.util.concurrent.TimeUnit

object OneDriveUploader {

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    private const val GRAPH_BASE = "https://graph.microsoft.com/v1.0"
    private val XLSX_MEDIA_TYPE =
        "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet".toMediaType()

    // remotePath : chemin relatif depuis la racine OneDrive, ex. "Documents/StageVet/export.xlsx"
    fun upload(bytes: ByteArray, remotePath: String, accessToken: String) {
        require(bytes.size < 4 * 1024 * 1024) {
            "Fichier trop volumineux pour un upload simple (> 4 Mo). Réduire le nombre de stages."
        }

        val url = "$GRAPH_BASE/me/drive/root:/$remotePath:/content"
        val request = Request.Builder()
            .url(url)
            .put(bytes.toRequestBody(XLSX_MEDIA_TYPE))
            .header("Authorization", "Bearer $accessToken")
            .build()

        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                val body = runCatching { response.body?.string() }.getOrNull() ?: ""
                throw IOException("Graph API ${response.code} : $body")
            }
        }
    }
}
