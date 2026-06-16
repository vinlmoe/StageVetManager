package fr.vetbrain.stagevetmanager.scraper

import fr.vetbrain.stagevetmanager.model.Internship

sealed class ScraperResult {
    data class Success(val internships: List<Internship>, val pageCount: Int) : ScraperResult()
    data class Failure(val message: String, val cause: Throwable? = null) : ScraperResult()
}
