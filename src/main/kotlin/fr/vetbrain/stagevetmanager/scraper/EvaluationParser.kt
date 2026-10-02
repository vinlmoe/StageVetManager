package fr.vetbrain.stagevetmanager.scraper

import org.jsoup.Jsoup

/** Only the result form is read: the sidebar contains sample stars, not scores. */
object EvaluationParser {
    fun parse(html: String): String {
        val form = Jsoup.parse(html).selectFirst("form#base")
            ?: throw IllegalArgumentException("Formulaire d'évaluation absent")
        require(form.select("input.rating").isNotEmpty()) { "Évaluation sans notes reconnaissables" }
        return buildList {
            for (element in form.select(".card-header, dl, .form-group")) {
                when {
                    element.hasClass("card-header") -> {
                        val title = element.selectFirst("h2")?.text().orEmpty()
                        val rating = element.selectFirst("input.rating")
                        if (title.isNotBlank()) add(if (rating == null) title else "$title : ${score(rating.attr("value"))}")
                        if (title.equals("Commentaire", ignoreCase = true)) {
                            element.nextElementSibling()?.text()?.takeIf { it.isNotBlank() }?.let { add(it) }
                        }
                    }
                    element.tagName() == "dl" -> {
                        val label = element.selectFirst("dt")?.text().orEmpty()
                        val rating = element.selectFirst("input.rating") ?: continue
                        add("$label : ${score(rating.attr("value"))}")
                    }
                    else -> {
                        val label = element.selectFirst("label")?.text().orEmpty()
                        val value = element.selectFirst("textarea")?.wholeText()
                            ?: element.selectFirst(".border")?.text().orEmpty()
                        if (value.isNotBlank()) add("$label $value")
                    }
                }
            }
        }.joinToString("\n")
    }

    private fun score(raw: String): String {
        val value = raw.trim().toIntOrNull()?.takeIf { it in 0..5 }
        return value?.let { "$it/5" } ?: "Non renseigné"
    }
}
