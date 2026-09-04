package fr.vetbrain.stagevetmanager.scraper

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class FirefoxBinaryLocatorTest {
    @Test
    fun `macOS candidates include system and user Applications folders`() {
        val paths = FirefoxBinaryLocator.candidatePaths("Mac OS X", "/Users/test", emptyMap())

        assertTrue(paths.contains("/Applications/Firefox.app/Contents/MacOS/firefox"))
        assertTrue(paths.contains("/Users/test/Applications/Firefox.app/Contents/MacOS/firefox"))
    }

    @Test
    fun `Linux candidates include Firefox executables from PATH`() {
        val paths = FirefoxBinaryLocator.candidatePaths(
            "Linux",
            "/home/test",
            mapOf("PATH" to "/custom/bin:/usr/local/bin"),
        )

        assertTrue(paths.contains("/custom/bin/firefox"))
        assertTrue(paths.contains("/custom/bin/firefox-esr"))
    }

    @Test
    fun `platform label recognizes supported systems`() {
        assertEquals("macOS", FirefoxBinaryLocator.platformLabel("Mac OS X"))
        assertEquals("Windows", FirefoxBinaryLocator.platformLabel("Windows 11"))
        assertEquals("Linux", FirefoxBinaryLocator.platformLabel("Linux"))
    }
}
