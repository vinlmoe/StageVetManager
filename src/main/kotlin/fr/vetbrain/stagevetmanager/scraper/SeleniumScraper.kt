package fr.vetbrain.stagevetmanager.scraper

import fr.vetbrain.stagevetmanager.model.Internship
import fr.vetbrain.stagevetmanager.model.ScrapeFilters
import okhttp3.OkHttpClient
import okhttp3.Request
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
import java.io.PrintWriter
import java.time.Duration
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.util.concurrent.TimeUnit
import java.util.zip.ZipInputStream

class SeleniumScraper(
    private val browserType: BrowserType = BrowserType.CHROME,
    private val headless: Boolean = false,
    private val chromeDriverPath: String = "",
    private val onProgress: (String) -> Unit = {},
) {
    enum class BrowserType { CHROME, FIREFOX }

    private var driver: WebDriver? = null

    // ── Logging vers fichier ─────────────────────────────────────────────────────
    private val sessionStart = System.currentTimeMillis()
    private val timeFmt = DateTimeFormatter.ofPattern("HH:mm:ss.SSS")

    private val logFile: File by lazy {
        val dir = File(System.getProperty("user.home"), "stagevetmanager/logs")
        dir.mkdirs()
        val stamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss"))
        File(dir, "scraper_$stamp.log")
    }

    // autoFlush=true : chaque ligne est écrite immédiatement même si le process hang
    private val logWriter: PrintWriter by lazy {
        PrintWriter(logFile.bufferedWriter(), true)
    }

    val logFilePath: String get() = logFile.absolutePath

    private fun ts() = LocalTime.now().format(timeFmt)
    private fun elapsed() = "+${System.currentTimeMillis() - sessionStart}ms"

    private fun log(msg: String) {
        val line = "${ts()} [${elapsed()}] $msg"
        System.err.println("[SVM] $line")
        runCatching { logWriter.println(line) }
        onProgress(msg)
    }

    private fun logSection(title: String) {
        val bar = "─".repeat(60)
        runCatching {
            logWriter.println("${ts()} [${elapsed()}] $bar")
            logWriter.println("${ts()} [${elapsed()}]  $title")
            logWriter.println("${ts()} [${elapsed()}] $bar")
        }
        System.err.println("[SVM] ── $title ──")
    }

    private fun logThrowable(msg: String, t: Throwable) {
        log("ERREUR $msg : ${t.javaClass.name}: ${t.message}")
        runCatching {
            t.printStackTrace(logWriter)
            var cause = t.cause
            var depth = 0
            while (cause != null && depth < 5) {
                logWriter.println("  Caused by: ${cause.javaClass.name}: ${cause.message}")
                cause.printStackTrace(logWriter)
                cause = cause.cause
                depth++
            }
        }
        // Flush explicite : autoFlush=true ne garantit pas le flush du BufferedWriter sous-jacent
        // avant que close() ne soit appelé depuis le finally du ViewModel.
        runCatching { logWriter.flush() }
    }

    private fun writeLogHeader() {
        runCatching {
            val eq = "═".repeat(60)
            logWriter.println(eq)
            logWriter.println("  StageVetManager — Session de scraping")
            logWriter.println("  Date/heure  : ${LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"))}")
            logWriter.println("  OS          : ${System.getProperty("os.name")} ${System.getProperty("os.version")} ${System.getProperty("os.arch")}")
            logWriter.println("  Java        : ${System.getProperty("java.version")} (${System.getProperty("java.vendor")})")
            logWriter.println("  java.io.tmp : ${System.getProperty("java.io.tmpdir")}")
            logWriter.println("  user.home   : ${System.getProperty("user.home")}")
            logWriter.println("  jpackage    : ${System.getProperty("jpackage.app-path") ?: "non (dev mode)"}")
            logWriter.println("  Navigateur  : $browserType | headless: $headless")
            logWriter.println("  ChromeDrv   : ${chromeDriverPath.ifBlank { "(auto)" }}")
            logWriter.println(eq)
            logWriter.flush()
        }
    }

    // ── API publique ─────────────────────────────────────────────────────────────

    fun login(username: String, password: String): Boolean {
        writeLogHeader()
        logSection("createDriver()")
        return try {
            driver = createDriver()
            val d = driver!!
            val t0 = System.currentTimeMillis()
            fun lap() = "${System.currentTimeMillis() - t0}ms"

            logSection("login() — navigation")
            log("Navigation vers https://www.stagevet.fr/login …")
            d.get("https://www.stagevet.fr/login")
            log("Page chargée (${lap()}) — URL : ${d.currentUrl}")

            val wait = WebDriverWait(d, Duration.ofSeconds(20))
            log("Attente #email visible (timeout 20s)…")
            wait.until(ExpectedConditions.visibilityOfElementLocated(By.cssSelector("#email")))
            log("Champ #email visible (${lap()}) — saisie des identifiants…")

            d.findElement(By.cssSelector("#email")).sendKeys(username)
            d.findElement(By.cssSelector("#password")).sendKeys(password)
            log("Clic sur button[type=submit]…")
            d.findElement(By.cssSelector("button[type='submit']")).click()
            log("Formulaire soumis (${lap()}) — URL : ${d.currentUrl}")

            log("Attente redirection /dashboard (timeout 20s)…")
            wait.until(ExpectedConditions.urlContains("dashboard"))
            log("Connexion réussie — URL : ${d.currentUrl} — durée totale : ${lap()}")
            true
        } catch (e: Exception) {
            logThrowable("login()", e)
            runCatching { log("URL au moment de l'erreur : ${driver?.currentUrl ?: "driver non initialisé"}") }
            false
        }
    }

    fun scrapeAllPages(
        filters: ScrapeFilters = ScrapeFilters(),
        onPageScraped: (List<Internship>) -> Unit = {},
    ): ScraperResult {
        val d = driver ?: return ScraperResult.Failure("Navigateur non initialisé")
        return try {
            logSection("scrapeAllPages()")
            val url = buildFilteredUrl(filters)
            log("Chargement dashboard : $url")
            val t0 = System.currentTimeMillis()
            d.get(url)
            log("Dashboard chargé (${System.currentTimeMillis() - t0}ms) — URL : ${d.currentUrl}")

            val wait = WebDriverWait(d, Duration.ofSeconds(20))
            log("sleep(1500) — attente rendu initial…")
            Thread.sleep(1500)
            try {
                wait.until(ExpectedConditions.presenceOfElementLocated(By.cssSelector("div.card")))
                log("Cartes de stages détectées.")
            } catch (_: Exception) {
                log("Aucun stage trouvé avec ces filtres.")
                return ScraperResult.Success(0, 0)
            }

            var totalCount = 0
            var pageCount = 0

            while (true) {
                log("sleep(1500) — stabilisation page ${ pageCount + 1}…")
                Thread.sleep(1500)
                // getPageSource() est @Nullable côté Selenium : le parser recevait un
                // String non-null par inférence, d'où un NPE possible en fin de session.
                val html = d.pageSource ?: ""
                val pageInternships = DashboardParser.parse(html)
                pageCount++
                totalCount += pageInternships.size

                onPageScraped(pageInternships)
                log("Page $pageCount : ${pageInternships.size} stage(s) (total : $totalCount)")

                val nextBtn = findNextButton(d)
                if (nextBtn == null) { log("Bouton Suivant absent — fin de pagination."); break }

                val firstCard = try { d.findElement(By.cssSelector("div.card")) } catch (_: Exception) { null }
                log("Clic Suivant → page ${pageCount + 1}…")
                nextBtn.click()

                if (firstCard != null) {
                    try {
                        WebDriverWait(d, Duration.ofSeconds(10))
                            .until(ExpectedConditions.stalenessOf(firstCard))
                        log("Page suivante chargée (staleness OK)")
                    } catch (_: Exception) {
                        log("stalenessOf timeout — sleep(2000) de secours…")
                        Thread.sleep(2000)
                    }
                }
            }

            log("Extraction terminée : $totalCount stage(s) sur $pageCount page(s)")
            ScraperResult.Success(totalCount, pageCount)
        } catch (e: Exception) {
            logThrowable("scrapeAllPages()", e)
            ScraperResult.Failure("Erreur lors du scraping : ${e.message}", e)
        }
    }

    fun getSessionCookies(): Map<String, String> =
        driver?.manage()?.cookies?.associate { it.name to it.value } ?: emptyMap()

    /** Ajoute au journal Selenium les étapes effectuées ensuite par le transport HTTP. */
    fun logHttpProgress(message: String) {
        log("HTTP dashboard — $message")
    }

    fun close() {
        try { driver?.quit() } catch (_: Exception) {}
        driver = null
        runCatching { logWriter.println("${ts()} [${elapsed()}] ── Session terminée ──"); logWriter.close() }
    }

    // ── Création du driver ───────────────────────────────────────────────────────

    private fun createDriver(): WebDriver {
        val os = System.getProperty("os.name").lowercase()
        val isWindows = os.contains("win")

        log("OS : ${System.getProperty("os.name")} | arch : ${System.getProperty("os.arch")}")
        log("Java : ${System.getProperty("java.version")} | tmp : ${System.getProperty("java.io.tmpdir")}")
        log("Navigateur : $browserType | headless : $headless | isWindows : $isWindows")

        val driver = when (browserType) {
            BrowserType.CHROME -> createChromeDriver(isWindows)
            BrowserType.FIREFOX -> try {
                createFirefoxDriver(os)
            } catch (e: FirefoxNotInstalledException) {
                log("Firefox indisponible : ${e.message}")
                log("Repli automatique vers Chrome…")
                onProgress("Firefox introuvable — utilisation de Chrome…")
                createChromeDriver(isWindows)
            }
        }

        log("pageLoadTimeout → 30s")
        driver.manage().timeouts().pageLoadTimeout(Duration.ofSeconds(30))
        log("Driver prêt.")
        return driver
    }

    private fun createChromeDriver(isWindows: Boolean): WebDriver {
        logSection("ChromeDriver — résolution du binaire")
        val driverBinary = if (isWindows) "chromedriver.exe" else "chromedriver"

        // Hors Windows, laisser Selenium Manager choisir lui-même une version compatible.
        // Forcer le « dernier » fichier du cache peut sélectionner un pilote obsolète
        // après une mise à jour automatique de Chrome.
        val resolved: File? = when {
            chromeDriverPath.isNotBlank() -> {
                val f = File(chromeDriverPath)
                log("Chemin paramètre : ${f.absolutePath} | existe=${f.exists()} canExec=${f.canExecute()}")
                f.takeIf { it.exists() && it.canExecute() }
                    ?: throw RuntimeException("ChromeDriver introuvable au chemin configuré : $chromeDriverPath")
            }
            !isWindows -> {
                log("Résolution automatique par Selenium Manager (cache compatible inclus).")
                null
            }
            else -> {
                log("Vérification cache Selenium Manager (~/.cache/selenium/chromedriver/)…")
                findBestCachedChromeDriver()?.also { log("Cache SM : ${it.absolutePath}") }
                    ?: run {
                        log("Cache SM vide.")
                        log("Vérification dossier application (jpackage.app-path)…")
                        findDriverNextToApp(driverBinary)?.also { log("Dossier app : ${it.absolutePath}") }
                    }
                    ?: run {
                        log("Non trouvé dans le dossier app.")
                        log("Vérification PATH système…")
                        findExecutableInPath(driverBinary)?.also { log("PATH : ${it.absolutePath}") }
                    }
            }
        }

        if (resolved != null) {
            log("webdriver.chrome.driver → ${resolved.absolutePath}")
            System.setProperty("webdriver.chrome.driver", resolved.absolutePath)
        } else if (isWindows) {
            logSection("ChromeDriver — auto-download OkHttp (Windows)")
            val downloaded = downloadChromeDriverForWindows()
            if (downloaded != null) {
                log("webdriver.chrome.driver → ${downloaded.absolutePath}")
                System.setProperty("webdriver.chrome.driver", downloaded.absolutePath)
            } else {
                throw RuntimeException(
                    "ChromeDriver introuvable et téléchargement automatique échoué.\n\n" +
                    "Vérifiez :\n" +
                    "• Chrome est installé sur ce Windows\n" +
                    "• La connexion internet est disponible\n\n" +
                    "Sinon, placez chromedriver.exe dans le dossier de StageVetManager.exe\n" +
                    "ou configurez son chemin dans Paramètres.\n\n" +
                    "Log complet : $logFilePath"
                )
            }
        } else {
            System.clearProperty("webdriver.chrome.driver")
            log("Aucun ChromeDriver local → Selenium Manager va le télécharger")
        }

        logSection("ChromeDriver — lancement du processus")
        val opts = ChromeOptions()
        if (headless) {
            opts.addArguments("--headless=new")
            if (System.getProperty("os.name").lowercase().contains("linux")) {
                opts.addArguments("--no-sandbox", "--disable-dev-shm-usage")
            }
        }
        opts.addArguments("--window-size=1920,1080", "--lang=fr-FR")
        log("ChromeOptions : ${opts.asMap()}")

        // Pré-chauffe : laisse l'AV scanner chromedriver.exe avant que Selenium ne le lance
        val cdriverPath = System.getProperty("webdriver.chrome.driver")
        if (cdriverPath != null) preWarmExecutable(File(cdriverPath))

        val t = System.currentTimeMillis()
        return launchDriverWithTimeout("ChromeDriver") {
            ChromeDriver(opts).also { d ->
                log("ChromeDriver démarré en ${System.currentTimeMillis() - t}ms")
                runCatching {
                    val caps = d.capabilities
                    log("Chrome version    : ${caps.getBrowserVersion()}")
                    @Suppress("UNCHECKED_CAST")
                    val info = caps.getCapability("chrome") as? Map<*, *>
                    if (info != null) {
                        log("ChromeDriver ver  : ${(info["chromedriverVersion"] as? String)?.substringBefore(" ") ?: "?"}")
                        log("userDataDir       : ${info["userDataDir"]}")
                    }
                }
            }
        }
    }

    private fun createFirefoxDriver(os: String): WebDriver {
        logSection("FirefoxDriver — résolution GeckoDriver")
        val geckoDriver = resolveGeckoDriver()
        if (geckoDriver != null) {
            log("GeckoDriver résolu : ${geckoDriver.absolutePath}")
            log("  existe=${geckoDriver.exists()} | canExec=${geckoDriver.canExecute()} | taille=${geckoDriver.length()}B")
            System.setProperty("webdriver.gecko.driver", geckoDriver.absolutePath)
        } else {
            log("GeckoDriver embarqué introuvable dans les ressources → Selenium Manager")
        }

        val opts = FirefoxOptions()
        logSection("FirefoxDriver — localisation Firefox")
        val candidates = FirefoxBinaryLocator.candidatePaths(
            osName = os,
            userHome = System.getProperty("user.home"),
            environment = System.getenv(),
        )
        candidates.forEach { log("  Firefox candidat : $it | existe=${File(it).exists()}") }
        val firefoxBinary = FirefoxBinaryLocator.find(candidates)
            ?: throw FirefoxNotInstalledException(
                "aucun binaire Firefox trouvé (${FirefoxBinaryLocator.platformLabel(os)})"
            )
        log("Firefox trouvé : ${firefoxBinary.absolutePath}")
        opts.setBinary(firefoxBinary.absolutePath)
        if (headless) opts.addArguments("-headless")

        // Pré-chauffe : laisse l'AV scanner geckodriver.exe avant que Selenium ne le lance
        if (geckoDriver != null) preWarmExecutable(geckoDriver)

        logSection("FirefoxDriver — lancement du processus")
        val t = System.currentTimeMillis()
        return launchDriverWithTimeout("FirefoxDriver") {
            FirefoxDriver(opts).also { log("FirefoxDriver démarré en ${System.currentTimeMillis() - t}ms") }
        }
    }

    // ── Auto-download ChromeDriver via OkHttp (Windows) ─────────────────────────
    // Utilise OkHttp : download JVM → pas de sous-process extrait → pas de SmartScreen.

    private fun downloadChromeDriverForWindows(): File? {
        log("Détection version Chrome depuis le registre Windows…")
        val chromeVersion = detectChromeVersionOnWindows()
        if (chromeVersion == null) {
            log("Chrome non détecté — vérifiez l'installation de Chrome")
            return null
        }
        log("Chrome version : $chromeVersion")
        val major = chromeVersion.split(".").firstOrNull() ?: return null

        log("Requête LATEST_RELEASE_$major sur googlechromelabs.github.io…")
        val driverVersion = fetchChromeDriverVersion(major)
        if (driverVersion == null) {
            log("Réponse vide ou erreur réseau pour LATEST_RELEASE_$major")
            return null
        }
        log("ChromeDriver cible : $driverVersion")

        val cacheDir = File(System.getProperty("user.home"), ".cache/selenium/chromedriver/win32/$driverVersion")
        val destFile = File(cacheDir, "chromedriver.exe")
        if (destFile.exists() && destFile.canExecute()) {
            log("Déjà en cache : ${destFile.absolutePath}")
            return destFile
        }

        log("Téléchargement ChromeDriver $driverVersion (première utilisation)…")
        onProgress("Téléchargement ChromeDriver $driverVersion…")
        return if (downloadAndExtractChromeDriver(driverVersion, destFile)) {
            log("ChromeDriver prêt : ${destFile.absolutePath} (${destFile.length()} B)")
            destFile
        } else {
            log("Échec du téléchargement ChromeDriver")
            null
        }
    }

    private fun detectChromeVersionOnWindows(): String? {
        val rx = Regex("""(\d+\.\d+\.\d+\.\d+)""")
        val regQueries = listOf(
            arrayOf("reg", "query", "HKEY_CURRENT_USER\\Software\\Google\\Chrome\\BLBeacon", "/v", "version"),
            arrayOf("reg", "query", "HKEY_LOCAL_MACHINE\\Software\\Google\\Chrome\\BLBeacon", "/v", "version"),
            arrayOf("reg", "query", "HKEY_LOCAL_MACHINE\\Software\\Wow6432Node\\Google\\Chrome\\BLBeacon", "/v", "version"),
        )
        for (cmd in regQueries) {
            val keyPath = cmd[2]
            log("  reg query $keyPath…")
            runCatching {
                val proc = Runtime.getRuntime().exec(cmd)
                val out = proc.inputStream.bufferedReader().readText()
                val err = proc.errorStream.bufferedReader().readText()
                log("    stdout: ${out.trim().take(120)}")
                if (err.isNotBlank()) log("    stderr: ${err.trim().take(80)}")
                rx.find(out)?.groupValues?.get(1)?.let { v -> log("    → version : $v"); return v }
            }.onFailure { log("    exception : ${it.javaClass.simpleName}: ${it.message}") }
        }
        // Repli : chrome.exe --version
        val chromePaths = listOfNotNull(
            System.getenv("ProgramFiles")?.let { "$it\\Google\\Chrome\\Application\\chrome.exe" },
            System.getenv("ProgramFiles(x86)")?.let { "$it\\Google\\Chrome\\Application\\chrome.exe" },
            System.getenv("LOCALAPPDATA")?.let { "$it\\Google\\Chrome\\Application\\chrome.exe" },
        )
        for (path in chromePaths) {
            log("  Tentative chrome.exe --version : $path")
            if (!File(path).exists()) { log("    absent"); continue }
            runCatching {
                val proc = Runtime.getRuntime().exec(arrayOf(path, "--version"))
                val out = proc.inputStream.bufferedReader().readText()
                log("    stdout: ${out.trim().take(120)}")
                rx.find(out)?.groupValues?.get(1)?.let { v -> log("    → version : $v"); return v }
            }.onFailure { log("    exception : ${it.javaClass.simpleName}: ${it.message}") }
        }
        log("  Aucune version Chrome détectée.")
        return null
    }

    private fun fetchChromeDriverVersion(majorVersion: String): String? = runCatching {
        val client = OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(10, TimeUnit.SECONDS)
            .build()
        val url = "https://googlechromelabs.github.io/chrome-for-testing/LATEST_RELEASE_$majorVersion"
        log("  GET $url")
        client.newCall(Request.Builder().url(url).build()).execute().use { resp ->
            log("  HTTP ${resp.code}")
            if (resp.isSuccessful) resp.body?.string()?.trim().also { log("  body: $it") } else null
        }
    }.onFailure { log("  fetchChromeDriverVersion exception: ${it.javaClass.simpleName}: ${it.message}") }.getOrNull()

    private fun downloadAndExtractChromeDriver(version: String, destFile: File): Boolean = runCatching {
        val client = OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(120, TimeUnit.SECONDS)
            .build()
        val url = "https://storage.googleapis.com/chrome-for-testing-public/$version/win32/chromedriver-win32.zip"
        log("  GET $url")
        val zipBytes = client.newCall(Request.Builder().url(url).build()).execute().use { resp ->
            log("  HTTP ${resp.code} — Content-Length: ${resp.header("Content-Length") ?: "?"}")
            if (!resp.isSuccessful) return@runCatching false
            resp.body?.bytes() ?: return@runCatching false
        }
        log("  ZIP reçu : ${zipBytes.size} B")
        destFile.parentFile?.mkdirs()
        ZipInputStream(zipBytes.inputStream()).use { zis ->
            var entry = zis.nextEntry
            while (entry != null) {
                log("  ZIP entry: ${entry.name}")
                if (entry.name.endsWith("chromedriver.exe")) {
                    destFile.outputStream().use { out -> zis.copyTo(out) }
                    log("  Extrait → ${destFile.absolutePath} (${destFile.length()} B)")
                    return@runCatching true
                }
                entry = zis.nextEntry
            }
        }
        log("  chromedriver.exe introuvable dans le ZIP")
        false
    }.onFailure { logThrowable("downloadAndExtractChromeDriver", it) }.getOrElse { false }

    // ── Timeout + pré-chauffe AV ─────────────────────────────────────────────────

    /**
     * Lance [block] dans un thread dédié avec un timeout de [timeoutSec] secondes.
     * Si le thread n'a pas répondu à temps (AV/SmartScreen bloque l'exécutable),
     * on logue un message explicite et on lève une RuntimeException.
     * Le thread orphelin continue en arrière-plan mais ne bloque plus l'UI.
     */
    private fun <T : WebDriver> launchDriverWithTimeout(
        driverName: String,
        timeoutSec: Long = 90,
        block: () -> T,
    ): T {
        log("Lancement de $driverName dans un thread dédié (timeout ${timeoutSec}s)…")
        var result: T? = null
        var thrown: Throwable? = null
        val thread = Thread({
            try { result = block() } catch (e: Throwable) { thrown = e }
        }, "driver-launch-$driverName").apply { isDaemon = true; start() }

        thread.join(timeoutSec * 1000L)

        return when {
            result != null -> result!!.also { log("$driverName démarré avec succès.") }
            thrown != null -> { logThrowable("$driverName()", thrown!!); throw thrown!! }
            else -> {
                // Thread toujours vivant → timeout
                log("TIMEOUT ${timeoutSec}s — $driverName n'a pas répondu.")
                log("Cause probable : antivirus (Windows Defender) bloque l'exécution de l'exécutable.")
                log("Solution : exclure de l'antivirus :")
                log("  • ${System.getProperty("user.home")}\\stagevetmanager\\")
                log("  • ${System.getProperty("user.home")}\\.cache\\selenium\\")
                throw RuntimeException(
                    "Timeout ${timeoutSec}s — $driverName n'a pas démarré.\n\n" +
                    "Cause probable : votre antivirus bloque l'exécution du driver.\n\n" +
                    "Solution : ajoutez ces dossiers aux exclusions de votre antivirus :\n" +
                    "  • ${System.getProperty("user.home")}\\stagevetmanager\\\n" +
                    "  • ${System.getProperty("user.home")}\\.cache\\selenium\\\n\n" +
                    "Log complet : $logFilePath"
                )
            }
        }
    }

    /**
     * Exécute l'exécutable avec --version avant que Selenium ne le lance.
     * Cela laisse à l'AV Windows le temps de le scanner et de l'autoriser,
     * évitant ainsi le blocage lors du vrai lancement par Selenium.
     */
    private fun preWarmExecutable(file: File) {
        log("Pré-chauffe AV : exécution de ${file.name} --version (10s max)…")
        val t = System.currentTimeMillis()
        try {
            val proc = ProcessBuilder(file.absolutePath, "--version")
                .redirectErrorStream(true)
                .start()
            val completed = proc.waitFor(10, TimeUnit.SECONDS)
            val output = proc.inputStream.bufferedReader().readText().trim()
            if (completed) {
                log("  --version OK (${System.currentTimeMillis() - t}ms) : ${output.take(100)}")
            } else {
                proc.destroyForcibly()
                log("  --version TIMEOUT 10s — AV bloque probablement l'exécutable (${System.currentTimeMillis() - t}ms)")
                log("  → le lancement Selenium risque aussi d'être bloqué")
            }
        } catch (e: Exception) {
            log("  --version exception (${System.currentTimeMillis() - t}ms) : ${e.javaClass.simpleName}: ${e.message}")
        }
    }

    // ── Helpers navigation ───────────────────────────────────────────────────────

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

    private fun findNextButton(d: WebDriver): WebElement? = runCatching {
        d.findElements(
            By.xpath("//a[@class='page-link' and @rel='next' and contains(@aria-label,'Suivant')]")
        ).firstOrNull()?.takeIf { it.isEnabled && it.isDisplayed }
    }.getOrNull()

    // ── Helpers de détection ─────────────────────────────────────────────────────

    private fun findExecutableInPath(name: String): File? =
        System.getenv("PATH")
            ?.split(File.pathSeparator)
            ?.map { File(it, name) }
            ?.firstOrNull { it.exists() && it.canExecute() }

    private fun findDriverNextToApp(name: String): File? {
        val appPath = System.getProperty("jpackage.app-path") ?: return null
        return File(appPath).parentFile?.let { File(it, name) }?.takeIf { it.exists() && it.canExecute() }
    }

    // ── Companion (méthodes statiques réutilisables) ─────────────────────────────

    companion object {
        private const val GECKO_VERSION = "0.35.0"

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
            // Tri sémantique obligatoire : lexicographiquement "99.0.4844.51" est
            // supérieur à "120.0.6099.109", ce qui sélectionnait le pilote le plus
            // ancien après une mise à jour de Chrome (SessionNotCreatedException).
            return cacheDir.listFiles()
                ?.filter { it.isDirectory }
                ?.filter { File(it, binary).let { f -> f.exists() && f.canExecute() } }
                ?.maxWithOrNull(compareBy(VERSION_ORDER) { it.name })
                ?.let { File(it, binary) }
        }

        /** Compare des versions "120.0.6099.109" composant numérique par composant. */
        internal val VERSION_ORDER: Comparator<String> = Comparator { left, right ->
            val a = parseVersion(left)
            val b = parseVersion(right)
            var result = 0
            for (i in 0 until maxOf(a.size, b.size)) {
                result = (a.getOrElse(i) { 0 }).compareTo(b.getOrElse(i) { 0 })
                if (result != 0) break
            }
            // Départage stable des noms non numériques ("120.0.1" vs "120.0.1-beta").
            if (result != 0) result else left.compareTo(right)
        }

        private fun parseVersion(name: String): List<Int> =
            name.split('.').map { part -> part.takeWhile(Char::isDigit).toIntOrNull() ?: 0 }

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
            // ~/stagevetmanager/ est moins surveillé par l'AV que %TEMP%,
            // et persiste entre les sessions (évite de ré-extraire à chaque lancement).
            val appDir = File(System.getProperty("user.home"), "stagevetmanager").also { it.mkdirs() }
            val dest = File(appDir, "geckodriver-v${GECKO_VERSION}$ext")
            if (dest.exists() && dest.canExecute()) return dest
            val stream = SeleniumScraper::class.java.classLoader.getResourceAsStream(resource) ?: return null
            stream.use { input -> dest.outputStream().use { input.copyTo(it) } }
            dest.setExecutable(true)
            return dest
        }
    }
}
