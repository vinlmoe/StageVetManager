package fr.vetbrain.stagevetmanager.model

data class ScrapeFilters(
    val periode: String = "",
    val anneeEtude: String = "",
    val theme: String = "",
    val status: String = "",
    val order: String = "1",
)

// Filtres appliqués localement sur les données déjà importées
data class LocalFilters(
    val periode: String = "",     // startDate >= aujourd'hui - N mois
    val anneeEtude: String = "",  // match partiel sur studyYear
    val theme: String = "",       // match sur le libellé du thème stocké
)

data class FilterOption(val value: String, val label: String)

object ScrapeFilterOptions {
    val periodes = listOf(
        FilterOption("", "Tous les stages"),
        FilterOption("1", "Moins d'un mois"),
        FilterOption("3", "Moins de 3 mois"),
        FilterOption("6", "Moins de 6 mois"),
        FilterOption("12", "Moins d'1 an"),
    )
    val annees = listOf(
        FilterOption("", "Toutes les années"),
        FilterOption("1", "1ère année"),
        FilterOption("2", "2ème année"),
        FilterOption("3", "3ème année"),
        FilterOption("4", "4ème année"),
        FilterOption("5", "5ème année"),
        FilterOption("6", "6ème année et +"),
        FilterOption("99", "Autres - Reconversion"),
    )
    val themes = listOf(
        FilterOption("", "Tous les thèmes"),
        FilterOption("17", "CLINIQUE RURALE / A3, A4, A5"),
        FilterOption("18", "ELEVAGE LAITIER / A2"),
        FilterOption("19", "DIVERSITE DES METIERS VETERINAIRES / A2, A3, A4"),
        FilterOption("20", "SANTE PUBLIQUE VETERINAIRE / A3, A4, A5"),
        FilterOption("21", "CLINIQUE / A2, A3, A4"),
        FilterOption("22", "SOINS INFIRMIERS ET EXAMEN CLINIQUE / A2, A3"),
        FilterOption("23", "CLINIQUE DEBUT DE CURSUS / A2"),
        FilterOption("24", "ELEVAGE MONOGASTRIQUE / A4"),
        FilterOption("34", "CLINIQUE ANIMAUX DE COMPAGNIE, SPORT ET LOISIRS / A3, A4, A5"),
        FilterOption("35", "THEME LIBRE / A2, A3, A4, A5"),
        FilterOption("56", "Stage complémentaire - EP"),
        FilterOption("57", "STAGE - A1"),
        FilterOption("58", "A6 / NAC"),
        FilterOption("59", "A6 / AC"),
        FilterOption("60", "A6 / EQ"),
        FilterOption("61", "A6 / AP"),
        FilterOption("62", "A6 / Mixte-EQ"),
        FilterOption("63", "A6 / Mixte-AP"),
        FilterOption("64", "VetGate"),
    )
    val statuts = listOf(
        FilterOption("", "Tous les statuts"),
        FilterOption("1", "En préparation"),
        FilterOption("2", "Validé pédagogiquement"),
        FilterOption("7", "A signer par DEVE"),
        FilterOption("3", "Validé administrativement"),
        FilterOption("4", "En cours"),
        FilterOption("5", "A évaluer"),
        FilterOption("6", "Finalisé"),
        FilterOption("99", "Annulé"),
    )
    val tris = listOf(
        FilterOption("1", "Date génération convention"),
        FilterOption("2", "Date signature convention"),
        FilterOption("3", "Date début stage"),
        FilterOption("4", "Date fin stage"),
        FilterOption("5", "Ordre alphabétique"),
    )
}
