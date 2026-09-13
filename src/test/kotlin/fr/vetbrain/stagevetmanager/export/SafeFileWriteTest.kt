package fr.vetbrain.stagevetmanager.export

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.io.IOException
import java.nio.file.Path

class SafeFileWriteTest {

    @TempDir
    lateinit var tempDir: Path

    @Test
    fun `writes the new content and keeps a backup of the previous version`() {
        val target = tempDir.resolve("suivi.xlsx").toFile()
        target.writeText("ancien contenu")

        SafeFileWrite.replace(target) { out -> out.write("nouveau contenu".toByteArray()) }

        assertEquals("nouveau contenu", target.readText())
        assertEquals("ancien contenu", File(tempDir.toFile(), "suivi.xlsx.bak").readText())
    }

    @Test
    fun `leaves the original file untouched when the write fails`() {
        // Le coeur de la correction : FileOutputStream(file) tronquait le document
        // de l'utilisateur dès l'ouverture, avant même de savoir si l'écriture
        // aboutirait (disque plein, classeur verrouillé, erreur POI).
        val target = tempDir.resolve("suivi.xlsx").toFile()
        target.writeText("données irremplaçables")

        assertThrows<IOException> {
            SafeFileWrite.replace(target) { out ->
                out.write("écriture partielle".toByteArray())
                throw IOException("disque plein")
            }
        }

        assertEquals("données irremplaçables", target.readText())
    }

    @Test
    fun `does not leave temporary files behind on failure`() {
        val target = tempDir.resolve("suivi.xlsx").toFile()
        target.writeText("contenu")

        assertThrows<IOException> {
            SafeFileWrite.replace(target) { throw IOException("échec") }
        }

        val leftovers = tempDir.toFile().listFiles()?.filter { it.name.endsWith(".tmp") }.orEmpty()
        assertTrue(leftovers.isEmpty(), "fichiers temporaires résiduels : $leftovers")
    }

    @Test
    fun `creates the file and its parent directory when absent`() {
        val target = tempDir.resolve("sous/dossier/export.xlsx").toFile()

        SafeFileWrite.replace(target) { out -> out.write("contenu".toByteArray()) }

        assertEquals("contenu", target.readText())
        assertFalse(File(target.parentFile, "export.xlsx.bak").exists())
    }
}
