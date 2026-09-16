// SalooRepo §65 — DiziYou provider (BronzeCloud DiziYou v27 tabanlı).
// Akış canlı doğrulandı (2026-09-16):
//  - Arama: /?s=<q> → div.listepisodes a kartları (aria-label = "X n. Sezon n. Bölüm")
//  - Dizi sayfası: /<slug>/ → h1 (başlık), div.category_image img (poster),
//    div.diziyou_desc (özet), span.dizimeta (meta satırları), div.bolumust (bölümler)
//  - Bölüm sayfası: iframe#diziyouPlayer → mainUrl/player/<id>.html →
//    player HTML'inde açık m3u8: https://storage.diziyou.one/episodes/<id>/play.m3u8

package com.saloo.sites.diziyou

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addActors
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.M3u8Helper
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.newExtractorLink
import org.jsoup.nodes.Element

class DiziYou : MainAPI() {
    override var mainUrl = "https://www.diziyou.one"
    override var name = "DiziYou"
    override val hasMainPage = true
    override var lang = "tr"
    override val supportedTypes = setOf(TvType.TvSeries)

    companion object {
        /** Bölüm URL kalıbı: /<dizi-slug>-<n>-sezon-<n>-bolum/ */
        private val EPISODE_URL_REGEX = Regex("""-(\d+)-sezon-(\d+)-bolum/?$""")
        private const val STORAGE_M3U8_REGEX =
            """https://storage\.diziyou\.one/episodes/\d+/play\.m3u8"""
    }

    /** Bölüm linkinden dizi sayfası URL'ini türetir. */
    private fun seriesUrlFromEpisode(episodeUrl: String): String? {
        val clean = episodeUrl.substringBefore("?").trimEnd('/')
        val m = EPISODE_URL_REGEX.find(clean) ?: return null
        val slug = clean.substringAfter("://").substringAfter('/')
        return "$mainUrl/" + slug.dropLast(m.value.length - 1) + "/"
    }

    private fun Element.toSearchResponse(): SearchResponse? {
        val href = this.attr("abs:href").ifBlank { this.attr("href") }
        if (href.isBlank()) return null
        val seriesUrl = seriesUrlFromEpisode(href) ?: return null
        val title = this.selectFirst("div#dizi-ismi")?.text()?.trim()
            ?: this.attr("aria-label").trim().ifBlank { return null }
        val poster = this.selectFirst("img")?.attr("data-src")?.takeIf { it.isNotBlank() }
            ?: this.selectFirst("img")?.attr("src")

        return newTvSeriesSearchResponse(title, seriesUrl, TvType.TvSeries) {
            this.posterUrl = poster
        }
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val document = app.get("$mainUrl/").document

        // Ana sayfa + alt sayfalardaki dizi indeksi: /<slug>/ biçimli iç bağlantılar.
        val series = document.select("a[href]").mapNotNull { el ->
            val href = el.attr("abs:href")
            if (!href.startsWith(mainUrl)) return@mapNotNull null
            val path = href.removePrefix(mainUrl).trimStart('/').trimEnd('/')
            if (path.isEmpty() || path.contains("?")) return@mapNotNull null
            if (path.contains("-bolum") || path.contains("/")) return@mapNotNull null
            val title = el.ownText().trim().ifBlank {
                el.selectFirst("div#dizi-ismi")?.text()?.trim() ?: return@mapNotNull null
            }
            if (title.isBlank()) return@mapNotNull null
            newTvSeriesSearchResponse(title, "$mainUrl/$path/", TvType.TvSeries) {
                this.posterUrl = el.selectFirst("img")?.attr("data-src")
                    ?: el.selectFirst("img")?.attr("src")
            }
        }.distinctBy { it.url }.take(48)

        return newHomePageResponse(HomePageList(name, series, false), false)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val document = app.get("$mainUrl/?s=${java.net.URLEncoder.encode(query, "UTF-8")}").document
        return document.select("div.listepisodes a").mapNotNull { it.toSearchResponse() }
            .distinctBy { it.url }
    }


    override suspend fun load(url: String): LoadResponse {
        val document = app.get(url).document

        val title = document.selectFirst("div.title h1")?.ownText()?.trim()
            ?: document.selectFirst("h1")?.ownText()?.trim()
            ?: url.trimEnd('/').substringAfterLast('/')
        val poster = document.selectFirst("div.category_image img")?.attr("src")?.takeIf { it.isNotBlank() }
        val plot = document.selectFirst("div.diziyou_desc")?.text()?.trim()
        val rating = document.selectFirst("div.imdb-logo")?.text()
            ?.substringAfter("IMDb:")?.trim()?.toDoubleOrNull()

        val metaLines = document.select("span.dizimeta").map { it.text().trim() }
        val year = metaLines.firstOrNull { it.contains("Yapım", ignoreCase = true) }
            ?.substringAfter(":")?.trim()?.substringBefore(" ")?.toIntOrNull()
        val actors = metaLines.firstOrNull { it.contains("Oyuncular", ignoreCase = true) }
            ?.substringAfter(":")?.split(",")?.mapNotNull { part ->
                part.trim().takeIf { it.isNotBlank() }?.let { Actor(it) }
            }.orEmpty()

        val episodes = document.select("div.bolumust").mapNotNull { block ->
            val link = block.selectFirst("a[href*='-bolum']")
                ?: block.selectFirst("a[href]") ?: return@mapNotNull null
            val href = link.attr("abs:href").ifBlank { link.attr("href") }
            if (href.isBlank()) return@mapNotNull null
            val m = EPISODE_URL_REGEX.find(href)
            val epName = block.selectFirst("div.bolumismi")?.text()?.trim()?.ifBlank { null }
                ?: link.attr("aria-label").trim().ifBlank { link.text().trim() }

            newEpisode(href) {
                this.name = epName
                this.season = m?.groupValues?.get(1)?.toIntOrNull() ?: 1
                this.episode = m?.groupValues?.get(2)?.toIntOrNull()
                this.posterUrl = poster
            }
        }.sortedWith(compareBy({ it.season }, { it.episode }))

        return newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
            this.posterUrl = poster
            this.plot = plot
            this.year = year
            this.score = Score.from10(rating)
            addActors(actors)
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ): Boolean {
        val episodeDoc = app.get(data).document
        val playerUrl = episodeDoc.selectFirst("iframe#diziyouPlayer")?.attr("abs:href")
            ?: episodeDoc.selectFirst("iframe#diziyouPlayer")?.attr("src")
            ?: return false

        val playerDoc = app.get(playerUrl, referer = "$mainUrl/").text
        val m3u8 = Regex(STORAGE_M3U8_REGEX).find(playerDoc)?.value ?: return false

        callback.invoke(
            newExtractorLink(
                source = name,
                name = name,
                url = m3u8,
                type = ExtractorLinkType.M3U8,
            ) {
                this.headers = mapOf("Referer" to "$mainUrl/")
                this.quality = Qualities.P1080.value
            }
        )

        // HLS parçalarını da tek tek sun (hata toleranslı; başarısızsa master kalır).
        try {
            M3u8Helper.generateM3u8(name, m3u8, "$mainUrl/").forEach(callback)
        } catch (_: Exception) {
        }

        return true
    }
}
