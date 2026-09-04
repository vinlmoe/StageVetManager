package fr.vetbrain.stagevetmanager.scraper

import java.io.File

internal class FirefoxNotInstalledException(message: String) : RuntimeException(message)

/** Résolution explicite du navigateur, car GeckoDriver ne le localise pas toujours seul. */
internal object FirefoxBinaryLocator {
    fun candidatePaths(
        osName: String,
        userHome: String,
        environment: Map<String, String>,
    ): List<String> {
        val os = osName.lowercase()
        val candidates = when {
            os.contains("win") -> listOfNotNull(
                environment["ProgramFiles"],
                environment["ProgramFiles(x86)"],
                environment["LOCALAPPDATA"],
            ).map { "$it\\Mozilla Firefox\\firefox.exe" } + listOf(
                "C:\\Program Files\\Mozilla Firefox\\firefox.exe",
                "C:\\Program Files (x86)\\Mozilla Firefox\\firefox.exe",
            )
            os.contains("mac") -> listOf(
                "/Applications/Firefox.app/Contents/MacOS/firefox",
                "$userHome/Applications/Firefox.app/Contents/MacOS/firefox",
                "/Applications/Firefox Developer Edition.app/Contents/MacOS/firefox",
                "$userHome/Applications/Firefox Developer Edition.app/Contents/MacOS/firefox",
            )
            else -> {
                val pathCandidates = environment["PATH"].orEmpty()
                    .split(File.pathSeparator)
                    .filter { it.isNotBlank() }
                    .flatMap { dir -> listOf("$dir/firefox", "$dir/firefox-esr") }
                pathCandidates + listOf("/usr/bin/firefox", "/usr/bin/firefox-esr", "/snap/bin/firefox")
            }
        }
        return candidates.distinct()
    }

    fun find(candidates: List<String>): File? = candidates
        .asSequence()
        .map(::File)
        .firstOrNull { it.isFile && it.canExecute() }

    fun platformLabel(osName: String): String = when {
        osName.contains("win", ignoreCase = true) -> "Windows"
        osName.contains("mac", ignoreCase = true) -> "macOS"
        else -> "Linux"
    }
}
