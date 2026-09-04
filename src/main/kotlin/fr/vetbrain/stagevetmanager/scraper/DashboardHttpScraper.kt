package fr.vetbrain.stagevetmanager.scraper

import fr.vetbrain.stagevetmanager.model.Internship
import fr.vetbrain.stagevetmanager.model.ScrapeFilters
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import org.jsoup.Jsoup
import java.io.IOException
import java.time.Duration
import java.util.concurrent.ExecutionException
import java.util.concurrent.ExecutorCompletionService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * Extrait le dashboard par HTTP après que Selenium a établi la session.
 *
 * Lorsque la première réponse expose la dernière page, les pages restantes sont
 * téléchargées en parallèle. Sinon, l'URL suivante est suivie séquentiellement.
 */
class DashboardHttpScraper(
    private val sessionCookies: Map<String, String>,
    private val baseUrl: HttpUrl = HttpUrl.Builder()
        .scheme("https")
        .host("www.stagevet.fr")
        .addPathSegment("dashboard")
        .build(),
    private val client: OkHttpClient = defaultClient(),
    private val maxAttempts: Int = 3,
    private val maxConcurrency: Int = 5,
    private val retryDelay: Duration = Duration.ofMillis(250),
    private val onProgress: (String) -> Unit = {},
) {
    init {
        require(maxAttempts >= 1) { "maxAttempts doit être supérieur ou égal à 1" }
        require(maxConcurrency >= 1) { "maxConcurrency doit être supérieur ou égal à 1" }
    }

    fun scrapeAllPages(
        filters: ScrapeFilters = ScrapeFilters(),
        completedPages: Map<Int, Int> = emptyMap(),
        onPageScraped: (pageNumber: Int, internships: List<Internship>) -> Unit = { _, _ -> },
    ): ScraperResult {
        if (sessionCookies.isEmpty()) {
            return ScraperResult.Failure("Session HTTP indisponible : aucun cookie Selenium")
        }

        return try {
            val firstUrl = buildFilteredUrl(filters)
            val visited = mutableSetOf<String>()
            var totalCount = 0
            var pageCount = 0

            onProgress("Téléchargement HTTP de la page 1…")
            val firstPage = downloadAndParse(firstUrl)
            visited += firstPage.finalUrl.toString()
            pageCount++
            totalCount += firstPage.internships.size
            onPageScraped(1, firstPage.internships)
            onProgress("Page HTTP 1 : ${firstPage.internships.size} stage(s) (total : $totalCount)")

            val lastPage = findLastPageNumber(firstPage.html, firstPage.finalUrl)
            if (lastPage != null && lastPage > 2) {
                val remaining = downloadNumberedPages(
                    firstUrl = firstPage.finalUrl,
                    lastPage = lastPage,
                    completedPages = completedPages - 1,
                    onPageScraped = onPageScraped,
                )
                pageCount += remaining.pageCount
                totalCount += remaining.itemCount
                onProgress("$pageCount/$lastPage pages HTTP traitées ($totalCount stage(s))")
                return ScraperResult.Success(totalCount, pageCount)
            }

            var nextUrl = findNextUrl(firstPage.html, firstPage.finalUrl)
            while (nextUrl != null) {
                if (!visited.add(nextUrl.toString())) {
                    throw IOException("Boucle détectée dans la pagination HTTP : $nextUrl")
                }

                onProgress("Téléchargement HTTP de la page ${pageCount + 1}…")
                val downloaded = downloadAndParse(nextUrl)
                pageCount++
                totalCount += downloaded.internships.size
                onPageScraped(pageCount, downloaded.internships)
                onProgress("Page HTTP $pageCount : ${downloaded.internships.size} stage(s) (total : $totalCount)")

                nextUrl = findNextUrl(downloaded.html, downloaded.finalUrl)
            }

            ScraperResult.Success(totalCount, pageCount)
        } catch (e: Exception) {
            ScraperResult.Failure("Extraction HTTP impossible : ${e.message}", e)
        }
    }

    private fun downloadNumberedPages(
        firstUrl: HttpUrl,
        lastPage: Int,
        completedPages: Map<Int, Int>,
        onPageScraped: (Int, List<Internship>) -> Unit,
    ): PageTotals {
        val pageNumbers = (2..lastPage).filterNot { it in completedPages }
        val alreadyCompletedCount = completedPages.keys.count { it in 2..lastPage }
        val alreadyCompletedItems = completedPages
            .filterKeys { it in 2..lastPage }
            .values.sum()
        if (pageNumbers.isEmpty()) {
            onProgress("Reprise du checkpoint : $alreadyCompletedCount page(s) déjà enregistrée(s)")
            return PageTotals(alreadyCompletedCount, alreadyCompletedItems)
        }

        val concurrency = minOf(maxConcurrency, pageNumbers.size)
        onProgress("Pagination détectée : $lastPage pages — $concurrency téléchargement(s) simultané(s)")
        if (alreadyCompletedCount > 0) {
            onProgress("Reprise du checkpoint : $alreadyCompletedCount page(s) ignorée(s)")
        }
        val completed = AtomicInteger(1 + alreadyCompletedCount)
        val executor = Executors.newFixedThreadPool(concurrency)
        val completion = ExecutorCompletionService<NumberedPage>(executor)
        val futures = pageNumbers.map { pageNumber ->
            completion.submit {
                val url = firstUrl.newBuilder()
                    .setQueryParameter("page", pageNumber.toString())
                    .build()
                val parsed = downloadAndParse(url)
                val done = completed.incrementAndGet()
                onProgress("Pages HTTP téléchargées : $done/$lastPage")
                NumberedPage(pageNumber, parsed)
            }
        }

        return try {
            var downloadedItems = 0
            repeat(futures.size) {
                try {
                    val numberedPage = completion.take().get()
                    // Callback exécuté sur le thread d'orchestration : les écritures SQLite
                    // restent sérialisées, mais ont lieu dès la fin de chaque page.
                    onPageScraped(numberedPage.number, numberedPage.page.internships)
                    downloadedItems += numberedPage.page.internships.size
                } catch (e: ExecutionException) {
                    throw (e.cause as? Exception ?: e)
                }
            }
            PageTotals(
                pageCount = alreadyCompletedCount + futures.size,
                itemCount = alreadyCompletedItems + downloadedItems,
            )
        } finally {
            futures.forEach { if (!it.isDone) it.cancel(true) }
            executor.shutdownNow()
        }
    }

    private fun downloadAndParse(url: HttpUrl): ParsedPage {
        val downloaded = download(url)
        ensureDashboardResponse(downloaded.finalUrl, downloaded.html)
        val internships = DashboardParser.parse(downloaded.html)
        if (internships.isEmpty()) {
            // Peut signifier « aucun résultat », mais aussi que les cartes sont
            // injectées en JavaScript. Le repli Selenium tranche ce cas sans
            // risquer de déclarer à tort une extraction vide réussie.
            throw IOException("aucune carte exploitable dans la réponse HTTP")
        }
        return ParsedPage(downloaded.finalUrl, downloaded.html, internships)
    }

    private fun buildFilteredUrl(filters: ScrapeFilters): HttpUrl = baseUrl.newBuilder().apply {
        if (filters.periode.isNotEmpty()) addQueryParameter("periode", filters.periode)
        if (filters.anneeEtude.isNotEmpty()) addQueryParameter("anneeetude", filters.anneeEtude)
        if (filters.theme.isNotEmpty()) addQueryParameter("theme", filters.theme)
        if (filters.status.isNotEmpty()) addQueryParameter("status", filters.status)
        if (filters.order.isNotEmpty()) addQueryParameter("order", filters.order)
    }.build()

    private fun download(url: HttpUrl): DownloadedPage {
        var lastError: IOException? = null
        repeat(maxAttempts) { attempt ->
            try {
                val request = Request.Builder()
                    .url(url)
                    .header("Cookie", cookieHeader())
                    .header("Accept", "text/html,application/xhtml+xml")
                    .header("Accept-Language", "fr-FR,fr;q=0.9")
                    .header("Referer", baseUrl.toString())
                    .header("User-Agent", "StageVetManager/1.0")
                    .get()
                    .build()

                client.newCall(request).execute().use { response ->
                    if (response.code in RETRYABLE_CODES && attempt + 1 < maxAttempts) {
                        pauseBeforeRetry(attempt)
                        return@repeat
                    }
                    if (!response.isSuccessful) {
                        throw IOException("HTTP ${response.code} pour $url")
                    }
                    val html = response.body?.string()
                        ?: throw IOException("Réponse vide pour $url")
                    return DownloadedPage(response.request.url, html)
                }
            } catch (e: IOException) {
                lastError = e
                if (attempt + 1 < maxAttempts) pauseBeforeRetry(attempt)
            }
        }
        throw lastError ?: IOException("Échec du téléchargement de $url")
    }

    private fun pauseBeforeRetry(attempt: Int) {
        val delayMillis = retryDelay.toMillis() * (1L shl attempt)
        onProgress("Nouvelle tentative HTTP dans ${delayMillis} ms…")
        if (delayMillis > 0) Thread.sleep(delayMillis)
    }

    private fun ensureDashboardResponse(finalUrl: HttpUrl, html: String) {
        val doc = Jsoup.parse(html, finalUrl.toString())
        val looksLikeLogin = finalUrl.encodedPath.contains("/login") ||
            (doc.selectFirst("#email") != null && doc.selectFirst("#password") != null)
        if (looksLikeLogin) throw IOException("session expirée ou redirection vers la connexion")

        // Une page sans carte est valide uniquement si elle ressemble toujours au dashboard.
        if (doc.select("div.card").isEmpty() &&
            doc.selectFirst("a[rel=next], .pagination, form[action*=dashboard]") == null &&
            !finalUrl.encodedPath.contains("dashboard")) {
            throw IOException("la réponse reçue n'est pas un dashboard reconnaissable")
        }
    }

    private fun findNextUrl(html: String, currentUrl: HttpUrl): HttpUrl? {
        val href = Jsoup.parse(html, currentUrl.toString())
            .selectFirst("a[rel=next][href], a.page-link[aria-label*=Suivant][href]")
            ?.attr("abs:href")
            ?.takeIf { it.isNotBlank() }
            ?: return null
        val next = href.toHttpUrlOrNull()
            ?: throw IOException("URL de pagination invalide : $href")
        if (next.scheme != baseUrl.scheme || next.host != baseUrl.host || next.port != baseUrl.port) {
            throw IOException("pagination vers une origine inattendue : $next")
        }
        return next
    }

    private fun findLastPageNumber(html: String, currentUrl: HttpUrl): Int? {
        val pageNumbers = Jsoup.parse(html, currentUrl.toString())
            .select("a.page-link[href], a[rel=last][href]")
            .mapNotNull { element ->
                element.attr("abs:href").toHttpUrlOrNull()
                    ?.takeIf { sameOrigin(it) && it.encodedPath == currentUrl.encodedPath }
                    ?.queryParameter("page")
                    ?.toIntOrNull()
            }
        val maximum = pageNumbers.maxOrNull() ?: return null
        // Un simple lien « suivant » vers la page 2 ne prouve pas qu'elle est la dernière.
        return maximum.takeIf { it > 2 }
    }

    private fun sameOrigin(url: HttpUrl): Boolean =
        url.scheme == baseUrl.scheme && url.host == baseUrl.host && url.port == baseUrl.port

    private fun cookieHeader(): String = sessionCookies.entries
        .joinToString("; ") { "${it.key}=${it.value}" }

    private data class DownloadedPage(val finalUrl: HttpUrl, val html: String)
    private data class ParsedPage(
        val finalUrl: HttpUrl,
        val html: String,
        val internships: List<Internship>,
    )
    private data class NumberedPage(val number: Int, val page: ParsedPage)
    private data class PageTotals(val pageCount: Int, val itemCount: Int)

    companion object {
        private val RETRYABLE_CODES = setOf(429, 500, 502, 503, 504)

        private fun defaultClient(): OkHttpClient = OkHttpClient.Builder()
            .followRedirects(true)
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .callTimeout(45, TimeUnit.SECONDS)
            .build()
    }
}
