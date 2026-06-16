package fr.vetbrain.stagevetmanager.scraper

import fr.vetbrain.stagevetmanager.model.Internship

sealed class ScraperResult {
    data class Success(val totalCount: Int, val pageCount: Int) : ScraperResult()
    data class Failure(val message: String, val cause: Throwable? = null) : ScraperResult()
}
