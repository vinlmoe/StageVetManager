package fr.vetbrain.stagevetmanager.onedrive

import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.io.IOException

class OneDriveExcelUpdaterTest {

    private lateinit var server: MockWebServer

    @BeforeEach
    fun setUp() {
        server = MockWebServer()
        server.start()
    }

    @AfterEach
    fun tearDown() {
        server.shutdown()
    }

    private fun updater(): OneDriveExcelUpdater =
        OneDriveExcelUpdater("fake-token", server.url("/").toString().trimEnd('/'))

    // ── enc / encPath ─────────────────────────────────────────────────────────

    @Test
    fun `enc encodes spaces as percent-20`() {
        assertEquals("Tous%20les%20stages", OneDriveExcelUpdater("t").enc("Tous les stages"))
    }

    @Test
    fun `encPath preserves slashes between segments`() {
        assertEquals(
            "Documents/StageVet/export.xlsx",
            OneDriveExcelUpdater("t").encPath("Documents/StageVet/export.xlsx")
        )
    }

    @Test
    fun `encPath encodes spaces within path segments`() {
        assertEquals(
            "My%20OneDrive/StageVet",
            OneDriveExcelUpdater("t").encPath("My OneDrive/StageVet")
        )
    }

    // ── buildValuesBody ───────────────────────────────────────────────────────

    @Test
    fun `buildValuesBody produces valid JSON structure`() {
        val json = OneDriveExcelUpdater("t").buildValuesBody(listOf(listOf("A", "B"), listOf("C", "D")))
        assertEquals("""{"values":[["A","B"],["C","D"]]}""", json)
    }

    @Test
    fun `buildValuesBody escapes double quotes in cell values`() {
        val json = OneDriveExcelUpdater("t").buildValuesBody(listOf(listOf("""say "hello"""")))
        assertTrue(json.contains("""\""""), "Expected escaped quote in: $json")
    }

    @Test
    fun `buildValuesBody replaces newlines with space`() {
        val json = OneDriveExcelUpdater("t").buildValuesBody(listOf(listOf("line1\nline2")))
        assertFalse(json.contains('\n'), "Newline should be replaced")
        assertTrue(json.contains("line1 line2"))
    }

    // ── extractJsonString ─────────────────────────────────────────────────────

    @Test
    fun `extractJsonString returns value for existing key`() {
        assertEquals("abc123", OneDriveExcelUpdater("t").extractJsonString("""{"id":"abc123"}""", "id"))
    }

    @Test
    fun `extractJsonString returns null for missing key`() {
        assertNull(OneDriveExcelUpdater("t").extractJsonString("""{"other":"x"}""", "id"))
    }

    // ── MockWebServer integration ─────────────────────────────────────────────

    @Test
    fun `update calls closeSession even when addWorksheet returns 500`() {
        server.enqueue(ok())                                      // ensureFileExists
        server.enqueue(ok("""{"id":"sess-001"}"""))              // createSession
        server.enqueue(ok("""{"value":[]}"""))                   // listWorksheets (no sheets)
        server.enqueue(MockResponse().setResponseCode(500).setBody("Server Error")) // addWorksheet fails
        server.enqueue(ok())                                      // closeSession (finally block)

        assertThrows(IOException::class.java) {
            updater().update(emptyList(), "test/file.xlsx")
        }

        assertEquals(5, server.requestCount)
        val requests = (1..5).map { server.takeRequest() }
        assertTrue(
            requests.last().path!!.contains("closeSession"),
            "Last request should be closeSession, got: ${requests.last().path}"
        )
    }

    @Test
    fun `update calls addWorksheet for each sheet absent from listWorksheets`() {
        fun ok(body: String = """{"id":"sess-001"}""") =
            MockResponse().setResponseCode(200).setBody(body)

        server.enqueue(ok())                          // 1: ensureFileExists
        server.enqueue(ok())                          // 2: createSession → id extracted
        server.enqueue(ok("""{"value":[]}"""))        // 3: listWorksheets (empty — all 3 sheets absent)
        repeat(9) { server.enqueue(ok()) }            // 4-12: addWorksheet×3 + clear×3 + write×3
        server.enqueue(ok())                          // 13: closeSession

        updater().update(emptyList(), "test/file.xlsx")

        assertEquals(13, server.requestCount)
        val req4 = (1..4).map { server.takeRequest() }.last()
        assertTrue(
            req4.path!!.contains("worksheets/add"),
            "4th request should be addWorksheet, got: ${req4.path}"
        )
    }

    @Test
    fun `update throws IOException when createSession returns 401`() {
        server.enqueue(ok())                                             // ensureFileExists
        server.enqueue(MockResponse().setResponseCode(401).setBody("Unauthorized")) // createSession

        assertThrows(IOException::class.java) {
            updater().update(emptyList(), "test/file.xlsx")
        }
        assertEquals(2, server.requestCount)
    }

    private fun ok(body: String = "{}") =
        MockResponse().setResponseCode(200).setBody(body)
}
