import org.jetbrains.compose.desktop.application.dsl.TargetFormat

plugins {
    kotlin("jvm") version "2.0.21"
    id("org.jetbrains.compose") version "1.7.3"
    id("org.jetbrains.kotlin.plugin.compose") version "2.0.21"
}

group = "fr.vetbrain"
version = "1.0.0"

repositories {
    mavenCentral()
    google()
    maven("https://maven.pkg.jetbrains.space/public/p/compose/dev")
}

dependencies {
    implementation(compose.desktop.currentOs)
    implementation(compose.material3)
    implementation(compose.materialIconsExtended)

    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.9.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-swing:1.9.0")

    implementation("org.seleniumhq.selenium:selenium-java:4.27.0")
    implementation("io.github.bonigarcia:webdrivermanager:5.9.2")

    implementation("org.apache.poi:poi:5.3.0")
    implementation("org.apache.poi:poi-ooxml:5.3.0")

    implementation("org.jsoup:jsoup:1.18.3")

    implementation("org.xerial:sqlite-jdbc:3.47.1.0")

    implementation("org.slf4j:slf4j-simple:2.0.16")
}

compose.desktop {
    application {
        mainClass = "fr.vetbrain.stagevetmanager.MainKt"
        nativeDistributions {
            targetFormats(TargetFormat.Dmg, TargetFormat.Msi, TargetFormat.Deb)
            packageName = "StageVetManager"
            packageVersion = "1.0.0"
            description = "Gestion des stages vétérinaires - VetAgro Sup"
            vendor = "VetBrain"
        }
    }
}
