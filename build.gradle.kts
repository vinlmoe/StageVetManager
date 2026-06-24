import org.jetbrains.compose.desktop.application.dsl.TargetFormat

plugins {
    kotlin("jvm") version "2.0.21"
    id("org.jetbrains.compose") version "1.7.3"
    id("org.jetbrains.kotlin.plugin.compose") version "2.0.21"
}

group = "fr.vetbrain"
version = "1.0.0"

kotlin {
    jvmToolchain(21)
}


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

    implementation("com.microsoft.azure:msal4j:1.19.0")
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
