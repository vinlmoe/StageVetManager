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

        val result = newScraper().scrapeAllPages()

        assertInstanceOf(ScraperResult.Success::class.java, result)
        assertEquals(2, server.requestCount)
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

    private fun newScraper(maxConcurrency: Int = 5) = DashboardHttpScraper(
        sessionCookies = linkedMapOf("session" to "test-session", "preference" to "fr"),
        baseUrl = server.url("/dashboard"),
        maxAttempts = 2,
        maxConcurrency = maxConcurrency,
        retryDelay = Duration.ZERO,
    )

    private fun page(card: String, nextHref: String? = null, pageLinks: String = ""): String = buildString {
        append("<html><body><main>")
        append(card)
        append(pageLinks)
        if (nextHref != null) append("<a class='page-link' rel='next' aria-label='Suivant' href='$nextHref'>Suivant</a>")
        append("</main></body></html>")
    }
}
