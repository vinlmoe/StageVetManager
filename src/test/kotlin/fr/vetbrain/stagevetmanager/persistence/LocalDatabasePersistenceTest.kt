package fr.vetbrain.stagevetmanager.persistence

import fr.vetbrain.stagevetmanager.model.ClinicStatus
import fr.vetbrain.stagevetmanager.model.Internship
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.sql.DriverManager
import java.time.LocalDate
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class LocalDatabasePersistenceTest {

    @TempDir
    lateinit var tempDir: Path

    private lateinit var dbPath: Path
    private lateinit var db: LocalDatabase

    @BeforeEach
    fun setUp() {
        dbPath = tempDir.resolve("internships.db")
        db = LocalDatabase(dbPath)
        db.init()
    }

    // — Configuration des connexions ————————————————————————————————————

    @Test
    fun `uses WAL so readers and a writer can work concurrently`() {
        assertEquals("wal", db.pragma("journal_mode").lowercase())
    }

    @Test
    fun `enables foreign keys so the schema cascade is not inert`() {
        // Le ON DELETE CASCADE de scrape_pages → scrape_runs est déclaré au schéma
        // mais SQLite l'ignore tant que le PRAGMA n'est pas activé sur la connexion.
        // Durcissement : aucun appelant ne s'en remet aujourd'hui à cette cascade.
        assertEquals("1", db.pragma("foreign_keys"))
    }

    @Test
    fun `waits instead of failing when another connection holds the write lock`() {
        assertTrue(
            db.pragma("busy_timeout").toInt() >= 5_000,
            "busy_timeout trop court : ${db.pragma("busy_timeout")}",
        )
    }

    @Test
    fun `keeps every row when several threads write at once`() {
        // Après une extraction, autoParseNewPdfs et autoDownloadSignedPdfs écrivent
        // en parallèle sur Dispatchers.IO : en mode journal `delete` et sans
        // busy_timeout, SQLite renvoyait SQLITE_BUSY et des stages étaient perdus.
        val pool = Executors.newFixedThreadPool(8)
        val failures = ConcurrentLinkedQueue<String>()
        repeat(60) { i ->
            pool.submit {
                runCatching { db.upsertAll(listOf(internship(studentName = "Etudiant $i"))) }
                    .onFailure { failures += "${it.javaClass.simpleName}: ${it.message}" }
            }
        }
        pool.shutdown()
        assertTrue(pool.awaitTermination(60, TimeUnit.SECONDS), "écritures non terminées")

        assertTrue(failures.isEmpty(), "écritures en échec : ${failures.take(3)}")
        assertEquals(60, db.count())
    }

    @Test
    fun `mixes stage writes and pdf writes from different threads`() {
        db.upsertAll(listOf(internship(studentName = "Dupont Marie")))
        val pool = Executors.newFixedThreadPool(4)
        val failures = ConcurrentLinkedQueue<String>()
        repeat(20) { i ->
            pool.submit {
                runCatching { db.setClinicStatus("Clinique $i", ClinicStatus.OK) }
                    .onFailure { failures += it.javaClass.simpleName }
            }
            pool.submit {
                runCatching { db.updateSuiviTable(internship(studentName = "Dupont Marie"), true) }
                    .onFailure { failures += it.javaClass.simpleName }
            }
        }
        pool.shutdown()
        assertTrue(pool.awaitTermination(60, TimeUnit.SECONDS))

        assertTrue(failures.isEmpty(), "écritures concurrentes en échec : ${failures.take(3)}")
        assertEquals(20, db.loadAllClinicStatuses().size)
    }

    // — Sauvegarde ——————————————————————————————————————————————————————

    @Test
    fun `refuses to produce a backup of an empty database`() {
        // connect() crée le fichier : une base jamais alimentée produisait une
        // « sauvegarde » de 0 octet annoncée comme un succès dans les Paramètres.
        val error = assertThrows<IllegalStateException> { db.backup() }
        assertTrue(error.message!!.contains("vide"), "message inattendu : ${error.message}")
    }

    @Test
    fun `two backups within the same second produce two distinct files`() {
        db.upsertAll(listOf(internship()))

        val first = db.backup()
        val second = db.backup()

        assertNotEquals(first, second)
        assertTrue(Files.exists(first) && Files.exists(second))
        assertTrue(Files.size(first) > 0 && Files.size(second) > 0)
    }

    @Test
    fun `a backup keeps the rows written just before it`() {
        db.upsertAll(listOf(internship(studentName = "Dupont Marie")))

        val backup = db.backup()

        // Relit la sauvegarde : sans checkpoint WAL préalable, les dernières
        // transactions resteraient dans le fichier -wal et seraient absentes.
        assertEquals(1, LocalDatabase(backup).count())
    }

    // — Protection à l'effacement ————————————————————————————————————————

    @Test
    fun `clear makes a safety backup that still holds the data`() {
        db.upsertAll(listOf(internship(studentName = "Dupont Marie")))
        db.updateSuiviTable(internship(studentName = "Dupont Marie"), checked = true)

        val backup = db.clear()

        assertNotNull(backup, "un effacement doit toujours laisser une sauvegarde")
        assertEquals(0, db.count())
        val restored = LocalDatabase(backup!!).loadAll()
        assertEquals(1, restored.size)
        assertTrue(restored.single().inSuiviTable, "l'annotation locale doit être dans la sauvegarde")
    }

    @Test
    fun `clear keeps clinic statuses and wipes scrape checkpoints`() {
        db.upsertAll(listOf(internship()))
        db.setClinicStatus("Clinique du Lac", ClinicStatus.OK)
        val checkpoint = db.beginOrResumeScrape("filtres")
        db.markScrapePageCompleted(checkpoint.runId, 1, 5)

        db.clear()

        assertEquals(1, db.loadAllClinicStatuses().size, "les statuts cliniques survivent")
        assertEquals(0, countScrapePages(), "les points de reprise doivent disparaître")
        assertTrue(db.beginOrResumeScrape("filtres").completedPages.isEmpty())
    }

    @Test
    fun `clear on an empty database reports no backup instead of an empty one`() {
        assertEquals(null, db.clear())
        assertEquals(0, db.count())
    }

    private fun countScrapePages(): Int =
        DriverManager.getConnection("jdbc:sqlite:${dbPath.toAbsolutePath()}").use { conn ->
            conn.createStatement().executeQuery("SELECT COUNT(*) FROM scrape_pages").use {
                if (it.next()) it.getInt(1) else -1
            }
        }

    private fun internship(
        studentName: String = "Dupont Marie",
        organization: String = "Clinique du Lac",
    ) = Internship(
        studentName = studentName,
        studyYear = "3ème année",
        organization = organization,
        address = "Lyon",
        conventionNumber = "2025-001",
        conventionGenDate = "",
        signingDate = null,
        startDate = LocalDate.of(2025, 6, 1),
        endDate = LocalDate.of(2025, 6, 30),
        rawDateStage = "01/06/2025 au 30/06/2025",
        theme = "Animaux de compagnie",
    )
}
