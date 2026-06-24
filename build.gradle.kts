import org.jetbrains.compose.desktop.application.dsl.TargetFormat

plugins {
    kotlin("jvm") version "2.0.21"
    id("org.jetbrains.compose") version "1.7.3"
    id("org.jetbrains.kotlin.plugin.compose") version "2.0.21"
}

group = "fr.vetbrain"
version = "1.0.0"
val geckoDriverVersion = "0.35.0"

kotlin {
    jvmToolchain(21)
}


repositories {
    mavenCentral()
    google()
    maven("https://maven.pkg.jetbrains.space/public/p/compose/dev")
}

// GeckoDriver binaries are downloaded into build/ and included as resources
sourceSets {
    main {
        resources {
            srcDir(layout.buildDirectory.dir("geckodriver-resources"))
        }
    }
}

data class GeckoDriverPlatform(val key: String, val archive: String, val binary: String, val output: String)

val geckoPlatforms = listOf(
    GeckoDriverPlatform("linux-x64",   "geckodriver-v${geckoDriverVersion}-linux64.tar.gz",       "geckodriver",     "geckodriver-linux-x64"),
    GeckoDriverPlatform("linux-arm64", "geckodriver-v${geckoDriverVersion}-linux-aarch64.tar.gz", "geckodriver",     "geckodriver-linux-arm64"),
    GeckoDriverPlatform("macos-x64",   "geckodriver-v${geckoDriverVersion}-macos.tar.gz",         "geckodriver",     "geckodriver-macos-x64"),
    GeckoDriverPlatform("macos-arm64", "geckodriver-v${geckoDriverVersion}-macos-aarch64.tar.gz", "geckodriver",     "geckodriver-macos-arm64"),
    GeckoDriverPlatform("win-x64",     "geckodriver-v${geckoDriverVersion}-win64.zip",            "geckodriver.exe", "geckodriver-win-x64.exe"),
)

tasks.register("downloadGeckoDrivers") {
    description = "Télécharge les binaires GeckoDriver pour toutes les plateformes cibles"
    group = "distribution"

    val downloadDir = layout.buildDirectory.dir("geckodriver-downloads")
    val outputDir   = layout.buildDirectory.dir("geckodriver-resources/drivers")

    outputs.dir(outputDir)
    inputs.property("geckoDriverVersion", geckoDriverVersion)

    doLast {
        val dlDir  = downloadDir.get().asFile.also { it.mkdirs() }
        val outDir = outputDir.get().asFile.also { it.mkdirs() }

        for (p in geckoPlatforms) {
            val dest = File(outDir, p.output)
            if (dest.exists()) { logger.lifecycle("  ✓ GeckoDriver ${p.key} déjà présent"); continue }

            val archiveFile = File(dlDir, p.archive)
            val url = "https://github.com/mozilla/geckodriver/releases/download/v${geckoDriverVersion}/${p.archive}"
            logger.lifecycle("  ↓ Téléchargement GeckoDriver ${p.key}…")

            // Follow HTTP redirects (GitHub releases use CDN redirects)
            var connection = uri(url).toURL().openConnection() as java.net.HttpURLConnection
            connection.instanceFollowRedirects = true
            var redirects = 0
            while (connection.responseCode in 301..308 && redirects < 5) {
                val location = connection.getHeaderField("Location")
                connection.disconnect()
                connection = uri(location).toURL().openConnection() as java.net.HttpURLConnection
                connection.instanceFollowRedirects = true
                redirects++
            }
            connection.inputStream.use { input -> archiveFile.outputStream().use { input.copyTo(it) } }
            connection.disconnect()

            if (p.archive.endsWith(".zip")) {
                project.copy {
                    from(project.zipTree(archiveFile)) { include(p.binary) }
                    into(outDir)
                    rename(p.binary, p.output)
                }
            } else {
                project.copy {
                    from(project.tarTree(project.resources.gzip(archiveFile))) { include(p.binary) }
                    into(outDir)
                    rename(p.binary, p.output)
                }
            }
            dest.setExecutable(true)
            archiveFile.delete()
            logger.lifecycle("  ✓ GeckoDriver ${p.key} prêt")
        }
    }
}

tasks.named("processResources") {
    dependsOn("downloadGeckoDrivers")
}

dependencies {
    implementation(compose.desktop.currentOs)
    implementation(compose.material3)
    implementation(compose.materialIconsExtended)

    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.9.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-swing:1.9.0")

    implementation("org.seleniumhq.selenium:selenium-java:4.27.0")

    implementation("org.apache.poi:poi:5.3.0")
    implementation("org.apache.poi:poi-ooxml:5.3.0")

    implementation("org.jsoup:jsoup:1.18.3")

    implementation("org.xerial:sqlite-jdbc:3.47.1.0")

    implementation("com.squareup.okhttp3:okhttp:4.12.0")

    implementation("org.slf4j:slf4j-simple:2.0.16")

    implementation("org.apache.pdfbox:pdfbox:3.0.3")

    testImplementation("org.junit.jupiter:junit-jupiter:5.10.2")
    testImplementation("io.mockk:mockk:1.13.11")
    testImplementation("com.squareup.okhttp3:mockwebserver:4.12.0")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.9.0")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.test {
    useJUnitPlatform()
}

// Tests don't use any Compose/UI code, so we can exclude androidx transitive
// deps that Compose pulls in but are unavailable from the network policy.
configurations.testRuntimeClasspath {
    exclude(group = "androidx.annotation")
    exclude(group = "androidx.collection")
    exclude(group = "androidx.lifecycle")
    exclude(group = "androidx.arch.core")
    exclude(group = "org.jetbrains.compose.annotation-internal")
    exclude(group = "org.jetbrains.compose.collection-internal")
    exclude(group = "org.jetbrains.androidx.lifecycle")
}

compose.desktop {
    application {
        mainClass = "fr.vetbrain.stagevetmanager.MainKt"
        nativeDistributions {
            targetFormats(TargetFormat.Dmg, TargetFormat.Msi, TargetFormat.Deb, TargetFormat.Exe)
            packageName = "StageVetManager"
            packageVersion = "1.0.0"
            description = "Gestion des stages vétérinaires - VetAgro Sup"
            vendor = "VetBrain"
        }
    }
}
