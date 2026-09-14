package fr.vetbrain.stagevetmanager.scraper

import fr.vetbrain.stagevetmanager.model.ScrapeFilters
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.RecordedRequest
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.time.Duration
import java.util.concurrent.atomic.AtomicInteger

class DashboardHttpScraperTest {
    private lateinit var server: MockWebServer
    private val cardHtml: String = DashboardHttpScraperTest::class.java
        .getResourceAsStream("/fixtures/sample_card.html")!!
        .bufferedReader().readText()

    @BeforeEach
    fun startServer() {
        server = MockWebServer()
        server.start()
    }

    @AfterEach
    fun stopServer() {
        server.shutdown()
    }

    @Test
    fun `downloads every page with filters and Selenium cookies`() {
        server.enqueue(MockResponse().setResponseCode(200).setBody(page(cardHtml, "/dashboard?page=2")))
        server.enqueue(MockResponse().setResponseCode(200).setBody(page(cardHtml)))

        val pages = mutableListOf<Int>()
        val result = newScraper().scrapeAllPages(
            ScrapeFilters(periode = "3", theme = "21", order = "2"),
        ) { _, internships -> pages += internships.size }

        val success = assertInstanceOf(ScraperResult.Success::class.java, result)
        assertEquals(2, success.totalCount)
        assertEquals(2, success.pageCount)
        assertEquals(listOf(1, 1), pages)

        val first = server.takeRequest()
        assertEquals("session=test-session; preference=fr", first.getHeader("Cookie"))
        assertEquals("3", first.requestUrl?.queryParameter("periode"))
        assertEquals("21", first.requestUrl?.queryParameter("theme"))
        assertEquals("2", first.requestUrl?.queryParameter("order"))
        assertEquals("2", server.takeRequest().requestUrl?.queryParameter("page"))
    }

    @Test
    fun `retries a temporary server error`() {
        server.enqueue(MockResponse().setResponseCode(503))
        server.enqueue(MockResponse().setResponseCode(200).setBody(page(cardHtml)))
        // La page 1 n'expose aucun lien de pagination : le scraper sonde la page 2.
        server.enqueue(MockResponse().setResponseCode(200).setBody(emptyDashboard()))

        val result = newScraper().scrapeAllPages()

        val success = assertInstanceOf(ScraperResult.Success::class.java, result)
        assertEquals(1, success.pageCount)
        assertEquals(3, server.requestCount)
    }

