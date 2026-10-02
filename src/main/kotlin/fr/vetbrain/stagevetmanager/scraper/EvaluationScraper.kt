package fr.vetbrain.stagevetmanager.scraper

import fr.vetbrain.stagevetmanager.model.Internship
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.HttpUrl.Companion.toHttpUrl
import java.time.Duration

class EvaluationScraper(
    private val cookies: Map<String, String>,
    private val onWarning: (String) -> Unit = {},
) {
    private val origin = "https://www.stagevet.fr/".toHttpUrl()
    private val client = OkHttpClient.Builder()
        .followRedirects(false).callTimeout(Duration.ofSeconds(30)).build()

    fun enrich(stage: Internship): Internship = stage.copy(
        supervisorEvaluation = fetch(stage.supervisorEvaluationUrl),
        studentEvaluation = fetch(stage.studentEvaluationUrl),
    )

    private fun fetch(link: String): String? {
        if (link.isBlank()) return null
        return try {
            val url = requireNotNull(origin.resolve(link))
            require(url.scheme == origin.scheme && url.host == origin.host && url.port == origin.port)
            val request = Request.Builder().url(url)
                .header("Cookie", cookies.entries.joinToString("; ") { "${it.key}=${it.value}" })
                .get().build()
            client.newCall(request).execute().use { response ->
                check(response.isSuccessful) { "HTTP ${response.code}" }
                EvaluationParser.parse(response.body?.string().orEmpty())
            }
        } catch (e: Exception) {
            onWarning("Évaluation non récupérée (${e.message}) ; les données précédentes sont conservées")
            null
        }
    }
}
