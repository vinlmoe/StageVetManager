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

    private fun log(msg: String) {
        System.err.println("[SVM] $msg")
        onProgress(msg)
    }

    fun login(username: String, password: String): Boolean {
        return try {
            driver = createDriver()
            val d = driver!!
            val t0 = System.currentTimeMillis()
            fun elapsed() = "${System.currentTimeMillis() - t0}ms"

            log("[DEBUG] Navigation vers https://www.stagevet.fr/login …")
            d.get("https://www.stagevet.fr/login")
            log("[DEBUG] Page chargée (${elapsed()}) — URL : ${d.currentUrl}")

            val wait = WebDriverWait(d, Duration.ofSeconds(20))
            log("[DEBUG] Attente du champ #email (timeout 20s)…")
            wait.until(ExpectedConditions.visibilityOfElementLocated(By.cssSelector("#email")))
            log("[DEBUG] Champ #email visible (${elapsed()}) — saisie des identifiants…")

            d.findElement(By.cssSelector("#email")).sendKeys(username)
            d.findElement(By.cssSelector("#password")).sendKeys(password)
            log("[DEBUG] Clic sur le bouton submit…")
            d.findElement(By.cssSelector("button[type='submit']")).click()
            log("[DEBUG] Formulaire soumis (${elapsed()}) — URL : ${d.currentUrl}")

            log("[DEBUG] Attente de la redirection vers /dashboard (timeout 20s)…")
            wait.until(ExpectedConditions.urlContains("dashboard"))
            log("Connexion réussie — URL : ${d.currentUrl} — durée totale : ${elapsed()}")
            true
        } catch (e: Exception) {
            val cause = generateSequence<Throwable>(e) { it.cause }
                .map { it.javaClass.simpleName + ": " + it.message }
                .joinToString(" ← ")
            log("Erreur de connexion : $cause")
            runCatching { log("[DEBUG] URL au moment de l'erreur : ${driver?.currentUrl ?: "driver non initialisé"}") }
            false
        }
    }

    fun scrapeAllPages(
        filters: ScrapeFilters = ScrapeFilters(),
        onPageScraped: (List<Internship>) -> Unit = {},
    ): ScraperResult {
        val d = driver ?: return ScraperResult.Failure("Navigateur non initialisé")
        return try {
            val url = buildFilteredUrl(filters)
            log("[DEBUG] Chargement dashboard : $url")
            val t0 = System.currentTimeMillis()
            d.get(url)
            log("[DEBUG] Dashboard chargé (${System.currentTimeMillis() - t0}ms) — URL : ${d.currentUrl}")

            val wait = WebDriverWait(d, Duration.ofSeconds(20))
            Thread.sleep(1500)
            try {
                wait.until(ExpectedConditions.presenceOfElementLocated(By.cssSelector("div.card")))
                log("[DEBUG] Cartes de stages détectées.")
            } catch (_: Exception) {
                log("Aucun stage trouvé avec ces filtres.")
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
                log("Page $pageCount : ${pageInternships.size} stage(s) extraits (total : $totalCount)")

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

            log("Extraction terminée : $totalCount stage(s) sur $pageCount page(s)")
            ScraperResult.Success(totalCount, pageCount)
        } catch (e: Exception) {
            ScraperResult.Failure("Erreur lors du scraping : ${e.message}", e)
        }
    }

    fun getSessionCookies(): Map<String, String> =
        driver?.manage()?.cookies
            ?.associate { it.name to it.value }
            ?: emptyMap()

    fun close() {
        try { driver?.quit() } catch (_: Exception) {}
        driver = null
    }

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

        /**
         * Retourne le ChromeDriver le plus récent dans le cache de Selenium Manager
         * (~/.cache/selenium/chromedriver/{platform}/{version}/chromedriver).
         * Permet d'ignorer un chromedriver obsolète installé dans le PATH (ex. via Homebrew).
         */
        fun findBestCachedChromeDriver(): File? {
            val os   = System.getProperty("os.name").lowercase()
            val arch = System.getProperty("os.arch").lowercase()
            val arm  = arch.contains("aarch64") || arch.contains("arm")
            val platform = when {
                os.contains("win")        -> "win32"
                os.contains("mac") && arm -> "mac-arm64"
                os.contains("mac")        -> "mac-x64"
                arm                       -> "linux-arm64"
                else                      -> "linux64"
            }
            val cacheDir = File(System.getProperty("user.home"), ".cache/selenium/chromedriver/$platform")
            if (!cacheDir.exists()) return null
            val binary = if (os.contains("win")) "chromedriver.exe" else "chromedriver"
            return cacheDir.listFiles()
                ?.filter { it.isDirectory }
                ?.maxByOrNull { it.name }   // tri lexicographique fiable sur "149.0.x" vs "132.0.x"
                ?.let { File(it, binary) }
                ?.takeIf { it.exists() && it.canExecute() }
        }

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
            if (dest.exists() && dest.canExecute()) return dest

            val stream = SeleniumScraper::class.java.classLoader.getResourceAsStream(resource)
                ?: return null

            stream.use { input -> dest.outputStream().use { input.copyTo(it) } }
            dest.setExecutable(true)
            return dest
        }
    }

    private fun createDriver(): WebDriver {
        log("[DEBUG] OS : ${System.getProperty("os.name")} | arch : ${System.getProperty("os.arch")}")
        log("[DEBUG] Java : ${System.getProperty("java.version")} | tmp : ${System.getProperty("java.io.tmpdir")}")
        log("[DEBUG] Navigateur : $browserType | headless : $headless")

        val driver = when (browserType) {
            BrowserType.CHROME -> {
                log("[DEBUG] Recherche de Chrome sur le système…")
                // Selenium Manager (intégré dans selenium-java 4.x) détecte Chrome
                // et télécharge le ChromeDriver compatible si nécessaire (~/.cache/selenium/).
                // Ce téléchargement peut prendre 10-30s au premier lancement.
                val seleniumCache = File(System.getProperty("user.home"), ".cache/selenium")
                log("[DEBUG] Cache Selenium Manager : ${seleniumCache.absolutePath} (existe : ${seleniumCache.exists()})")
                if (seleniumCache.exists()) {
                    val drivers = seleniumCache.walkTopDown()
                        .filter { it.name.startsWith("chromedriver") && it.canExecute() }
                        .toList()
                    if (drivers.isNotEmpty()) {
                        log("[DEBUG] ChromeDriver(s) en cache : ${drivers.joinToString { it.relativeTo(seleniumCache).path }}")
                    } else {
                        log("[DEBUG] Aucun ChromeDriver en cache → Selenium Manager va le télécharger")
                    }
                } else {
                    log("[DEBUG] Cache absent → Selenium Manager va télécharger ChromeDriver (connexion internet requise)")
                }

                // Évite d'utiliser un chromedriver obsolète installé dans le PATH (ex. Homebrew).
                // Selenium Manager cache la bonne version dans ~/.cache/selenium/ — on l'utilise en priorité.
                val cachedDriver = findBestCachedChromeDriver()
                if (cachedDriver != null) {
                    log("[DEBUG] ChromeDriver depuis cache Selenium : ${cachedDriver.absolutePath}")
                    System.setProperty("webdriver.chrome.driver", cachedDriver.absolutePath)
                } else {
                    System.clearProperty("webdriver.chrome.driver")
                    log("[DEBUG] Aucun ChromeDriver en cache → Selenium Manager va le télécharger")
                }

                log("[DEBUG] Création ChromeOptions…")
                val opts = ChromeOptions()
                if (headless) {
                    opts.addArguments("--headless=new")
                    // --no-sandbox / --disable-dev-shm-usage uniquement sur Linux (Docker/CI) ;
                    // inutile et potentiellement problématique sur Windows et macOS.
                    if (System.getProperty("os.name").lowercase().contains("linux")) {
                        opts.addArguments("--no-sandbox", "--disable-dev-shm-usage")
                    }
                }
                opts.addArguments("--window-size=1920,1080", "--lang=fr-FR")
                log("[DEBUG] Lancement de ChromeDriver (peut prendre 10-30s si premier lancement)…")
                val t = System.currentTimeMillis()
                ChromeDriver(opts).also { d ->
                    log("[DEBUG] ChromeDriver démarré en ${System.currentTimeMillis() - t}ms")
                    runCatching {
                        val caps = d.capabilities
                        log("[DEBUG] Chrome version   : ${caps.getBrowserVersion()}")
                        @Suppress("UNCHECKED_CAST")
                        val info = caps.getCapability("chrome") as? Map<*, *>
                        if (info != null) {
                            log("[DEBUG] ChromeDriver ver : ${(info["chromedriverVersion"] as? String)?.substringBefore(" ") ?: "?"}")
                            log("[DEBUG] userDataDir      : ${info["userDataDir"]}")
                        }
                    }
                }
            }
            BrowserType.FIREFOX -> {
                val geckoDriver = resolveGeckoDriver()
                if (geckoDriver != null) {
                    log("[DEBUG] GeckoDriver : ${geckoDriver.absolutePath} (existe : ${geckoDriver.exists()}, exécutable : ${geckoDriver.canExecute()})")
                    System.setProperty("webdriver.gecko.driver", geckoDriver.absolutePath)
                } else {
                    log("[DEBUG] GeckoDriver embarqué introuvable → Selenium Manager")
                }
                val opts = FirefoxOptions()
                if (headless) opts.addArguments("-headless")
                log("[DEBUG] Lancement FirefoxDriver…")
                FirefoxDriver(opts).also { log("[DEBUG] FirefoxDriver démarré") }
            }
        }
        driver.manage().timeouts().pageLoadTimeout(Duration.ofSeconds(30))
        log("[DEBUG] pageLoadTimeout fixé à 30s")
        return driver
    }
}
