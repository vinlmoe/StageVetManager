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
            // Vrai dès qu'un marqueur de pagination a été reconnu dans le HTML.
            // S'il reste faux, l'absence de page suivante n'est pas une preuve :
            // voir probeUnlinkedPages().
            var sawPagination = false

            onProgress("Téléchargement HTTP de la page 1…")
            val firstPage = downloadAndParse(firstUrl)
            visited += firstPage.finalUrl.toString()
            pageCount++
            totalCount += firstPage.internships.size
            onPageScraped(1, firstPage.internships)
            onProgress("Page HTTP 1 : ${firstPage.internships.size} stage(s) (total : $totalCount)")

            // Page de départ du parcours séquentiel : la page 1 par défaut, ou la
            // dernière page numérotée lorsqu'un lot parallèle a été traité avant.
            var tail: ParsedPage = firstPage

            val lastPage = findLastPageNumber(firstPage.html, firstPage.finalUrl)
            if (lastPage != null && lastPage > 2) {
                sawPagination = true
                val remaining = downloadNumberedPages(
                    firstUrl = firstPage.finalUrl,
                    lastPage = lastPage,
                    completedPages = completedPages - 1,
                    onPageScraped = onPageScraped,
                )
                pageCount += remaining.pageCount
                totalCount += remaining.itemCount
                onProgress("$pageCount/$lastPage pages HTTP traitées ($totalCount stage(s))")

                // Une pagination « fenêtrée » (1 2 3 … Suivant, sans lien vers la
                // dernière page) faisait conclure à tort que lastPage était la fin :
                // les pages au-delà étaient perdues sans la moindre erreur. On ne
                // s'arrête donc que si la dernière page traitée n'a plus de suivant.
                val lastParsed = remaining.lastParsedPage
                if (lastParsed == null) {
                    return ScraperResult.Success(totalCount, pageCount)
                }
                tail = lastParsed
                visited += tail.finalUrl.toString()
                if (findNextUrl(tail.html, tail.finalUrl) != null) {
                    onProgress(
                        "Pagination plus longue qu'annoncée ($lastPage pages) — " +
                            "poursuite séquentielle…"
                    )
                }
            }

            var nextUrl = findNextUrl(tail.html, tail.finalUrl)
            while (nextUrl != null) {
                sawPagination = true
                if (!visited.add(nextUrl.toString())) {
                    throw IOException("Boucle détectée dans la pagination HTTP : $nextUrl")
                }

                onProgress("Téléchargement HTTP de la page ${pageCount + 1}…")
                val downloaded = downloadAndParse(nextUrl)
                pageCount++
                totalCount += downloaded.internships.size
                onPageScraped(pageCount, downloaded.internships)
                onProgress("Page HTTP $pageCount : ${downloaded.internships.size} stage(s) (total : $totalCount)")

                tail = downloaded
                nextUrl = findNextUrl(downloaded.html, downloaded.finalUrl)
            }

            if (!sawPagination) {
                val probed = probeUnlinkedPages(firstUrl, tail, pageCount, onPageScraped)
                pageCount += probed.pageCount
                totalCount += probed.itemCount
            }

            ScraperResult.Success(totalCount, pageCount)
        } catch (e: Exception) {
            ScraperResult.Failure("Extraction HTTP impossible : ${e.message}", e)
        }
    }

    /**
     * Poursuit la pagination « à l'aveugle » quand aucun lien de pagination n'a été
     * reconnu dans le HTML.
     *
     * Un dashboard dont les liens « Suivant » / « page N » sont absents ou rendus
     * différemment faisait conclure à une extraction complète après la seule page 1 :
     * seuls les dix premiers stages étaient enregistrés, et l'extraction était
     * annoncée comme réussie. On demande donc explicitement ?page=2, ?page=3… jusqu'à
     * ce que le serveur renvoie une page sans carte, rejoue une page déjà extraite
     * (comportement courant pour un numéro hors limites) ou refuse la requête.
     */
    private fun probeUnlinkedPages(
        firstUrl: HttpUrl,
        lastKnownPage: ParsedPage,
        alreadyScrapedPages: Int,
        onPageScraped: (Int, List<Internship>) -> Unit,
    ): PageTotals {
        // Un numéro hors limites peut renvoyer la dernière page comme la première :
        // on mémorise donc toutes les pages déjà vues, pas seulement la précédente.
        val seenSignatures = mutableSetOf(pageSignature(lastKnownPage.internships))
        var pages = 0
        var items = 0
        var pageNumber = alreadyScrapedPages + 1

        while (pageNumber <= MAX_PROBED_PAGES) {
            val url = firstUrl.newBuilder()
                .setQueryParameter("page", pageNumber.toString())
                .build()
            onProgress("Aucun lien de pagination — vérification de la page $pageNumber…")
            val parsed = downloadIfNotEmpty(url) ?: break

            if (!seenSignatures.add(pageSignature(parsed.internships))) {
                onProgress("Page $pageNumber déjà extraite — fin de la pagination")
                break
            }

            onPageScraped(pageNumber, parsed.internships)
            pages++
            items += parsed.internships.size
            onProgress("Page HTTP $pageNumber : ${parsed.internships.size} stage(s)")
            pageNumber++
        }

        return PageTotals(pages, items)
    }

    /**
     * Télécharge une page sondée. Renvoie `null` quand la page n'existe pas (erreur
     * réseau/HTTP) ou ne contient plus de carte : ce n'est pas un échec d'extraction,
     * seulement la fin de la pagination.
     */
    private fun downloadIfNotEmpty(url: HttpUrl): ParsedPage? {
        val downloaded = try {
            download(url)
        } catch (e: IOException) {
            onProgress("Page $url indisponible (${e.message}) — fin de la pagination")
            return null
        }
        // Une redirection vers la connexion reste une vraie erreur : elle doit
        // interrompre l'extraction pour laisser Selenium reprendre la main.
        ensureDashboardResponse(downloaded.finalUrl, downloaded.html)
        val internships = DashboardParser.parse(downloaded.html)
        if (internships.isEmpty()) return null
        return ParsedPage(downloaded.finalUrl, downloaded.html, internships)
    }

    /** Identité du contenu d'une page, pour détecter un numéro de page hors limites. */
    private fun pageSignature(internships: List<Internship>): String =
        internships
            .map { "${it.studentName}|${it.organization}|${it.rawDateStage}" }
            .sorted()
            .joinToString("\n")

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
            // Aucune page téléchargée ici : l'appelant ne peut pas vérifier la suite
            // de la pagination, il conclut sur le checkpoint.
            return PageTotals(alreadyCompletedCount, alreadyCompletedItems, lastParsedPage = null)
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
            var highest: NumberedPage? = null
            repeat(futures.size) {
                try {
                    val numberedPage = completion.take().get()
                    // Callback exécuté sur le thread d'orchestration : les écritures SQLite
                    // restent sérialisées, mais ont lieu dès la fin de chaque page.
                    onPageScraped(numberedPage.number, numberedPage.page.internships)
                    downloadedItems += numberedPage.page.internships.size
                    // Les pages arrivent dans le désordre : on retient celle de plus
                    // grand numéro pour pouvoir vérifier la fin de la pagination.
                    if (highest == null || numberedPage.number > highest!!.number) {
                        highest = numberedPage
                    }
                } catch (e: ExecutionException) {
                    throw (e.cause as? Exception ?: e)
                }
            }
            PageTotals(
                pageCount = alreadyCompletedCount + futures.size,
                itemCount = alreadyCompletedItems + downloadedItems,
                // Seule la vraie dernière page permet de conclure ; si le checkpoint a
                // fait sauter des pages en fin de liste, on ne conclut pas.
                lastParsedPage = highest?.takeIf { it.number == lastPage }?.page,
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
            // Plusieurs gabarits coexistent sur stagevet.fr : rel="next" seul,
            // rel="next nofollow", ou un simple aria-label. Exiger les trois à la
            // fois (ancienne règle) revenait à ne jamais trouver la page 2.
            .selectFirst(
                "a[rel=next][href], a[rel~=(?i)next][href], " +
                    "a[aria-label*='Suivant'][href], a[aria-label*='Next'][href]"
            )
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
            .select("a.page-link[href], a.page-numbers[href], a[rel=last][href], a[href*='page=']")
            .mapNotNull { element ->
                element.attr("abs:href").toHttpUrlOrNull()
                    ?.takeIf { sameOrigin(it) && samePath(it, currentUrl) }
                    ?.queryParameter("page")
                    ?.toIntOrNull()
            }
        val maximum = pageNumbers.maxOrNull() ?: return null
        // Un simple lien « suivant » vers la page 2 ne prouve pas qu'elle est la dernière.
        return maximum.takeIf { it > 2 }
    }

    /** Compare les chemins en ignorant un « / » final, que le serveur ajoute parfois. */
    private fun samePath(candidate: HttpUrl, current: HttpUrl): Boolean =
        candidate.encodedPath.trimEnd('/') == current.encodedPath.trimEnd('/')

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
    private data class PageTotals(
        val pageCount: Int,
        val itemCount: Int,
        /** Dernière page numérotée effectivement téléchargée, si elle est connue. */
        val lastParsedPage: ParsedPage? = null,
    )

    companion object {
        private val RETRYABLE_CODES = setOf(429, 500, 502, 503, 504)

        /** Garde-fou du sondage : au-delà, on considère la pagination aberrante. */
        private const val MAX_PROBED_PAGES = 500

        private fun defaultClient(): OkHttpClient = OkHttpClient.Builder()
            .followRedirects(true)
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .callTimeout(45, TimeUnit.SECONDS)
            .build()
    }
}
