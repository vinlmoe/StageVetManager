package fr.vetbrain.stagevetmanager.viewmodel

import fr.vetbrain.stagevetmanager.export.ExcelExporter
import fr.vetbrain.stagevetmanager.model.ConventionPdfData
import fr.vetbrain.stagevetmanager.model.Internship
import fr.vetbrain.stagevetmanager.model.ScrapeFilters
import fr.vetbrain.stagevetmanager.model.ViewFilter
import fr.vetbrain.stagevetmanager.onedrive.OneDriveAuthClient
import fr.vetbrain.stagevetmanager.onedrive.OneDriveExcelUpdater
import fr.vetbrain.stagevetmanager.persistence.LocalDatabase
import fr.vetbrain.stagevetmanager.persistence.UpsertStats
import fr.vetbrain.stagevetmanager.scraper.ConventionPdfParser
import fr.vetbrain.stagevetmanager.scraper.PdfDownloader
import fr.vetbrain.stagevetmanager.scraper.ScraperResult
import fr.vetbrain.stagevetmanager.scraper.SeleniumScraper
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import java.nio.file.Path
import java.time.LocalDate
import java.time.format.DateTimeFormatter

class DashboardViewModel {

    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    val allInternships  = MutableStateFlow<List<Internship>>(emptyList())
    val scrapeFilters   = MutableStateFlow(ScrapeFilters())
    val filterText      = MutableStateFlow("")
    val activeFilter    = MutableStateFlow(ViewFilter.ALL)
    val isLoading       = MutableStateFlow(false)
    val statusMessage   = MutableStateFlow("Initialisation…")
    val errorMessage    = MutableStateFlow<String?>(null)
    val sortColumn      = MutableStateFlow(SortColumn.STUDENT)
    val sortAscending   = MutableStateFlow(true)
    val dbCount         = MutableStateFlow(0)

    // — PDF extraction ————————————————————————————————————————————————————
    val selectedPdfData = MutableStateFlow<ConventionPdfData?>(null)
    val isPdfLoading    = MutableStateFlow(false)
    val selectedInternship = MutableStateFlow<Internship?>(null)
    private val _pdfDataCache = MutableStateFlow<Map<String, ConventionPdfData>>(emptyMap())
    val pdfDataCache: StateFlow<Map<String, ConventionPdfData>> = _pdfDataCache
    val hasSessionCookies get() = sessionCookies.isNotEmpty()
    // Cookies Selenium récupérés après login — valides jusqu'à la prochaine extraction
    private var sessionCookies: Map<String, String> = emptyMap()

    val displayed: StateFlow<List<Internship>> = combine(
        allInternships, filterText, activeFilter, sortColumn, sortAscending
    ) { list, text, filter, col, asc ->
        val filtered = list.filter(filter.predicate).filter { it.matchesText(text) }
        val sorted = when (col) {
            SortColumn.STUDENT      -> filtered.sortedBy { it.studentName }
            SortColumn.YEAR         -> filtered.sortedBy { it.studyYear }
            SortColumn.ORGANIZATION -> filtered.sortedBy { it.organization }
            SortColumn.START_DATE   -> filtered.sortedWith(compareBy(nullsLast()) { it.startDate })
            SortColumn.SIGN_DATE    -> filtered.sortedWith(compareBy(nullsLast()) { it.signingDate })
            SortColumn.THEME        -> filtered.sortedBy { it.theme }
        }
        if (asc) sorted else sorted.reversed()
    }.stateIn(scope, SharingStarted.Eagerly, emptyList())

    init {
        scope.launch {
            withContext(Dispatchers.IO) { LocalDatabase.instance.init() }
            loadFromDatabase()
            val cache = withContext(Dispatchers.IO) { LocalDatabase.instance.loadAllPdfData() }
            _pdfDataCache.value = cache
        }
    }

    fun setFilter(text: String) { filterText.value = text }
    fun setView(filter: ViewFilter) { activeFilter.value = filter }
    fun setScrapeFilters(f: ScrapeFilters) { scrapeFilters.value = f }

    fun toggleSort(col: SortColumn) {
        if (sortColumn.value == col) {
            sortAscending.value = !sortAscending.value
        } else {
            sortColumn.value = col
            sortAscending.value = true
        }
    }

