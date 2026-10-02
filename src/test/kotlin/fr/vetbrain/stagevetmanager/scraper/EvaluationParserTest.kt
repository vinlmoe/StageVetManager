package fr.vetbrain.stagevetmanager.scraper

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class EvaluationParserTest {
    private fun fixture(name: String) = javaClass.getResource("/fixtures/evaluation_$name.html")!!.readText()

    @Test
    fun `dashboard associates the two evaluation links with their author`() {
        val html = """<div class="card"><div class="h4"><a>Étudiant test</a></div>
            <a href="/evaluation/test/results/1">Maître</a>
            <a href="/evaluation/test/results/2">Étudiant</a></div>"""
        val stage = DashboardParser.parse(html).single()
        assertEquals("/evaluation/test/results/1", stage.supervisorEvaluationUrl)
        assertEquals("/evaluation/test/results/2", stage.studentEvaluationUrl)
    }

    @Test
    fun `supervisor scores and comments exclude sidebar legend`() {
        val result = EvaluationParser.parse(fixture("supervisor") + "<input class='rating' value='1'>")
        assertTrue(result.contains("Respect des horaires de travail : ponctualité et assiduité : 5/5"))
        assertTrue(result.contains("Avis global : 5/5"))
        assertTrue(result.contains("stagiaire agréable et motivé"))
        assertTrue(result.contains("Aucun commentaire fourni"))
        assertEquals(2, Regex("Non renseigné").findAll(result).count())
        assertEquals(21, Regex("[0-5]/5").findAll(result).count())
    }

    @Test
    fun `student objectives and technical acts are included`() {
        val result = EvaluationParser.parse(fixture("student"))
        assertTrue(result.contains("très bon stage"))
        assertTrue(result.contains("approfondir travail théorique"))
        assertTrue(result.contains("cathé, plan fluido, torsion, suture"))
        assertEquals(22, Regex("[0-5]/5").findAll(result).count())
    }

    @Test
    fun `zero and missing scores remain distinct and login pages fail`() {
        val html = "<form id='base'><dl><dt>Item</dt><dd><input class='rating' value='0'></dd></dl></form>"
        assertEquals("Item : 0/5", EvaluationParser.parse(html))
        assertEquals("Item : Non renseigné", EvaluationParser.parse(html.replace("value='0'", "value=''")))
        assertThrows(IllegalArgumentException::class.java) { EvaluationParser.parse("<form id='login'></form>") }
    }
}
