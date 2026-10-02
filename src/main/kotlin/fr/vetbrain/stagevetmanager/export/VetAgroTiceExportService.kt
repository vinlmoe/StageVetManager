package fr.vetbrain.stagevetmanager.export

import fr.vetbrain.stagevetmanager.model.ConventionPdfData
import fr.vetbrain.stagevetmanager.model.Internship
import fr.vetbrain.stagevetmanager.model.VetAgroTiceExport
import fr.vetbrain.stagevetmanager.persistence.LocalDatabase
import fr.vetbrain.stagevetmanager.persistence.localId
import java.nio.file.Path
import java.time.LocalDateTime

object VetAgroTiceExportService {
    /** Serialize exports so an older snapshot cannot overwrite a newer baseline. */
    @Synchronized
    fun export(
        db: LocalDatabase,
        internships: List<Internship>,
        path: Path,
        studyYear: String,
        pdfDataCache: Map<String, ConventionPdfData> = emptyMap(),
        fullExport: Boolean = false,
    ): Int {
        val previous = db.loadVetAgroTiceFingerprints()
        val candidates = internships.filter {
            it.signingDate != null && it.studyYear.trim() == studyYear.trim()
        }.distinctBy { it.localId() }
        val hashes = candidates.associate { stage ->
            stage.localId() to VetAgroTiceCsvExporter.fingerprint(stage, pdfDataCache[stage.conventionPdfUrl])
        }
        val selected = candidates.filter { fullExport || previous[it.localId()] != hashes[it.localId()] }
        // Preserve an existing file when nothing has changed.
        if (selected.isEmpty()) return 0
        VetAgroTiceCsvExporter.export(selected, path, pdfDataCache)
        try {
            db.recordVetAgroTiceExport(
                VetAgroTiceExport(LocalDateTime.now().toString(), studyYear.trim(), fullExport,
                    selected.size, path.toAbsolutePath().toString()),
                selected.associate { it.localId() to hashes.getValue(it.localId()) },
            )
        } catch (e: Exception) {
            throw IllegalStateException(
                "CSV créé, mais suivi non enregistré ; les stages seront proposés au prochain export", e
            )
        }
        return selected.size
    }
}
