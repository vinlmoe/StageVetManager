package fr.vetbrain.stagevetmanager.viewmodel

import fr.vetbrain.stagevetmanager.export.ExcelExporter
import fr.vetbrain.stagevetmanager.model.ClinicStatus
import fr.vetbrain.stagevetmanager.model.ConventionPdfData
import fr.vetbrain.stagevetmanager.model.Internship
import fr.vetbrain.stagevetmanager.model.LocalFilters
import fr.vetbrain.stagevetmanager.model.ScrapeFilterOptions
import fr.vetbrain.stagevetmanager.model.ScrapeFilters
import fr.vetbrain.stagevetmanager.model.TrackingTarget
import fr.vetbrain.stagevetmanager.model.ViewFilter
import fr.vetbrain.stagevetmanager.export.LocalExcelUpdater
import fr.vetbrain.stagevetmanager.export.LocalTrackingUpdater
import fr.vetbrain.stagevetmanager.export.VetAgroTiceCsvExporter
import fr.vetbrain.stagevetmanager.persistence.LocalDatabase
import fr.vetbrain.stagevetmanager.persistence.UpsertStats
import fr.vetbrain.stagevetmanager.persistence.localId
import fr.vetbrain.stagevetmanager.scraper.ConventionPdfParser
import fr.vetbrain.stagevetmanager.scraper.DashboardHttpScraper
import fr.vetbrain.stagevetmanager.scraper.PdfDownloader
import fr.vetbrain.stagevetmanager.scraper.ScraperResult
import fr.vetbrain.stagevetmanager.scraper.SeleniumScraper
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import java.awt.Desktop
import java.net.URI
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.time.LocalDate
import java.time.format.DateTimeFormatter

private data class FilterState(
    val internships: List<Internship>,
    val text: String,
    val viewFilter: ViewFilter,
    val localFilters: LocalFilters,
)

class DashboardViewModel {