    fun loadFromDatabase() {
        scope.launch {
            statusMessage.value = "Chargement depuis la base locale…"
            val (internships, count) = withContext(Dispatchers.IO) {
                LocalDatabase.instance.loadAll() to LocalDatabase.instance.count()
            }
            allInternships.value = internships
            dbCount.value = count
            statusMessage.value = if (internships.isEmpty())
                "Base locale vide — cliquez sur Extraire"
            else
                "${internships.size} stage(s) chargés depuis la base locale"
        }
    }

    fun scrape(
        username: String,
        password: String,
        browserType: SeleniumScraper.BrowserType,
        headless: Boolean,
    ) {
        if (isLoading.value) return
        scope.launch {
            isLoading.value = true
            errorMessage.value = null
            allInternships.value = emptyList()
            statusMessage.value = "Connexion en cours…"

            // Stats cumulées sur toutes les pages
            var totalAdded = 0
            var totalUpdated = 0

            val result = withContext(Dispatchers.IO) {
                val scraper = SeleniumScraper(
                    browserType = browserType,
                    headless = headless,
                    onProgress = { msg ->
                        scope.launch(Dispatchers.Main) { statusMessage.value = msg }
                    }
                )
                try {
                    val loggedIn = scraper.login(username, password)
                    if (!loggedIn) {
                        ScraperResult.Failure("Identifiants incorrects ou timeout de connexion")
                    } else {
                        val result = scraper.scrapeAllPages(filters = scrapeFilters.value) { pageInternships ->
                            val stats: UpsertStats = LocalDatabase.instance.upsertAll(pageInternships)
                            totalAdded += stats.added
                            totalUpdated += stats.updated
                            scope.launch(Dispatchers.Main) {
                                allInternships.value = allInternships.value + pageInternships
                            }
                        }
                        // Capturer les cookies AVANT la fermeture du navigateur
                        sessionCookies = scraper.getSessionCookies()
                        result
                    }
                } finally {
                    scraper.close()
                }
            }

            when (result) {
                is ScraperResult.Success -> {
                    // Recharger depuis la DB pour avoir l'état dédoublonné final
                    val (fromDb, count) = withContext(Dispatchers.IO) {
                        LocalDatabase.instance.loadAll() to LocalDatabase.instance.count()
                    }
                    allInternships.value = fromDb
                    dbCount.value = count
                    statusMessage.value = buildString {
                        append("${result.totalCount} stage(s) extraits — ")
                        append("$totalAdded nouveau(x), $totalUpdated mis à jour")
                        append(" — base : $count au total")
                    }
                    // Lancer le parsing automatique des nouvelles conventions
                    val urlsToAutoParse = fromDb
                        .mapNotNull { it.conventionPdfUrl.takeIf { u -> u.isNotEmpty() && u !in _pdfDataCache.value } }
                        .distinct()
                    if (urlsToAutoParse.isNotEmpty()) {
                        launch { autoParseNewPdfs(urlsToAutoParse) }
                    }
                }
                is ScraperResult.Failure -> {
                    // Même en cas d'erreur, recharger ce qui est en base
                    loadFromDatabase()
                    errorMessage.value = result.message
                    statusMessage.value = "Erreur lors de l'extraction"
                }
            }
            isLoading.value = false
        }
    }

    /**
     * Télécharge la convention PDF depuis [url] et extrait ses champs.
     * Nécessite une session active (scraping préalable dans la même session).
     * Le résultat est exposé dans [selectedPdfData].
     */
    fun downloadConventionPdf(url: String) {
        if (sessionCookies.isEmpty()) {
            errorMessage.value = "Session expirée — relancez une extraction pour reconnecter"
            return
        }
        if (isPdfLoading.value) return
        scope.launch {
            isPdfLoading.value = true
            statusMessage.value = "Téléchargement de la convention…"
            withContext(Dispatchers.IO) {
                try {
                    val bytes = PdfDownloader(sessionCookies).download(url)
                    val data  = ConventionPdfParser.parse(bytes, sourceUrl = url)
                    LocalDatabase.instance.savePdfData(data)
                    scope.launch(Dispatchers.Main) {
                        selectedPdfData.value = data
                        _pdfDataCache.value = _pdfDataCache.value + (url to data)
                        statusMessage.value = "Convention téléchargée et analysée"
                    }
                } catch (e: Exception) {
                    scope.launch(Dispatchers.Main) {
                        errorMessage.value = "Téléchargement PDF échoué : ${e.message}"
                        statusMessage.value = "Erreur téléchargement PDF"
                    }
                }
            }
            isPdfLoading.value = false
        }
    }

