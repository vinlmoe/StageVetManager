package fr.vetbrain.stagevetmanager.model

import java.time.LocalDate

data class Internship(
    val studentName: String,
    val studyYear: String,
    val organization: String,
    val address: String,
    val conventionNumber: String,
    val conventionGenDate: String,
    val signingDate: LocalDate?,
    val startDate: LocalDate?,
    val endDate: LocalDate?,
    val rawDateStage: String,
    val theme: String,
    val conventionPdfUrl: String = "",
    val conventionSignUrl: String = "",
) {
    fun matchesText(query: String): Boolean {
        if (query.isBlank()) return true
        val q = query.lowercase()
        return studentName.lowercase().contains(q) ||
            organization.lowercase().contains(q) ||
            address.lowercase().contains(q) ||
            theme.lowercase().contains(q) ||
            studyYear.lowercase().contains(q)
    }
}

enum class ViewFilter(val label: String, val predicate: (Internship) -> Boolean) {
    ALL("Tous les stages", { true }),
    STARTING_SOON("Débuts dans 15 j", {
        val now = LocalDate.now()
        it.startDate != null && !it.startDate.isBefore(now) && !it.startDate.isAfter(now.plusDays(15))
    }),
    RECENTLY_SIGNED("Signés 15 derniers j", {
        val now = LocalDate.now()
        it.signingDate != null && !it.signingDate.isBefore(now.minusDays(15)) && !it.signingDate.isAfter(now)
    }),
    PENDING_SCHOOL_SIGNATURE("À signer (école)", {
        // conventionSignUrl présent = stagiaire + maître ont signé, il reste la signature école
        it.conventionSignUrl.isNotEmpty() && it.signingDate == null
    }),
}
