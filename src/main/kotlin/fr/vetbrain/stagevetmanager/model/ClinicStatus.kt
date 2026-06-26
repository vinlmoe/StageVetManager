package fr.vetbrain.stagevetmanager.model

enum class ClinicStatus(val label: String) {
    OK("OK"),
    WATCH("À surveiller"),
    BLACKLISTED("Ne plus envoyer"),
}
