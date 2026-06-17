package fr.vetbrain.stagevetmanager.persistence

import fr.vetbrain.stagevetmanager.model.Internship
import java.nio.file.Path
import java.nio.file.Paths
import java.security.MessageDigest
import java.sql.Connection
import java.sql.DriverManager
import java.time.LocalDate
import java.time.LocalDateTime

data class UpsertStats(val added: Int, val updated: Int) {
    val total get() = added + updated
    override fun toString() = "$added nouveau(x), $updated mis à jour"
}

class LocalDatabase(private val dbPath: Path = defaultDbPath) {

    companion object {
        val defaultDbPath: Path = Paths.get(
            System.getProperty("user.home"), ".stagevetmanager", "internships.db"
        )
        val instance = LocalDatabase()
    }

    private fun connect(): Connection {
        dbPath.parent.toFile().mkdirs()
        Class.forName("org.sqlite.JDBC")
        return DriverManager.getConnection("jdbc:sqlite:${dbPath.toAbsolutePath()}")
    }

    fun init() {
        connect().use { conn ->
            conn.createStatement().execute("""
                CREATE TABLE IF NOT EXISTS internships (
                    id                  TEXT PRIMARY KEY,
                    student_name        TEXT NOT NULL,
                    study_year          TEXT,
                    organization        TEXT,
                    address             TEXT,
                    convention_number   TEXT,
                    convention_gen_date TEXT,
                    signing_date        TEXT,
                    start_date          TEXT,
                    end_date            TEXT,
                    raw_date_stage      TEXT,
                    theme               TEXT,
                    created_at          TEXT NOT NULL,
                    last_seen           TEXT NOT NULL
                )
            """.trimIndent())
        }
    }

    fun upsertAll(internships: List<Internship>): UpsertStats {
        var added = 0
        var updated = 0
        val now = LocalDateTime.now().toString()

        connect().use { conn ->
            conn.autoCommit = false
            try {
                val checkStmt  = conn.prepareStatement("SELECT id FROM internships WHERE id = ?")
                val insertStmt = conn.prepareStatement("""
                    INSERT INTO internships
                        (id, student_name, study_year, organization, address,
                         convention_number, convention_gen_date, signing_date,
                         start_date, end_date, raw_date_stage, theme, created_at, last_seen)
                    VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?)
                """.trimIndent())
                val updateStmt = conn.prepareStatement("""
                    UPDATE internships
                    SET student_name=?, study_year=?, organization=?, address=?,
                        convention_number=?, convention_gen_date=?, signing_date=?,
                        start_date=?, end_date=?, raw_date_stage=?, theme=?, last_seen=?
                    WHERE id=?
                """.trimIndent())

                for (s in internships) {
                    val id = s.localId()
                    checkStmt.setString(1, id)
                    val exists = checkStmt.executeQuery().use { it.next() }

                    if (exists) {
                        updateStmt.run {
                            setString(1, s.studentName)
                            setString(2, s.studyYear)
                            setString(3, s.organization)
                            setString(4, s.address)
                            setString(5, s.conventionNumber)
                            setString(6, s.conventionGenDate)
                            setString(7, s.signingDate?.toString())
                            setString(8, s.startDate?.toString())
                            setString(9, s.endDate?.toString())
                            setString(10, s.rawDateStage)
                            setString(11, s.theme)
                            setString(12, now)
                            setString(13, id)
                            executeUpdate()
                        }
                        updated++
                    } else {
                        insertStmt.run {
                            setString(1, id)
                            setString(2, s.studentName)
                            setString(3, s.studyYear)
                            setString(4, s.organization)
                            setString(5, s.address)
                            setString(6, s.conventionNumber)
                            setString(7, s.conventionGenDate)
                            setString(8, s.signingDate?.toString())
                            setString(9, s.startDate?.toString())
                            setString(10, s.endDate?.toString())
                            setString(11, s.rawDateStage)
                            setString(12, s.theme)
                            setString(13, now)
                            setString(14, now)
                            executeUpdate()
                        }
                        added++
                    }
                }
                conn.commit()
            } catch (e: Exception) {
                conn.rollback()
                throw e
            } finally {
                conn.autoCommit = true
            }
        }
        return UpsertStats(added, updated)
    }

    fun loadAll(): List<Internship> {
        return connect().use { conn ->
            val rs = conn.createStatement().executeQuery(
                "SELECT * FROM internships ORDER BY start_date ASC NULLS LAST, student_name ASC"
            )
            buildList {
                while (rs.next()) {
                    add(Internship(
                        studentName     = rs.getString("student_name") ?: "",
                        studyYear       = rs.getString("study_year") ?: "",
                        organization    = rs.getString("organization") ?: "",
                        address         = rs.getString("address") ?: "",
                        conventionNumber = rs.getString("convention_number") ?: "",
                        conventionGenDate = rs.getString("convention_gen_date") ?: "",
                        signingDate     = rs.getString("signing_date")?.let { runCatching { LocalDate.parse(it) }.getOrNull() },
                        startDate       = rs.getString("start_date")?.let { runCatching { LocalDate.parse(it) }.getOrNull() },
                        endDate         = rs.getString("end_date")?.let { runCatching { LocalDate.parse(it) }.getOrNull() },
                        rawDateStage    = rs.getString("raw_date_stage") ?: "",
                        theme           = rs.getString("theme") ?: "",
                    ))
                }
            }
        }
    }

    fun count(): Int = connect().use { conn ->
        conn.createStatement().executeQuery("SELECT COUNT(*) FROM internships").use {
            if (it.next()) it.getInt(1) else 0
        }
    }

    fun clear() {
        connect().use { it.createStatement().execute("DELETE FROM internships") }
    }
}

// Clé stable : étudiant + organisme + dates brutes → SHA-256 tronqué à 24 hex chars
internal fun Internship.localId(): String {
    val key = "${studentName.trim().lowercase()}|${organization.trim().lowercase()}|${rawDateStage.trim()}"
    return MessageDigest.getInstance("SHA-256")
        .digest(key.toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it) }
        .substring(0, 24)
}
