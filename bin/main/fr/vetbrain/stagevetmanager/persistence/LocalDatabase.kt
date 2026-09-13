package fr.vetbrain.stagevetmanager.persistence

import fr.vetbrain.stagevetmanager.model.ClinicStatus
import fr.vetbrain.stagevetmanager.model.ConventionPdfData
import fr.vetbrain.stagevetmanager.model.Internship
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.security.MessageDigest
import java.time.format.DateTimeFormatter
import java.sql.Connection
import java.sql.DriverManager
import java.time.LocalDate
import java.time.LocalDateTime

data class UpsertStats(val added: Int, val updated: Int) {
    val total get() = added + updated
    override fun toString() = "$added nouveau(x), $updated mis à jour"
}

class LocalDatabase(val dbPath: Path = defaultDbPath) {

    companion object {
        val defaultDbPath: Path = Paths.get(
            System.getProperty("user.home"), ".stagevetmanager", "internships.db"
        )
        var instance = LocalDatabase()
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
                    id                   TEXT PRIMARY KEY,
                    student_name         TEXT NOT NULL,
                    study_year           TEXT,
                    organization         TEXT,
                    address              TEXT,
                    convention_number    TEXT,
                    convention_gen_date  TEXT,
                    signing_date         TEXT,
                    start_date           TEXT,
                    end_date             TEXT,
                    raw_date_stage       TEXT,
                    theme                TEXT,
                    convention_pdf_url    TEXT,
                    convention_sign_url  TEXT,
                    convention_cancel_url TEXT,
                    duration_label       TEXT,
                    in_suivi_table       INTEGER DEFAULT 0,
                    local_pdf_path       TEXT DEFAULT '',
                    created_at           TEXT NOT NULL,
                    last_seen            TEXT NOT NULL
                )
            """.trimIndent())
            runCatching {
                conn.createStatement().execute(
                    "ALTER TABLE internships ADD COLUMN convention_pdf_url TEXT"
                )
            }
            runCatching {
                conn.createStatement().execute(
                    "ALTER TABLE internships ADD COLUMN convention_sign_url TEXT"
                )
            }
            runCatching {
                conn.createStatement().execute(
                    "ALTER TABLE internships ADD COLUMN convention_cancel_url TEXT"
                )
            }
            runCatching {
                conn.createStatement().execute(
                    "ALTER TABLE internships ADD COLUMN in_suivi_table INTEGER DEFAULT 0"
                )
            }
            runCatching {
                conn.createStatement().execute(
                    "ALTER TABLE internships ADD COLUMN duration_label TEXT"
                )
            }
            runCatching {
                conn.createStatement().execute(
                    "ALTER TABLE internships ADD COLUMN local_pdf_path TEXT DEFAULT ''"
                )
            }

            // Migration : renommer les colonnes de signature mal nommées dans les versions
            // antérieures (signing_date_student stockait en réalité la date du tuteur).
            runCatching {
                conn.createStatement().execute(
                    "ALTER TABLE pdf_data RENAME COLUMN signing_date_student TO signing_date_tutor"
                )
            }
            runCatching {
                conn.createStatement().execute(
                    "ALTER TABLE pdf_data RENAME COLUMN signing_date_host TO signing_date_student"
                )
            }
            runCatching {
                conn.createStatement().execute(
                    "ALTER TABLE pdf_data ADD COLUMN signing_date_host TEXT"
                )
            }
            runCatching {
                conn.createStatement().execute(
                    "ALTER TABLE pdf_data ADD COLUMN signing_date_school TEXT"
                )
            }
            runCatching {
                conn.createStatement().execute(
                    "ALTER TABLE pdf_data ADD COLUMN has_weekly_rest_day INTEGER"
                )
            }

            conn.createStatement().execute("""
                CREATE TABLE IF NOT EXISTS clinic_statuses (
                    organization TEXT PRIMARY KEY,
                    status       TEXT NOT NULL DEFAULT 'OK',
                    notes        TEXT
                )
            """.trimIndent())

            conn.createStatement().execute("""
                CREATE TABLE IF NOT EXISTS pdf_data (
                    url                  TEXT PRIMARY KEY,
                    parsed_at            TEXT NOT NULL,
                    school_contact       TEXT,
                    tutor_name           TEXT,
                    tutor_function       TEXT,
                    tutor_phone          TEXT,
                    tutor_email          TEXT,
                    host_organization    TEXT,
                    host_address         TEXT,
                    host_representative  TEXT,
                    supervisor_quality   TEXT,
                    host_phone           TEXT,
                    host_email           TEXT,
                    supervisor_name      TEXT,
                    supervisor_function  TEXT,
                    student_last_name    TEXT,
                    student_first_name   TEXT,
                    student_birth_date   TEXT,
                    student_study_year   TEXT,
                    student_address      TEXT,
                    student_phone        TEXT,
                    student_email        TEXT,
                    academic_year        TEXT,
                    start_date           TEXT,
                    end_date             TEXT,
                    duration_label       TEXT,
                    night_presence       INTEGER DEFAULT 0,
                    sunday_presence      INTEGER DEFAULT 0,
                    holiday_presence     INTEGER DEFAULT 0,
                    home_presence        INTEGER DEFAULT 0,
                    theme                TEXT,
                    gratification        TEXT,
                    signing_date_tutor   TEXT,
                    signing_date_student TEXT,
                    signing_date_host    TEXT,
                    signing_date_school  TEXT,
                    has_weekly_rest_day  INTEGER
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
                         start_date, end_date, raw_date_stage, theme,
                         convention_pdf_url, convention_sign_url, convention_cancel_url,
                         duration_label, in_suivi_table, created_at, last_seen)
                    VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)
                """.trimIndent())
                val updateStmt = conn.prepareStatement("""
                    UPDATE internships
                    SET student_name=?, study_year=?, organization=?, address=?,
                        convention_number=?, convention_gen_date=?, signing_date=COALESCE(?, signing_date),
                        start_date=?, end_date=?, raw_date_stage=?, theme=?,
                        convention_pdf_url=?, convention_sign_url=?, convention_cancel_url=?,
                        duration_label=?, last_seen=?
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
                            setString(12, s.conventionPdfUrl)
                            setString(13, s.conventionSignUrl)
                            setString(14, s.conventionCancelUrl)
                            setString(15, s.durationLabel)
                            // in_suivi_table intentionnellement absent : annotation locale,
                            // jamais écrasée par le scraper
                            setString(16, now)
                            setString(17, id)
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
                            setString(13, s.conventionPdfUrl)
                            setString(14, s.conventionSignUrl)
                            setString(15, s.conventionCancelUrl)
                            setString(16, s.durationLabel)
                            setInt(17, 0) // in_suivi_table = false pour les nouveaux stages
                            setString(18, now)
                            setString(19, now)
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
                        rawDateStage     = rs.getString("raw_date_stage") ?: "",
                        theme            = rs.getString("theme") ?: "",
                        conventionPdfUrl  = rs.getString("convention_pdf_url") ?: "",
                        conventionSignUrl = rs.getString("convention_sign_url") ?: "",
                        conventionCancelUrl = rs.getString("convention_cancel_url") ?: "",
                        durationLabel    = rs.getString("duration_label") ?: "",
                        inSuiviTable     = rs.getInt("in_suivi_table") == 1,
                        localPdfPath     = rs.getString("local_pdf_path") ?: "",
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

    fun updateSuiviTable(internship: Internship, checked: Boolean) {
        connect().use { conn ->
            conn.prepareStatement(
                "UPDATE internships SET in_suivi_table=? WHERE id=?"
            ).use { stmt ->
                stmt.setInt(1, if (checked) 1 else 0)
                stmt.setString(2, internship.localId())
                stmt.executeUpdate()
            }
        }
    }

    fun updateLocalPdfPath(internship: Internship, path: String) {
        connect().use { conn ->
            conn.prepareStatement(
                "UPDATE internships SET local_pdf_path=? WHERE id=?"
            ).use { stmt ->
                stmt.setString(1, path)
                stmt.setString(2, internship.localId())
                stmt.executeUpdate()
            }
        }
    }

    fun backup(): Path {
        val ts = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss"))
        val dest = dbPath.resolveSibling("internships_backup_$ts.db")
        // SQLite WAL checkpoint avant copie pour s'assurer que toutes les données sont dans le fichier principal
        connect().use { conn ->
            conn.createStatement().execute("PRAGMA wal_checkpoint(TRUNCATE)")
        }
        Files.copy(dbPath, dest)
        return dest
    }

    fun clear() {
        connect().use { conn ->
            conn.createStatement().execute("DELETE FROM internships")
            conn.createStatement().execute("DELETE FROM pdf_data")
        }
    }

    fun savePdfData(data: ConventionPdfData) {
        if (data.sourceUrl.isBlank()) return
        val now = LocalDateTime.now().toString()
        connect().use { conn ->
            conn.prepareStatement("""
                INSERT OR REPLACE INTO pdf_data (
                    url, parsed_at,
                    school_contact, tutor_name, tutor_function, tutor_phone, tutor_email,
                    host_organization, host_address, host_representative, supervisor_quality,
                    host_phone, host_email, supervisor_name, supervisor_function,
                    student_last_name, student_first_name, student_birth_date, student_study_year,
                    student_address, student_phone, student_email,
                    academic_year, start_date, end_date, duration_label,
                    night_presence, sunday_presence, holiday_presence, home_presence,
                    theme, gratification,
                    signing_date_tutor, signing_date_student, signing_date_host, signing_date_school,
                    has_weekly_rest_day
                ) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)
            """.trimIndent()).use { stmt ->
                stmt.setString(1, data.sourceUrl)
                stmt.setString(2, now)
                stmt.setString(3, data.schoolContact)
                stmt.setString(4, data.tutorName)
                stmt.setString(5, data.tutorFunction)
                stmt.setString(6, data.tutorPhone)
                stmt.setString(7, data.tutorEmail)
                stmt.setString(8, data.hostOrganization)
                stmt.setString(9, data.hostAddress)
                stmt.setString(10, data.hostRepresentative)
                stmt.setString(11, data.supervisorQuality)
                stmt.setString(12, data.hostPhone)
                stmt.setString(13, data.hostEmail)
                stmt.setString(14, data.supervisorName)
                stmt.setString(15, data.supervisorFunction)
                stmt.setString(16, data.studentLastName)
                stmt.setString(17, data.studentFirstName)
                stmt.setString(18, data.studentBirthDate)
                stmt.setString(19, data.studentStudyYear)
                stmt.setString(20, data.studentAddress)
                stmt.setString(21, data.studentPhone)
                stmt.setString(22, data.studentEmail)
                stmt.setString(23, data.academicYear)
                stmt.setString(24, data.startDate)
                stmt.setString(25, data.endDate)
                stmt.setString(26, data.durationLabel)
                stmt.setInt(27, if (data.nightPresence) 1 else 0)
                stmt.setInt(28, if (data.sundayPresence) 1 else 0)
                stmt.setInt(29, if (data.holidayPresence) 1 else 0)
                stmt.setInt(30, if (data.homePresence) 1 else 0)
                stmt.setString(31, data.theme)
                stmt.setString(32, data.gratification)
                stmt.setString(33, data.signingDateTutor)
                stmt.setString(34, data.signingDateStudent)
                stmt.setString(35, data.signingDateHost)
                stmt.setString(36, data.signingDateSchool)
                when (data.hasWeeklyRestDay) {
                    true  -> stmt.setInt(37, 1)
                    false -> stmt.setInt(37, 0)
                    null  -> stmt.setNull(37, java.sql.Types.INTEGER)
                }
                stmt.executeUpdate()
            }
        }
    }

    fun loadAllClinicStatuses(): Map<String, ClinicStatus> {
        return connect().use { conn ->
            val rs = conn.createStatement().executeQuery("SELECT organization, status FROM clinic_statuses")
            buildMap {
                while (rs.next()) {
                    val org = rs.getString("organization") ?: continue
                    val status = runCatching { ClinicStatus.valueOf(rs.getString("status")) }
                        .getOrDefault(ClinicStatus.OK)
                    put(org, status)
                }
            }
        }
    }

    fun setClinicStatus(organization: String, status: ClinicStatus, notes: String = "") {
        connect().use { conn ->
            conn.prepareStatement(
                "INSERT OR REPLACE INTO clinic_statuses (organization, status, notes) VALUES (?, ?, ?)"
            ).use { stmt ->
                stmt.setString(1, organization)
                stmt.setString(2, status.name)
                stmt.setString(3, notes)
                stmt.executeUpdate()
            }
        }
    }

    fun loadAllPdfData(): Map<String, ConventionPdfData> {
        return connect().use { conn ->
            val rs = conn.createStatement().executeQuery("SELECT * FROM pdf_data")
            buildMap {
                while (rs.next()) {
                    val url = rs.getString("url") ?: continue
                    put(url, ConventionPdfData(
                        rawText            = "",
                        sourceUrl          = url,
                        schoolContact      = rs.getString("school_contact") ?: "",
                        tutorName          = rs.getString("tutor_name") ?: "",
                        tutorFunction      = rs.getString("tutor_function") ?: "",
                        tutorPhone         = rs.getString("tutor_phone") ?: "",
                        tutorEmail         = rs.getString("tutor_email") ?: "",
                        hostOrganization   = rs.getString("host_organization") ?: "",
                        hostAddress        = rs.getString("host_address") ?: "",
                        hostRepresentative = rs.getString("host_representative") ?: "",
                        supervisorQuality  = rs.getString("supervisor_quality") ?: "",
                        hostPhone          = rs.getString("host_phone") ?: "",
                        hostEmail          = rs.getString("host_email") ?: "",
                        supervisorName     = rs.getString("supervisor_name") ?: "",
                        supervisorFunction = rs.getString("supervisor_function") ?: "",
                        studentLastName    = rs.getString("student_last_name") ?: "",
                        studentFirstName   = rs.getString("student_first_name") ?: "",
                        studentBirthDate   = rs.getString("student_birth_date") ?: "",
                        studentStudyYear   = rs.getString("student_study_year") ?: "",
                        studentAddress     = rs.getString("student_address") ?: "",
                        studentPhone       = rs.getString("student_phone") ?: "",
                        studentEmail       = rs.getString("student_email") ?: "",
                        academicYear       = rs.getString("academic_year") ?: "",
                        startDate          = rs.getString("start_date") ?: "",
                        endDate            = rs.getString("end_date") ?: "",
                        durationLabel      = rs.getString("duration_label") ?: "",
                        nightPresence      = rs.getInt("night_presence") == 1,
                        sundayPresence     = rs.getInt("sunday_presence") == 1,
                        holidayPresence    = rs.getInt("holiday_presence") == 1,
                        homePresence       = rs.getInt("home_presence") == 1,
                        theme              = rs.getString("theme") ?: "",
                        gratification      = rs.getString("gratification") ?: "",
                        signingDateTutor   = rs.getString("signing_date_tutor") ?: "",
                        signingDateStudent = rs.getString("signing_date_student") ?: "",
                        signingDateHost    = rs.getString("signing_date_host") ?: "",
                        signingDateSchool  = rs.getString("signing_date_school") ?: "",
                        hasWeeklyRestDay   = rs.getString("has_weekly_rest_day")?.let { it != "0" },
                    ))
                }
            }
        }
    }
}

// Clé stable : étudiant + organisme + dates brutes → SHA-256 tronqué à 24 hex chars
fun Internship.localId(): String {
    val key = "${studentName.trim().lowercase()}|${organization.trim().lowercase()}|${rawDateStage.trim()}"
    return MessageDigest.getInstance("SHA-256")
        .digest(key.toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it) }
        .substring(0, 24)
}
