package fr.vetbrain.stagevetmanager.scraper

import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * Télécharge un PDF depuis stagevet.fr en réutilisant les cookies de session
 * Selenium (obtenus via SeleniumScraper.getSessionCookies() avant la fermeture
 * du navigateur).
 */
class PdfDownloader(private val sessionCookies: Map<String, String>) {

    private val client = OkHttpClient.Builder()
        .followRedirects(true)
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()

    /**
     * Télécharge [url] et renvoie les octets bruts du PDF.
     * Lève [IOException] si la réponse HTTP n'est pas un succès ou si le corps
     * est absent.
     */
    fun download(url: String): ByteArray {
        val cookieHeader = sessionCookies.entries
            .joinToString("; ") { "${it.key}=${it.value}" }

        val req = Request.Builder()
            .url(url)
            .header("Cookie", cookieHeader)
            .header("Accept", "application/pdf,*/*")
            .header("Referer", "https://www.stagevet.fr/dashboard")
            .get()
            .build()

        return client.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful)
                throw IOException("HTTP ${resp.code} lors du téléchargement de $url")
            resp.body?.bytes()
                ?: throw IOException("Corps de réponse vide pour $url")
        }
    }
}
