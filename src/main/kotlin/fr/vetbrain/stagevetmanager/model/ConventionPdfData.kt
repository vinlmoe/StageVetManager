package fr.vetbrain.stagevetmanager.model

data class ConventionPdfData(
    val rawText: String,
    val sourceUrl: String = "",

    // — École ————————————————————————————————————
    val schoolContact: String = "",
    val tutorName: String = "",
    val tutorFunction: String = "",
    val tutorPhone: String = "",
    val tutorEmail: String = "",

    // — Organisme d'accueil ——————————————————————
    val hostOrganization: String = "",
    val hostAddress: String = "",
    val hostRepresentative: String = "",
    val supervisorQuality: String = "",
    val hostPhone: String = "",
    val hostEmail: String = "",
    val supervisorName: String = "",
    val supervisorFunction: String = "",

    // — Stagiaire ————————————————————————————————
    val studentLastName: String = "",
    val studentFirstName: String = "",
    val studentBirthDate: String = "",
    val studentStudyYear: String = "",
    val studentAddress: String = "",
    val studentPhone: String = "",
    val studentEmail: String = "",

    // — Période ——————————————————————————————————
    val academicYear: String = "",
    val startDate: String = "",
    val endDate: String = "",
    val durationLabel: String = "",

    // — Modalités particulières (art. 3.2) ————————
    val nightPresence: Boolean = false,
    val sundayPresence: Boolean = false,
    val holidayPresence: Boolean = false,
    val homePresence: Boolean = false,
    val hasWeeklyRestDay: Boolean? = null, // null = pas de dates de présence pour vérifier

    // — Encadrement / conditions ——————————————————
    val theme: String = "",
    val gratification: String = "",

    // — Signatures (ordre PDF : Tuteur | Stagiaire | Maître de stage | École) —
    val signingDateTutor: String = "",    // enseignant tuteur       (col. 1)
    val signingDateStudent: String = "",  // stagiaire               (col. 2)
    val signingDateHost: String = "",     // maître de stage         (col. 3)
    val signingDateSchool: String = "",   // Pour VetAgro Sup (école, col. 4)
) {
    /** True si les 3 signataires requis AVANT la signature école ont tous signé. */
    val allPreSignaturesDone: Boolean get() =
        signingDateTutor.isNotBlank() && signingDateStudent.isNotBlank() && signingDateHost.isNotBlank()
}
