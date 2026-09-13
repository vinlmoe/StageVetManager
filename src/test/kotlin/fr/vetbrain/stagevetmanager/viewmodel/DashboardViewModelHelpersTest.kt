package fr.vetbrain.stagevetmanager.viewmodel

import fr.vetbrain.stagevetmanager.model.Internship
import fr.vetbrain.stagevetmanager.model.LocalFilters
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.LocalDate

class DashboardViewModelHelpersTest {

    @Test
    fun `an invalid periode does not disable the other local filters`() {
        // Le `return true` sur periode.toIntOrNull() == null court-circuitait aussi
        // les critères anneeEtude et theme évalués plus bas.
        val stage = stage(studyYear = "3")

        val matches = stage.matchesLocalFilters(
            LocalFilters(periode = "pas-un-nombre", anneeEtude = "5"),
        )

        assertFalse(matches, "le filtre année doit continuer de s'appliquer")
    }

    @Test
    fun `an invalid periode still accepts a stage matching the other filters`() {
        val stage = stage(studyYear = "5")

        assertTrue(stage.matchesLocalFilters(LocalFilters(periode = "xx", anneeEtude = "5")))
    }

    @Test
    fun `an empty periode leaves the other filters active`() {
        assertFalse(stage(studyYear = "3").matchesLocalFilters(LocalFilters(anneeEtude = "5")))
        assertTrue(stage(studyYear = "5").matchesLocalFilters(LocalFilters(anneeEtude = "5")))
    }

    @Test
    fun `pdf filenames stay distinct for two conventions of the same student and date`() {
        // Une convention annulée puis régénérée produit deux stages avec le même
        // étudiant, la même année et la même date de début : les deux fichiers
        // s'écrasaient mutuellement dans le dossier des conventions.
        val first  = stage(studyYear = "5").copy(rawDateStage = "01/06/2026 au 30/06/2026")
        val second = stage(studyYear = "5").copy(rawDateStage = "01/06/2026 au 15/07/2026")

        assertNotEquals(buildPdfFilename(first), buildPdfFilename(second))
    }

    @Test
    fun `pdf filename stays readable and keeps the pdf extension`() {
        val name = buildPdfFilename(stage(studyYear = "5"))

        assertTrue(name.startsWith("Dupont_Marie_5_2026-06-01_"), "nom inattendu : $name")
        assertTrue(name.endsWith(".pdf"))
    }

    @Test
    fun `pdf filename falls back to placeholders on empty fields`() {
        val name = buildPdfFilename(stage(studyYear = "").copy(studentName = ""))

        assertTrue(name.startsWith("sans-nom_sans-annee_"), "nom inattendu : $name")
    }

    @Test
    fun `chromedriver cache versions are compared numerically`() {
        // "99.0.4844.51" > "120.0.6099.109" en comparaison lexicale : le pilote
        // le plus ancien était retenu après une mise à jour de Chrome.
        val versions = listOf("99.0.4844.51", "120.0.6099.109", "114.0.5735.90")

        val newest = versions.maxWithOrNull(
            fr.vetbrain.stagevetmanager.scraper.SeleniumScraper.VERSION_ORDER,
        )

        assertEquals("120.0.6099.109", newest)
    }

    private fun stage(studyYear: String) = Internship(
        studentName = "Dupont Marie",
        studyYear = studyYear,
        organization = "Clinique du Parc",
        address = "Lyon",
        conventionNumber = "C-1",
        conventionGenDate = "",
        signingDate = null,
        startDate = LocalDate.of(2026, 6, 1),
        endDate = LocalDate.of(2026, 6, 30),
        rawDateStage = "01/06/2026 au 30/06/2026",
        theme = "Animaux de compagnie",
    )
}
