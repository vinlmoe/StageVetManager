package fr.vetbrain.stagevetmanager.scraper

import fr.vetbrain.stagevetmanager.model.Internship
import fr.vetbrain.stagevetmanager.model.ScrapeFilters
import org.openqa.selenium.By
import org.openqa.selenium.WebDriver
import org.openqa.selenium.WebElement
import org.openqa.selenium.chrome.ChromeDriver
import org.openqa.selenium.chrome.ChromeOptions
import org.openqa.selenium.firefox.FirefoxDriver
import org.openqa.selenium.firefox.FirefoxOptions
import org.openqa.selenium.support.ui.ExpectedConditions
import org.openqa.selenium.support.ui.WebDriverWait
import java.io.File
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
            // Navigate directly to the filtered URL — more reliable than manipulating
            // the form via JS (listeners on stagevet.fr can reset values before submission).
            val url = buildFilteredUrl(filters)
            onProgress("Chargement du tableau de bord (filtres appliqués en URL)...")
            d.get(url)

            val wait = WebDriverWait(d, Duration.ofSeconds(20))
            // Attendre les résultats (ou absence de résultats)
            Thread.sleep(1500)
            try {
                wait.until(ExpectedConditions.presenceOfElementLocated(By.cssSelector("div.card")))
            } catch (_: Exception) {
                onProgress("Aucun stage trouvé avec ces filtres.")
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

    // Construit l'URL du dashboard avec les filtres en paramètres GET.
    // Équivaut exactement à ce que soumet le formulaire #filtre sur stagevet.fr.
    // Les valeurs vides sont omises (stagevet.fr utilise alors son défaut serveur).
    private fun buildFilteredUrl(filters: ScrapeFilters): String {
        val params = mutableListOf<String>()
        if (filters.periode.isNotEmpty())    params.add("periode=${filters.periode}")
        if (filters.anneeEtude.isNotEmpty()) params.add("anneeetude=${filters.anneeEtude}")
        if (filters.theme.isNotEmpty())      params.add("theme=${filters.theme}")
        if (filters.status.isNotEmpty())     params.add("status=${filters.status}")
        if (filters.order.isNotEmpty())      params.add("order=${filters.order}")
        val qs = if (params.isEmpty()) "" else "?" + params.joinToString("&")
        return "https://www.stagevet.fr/dashboard$qs"
    }

    private fun findNextButton(d: WebDriver): WebElement? {
        return try {
            val elements = d.findElements(
                By.xpath("//a[@class='page-link' and @rel='next' and contains(@aria-label,'Suivant')]")
            )
            elements.firstOrNull()?.takeIf { it.isEnabled && it.isDisplayed }
        } catch (_: Exception) { null }
    }

    companion object {
        private const val GECKO_VERSION = "0.35.0"

        fun resolveGeckoDriver(): File? {
            val os   = System.getProperty("os.name").lowercase()
            val arch = System.getProperty("os.arch").lowercase()
            val arm  = arch.contains("aarch64") || arch.contains("arm")

            val resource = when {
                os.contains("win")          -> "drivers/geckodriver-win-x64.exe"
                os.contains("mac") && arm   -> "drivers/geckodriver-macos-arm64"
                os.contains("mac")          -> "drivers/geckodriver-macos-x64"
                arm                         -> "drivers/geckodriver-linux-arm64"
                else                        -> "drivers/geckodriver-linux-x64"
            }

            val ext  = if (os.contains("win")) ".exe" else ""
            val dest = File(System.getProperty("java.io.tmpdir"), "geckodriver-svm-v${GECKO_VERSION}$ext")
            if (dest.exists()) return dest

            val stream = SeleniumScraper::class.java.classLoader.getResourceAsStream(resource)
                ?: return null  // dev mode sans binaires embarqués → Selenium Manager prend le relais

            stream.use { input -> dest.outputStream().use { input.copyTo(it) } }
            dest.setExecutable(true)
            return dest
        }
    }

    private fun createDriver(): WebDriver {
        val driver = when (browserType) {
            BrowserType.CHROME -> {
                val opts = ChromeOptions()
                if (headless) opts.addArguments("--headless=new", "--no-sandbox", "--disable-dev-shm-usage")
                opts.addArguments("--window-size=1920,1080", "--lang=fr-FR")
                ChromeDriver(opts)
            }
            BrowserType.FIREFOX -> {
                val geckoDriver = resolveGeckoDriver()
                if (geckoDriver != null) {
                    System.setProperty("webdriver.gecko.driver", geckoDriver.absolutePath)
                }
                val opts = FirefoxOptions()
                if (headless) opts.addArguments("-headless")
                FirefoxDriver(opts)
            }
        }
        driver.manage().timeouts().pageLoadTimeout(Duration.ofSeconds(30))
        return driver
    }
}
