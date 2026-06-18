package fr.vetbrain.stagevetmanager.model

data class ConventionPdfData(
    val rawText: String,
    val sourceUrl: String = "",

    // — École ————————————————————————————————————
    val schoolContact: String = "",       // Personne contact
    val tutorName: String = "",           // Nom et prénom de l'enseignant tuteur
    val tutorFunction: String = "",       // Fonction et discipline
    val tutorPhone: String = "",
    val tutorEmail: String = "",

    // — Organisme d'accueil ——————————————————————
    val hostOrganization: String = "",
    val hostAddress: String = "",
    val hostRepresentative: String = "",  // Représenté par
    val supervisorQuality: String = "",   // Qualité du maître de stage
    val hostPhone: String = "",
    val hostEmail: String = "",
    val supervisorName: String = "",      // Nom et prénom du maître de stage
    val supervisorFunction: String = "",  // Fonction (maître de stage)

    // — Stagiaire ————————————————————————————————
    val studentLastName: String = "",
    val studentFirstName: String = "",
    val studentBirthDate: String = "",
    val studentStudyYear: String = "",    // ex. "5"
    val studentAddress: String = "",
    val studentPhone: String = "",
    val studentEmail: String = "",

    // — Période ——————————————————————————————————
    val academicYear: String = "",
    val startDate: String = "",
    val endDate: String = "",
    val durationLabel: String = "",       // ex. "6 jours effectifs"

    // — Encadrement / conditions ——————————————————
    val theme: String = "",
    val gratification: String = "",

    // — Signatures ————————————————————————————————
    val signingDateStudent: String = "",  // Date : DD-MM-YYYY à HH:MM (stagiaire)
    val signingDateHost: String = "",     // Date : DD-MM-YYYY à HH:MM (maître de stage)
)
