package fr.vetbrain.stagevetmanager.model

/**
 * Données extraites d'un PDF de convention de stage stagevet.fr.
 *
 * Champs parsés à partir du texte brut via regex (voir ConventionPdfParser).
 * rawText contient l'intégralité du texte extrait — utile pour affiner les
 * sélecteurs une fois le format réel connu.
 */
data class ConventionPdfData(
    val rawText: String,
    val sourceUrl: String = "",

    // — Identifiant ——————————————————————————————————————————
    val conventionNumber: String = "",

    // — Stagiaire ————————————————————————————————————————————
    val studentName: String = "",
    val studentBirthDate: String = "",
    val studentAddress: String = "",

    // — École ————————————————————————————————————————————————
    val schoolTutor: String = "",      // référent pédagogique / tuteur école

    // — Organisme d'accueil ——————————————————————————————————
    val hostOrganization: String = "",
    val hostSiret: String = "",
    val hostAddress: String = "",

    // — Maître de stage ——————————————————————————————————————
    val supervisorName: String = "",
    val supervisorTitle: String = "",

    // — Période ——————————————————————————————————————————————
    val startDate: String = "",
    val endDate: String = "",
    val duration: String = "",

    // — Conditions ———————————————————————————————————————————
    val gratification: String = "",
    val objectives: String = "",
)
