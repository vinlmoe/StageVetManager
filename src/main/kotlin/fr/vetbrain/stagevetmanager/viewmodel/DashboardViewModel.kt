package fr.vetbrain.stagevetmanager.viewmodel

import fr.vetbrain.stagevetmanager.export.ExcelExporter
import fr.vetbrain.stagevetmanager.model.Internship
import fr.vetbrain.stagevetmanager.model.ViewFilter
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
    val filterText     = MutableStateFlow("")
    val activeFilter   = MutableStateFlow(ViewFilter.ALL)
    val isLoading      = MutableStateFlow(false)
    val statusMessage  = MutableStateFlow("")
    val errorMessage   = MutableStateFlow<String?>(null)
    val sortColumn     = MutableStateFlow(SortColumn.STUDENT)
    val sortAscending  = MutableStateFlow(true)

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

    fun setFilter(text: String) { filterText.value = text }
    fun setView(filter: ViewFilter) { activeFilter.value = filter }

    fun toggleSort(col: SortColumn) {
        if (sortColumn.value == col) {
            sortAscending.value = !sortAscending.value
        } else {
            sortColumn.value = col
            sortAscending.value = true
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
            statusMessage.value = "Connexion en cours..."

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
                        scraper.scrapeAllPages { pageInternships ->
                            // Appelé sur IO thread après chaque page → on met à jour le StateFlow sur Main
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
                is ScraperResult.Success ->
                    statusMessage.value =
                        "${result.totalCount} stage(s) extraits depuis ${result.pageCount} page(s)"
                is ScraperResult.Failure -> {
                    errorMessage.value = result.message
                    statusMessage.value = "Erreur lors de l'extraction"
                }
            }
            isLoading.value = false
        }
    }

    fun exportToExcel(path: Path) {
        scope.launch {
            isLoading.value = true
            statusMessage.value = "Export Excel en cours..."
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

    fun suggestedExportFileName(): String {
        val today = LocalDate.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd"))
        return "${today}_extract_stagevet.xlsx"
    }

    fun dispose() {
        scope.cancel()
    }
}

enum class SortColumn { STUDENT, YEAR, ORGANIZATION, START_DATE, SIGN_DATE, THEME }
