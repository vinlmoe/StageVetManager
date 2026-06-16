package fr.vetbrain.stagevetmanager.scraper

import fr.vetbrain.stagevetmanager.model.Internship
import io.github.bonigarcia.wdm.WebDriverManager
import org.openqa.selenium.By
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

    fun scrapeAllPages(onPageScraped: (List<Internship>) -> Unit = {}): ScraperResult {
        val d = driver ?: return ScraperResult.Failure("Navigateur non initialisé")
        return try {
            d.get("https://www.stagevet.fr/dashboard")
            val wait = WebDriverWait(d, Duration.ofSeconds(15))
            wait.until(ExpectedConditions.presenceOfElementLocated(By.cssSelector("div.card")))

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

    fun close() {
        try { driver?.quit() } catch (_: Exception) {}
        driver = null
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
