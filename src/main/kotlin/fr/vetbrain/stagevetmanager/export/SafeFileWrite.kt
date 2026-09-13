package fr.vetbrain.stagevetmanager.export

import java.io.File
import java.io.FileOutputStream
import java.io.OutputStream
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/**
 * Remplacement sûr d'un fichier existant.
 *
 * `FileOutputStream(file)` tronque le fichier dès son ouverture : si l'écriture
 * échoue ensuite (disque plein, classeur verrouillé par Excel, erreur POI), le
 * document de l'utilisateur est perdu. On écrit donc dans un fichier temporaire
 * du même dossier, puis on bascule par un déplacement atomique — l'original
 * reste intact tant que le nouveau contenu n'est pas complètement écrit.
 */
internal object SafeFileWrite {

    /**
     * Écrit [target] via [write]. Conserve une copie `<nom>.bak` de la version
     * précédente lorsque [keepBackup] est vrai et que le fichier existait déjà.
     */
    fun replace(target: File, keepBackup: Boolean = true, write: (OutputStream) -> Unit) {
        val dir = target.absoluteFile.parentFile ?: File(".")
        Files.createDirectories(dir.toPath())

        val tmp = File.createTempFile("${target.nameWithoutExtension}-", ".tmp", dir)
        try {
            FileOutputStream(tmp).use { out ->
                write(out)
                out.flush()
                // Garantit que les octets sont sur le disque avant la bascule.
                runCatching { out.fd.sync() }
            }

            if (keepBackup && target.exists()) {
                Files.copy(
                    target.toPath(),
                    File(dir, "${target.name}.bak").toPath(),
                    StandardCopyOption.REPLACE_EXISTING,
                )
            }

            try {
                Files.move(
                    tmp.toPath(), target.toPath(),
                    StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE,
                )
            } catch (_: AtomicMoveNotSupportedException) {
                // Certains systèmes de fichiers réseau ne gèrent pas ATOMIC_MOVE.
                Files.move(tmp.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
            }
        } finally {
            // No-op si le déplacement a réussi.
            tmp.delete()
        }
    }
}
