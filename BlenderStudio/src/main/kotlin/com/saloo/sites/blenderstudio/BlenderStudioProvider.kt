package com.saloo.sites.blenderstudio

import com.lagradost.cloudstream3.HomePageList
import com.lagradost.cloudstream3.HomePageResponse
import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.MainPageRequest
import com.lagradost.cloudstream3.MovieSearchResponse
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.newHomePageResponse
import com.lagradost.cloudstream3.newMovieLoadResponse
import com.lagradost.cloudstream3.newMovieSearchResponse
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.loadExtractor

/**
 * Pilot provider for Blender Studio (https://studio.blender.org), the official
 * home of the Blender open movies (CC-licensed films, free to stream).
 *
 * Real scraping, verified against the live HTML (2026-09-08):
 *  - Catalog page: https://studio.blender.org/films/ -- server-rendered film
 *    cards: div.cards-item-film > a.cards-item-content[href="/projects/<slug>/"]
 *    with aria-label (title), img[src] (poster thumbnail),
 *    h3.cards-item-title (title span + year/"In Development" badge) and
 *    div.cards-item-excerpt (short description). The whole catalog lives on
 *    this single page: no pagination and no server-side search endpoint, so
 *    search() filters the live listing client-side.
 *  - Detail page:  https://studio.blender.org/projects/<slug>/ -- carries
 *    meta[property=og:image|og:description] and a watch button
 *    button.video-modal-link[data-video] pointing at the film's OFFICIAL
 *    YouTube upload; loadExtractor resolves it into playable streams
 *    (YoutubeExtractor is bundled with the CloudStream library).
 *
 * Registry record: siteId "blender-studio" in providers.json
 * (category=official, contentTypes=[movie, documentary]).
 */
class BlenderStudioProvider : MainAPI() {
    override var mainUrl = "https://studio.blender.org"
    override var name = "Blender Studio"
    override val supportedTypes = setOf(TvType.Movie, TvType.Documentary)
    override var lang = "en"

    override val hasMainPage = true

    companion object {
        /** Every film page <title> ends with this suffix (verified live). */
        private const val TITLE_SUFFIX = " - Blender Studio"
    }

    /**
     * Provider-local replacement for the removed CloudStream fixUrl() helper:
     * the current API (com.lagradost:cloudstream3:pre-release) no longer ships
     * it, so site-relative hrefs (e.g. "/projects/<slug>/") are resolved
     * against mainUrl here. Absolute URLs pass through unchanged.
     */
    private fun fixUrl(url: String): String =
        if (url.startsWith("http://") || url.startsWith("https://")) url
        else mainUrl + (if (url.startsWith("/")) "" else "/") + url

    private data class Film(
        val title: String,
        val url: String,
        val poster: String?,
        val year: Int?,
        val plot: String?,
    )

    private fun Film.toSearchResponse(): MovieSearchResponse =
        newMovieSearchResponse(title, url, TvType.Movie) {
            posterUrl = this@toSearchResponse.poster
            year = this@toSearchResponse.year
        }

    /**
     * Fetches and parses the server-rendered film listing at /films/.
     */
    private suspend fun fetchFilms(): List<Film> {
        val document = app.get("$mainUrl/films/").document
        return document.select("div.cards-item-film").mapNotNull { card ->
            val linkElement = card.selectFirst("a.cards-item-content")
                ?: return@mapNotNull null
            val href = linkElement.attr("href")
            if (href.isBlank()) return@mapNotNull null

            val title = linkElement.attr("aria-label").ifBlank {
                card.selectFirst("h3.cards-item-title span.me-2")?.text().orEmpty()
            }
            if (title.isBlank()) return@mapNotNull null

            Film(
                title = title,
                url = fixUrl(href),
                poster = card.selectFirst("img")?.attr("src")?.takeIf { it.isNotBlank() },
                // The badge holds the release year ("2026") or "In Development"
                // for unfinished productions, which maps to a null year here.
                year = card.selectFirst("h3.cards-item-title span.badge")
                    ?.text()?.trim()?.toIntOrNull(),
                plot = card.selectFirst("div.cards-item-excerpt")?.text()
                    ?.trim()?.takeIf { it.isNotEmpty() },
            )
        }
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val films = fetchFilms().map { it.toSearchResponse() }
        return newHomePageResponse(HomePageList(name, films), false)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val trimmed = query.trim()
        return fetchFilms()
            .filter { it.title.contains(trimmed, ignoreCase = true) }
            .map { it.toSearchResponse() }
    }

    override suspend fun load(url: String): LoadResponse {
        val document = app.get(url).document

        val title = document.title()
            .removeSuffix(TITLE_SUFFIX)
            .trim()
            .ifBlank {
                document.selectFirst("meta[property=og:title]")?.attr("content")
                    ?.removeSuffix(TITLE_SUFFIX)?.trim()
                    ?.takeIf { it.isNotEmpty() }
                    ?: name
            }

        val poster = document.selectFirst("meta[property=og:image]")?.attr("content")
            ?.takeIf { it.isNotBlank() }
        val plot = document.selectFirst("meta[property=og:description]")?.attr("content")
            ?.trim()?.takeIf { it.isNotEmpty() }

        // "Watch <film>" opens a modal that plays the film's official YouTube
        // upload; its URL is stored in the data-video attribute (HTML entities
        // such as &amp; are unescaped by jsoup automatically).
        val videoUrl = document.selectFirst("button.video-modal-link[data-video]")
            ?.attr("data-video")
            ?.trim()?.takeIf { it.isNotEmpty() }
            ?: document.selectFirst("[data-video]")?.attr("data-video")
                ?.trim()?.takeIf { it.isNotEmpty() }

        return newMovieLoadResponse(title, url, TvType.Movie, videoUrl) {
            this.posterUrl = poster
            this.plot = plot
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ): Boolean {
        if (data.isBlank()) return false
        // data-video is the film's official YouTube upload (e.g.
        // "https://www.youtube.com/watch?v=aqz-KE-bpKQ&t=511s"); CloudStream's
        // extractor pipeline resolves it into playable streams.
        loadExtractor(data, mainUrl, subtitleCallback, callback)
        return true
    }
}
