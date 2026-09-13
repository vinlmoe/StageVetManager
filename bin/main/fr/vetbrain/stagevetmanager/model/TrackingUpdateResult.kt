package fr.vetbrain.stagevetmanager.model

data class TrackingUpdateResult(
    val matched: Int,
    val warnings: List<String>,
)
