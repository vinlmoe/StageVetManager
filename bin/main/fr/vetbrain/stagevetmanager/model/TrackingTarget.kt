package fr.vetbrain.stagevetmanager.model

data class TrackingTarget(
    val yearLabel: String,  // filtre sur studyYear ; vide = tous les stages
    val filePath: String,   // chemin relatif OneDrive/SharePoint
)

fun serializeTrackingTargets(targets: List<TrackingTarget>): String =
    targets.joinToString("\n") { "${it.yearLabel}\t${it.filePath}" }

fun deserializeTrackingTargets(s: String): List<TrackingTarget> =
    if (s.isBlank()) emptyList()
    else s.lines()
        .filter { '\t' in it }
        .map { line ->
            val idx = line.indexOf('\t')
            TrackingTarget(line.substring(0, idx), line.substring(idx + 1))
        }
