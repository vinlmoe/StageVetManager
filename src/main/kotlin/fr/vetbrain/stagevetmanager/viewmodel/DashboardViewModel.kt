package fr.vetbrain.stagevetmanager.viewmodel

import fr.vetbrain.stagevetmanager.export.ExcelExporter
import fr.vetbrain.stagevetmanager.model.ConventionPdfData
import fr.vetbrain.stagevetmanager.model.Internship
import fr.vetbrain.stagevetmanager.model.LocalFilters
import fr.vetbrain.stagevetmanager.model.ScrapeFilterOptions
import fr.vetbrain.stagevetmanager.model.ScrapeFilters
import fr.vetbrain.stagevetmanager.model.TrackingTarget
import fr.vetbrain.stagevetmanager.model.ViewFilter
import fr.vetbrain.stagevetmanager.export.LocalExcelUpdater
import fr.vetbrain.stagevetmanager.export.LocalTrackingUpdater
import fr.vetbrain.stagevetmanager.persistence.LocalDatabase
import fr.vetbrain.stagevetmanager.persistence.UpsertStats
import fr.vetbrain.stagevetmanager.persistence.localId
import fr.vetbrain.stagevetmanager.scraper.ConventionPdfParser
import fr.vetbrain.stagevetmanager.scraper.PdfDownloader
import fr.vetbrain.stagevetmanager.scraper.ScraperResult
import fr.vetbrain.stagevetmanager.scraper.SeleniumScraper
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import java.nio.file.Path
import java.time.LocalDate
import java.time.format.DateTimeFormatter

private data class FilterState(
    val internships: List<Internship>,
    val text: String,
    val viewFilter: ViewFilter,
    val localFilters: LocalFilters,
)

class DashboardViewModel {

    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())

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
                        if (pdf != null) pdf.allPreSignaturesDone && internship.signingDate == null
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
                        sessionCookies = scraper.getSessionCookies()
                        result
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
                    }
                    val urlsToAutoParse = fromDb
                        .mapNotNull { it.conventionPdfUrl.takeIf { u -> u.isNotEmpty() && u !in _pdfDataCache.value } }
                        .distinct()
                    if (urlsToAutoParse.isNotEmpty()) {
                        launch { autoParseNewPdfs(urlsToAutoParse) }
                    }
                }
                is ScraperResult.Failure -> {
                    loadFromDatabase()
                    errorMessage.value = result.message
                    statusMessage.value = "Erreur lors de l'extraction"
                }
            }
            isLoading.value = false
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
            isLoading.value = false
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
            withContext(Dispatchers.IO) {
                try {
                    if (complement) {
                        scope.launch(Dispatchers.Main) { statusMessage.value = "Complétion du fichier Excel en cours…" }
                        LocalExcelUpdater.complement(allInternships.value, localPath)
                        scope.launch(Dispatchers.Main) { statusMessage.value = "Fichier complété : ${java.io.File(localPath).name}" }
                    } else {
                        scope.launch(Dispatchers.Main) { statusMessage.value = "Écriture du fichier Excel en cours…" }
                        LocalExcelUpdater.update(allInternships.value, localPath)
                        scope.launch(Dispatchers.Main) { statusMessage.value = "Fichier mis à jour : ${java.io.File(localPath).name}" }
                    }
                } catch (e: Exception) {
                    scope.launch(Dispatchers.Main) {
                        errorMessage.value = "Export fichier échoué : ${e.message}"
                        statusMessage.value = "Erreur export fichier"
                    }
                }
            }
            isLoading.value = false
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

private fun Internship.matchesLocalFilters(lf: LocalFilters): Boolean {
    if (lf.periode.isNotEmpty()) {
        val months = lf.periode.toIntOrNull() ?: return true
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
