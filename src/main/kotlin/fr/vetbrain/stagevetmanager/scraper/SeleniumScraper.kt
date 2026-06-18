package fr.vetbrain.stagevetmanager.scraper

import fr.vetbrain.stagevetmanager.model.Internship
import fr.vetbrain.stagevetmanager.model.ScrapeFilters
import io.github.bonigarcia.wdm.WebDriverManager
import org.openqa.selenium.By
import org.openqa.selenium.JavascriptExecutor
import org.openqa.selenium.WebDriver
import org.openqa.selenium.WebElement
import org.openqa.selenium.chrome.ChromeDriver
import org.openqa.selenium.chrome.ChromeOptions
import org.openqa.selenium.firefox.FirefoxDriver
import org.openqa.selenium.firefox.FirefoxOptions
import org.openqa.selenium.support.ui.ExpectedConditions
import org.openqa.selenium.support.ui.WebDriverWait
import java.time.Duration

class SeleniumScraper(
    private val browserType: BrowserType = BrowserType.CHROME,
    private val headless: Boolean = false,
    private val onProgress: (String) -> Unit = {},
) {
    enum class BrowserType { CHROME, FIREFOX }

    private var driver: WebDriver? = null

    fun login(username: String, password: String): Boolean {
        return try {
            driver = createDriver()
            val d = driver!!
            d.get("https://www.stagevet.fr/login")

            val wait = WebDriverWait(d, Duration.ofSeconds(20))
            wait.until(ExpectedConditions.visibilityOfElementLocated(By.cssSelector("#email")))

            d.findElement(By.cssSelector("#email")).sendKeys(username)
            d.findElement(By.cssSelector("#password")).sendKeys(password)
            d.findElement(By.cssSelector("button[type='submit']")).click()

            // Wait for redirect to dashboard
            wait.until(ExpectedConditions.urlContains("dashboard"))
            onProgress("Connexion réussie")
            true
        } catch (e: Exception) {
            onProgress("Erreur de connexion : ${e.message}")
            false
        }
    }

    fun scrapeAllPages(
        filters: ScrapeFilters = ScrapeFilters(),
        onPageScraped: (List<Internship>) -> Unit = {},
    ): ScraperResult {
        val d = driver ?: return ScraperResult.Failure("Navigateur non initialisé")
        return try {
            d.get("https://www.stagevet.fr/dashboard")
            val wait = WebDriverWait(d, Duration.ofSeconds(15))
            // Attendre que le formulaire soit disponible
            wait.until(ExpectedConditions.presenceOfElementLocated(By.cssSelector("form#filtre")))

            onProgress("Application des filtres...")
            applyFilters(d, filters)

            // Attendre les résultats (ou absence de résultats)
            Thread.sleep(1500)
            try {
                wait.until(ExpectedConditions.presenceOfElementLocated(By.cssSelector("div.card")))
            } catch (_: Exception) {
                return ScraperResult.Success(0, 0)
            }

            var totalCount = 0
            var pageCount = 0

            while (true) {
                Thread.sleep(1500)
                val html = d.pageSource
                val pageInternships = DashboardParser.parse(html)
                pageCount++
                totalCount += pageInternships.size

                onPageScraped(pageInternships)
                onProgress("Page $pageCount : ${pageInternships.size} stage(s) extraits (total : $totalCount)")

                val nextBtn = findNextButton(d)
                if (nextBtn == null) break

                val firstCard = try {
                    d.findElement(By.cssSelector("div.card"))
                } catch (_: Exception) { null }

                nextBtn.click()

                if (firstCard != null) {
                    try {
                        WebDriverWait(d, Duration.ofSeconds(10))
                            .until(ExpectedConditions.stalenessOf(firstCard))
                    } catch (_: Exception) {
                        Thread.sleep(2000)
                    }
                }
            }

            onProgress("Extraction terminée : $totalCount stage(s) sur $pageCount page(s)")
            ScraperResult.Success(totalCount, pageCount)
        } catch (e: Exception) {
            ScraperResult.Failure("Erreur lors du scraping : ${e.message}", e)
        }
    }

    /**
     * Retourne tous les cookies de session du navigateur.
     * À appeler AVANT [close] pour pouvoir réutiliser la session (ex. téléchargement PDF).
     */
    fun getSessionCookies(): Map<String, String> =
        driver?.manage()?.cookies
            ?.associate { it.name to it.value }
            ?: emptyMap()

    fun close() {
        try { driver?.quit() } catch (_: Exception) {}
        driver = null
    }

    // Les selects stagevet.fr sont des widgets Select2 : on force la valeur via JS
    // puis on déclenche l'événement change pour que le widget se synchronise.
    private fun applyFilters(d: WebDriver, filters: ScrapeFilters) {
        val js = d as? JavascriptExecutor ?: return

        fun setSelect(name: String, value: String) {
            js.executeScript(
                """
                var el = document.querySelector('[name="${name}"]');
                if (el) {
                    el.value = arguments[0];
                    el.dispatchEvent(new Event('change'));
                }
                """.trimIndent(),
                value
            )
        }

        setSelect("periode", filters.periode)
        setSelect("anneeetude", filters.anneeEtude)
        setSelect("theme", filters.theme)
        setSelect("status", filters.status)
        setSelect("order", filters.order)

        // Clic sur "Rechercher"
        try {
            d.findElement(By.cssSelector("input[type='submit'][value='Rechercher']")).click()
        } catch (_: Exception) {
            d.findElement(By.cssSelector("form#filtre input[type='submit']")).click()
        }
    }

    private fun findNextButton(d: WebDriver): WebElement? {
        return try {
            val elements = d.findElements(
                By.xpath("//a[@class='page-link' and @rel='next' and contains(@aria-label,'Suivant')]")
            )
            elements.firstOrNull()?.takeIf { it.isEnabled && it.isDisplayed }
        } catch (_: Exception) { null }
    }

    private fun createDriver(): WebDriver {
        return when (browserType) {
            BrowserType.CHROME -> {
                WebDriverManager.chromedriver().setup()
                val opts = ChromeOptions()
                if (headless) opts.addArguments("--headless=new", "--no-sandbox", "--disable-dev-shm-usage")
                opts.addArguments("--window-size=1920,1080", "--lang=fr-FR")
                ChromeDriver(opts)
            }
            BrowserType.FIREFOX -> {
                WebDriverManager.firefoxdriver().setup()
                val opts = FirefoxOptions()
                if (headless) opts.addArguments("-headless")
                FirefoxDriver(opts)
            }
        }
    }
}