    @Test
    fun `probes the next pages when the dashboard exposes no pagination link`() {
        // Symptôme constaté sous Windows : sans lien « Suivant » reconnu, seule la
        // première page (dix stages) était enregistrée et l'extraction était
        // déclarée réussie. Le scraper doit demander ?page=2, ?page=3… lui-même.
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val number = request.requestUrl?.queryParameter("page")?.toIntOrNull() ?: 1
                if (number > 3) {
                    return MockResponse().setResponseCode(200).setBody(emptyDashboard())
                }
                return MockResponse().setResponseCode(200).setBody(
                    page(cardHtml.replace("Dupont Marie", "Etudiant page $number")),
                )
            }
        }

        val students = mutableListOf<String>()
        val seenPages = mutableListOf<Int>()
        val result = newScraper().scrapeAllPages { pageNumber, internships ->
            seenPages += pageNumber
            students += internships.single().studentName
        }

        val success = assertInstanceOf(ScraperResult.Success::class.java, result)
        assertEquals(3, success.pageCount)
        assertEquals(3, success.totalCount)
        assertEquals(listOf(1, 2, 3), seenPages)
        assertEquals((1..3).map { "Etudiant page $it" }, students)
    }

    @Test
    fun `stops probing when the server replays the same page`() {
        // Un numéro de page hors limites renvoie souvent la dernière page : sans
        // comparaison de contenu, le sondage bouclerait et doublerait les stages.
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse =
                MockResponse().setResponseCode(200).setBody(page(cardHtml))
        }

        val result = newScraper().scrapeAllPages()

        val success = assertInstanceOf(ScraperResult.Success::class.java, result)
        assertEquals(1, success.pageCount)
        assertEquals(1, success.totalCount)
        assertEquals(2, server.requestCount)
    }

    @Test
    fun `follows a next link carrying several rel values`() {
        // Laravel rend « rel="next" » mais d'autres gabarits ajoutent nofollow :
        // l'ancien sélecteur strict ne voyait alors plus la page suivante.
        server.enqueue(MockResponse().setResponseCode(200).setBody(
            page(cardHtml.replace("Dupont Marie", "Etudiant page 1")) +
                "<a class='pagination__next' rel='next nofollow' href='/dashboard?page=2'>›</a>",
        ))
        server.enqueue(MockResponse().setResponseCode(200).setBody(
            page(cardHtml.replace("Dupont Marie", "Etudiant page 2")),
        ))

        val students = mutableListOf<String>()
        val result = newScraper().scrapeAllPages { _, internships ->
            students += internships.single().studentName
        }

        val success = assertInstanceOf(ScraperResult.Success::class.java, result)
        assertEquals(2, success.pageCount)
        assertEquals(listOf("Etudiant page 1", "Etudiant page 2"), students)
    }

    @Test
    fun `rejects a redirect to the login page`() {
        server.enqueue(MockResponse().setResponseCode(302).setHeader("Location", "/login"))
        server.enqueue(MockResponse().setResponseCode(200).setBody(
            "<html><form><input id='email'><input id='password'></form></html>",
        ))

        val result = newScraper().scrapeAllPages()

        val failure = assertInstanceOf(ScraperResult.Failure::class.java, result)
        assertTrue(failure.message.contains("connexion"))
    }

    @Test
    fun `rejects an empty HTTP dashboard so Selenium can take over`() {
        server.enqueue(MockResponse().setResponseCode(200).setBody("<html><body><main></main></body></html>"))

        val result = newScraper().scrapeAllPages()

        val failure = assertInstanceOf(ScraperResult.Failure::class.java, result)
        assertTrue(failure.message.contains("aucune carte"))
    }

    @Test
    fun `downloads known numbered pages concurrently and reports every page`() {
        val active = AtomicInteger(0)
        val maximumActive = AtomicInteger(0)
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val number = request.requestUrl?.queryParameter("page")?.toIntOrNull() ?: 1
                if (number > 1) {
                    val nowActive = active.incrementAndGet()
                    maximumActive.updateAndGet { maxOf(it, nowActive) }
                    Thread.sleep((7 - number) * 20L)
                    active.decrementAndGet()
                }
                val links = if (number == 1) (1..6).joinToString("") {
                    "<a class='page-link' href='/dashboard?page=$it'>$it</a>"
                } else ""
                return MockResponse().setResponseCode(200).setBody(
                    page(cardHtml.replace("Dupont Marie", "Etudiant page $number"), pageLinks = links),
                )
            }
        }

        val studentsByPage = mutableListOf<String>()
        val completedPageNumbers = mutableListOf<Int>()
        val result = newScraper(maxConcurrency = 3).scrapeAllPages { pageNumber, internships ->
            completedPageNumbers += pageNumber
            studentsByPage += internships.single().studentName
        }

        val success = assertInstanceOf(ScraperResult.Success::class.java, result)
        assertEquals(6, success.pageCount)
        assertEquals((1..6).toSet(), completedPageNumbers.toSet())
        assertEquals((1..6).map { "Etudiant page $it" }.toSet(), studentsByPage.toSet())
        assertTrue(maximumActive.get() in 2..3)
    }

    @Test
    fun `resume skips numbered pages already stored in checkpoint`() {
        server.enqueue(MockResponse().setResponseCode(200).setBody(
            page(
                cardHtml.replace("Dupont Marie", "Etudiant page 1"),
                pageLinks = (1..4).joinToString("") {
                    "<a class='page-link' href='/dashboard?page=$it'>$it</a>"
                },
            ),
        ))
        server.enqueue(MockResponse().setResponseCode(200).setBody(
            page(cardHtml.replace("Dupont Marie", "Etudiant page 4")),
        ))

        val downloadedPages = mutableListOf<Int>()
        val result = newScraper().scrapeAllPages(
            completedPages = mapOf(2 to 10, 3 to 10),
        ) { pageNumber, _ -> downloadedPages += pageNumber }

        val success = assertInstanceOf(ScraperResult.Success::class.java, result)
        assertEquals(listOf(1, 4), downloadedPages)
        assertEquals(4, success.pageCount)
        assertEquals(22, success.totalCount)
        assertEquals(2, server.requestCount)
    }

    @Test
    fun `follows the next link when pagination only exposes a window of pages`() {
        // stagevet.fr peut n'afficher que « 1 2 3 … Suivant » : le maximum des liens
        // numérotés (3) n'est alors PAS la dernière page. Sans vérification du lien
        // « suivant » après la page 3, les pages 4+ étaient perdues silencieusement
        // et l'extraction était déclarée réussie.
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val number = request.requestUrl?.queryParameter("page")?.toIntOrNull() ?: 1
                val windowLinks = if (number == 1) (1..3).joinToString("") {
                    "<a class='page-link' href='/dashboard?page=$it'>$it</a>"
                } else ""
                // La page 3 annonce une page 4 que la fenêtre de la page 1 ignorait.
                val nextHref = if (number == 3) "/dashboard?page=4" else null
                return MockResponse().setResponseCode(200).setBody(
                    page(
                        cardHtml.replace("Dupont Marie", "Etudiant page $number"),
                        nextHref = nextHref,
                        pageLinks = windowLinks,
                    ),
                )
            }
        }

        val seenPages = mutableListOf<Int>()
        val students = mutableListOf<String>()
        val result = newScraper().scrapeAllPages { pageNumber, internships ->
            seenPages += pageNumber
            students += internships.single().studentName
        }

        val success = assertInstanceOf(ScraperResult.Success::class.java, result)
        assertEquals(4, success.pageCount)
        assertEquals(4, success.totalCount)
        assertEquals((1..4).toSet(), seenPages.toSet())
        assertTrue(students.contains("Etudiant page 4"), "la page hors fenêtre doit être extraite")
    }

    @Test
    fun `stops when the last numbered page has no next link`() {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val number = request.requestUrl?.queryParameter("page")?.toIntOrNull() ?: 1
                val links = if (number == 1) (1..3).joinToString("") {
                    "<a class='page-link' href='/dashboard?page=$it'>$it</a>"
                } else ""
                return MockResponse().setResponseCode(200).setBody(
                    page(cardHtml.replace("Dupont Marie", "Etudiant page $number"), pageLinks = links),
                )
            }
        }

        val result = newScraper().scrapeAllPages()

        val success = assertInstanceOf(ScraperResult.Success::class.java, result)
        assertEquals(3, success.pageCount)
        assertEquals(3, server.requestCount)
    }

    private fun newScraper(maxConcurrency: Int = 5) = DashboardHttpScraper(
        sessionCookies = linkedMapOf("session" to "test-session", "preference" to "fr"),
        baseUrl = server.url("/dashboard"),
        maxAttempts = 2,
        maxConcurrency = maxConcurrency,
        retryDelay = Duration.ZERO,
    )

    /** Dashboard valide mais sans aucune carte : marque la fin de la pagination. */
    private fun emptyDashboard(): String =
        "<html><body><main><nav class='pagination'></nav></main></body></html>"

    private fun page(card: String, nextHref: String? = null, pageLinks: String = ""): String = buildString {
        append("<html><body><main>")
        append(card)
        append(pageLinks)
        if (nextHref != null) append("<a class='page-link' rel='next' aria-label='Suivant' href='$nextHref'>Suivant</a>")
        append("</main></body></html>")
    }
}
