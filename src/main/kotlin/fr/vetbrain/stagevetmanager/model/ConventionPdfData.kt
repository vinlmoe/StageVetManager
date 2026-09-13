package fr.vetbrain.stagevetmanager.model

import java.time.LocalDate
import java.time.format.DateTimeFormatter

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
    /** Nombre de jours effectifs déclaré dans la convention ("X jours effectifs"). */
    val declaredDaysCount: Int? = null,
    /** Nombre de dates de présence effectivement listées dans la convention. */
    val effectiveDaysCount: Int? = null,
    /** true si declaredDaysCount == effectiveDaysCount, false si incohérent, null si indéterminé. */
    val daysCountCoherent: Boolean? = null,

    // — Modalités particulières (art. 3.2) ————————
    val nightPresence: Boolean = false,
    val sundayPresence: Boolean = false,
    val holidayPresence: Boolean = false,
    val homePresence: Boolean = false,
    val hasWeeklyRestDay: Boolean? = null,

    // — Encadrement / conditions ——————————————————
    val theme: String = "",
    /** "avec" | "sans" | "" selon la case cochée à l'alinéa d-. */
    val gratificationStatus: String = "",
    /** Montant brut extrait (ex. "0", "612,50"). Vide si sans gratification ou non renseigné. */
    val gratificationAmount: String = "",
    /**
     * true  = cohérent (sans + pas de montant, ou avec + montant > 0)
     * false = incohérent (ex. avec + 0 €, ou sans + montant > 0)
     * null  = indéterminé
     */
    val gratificationCoherent: Boolean? = null,
    /** Champ de compatibilité : résumé lisible de la gratification. */
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

    val schoolSigningDate: LocalDate? get() = runCatching {
        LocalDate.parse(signingDateSchool.substringBefore(' ').trim(), DateTimeFormatter.ofPattern("dd-MM-uuuu"))
    }.getOrNull()
}