    private suspend fun autoParseNewPdfs(urls: List<String>) {
        val total = urls.size
        statusMessage.value = "Analyse automatique de $total convention(s)…"
        withContext(Dispatchers.IO) {
            val downloader = PdfDownloader(sessionCookies)
            var done = 0
            for (url in urls) {
                runCatching {
                    val bytes = downloader.download(url)
                    val data  = ConventionPdfParser.parse(bytes, sourceUrl = url)
                    LocalDatabase.instance.savePdfData(data)
                    scope.launch(Dispatchers.Main) {
                        _pdfDataCache.value = _pdfDataCache.value + (url to data)
                    }
                }
                done++
                val d = done
                scope.launch(Dispatchers.Main) { statusMessage.value = "Conventions analysées : $d/$total" }
            }
            scope.launch(Dispatchers.Main) {
                statusMessage.value = "$done/$total convention(s) analysée(s) et sauvegardées"
            }
        }
    }

    fun selectInternship(internship: Internship?) {
        selectedInternship.value = internship
    }

    fun clearDatabase() {
        scope.launch {
            withContext(Dispatchers.IO) { LocalDatabase.instance.clear() }
            allInternships.value = emptyList()
            dbCount.value = 0
            statusMessage.value = "Base locale vidée"
        }
    }

    fun exportToExcel(path: Path) {
        scope.launch {
            isLoading.value = true
            statusMessage.value = "Export Excel en cours…"
            withContext(Dispatchers.IO) {
                try {
                    ExcelExporter.export(allInternships.value, path)
                } catch (e: Exception) {
                    withContext(Dispatchers.Main) {
                        errorMessage.value = "Export échoué : ${e.message}"
                    }
                    return@withContext
                }
            }
            statusMessage.value = "Export réussi : ${path.fileName}"
            isLoading.value = false
        }
    }

    fun exportToOneDrive(clientId: String, remotePath: String) {
        if (clientId.isBlank()) {
            errorMessage.value = "Client ID Azure non configuré — allez dans Paramètres"
            return
        }
        if (isLoading.value) return
        scope.launch {
            isLoading.value = true
            errorMessage.value = null
            statusMessage.value = "Authentification Microsoft…"
            withContext(Dispatchers.IO) {
                try {
                    val auth = OneDriveAuthClient(clientId)
                    val token = auth.acquireToken { code ->
                        scope.launch(Dispatchers.Main) { statusMessage.value = code }
                    }
                    scope.launch(Dispatchers.Main) { statusMessage.value = "Mise à jour OneDrive en cours…" }
                    OneDriveExcelUpdater(token).update(allInternships.value, remotePath)
                    scope.launch(Dispatchers.Main) {
                        statusMessage.value = "OneDrive mis à jour : $remotePath"
                    }
                } catch (e: Exception) {
                    scope.launch(Dispatchers.Main) {
                        errorMessage.value = "Export OneDrive échoué : ${e.message}"
                        statusMessage.value = "Erreur export OneDrive"
                    }
                }
            }
            isLoading.value = false
        }
    }

    fun signOutOneDrive(clientId: String) {
        scope.launch(Dispatchers.IO) {
            runCatching { OneDriveAuthClient(clientId).signOut() }
            scope.launch(Dispatchers.Main) { statusMessage.value = "Déconnecté de Microsoft" }
        }
    }

    fun suggestedExportFileName(): String {
        val today = LocalDate.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd"))
        return "${today}_extract_stagevet.xlsx"
    }

    fun dispose() {
        scope.cancel()
    }
}

enum class SortColumn { STUDENT, YEAR, ORGANIZATION, START_DATE, SIGN_DATE, THEME }
