package fr.vetbrain.stagevetmanager.viewmodel

import fr.vetbrain.stagevetmanager.export.ExcelExporter
import fr.vetbrain.stagevetmanager.model.Internship
import fr.vetbrain.stagevetmanager.model.ScrapeFilters
import fr.vetbrain.stagevetmanager.model.ViewFilter
import fr.vetbrain.stagevetmanager.onedrive.OneDriveAuthClient
import fr.vetbrain.stagevetmanager.onedrive.OneDriveExcelUpdater
import fr.vetbrain.stagevetmanager.persistence.LocalDatabase
import fr.vetbrain.stagevetmanager.persistence.UpsertStats
import fr.vetbrain.stagevetmanager.scraper.ScraperResult
import fr.vetbrain.stagevetmanager.scraper.SeleniumScraper
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import java.nio.file.Path
import java.time.LocalDate
import java.time.format.DateTimeFormatter

class DashboardViewModel {

    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    val allInternships = MutableStateFlow<List<Internship>>(emptyList())
    val scrapeFilters  = MutableStateFlow(ScrapeFilters())
    val filterText     = MutableStateFlow("")
    val activeFilter   = MutableStateFlow(ViewFilter.ALL)
    val isLoading      = MutableStateFlow(false)
    val statusMessage  = MutableStateFlow("Initialisation…")
    val errorMessage   = MutableStateFlow<String?>(null)
    val sortColumn     = MutableStateFlow(SortColumn.STUDENT)
    val sortAscending  = MutableStateFlow(true)
    val dbCount        = MutableStateFlow(0)

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
            withContext(Dispatchers.IO) { LocalDatabase.init() }
            loadFromDatabase()
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
                LocalDatabase.loadAll() to LocalDatabase.count()
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
                        scraper.scrapeAllPages(filters = scrapeFilters.value) { pageInternships ->
                            // IO thread : upsert en base, puis mettre à jour l'UI
                            val stats: UpsertStats = LocalDatabase.upsertAll(pageInternships)
                            totalAdded += stats.added
                            totalUpdated += stats.updated
                            scope.launch(Dispatchers.Main) {
                                allInternships.value = allInternships.value + pageInternships
                            }
                        }
                    }
                } finally {
                    scraper.close()
                }
            }

            when (result) {
                is ScraperResult.Success -> {
                    // Recharger depuis la DB pour avoir l'état dédoublonné final
                    val (fromDb, count) = withContext(Dispatchers.IO) {
                        LocalDatabase.loadAll() to LocalDatabase.count()
                    }
                    allInternships.value = fromDb
                    dbCount.value = count
                    statusMessage.value = buildString {
                        append("${result.totalCount} stage(s) extraits — ")
                        append("$totalAdded nouveau(x), $totalUpdated mis à jour")
                        append(" — base : $count au total")
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

    fun clearDatabase() {
        scope.launch {
            withContext(Dispatchers.IO) { LocalDatabase.clear() }
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
