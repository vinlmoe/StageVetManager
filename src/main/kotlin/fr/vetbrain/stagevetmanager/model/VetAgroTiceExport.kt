package fr.vetbrain.stagevetmanager.model

/** A successfully written CSV, not confirmation of an import into VetAgroTice. */
data class VetAgroTiceExport(
    val exportedAt: String,
    val studyYear: String,
    val fullExport: Boolean,
    val stageCount: Int,
    val filePath: String,
)