    // Sans handler, le SupervisorJob avale les exceptions non rattrapées : l'UI
    // resterait bloquée sur isLoading=true sans qu'aucun message ne soit affiché.
    private val crashHandler = CoroutineExceptionHandler { _, throwable ->
        if (throwable is CancellationException) return@CoroutineExceptionHandler
        System.err.println("[SVM] Erreur non rattrapée : ${throwable.javaClass.name}: ${throwable.message}")
        throwable.printStackTrace()
        isLoading.value = false
        isPdfLoading.value = false
        errorMessage.value = "Erreur inattendue : ${throwable.message ?: throwable.javaClass.simpleName}"
        statusMessage.value = "Erreur — opération interrompue"
    }

    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob() + crashHandler)

    val allInternships  = MutableStateFlow<List<Internship>>(emptyList())
    val scrapeFilters   = MutableStateFlow(ScrapeFilters())
    val localFilters    = MutableStateFlow(LocalFilters())
    val filterText      = MutableStateFlow("")
    val activeFilter    = MutableStateFlow(ViewFilter.ALL)
    val isLoading       = MutableStateFlow(false)
    val statusMessage   = MutableStateFlow("Initialisation…")
    val errorMessage    = MutableStateFlow<String?>(null)
    val sortColumn      = MutableStateFlow(SortColumn.STUDENT)
    val sortAscending   = MutableStateFlow(true)
    val dbCount         = MutableStateFlow(0)
    val trackingWarnings = MutableStateFlow<List<String>>(emptyList())

    // — Statuts cliniques —————————————————————————————————————————————————
    val clinicStatuses = MutableStateFlow<Map<String, ClinicStatus>>(emptyMap())

    fun setClinicStatus(organization: String, status: ClinicStatus) {
        scope.launch(Dispatchers.IO) {
            LocalDatabase.instance.setClinicStatus(organization, status)
            clinicStatuses.value = LocalDatabase.instance.loadAllClinicStatuses()
        }
    }

    // — PDF extraction ————————————————————————————————————————————————————
    val selectedPdfData = MutableStateFlow<ConventionPdfData?>(null)
    val isPdfLoading    = MutableStateFlow(false)
    val selectedInternship = MutableStateFlow<Internship?>(null)
    private val _pdfDataCache = MutableStateFlow<Map<String, ConventionPdfData>>(emptyMap())
    val pdfDataCache: StateFlow<Map<String, ConventionPdfData>> = _pdfDataCache
    val hasSessionCookies get() = sessionCookies.isNotEmpty()
    private var sessionCookies: Map<String, String> = emptyMap()

    val displayed: StateFlow<List<Internship>> = combine(
        combine(allInternships, filterText, activeFilter, localFilters) { list, text, vf, lf ->
            FilterState(list, text, vf, lf)
        },
        combine(sortColumn, sortAscending, _pdfDataCache) { col, asc, cache -> Triple(col, asc, cache) }
    ) { fs, (col, asc, cache) ->
        val filtered = fs.internships.filter { internship ->
            internship.matchesLocalFilters(fs.localFilters) && run {
                val passesView = when (fs.viewFilter) {
                    ViewFilter.PENDING_SCHOOL_SIGNATURE -> {
                        val pdf = cache[internship.conventionPdfUrl]
                        if (pdf != null) pdf.allPreSignaturesDone && fs.viewFilter.predicate(internship)
                        else fs.viewFilter.predicate(internship)
                    }
                    else -> fs.viewFilter.predicate(internship)
                }
                passesView && internship.matchesText(fs.text)
            }
        }
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
            clinicStatuses.value = withContext(Dispatchers.IO) { LocalDatabase.instance.loadAllClinicStatuses() }
        }
    }

    fun setFilter(text: String) { filterText.value = text }
    fun setView(filter: ViewFilter) { activeFilter.value = filter }
    fun setScrapeFilters(f: ScrapeFilters) { scrapeFilters.value = f }
    fun setLocalFilters(f: LocalFilters) { localFilters.value = f }

    fun toggleSort(col: SortColumn) {
        if (sortColumn.value == col) {
            sortAscending.value = !sortAscending.value
        } else {
            sortColumn.value = col
            sortAscending.value = true
        }
    }

    fun backupDatabase(onSuccess: (String) -> Unit, onError: (String) -> Unit) {
        scope.launch(Dispatchers.IO) {
            runCatching { LocalDatabase.instance.backup() }
                .onSuccess { path -> scope.launch { onSuccess(path.toString()) } }
                .onFailure { e -> scope.launch { onError(e.message ?: "Erreur inconnue") } }
        }
    }

    /**
     * Bascule vers une autre base locale. L'ouverture et la migration du schéma
     * sont des I/O disque : elles ne doivent pas bloquer le thread de composition.
     */
    fun switchDatabase(path: Path) {
        scope.launch {
            isLoading.value = true
            statusMessage.value = "Ouverture de la base…"
            try {
                withContext(Dispatchers.IO) {
                    LocalDatabase.instance = LocalDatabase(path)
                    LocalDatabase.instance.init()
                }
                reloadAll()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                errorMessage.value = "Impossible d'ouvrir la base : ${e.message ?: e.javaClass.simpleName}"
                statusMessage.value = "Erreur d'ouverture de la base"
            } finally {
                isLoading.value = false
            }
        }
    }

    fun reloadAll() {
        scope.launch {
            loadFromDatabase()
            val cache = withContext(Dispatchers.IO) { LocalDatabase.instance.loadAllPdfData() }
            _pdfDataCache.value = cache
            clinicStatuses.value = withContext(Dispatchers.IO) { LocalDatabase.instance.loadAllClinicStatuses() }
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
        chromeDriverPath: String = "",
        conventionDir: String = "",
    ) {
        if (isLoading.value) return
        scope.launch {
            isLoading.value = true
            errorMessage.value = null
            allInternships.value = emptyList()
            statusMessage.value = "Connexion en cours…"

            var totalAdded = 0
            var totalUpdated = 0
            var logPath = ""

            try {
                val result = withContext(Dispatchers.IO) {
                    val scraper = SeleniumScraper(
                        browserType = browserType,
                        headless = headless,
                        chromeDriverPath = chromeDriverPath,
                        onProgress = { msg ->
                            scope.launch(Dispatchers.Main) { statusMessage.value = msg }
                        }
                    )
                    try {
                        logPath = scraper.logFilePath
                        val loggedIn = scraper.login(username, password)
                        if (!loggedIn) {
                            ScraperResult.Failure("Identifiants incorrects ou timeout de connexion")
                        } else {
                            sessionCookies = scraper.getSessionCookies()
                            val persistPage: (List<Internship>) -> Unit = { pageInternships ->
                                val stats: UpsertStats = LocalDatabase.instance.upsertAll(pageInternships)
                                totalAdded += stats.added
                                totalUpdated += stats.updated
                                scope.launch(Dispatchers.Main) {
                                    allInternships.value = allInternships.value + pageInternships
                                }
                            }
                            val httpResult = DashboardHttpScraper(
                                sessionCookies = sessionCookies,
                                onProgress = { msg ->
                                    scraper.logHttpProgress(msg)
                                    scope.launch(Dispatchers.Main) { statusMessage.value = msg }
                                },
                            )
                            // Chaque page HTTP validée est sauvegardée immédiatement. Les identifiants
                            // mémorisés évitent de la rejouer si Selenium doit reprendre la pagination.
                            val activeFilters = scrapeFilters.value
                            val checkpoint = LocalDatabase.instance.beginOrResumeScrape(
                                activeFilters.checkpointKey()
                            )
                            if (checkpoint.resumed && checkpoint.completedPages.isNotEmpty()) {
                                scraper.logHttpProgress(
                                    "Reprise : ${checkpoint.completedPages.size} page(s) déjà sauvegardée(s)"
                                )
                            }
                            val httpPersistedIds = mutableSetOf<String>()
                            val httpScrapeResult = httpResult.scrapeAllPages(
                                filters = activeFilters,
                                completedPages = checkpoint.completedPages,
                            ) { pageNumber, internships ->
                                persistPage(internships)
                                internships.mapTo(httpPersistedIds) { internship -> internship.localId() }
                                LocalDatabase.instance.markScrapePageCompleted(
                                    checkpoint.runId, pageNumber, internships.size
                                )
                            }

                            if (httpScrapeResult is ScraperResult.Success) {
                                LocalDatabase.instance.finishScrapeRun(
                                    checkpoint.runId,
                                    success = true,
                                    totalPages = httpScrapeResult.pageCount,
                                    totalItems = httpScrapeResult.totalCount,
                                )
                                scraper.logHttpProgress(
                                    "Extraction terminée : ${httpScrapeResult.totalCount} stage(s) " +
                                        "sur ${httpScrapeResult.pageCount} page(s)"
                                )
                                httpScrapeResult
                            } else {
                                val httpFailure = httpScrapeResult as ScraperResult.Failure
                                scraper.logHttpProgress(
                                    "Échec (${httpFailure.message}) — reprise avec Selenium"
                                )
                                scope.launch(Dispatchers.Main) {
                                    statusMessage.value = "HTTP indisponible — reprise avec le navigateur…"
                                }
                                val seleniumResult = scraper.scrapeAllPages(activeFilters) { seleniumPage ->
                                    val notAlreadyPersisted = seleniumPage.filter {
                                        it.localId() !in httpPersistedIds
                                    }
                                    if (notAlreadyPersisted.isNotEmpty()) persistPage(notAlreadyPersisted)
                                }
                                when (seleniumResult) {
                                    is ScraperResult.Success -> LocalDatabase.instance.finishScrapeRun(
                                        checkpoint.runId,
                                        success = true,
                                        totalPages = seleniumResult.pageCount,
                                        totalItems = seleniumResult.totalCount,
                                    )
                                    is ScraperResult.Failure -> LocalDatabase.instance.finishScrapeRun(
                                        checkpoint.runId,
                                        success = false,
                                        errorMessage = seleniumResult.message,
                                    )
                                }
                                seleniumResult
                            }
                        }
                    } finally {
                        scraper.close()
                    }
                }

                when (result) {
                    is ScraperResult.Success -> {
                        val (fromDb, count) = withContext(Dispatchers.IO) {
                            LocalDatabase.instance.loadAll() to LocalDatabase.instance.count()
                        }
                        allInternships.value = fromDb
                        dbCount.value = count
                        statusMessage.value = buildString {
                            append("${result.totalCount} stage(s) extraits — ")
                            append("$totalAdded nouveau(x), $totalUpdated mis à jour")
                            append(" — base : $count au total")
                            if (logPath.isNotBlank()) append(" | log : $logPath")
                        }
                        val pdfCache = withContext(Dispatchers.IO) { LocalDatabase.instance.loadAllPdfData() }
                        _pdfDataCache.value = pdfCache
                        val urlsToAutoParse = fromDb
                            .mapNotNull { internship ->
                                internship.conventionPdfUrl.takeIf { url ->
                                    url.isNotEmpty() && shouldAutoParsePdfAfterImport(internship, pdfCache[url])
                                }
                            }
                            .distinct()
                        if (urlsToAutoParse.isNotEmpty()) {
                            launch { autoParseNewPdfs(urlsToAutoParse) }
                        }
                        if (conventionDir.isNotBlank()) {
                            val toDownload = fromDb.filter { s ->
                                s.signingDate != null &&
                                s.conventionPdfUrl.isNotEmpty() &&
                                s.localPdfPath.isBlank()
                            }
                            if (toDownload.isNotEmpty()) {
                                launch { autoDownloadSignedPdfs(toDownload, conventionDir) }
                            }
                        }
                    }
                    is ScraperResult.Failure -> {
                        loadFromDatabase()
                        errorMessage.value = buildString {
                            append(result.message)
                            if (logPath.isNotBlank()) append("\n\nLog complet : $logPath")
                        }
                        statusMessage.value = "Erreur lors de l'extraction"
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // Typiquement une SQLException levée hors des blocs déjà protégés
                // (beginOrResumeScrape, finishScrapeRun, loadAll…).
                loadFromDatabase()
                errorMessage.value = buildString {
                    append("Extraction interrompue : ${e.message ?: e.javaClass.simpleName}")
                    if (logPath.isNotBlank()) append("\n\nLog complet : $logPath")
                }
                statusMessage.value = "Erreur lors de l'extraction"
            } finally {
                isLoading.value = false
            }
        }
    }

    fun downloadConventionPdf(url: String) {
        if (sessionCookies.isEmpty()) {
            errorMessage.value = "Session expirée — relancez une extraction pour reconnecter"
            return
        }
        if (isPdfLoading.value) return
        scope.launch {
            isPdfLoading.value = true
            statusMessage.value = "Téléchargement de la convention…"
            try {
                val data = withContext(Dispatchers.IO) {
                    val bytes = PdfDownloader(sessionCookies).download(url)
                    val parsed = ConventionPdfParser.parse(bytes, sourceUrl = url)
                    LocalDatabase.instance.savePdfData(parsed)
                    parsed.schoolSigningDate?.let {
                        LocalDatabase.instance.updateSchoolSignatureFromPdf(url, it)
                    }
                    parsed
                }
                selectedPdfData.value = data
                _pdfDataCache.value = _pdfDataCache.value + (url to data)
                data.schoolSigningDate?.let { date ->
                    allInternships.value = allInternships.value.map { internship ->
                        if (internship.conventionPdfUrl == url && internship.signingDate == null)
                            internship.copy(signingDate = date, conventionSignUrl = "")
                        else internship
                    }
                }
                statusMessage.value = "Convention téléchargée et analysée"
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                errorMessage.value = "Téléchargement PDF échoué : ${e.message ?: e.javaClass.simpleName}"
                statusMessage.value = "Erreur téléchargement PDF"
            } finally {
                isPdfLoading.value = false
            }
        }
    }

    fun reanalyzeAllPdfs() {
        if (sessionCookies.isEmpty()) {
            errorMessage.value = "Session expirée — relancez une extraction pour reconnecter"
            return
        }
        if (isLoading.value || isPdfLoading.value) return

        val urls = allInternships.value
            .map { it.conventionPdfUrl }
            .filter { it.isNotBlank() }
            .distinct()
        if (urls.isEmpty()) {
            errorMessage.value = "Aucune convention PDF à réanalyser"
            return
        }

        scope.launch {
            isLoading.value = true
            isPdfLoading.value = true
            try {
                autoParseNewPdfs(urls)
            } finally {
                isPdfLoading.value = false
                isLoading.value = false
            }
        }
    }

    private suspend fun autoParseNewPdfs(urls: List<String>) {
        val total = urls.size
        statusMessage.value = "Analyse automatique de $total convention(s)…"
        withContext(Dispatchers.IO) {
            val downloader = PdfDownloader(sessionCookies)
            var done = 0
            var succeeded = 0
            val failures = mutableListOf<String>()
            for (url in urls) {
                runCatching {
                    val bytes = downloader.download(url)
                    val data  = ConventionPdfParser.parse(bytes, sourceUrl = url)
                    LocalDatabase.instance.savePdfData(data)
                    data.schoolSigningDate?.let { LocalDatabase.instance.updateSchoolSignatureFromPdf(url, it) }
                    scope.launch(Dispatchers.Main) {
                        _pdfDataCache.value = _pdfDataCache.value + (url to data)
                        data.schoolSigningDate?.let { date ->
                            allInternships.value = allInternships.value.map { internship ->
                                if (internship.conventionPdfUrl == url && internship.signingDate == null)
                                    internship.copy(signingDate = date, conventionSignUrl = "")
                                else internship
                            }
                        }
                    }
                }.onSuccess {
                    succeeded++
                }.onFailure { error ->
                    failures += "${url.substringAfterLast('/')} : ${error.message ?: error.javaClass.simpleName}"
                }
                done++
                val d = done
                scope.launch(Dispatchers.Main) { statusMessage.value = "Conventions analysées : $d/$total" }
            }
            scope.launch(Dispatchers.Main) {
                statusMessage.value = "$succeeded/$total convention(s) analysée(s) — ${failures.size} échec(s)"
                if (failures.isNotEmpty()) {
                    errorMessage.value = buildString {
                        append("${failures.size} convention(s) n'ont pas pu être analysée(s) :\n")
                        append(failures.take(8).joinToString("\n"))
                        if (failures.size > 8) append("\n… et ${failures.size - 8} autre(s)")
                    }
                }
            }
        }
    }

    private fun shouldAutoParsePdfAfterImport(internship: Internship, cached: ConventionPdfData?): Boolean {
        if (internship.needsSignatureRefresh()) return true
        if (cached == null) return true
        if (internship.signingDate != null && cached.signingDateSchool.isBlank()) return true
        if (internship.conventionSignUrl.isNotBlank()) {
            return !cached.allPreSignaturesDone || cached.signingDateSchool.isBlank()
        }
        return false
    }

    private suspend fun autoDownloadSignedPdfs(internships: List<Internship>, conventionDir: String) {
        val dir = Paths.get(conventionDir)
        withContext(Dispatchers.IO) { Files.createDirectories(dir) }
        val downloader = PdfDownloader(sessionCookies)
        var done = 0
        var succeeded = 0
        val failures = mutableListOf<String>()
        val total = internships.size
        scope.launch(Dispatchers.Main) { statusMessage.value = "Téléchargement de $total convention(s) signée(s)…" }
        withContext(Dispatchers.IO) {
            for (internship in internships) {
                runCatching {
                    val bytes    = downloader.download(internship.conventionPdfUrl)
                    val filename = buildPdfFilename(internship)
                    Files.write(dir.resolve(filename), bytes)
                    LocalDatabase.instance.updateLocalPdfPath(internship, filename)
                    val updated = internship.copy(localPdfPath = filename)
                    scope.launch(Dispatchers.Main) {
                        allInternships.value = allInternships.value.map { i ->
                            if (i.localId() == internship.localId()) updated else i
                        }
                    }
                }.onSuccess {
                    succeeded++
                }.onFailure { error ->
                    failures += "${internship.studentName} : ${error.message ?: error.javaClass.simpleName}"
                }
                done++
                val d = done
                scope.launch(Dispatchers.Main) { statusMessage.value = "Conventions téléchargées : $d/$total" }
            }
        }
        scope.launch(Dispatchers.Main) {
            statusMessage.value = "$succeeded/$total convention(s) sauvegardée(s) — ${failures.size} échec(s)"
            if (failures.isNotEmpty()) {
                errorMessage.value = buildString {
                    append("${failures.size} téléchargement(s) de convention en échec :\n")
                    append(failures.take(8).joinToString("\n"))
                    if (failures.size > 8) append("\n… et ${failures.size - 8} autre(s)")
                }
            }
        }
    }

    fun selectInternship(internship: Internship?) {
        selectedInternship.value = internship
    }

    fun toggleSuivi(internship: Internship, checked: Boolean) {
        scope.launch {
            withContext(Dispatchers.IO) {
                LocalDatabase.instance.updateSuiviTable(internship, checked)
            }
            val updated = internship.copy(inSuiviTable = checked)
            allInternships.value = allInternships.value.map { i ->
                if (i.localId() == internship.localId()) updated else i
            }
            if (selectedInternship.value?.localId() == internship.localId()) {
                selectedInternship.value = updated
            }
        }
    }

    fun downloadSignedPdf(internship: Internship, conventionDir: String) {
        if (sessionCookies.isEmpty()) {
            errorMessage.value = "Session expirée — relancez une extraction pour reconnecter"
            return
        }
        if (conventionDir.isBlank()) {
            errorMessage.value = "Configurez d'abord le dossier des conventions dans les Paramètres"
            return
        }
        val url = internship.conventionPdfUrl
        if (url.isBlank()) {
            errorMessage.value = "Pas d'URL de convention disponible pour ce stage"
            return
        }
        scope.launch {
            statusMessage.value = "Téléchargement de la convention de ${internship.studentName}…"
            withContext(Dispatchers.IO) {
                try {
                    val bytes = PdfDownloader(sessionCookies).download(url)
                    val filename = buildPdfFilename(internship)
                    val dir = Paths.get(conventionDir)
                    Files.createDirectories(dir)
                    val filePath = dir.resolve(filename)
                    Files.write(filePath, bytes)
                    LocalDatabase.instance.updateLocalPdfPath(internship, filename)
                    val updated = internship.copy(localPdfPath = filename)
                    scope.launch(Dispatchers.Main) {
                        allInternships.value = allInternships.value.map { i ->
                            if (i.localId() == internship.localId()) updated else i
                        }
                        if (selectedInternship.value?.localId() == internship.localId()) {
                            selectedInternship.value = updated
                        }
                        statusMessage.value = "Convention sauvegardée : $filename"
                    }
                } catch (e: Exception) {
                    scope.launch(Dispatchers.Main) {
                        errorMessage.value = "Téléchargement échoué : ${e.message}"
                    }
                }
            }
        }
    }

    fun openLocalPdf(localPdfPath: String, conventionDir: String) {
        val file = Paths.get(conventionDir, localPdfPath).toFile()
        if (!file.exists()) {
            errorMessage.value = "Fichier introuvable : ${file.absolutePath}"
            return
        }
        runCatching {
            val desktop = Desktop.getDesktop()
            if (desktop.isSupported(Desktop.Action.OPEN)) desktop.open(file)
            else desktop.browse(URI("file://${file.absolutePath}"))
        }.onFailure { errorMessage.value = "Impossible d'ouvrir le PDF : ${it.message}" }
    }

    fun clearDatabase() {
        scope.launch {
            withContext(Dispatchers.IO) { LocalDatabase.instance.clear() }
            allInternships.value = emptyList()
            _pdfDataCache.value = emptyMap()
            selectedInternship.value = null
            selectedPdfData.value = null
            dbCount.value = 0
            statusMessage.value = "Base locale vidée"
        }
    }

    fun exportToExcel(path: Path) {
        scope.launch {
            isLoading.value = true
            statusMessage.value = "Export Excel en cours…"
            try {
                // `return@withContext` ne quittait que le withContext : le message de
                // succès s'affichait ensuite même après un échec.
                withContext(Dispatchers.IO) {
                    ExcelExporter.export(allInternships.value, path, _pdfDataCache.value)
                }
                statusMessage.value = "Export réussi : ${path.fileName}"
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                errorMessage.value = "Export échoué : ${e.message ?: e.javaClass.simpleName}"
                statusMessage.value = "Erreur lors de l'export Excel"
            } finally {
                isLoading.value = false
            }
        }
    }

    fun exportVetAgroTiceCsv(path: Path, studyYear: String) {
        scope.launch {
            isLoading.value = true
            statusMessage.value = "Export CSV VetAgroTice — $studyYear en cours…"
            try {
                val exportedCount = withContext(Dispatchers.IO) {
                    val selectedInternships = allInternships.value.filter {
                        it.studyYear.trim() == studyYear.trim()
                    }
                    VetAgroTiceCsvExporter.export(selectedInternships, path, _pdfDataCache.value)
                }
                statusMessage.value =
                    "Export CSV réussi : $exportedCount stage(s) signé(s) en $studyYear — ${path.fileName}"
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                errorMessage.value = "Export CSV échoué : ${e.message ?: e.javaClass.simpleName}"
                statusMessage.value = "Erreur lors de l'export CSV"
            } finally {
                isLoading.value = false
            }
        }
    }

    fun exportToOneDrive(localPath: String) {
        launchLocalExport(localPath, complement = false)
    }

    fun exportToOneDriveComplement(localPath: String) {
        launchLocalExport(localPath, complement = true)
    }

    fun exportToOneDriveTracking(targets: List<TrackingTarget>) {
        if (targets.isEmpty()) {
            errorMessage.value = "Aucun tableau de suivi configuré — allez dans Paramètres"
            return
        }
        if (isLoading.value) return
        scope.launch {
            isLoading.value = true
            errorMessage.value = null
            statusMessage.value = "Mise à jour du/des tableau(x) de suivi ER…"
            try {
                withContext(Dispatchers.IO) {
                    try {
                        val updater = LocalTrackingUpdater()
                        val allWarnings = mutableListOf<String>()
                        var totalMatched = 0
                        for (target in targets) {
                            val filtered = internshipsForYear(target.yearLabel)
                            val label = target.yearLabel.ifBlank { "tous" }
                            scope.launch(Dispatchers.Main) {
                                statusMessage.value = "Mise à jour « $label » (${target.filePath.substringAfterLast('/')})…"
                            }
                            val result = updater.update(filtered, target.filePath)
                            totalMatched += result.matched
                            val prefix = if (target.yearLabel.isBlank()) "" else "[${target.yearLabel}] "
                            allWarnings.addAll(result.warnings.map { "$prefix$it" })
                        }
                        scope.launch(Dispatchers.Main) {
                            statusMessage.value = "$totalMatched stage(s) mis à jour dans ${targets.size} tableau(x) de suivi"
                            if (allWarnings.isNotEmpty()) trackingWarnings.value = allWarnings
                        }
                    } catch (e: Exception) {
                        scope.launch(Dispatchers.Main) {
                            errorMessage.value = "Mise à jour tableau de suivi échouée : ${e.message}"
                            statusMessage.value = "Erreur tableau de suivi"
                        }
                    }
                }
            } finally {
                isLoading.value = false
            }
        }
    }

    private fun internshipsForYear(yearLabel: String): List<Internship> {
        if (yearLabel.isBlank()) return allInternships.value
        return allInternships.value.filter { s ->
            s.studyYear.trimStart().startsWith(yearLabel) ||
                s.studyYear.contains(yearLabel, ignoreCase = true)
        }
    }

    fun clearTrackingWarnings() { trackingWarnings.value = emptyList() }

    private fun launchLocalExport(localPath: String, complement: Boolean) {
        if (localPath.isBlank()) {
            errorMessage.value = "Chemin du fichier Excel non configuré — allez dans Paramètres"
            return
        }
        if (isLoading.value) return
        scope.launch {
            isLoading.value = true
            errorMessage.value = null
            try {
                withContext(Dispatchers.IO) {
                    try {
                        if (complement) {
                            scope.launch(Dispatchers.Main) { statusMessage.value = "Complétion du fichier Excel en cours…" }
                            LocalExcelUpdater.complement(allInternships.value, localPath, _pdfDataCache.value)
                            scope.launch(Dispatchers.Main) { statusMessage.value = "Fichier complété : ${java.io.File(localPath).name}" }
                        } else {
                            scope.launch(Dispatchers.Main) { statusMessage.value = "Écriture du fichier Excel en cours…" }
                            LocalExcelUpdater.update(allInternships.value, localPath, _pdfDataCache.value)
                            scope.launch(Dispatchers.Main) { statusMessage.value = "Fichier mis à jour : ${java.io.File(localPath).name}" }
                        }
                    } catch (e: Exception) {
                        scope.launch(Dispatchers.Main) {
                            errorMessage.value = "Export fichier échoué : ${e.message}"
                            statusMessage.value = "Erreur export fichier"
                        }
                    }
                }
            } finally {
                isLoading.value = false
            }
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

private fun ScrapeFilters.checkpointKey(): String = listOf(
    periode, anneeEtude, theme, status, order,
).joinToString("|")

internal fun Internship.matchesLocalFilters(lf: LocalFilters): Boolean {
    // Un `periode` non numérique ne doit désactiver QUE ce critère : un `return true`
    // ici court-circuitait aussi les filtres anneeEtude et theme placés plus bas.
    val months = lf.periode.takeIf { it.isNotEmpty() }?.toIntOrNull()
    if (months != null) {
        val cutoff = LocalDate.now().minusMonths(months.toLong())
        // Inclure les stages dont la date de début est dans la période ou à venir
        if (startDate != null && startDate.isBefore(cutoff)) return false
    }
    if (lf.anneeEtude.isNotEmpty()) {
        val matched = if (lf.anneeEtude == "99") {
            studyYear.contains("99", ignoreCase = true) ||
                studyYear.contains("reconvers", ignoreCase = true)
        } else {
            studyYear.trimStart().startsWith(lf.anneeEtude)
        }
        if (!matched) return false
    }
    if (lf.theme.isNotEmpty()) {
        val label = ScrapeFilterOptions.themes.find { it.value == lf.theme }?.label ?: ""
        if (label.isNotEmpty() && !theme.contains(label, ignoreCase = true)) return false
    }
    return true
}

enum class SortColumn { STUDENT, YEAR, ORGANIZATION, START_DATE, SIGN_DATE, THEME }

internal fun buildPdfFilename(internship: Internship): String {
    fun sanitize(s: String) = s.trim()
        .replace(Regex("[^\\p{L}\\p{N} _-]"), "")
        .replace(Regex("\\s+"), "_")
        .take(40)
    val name  = sanitize(internship.studentName).ifBlank { "sans-nom" }
    val year  = sanitize(internship.studyYear).ifBlank { "sans-annee" }
    val date  = internship.startDate?.format(DateTimeFormatter.ofPattern("yyyy-MM-dd")) ?: "sans-date"
    // Suffixe issu de localId() : deux conventions d'un même étudiant démarrant le même
    // jour (convention annulée puis régénérée) écrasaient sinon le même fichier.
    val suffix = internship.localId().take(8)
    return "${name}_${year}_${date}_$suffix.pdf"
}
