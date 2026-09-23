package com.saloo.sites.anizm

// §129 — anizm.net güncel zincir (hexated/yuzono referanslarına göre yeniden yazım):
// bölüm sayfası → div.episodeTranslators div#fansec a[translator] →
// translator JSON {data} → a[video] → /video JSON {player} → iframe src →
//  - https://anizmplayer.com: FirePlayer packed JS → key →
//    POST anizmplayer.com/player/index.php?data=<key>&do=getVideo →
//    {videoSource|securedLink} m3u8 → M3u8Helper gerçek varyantlar
//  - diğer hostlar → loadExtractor
// Not: anizm.net bu sürümde CloudflareKiller gerektirmiyor (hexated/yuzono kanıtı —
// iki bağımsız çalışan provider CFK'sız); zorlanmış mobil UA kaldırıldı — default
// istemci UA ile çalışıyor. §107 kanıtı: pinned lib'de getAndUnpack çözümlenmediği
// için yerel unpackPackedEvalJs korunuyor.

import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.AppUtils.tryParseJson
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.M3u8Helper
import com.lagradost.cloudstream3.utils.loadExtractor
import org.json.JSONObject
import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import java.net.URLEncoder

class Anizm : MainAPI() {
    override var mainUrl = "https://anizm.net"
    // §93: kullanıcıya görünen provider adı — "SiteAdı [Saloo]" ad kuralı.
    override var name = "Anizm [Saloo]"
    // §93: ana sayfa provider listesine girebilmesi için zorunlu.
    override val hasMainPage = true
    override var lang = "tr"
    // §93: anime sitesi; TvSeries korunur (search/load akışı), Anime beyan edilir.
    override val supportedTypes = setOf(TvType.TvSeries, TvType.Anime)

    companion object {
        /** §128 kanıtı: .net player host'u ayrı domain (FirePlayer + POST do=getVideo). */
        private const val MAIN_SERVER = "https://anizmplayer.com"

        /** XHR uçları için ortak header (hexated/yuzono deseni; custom UA YOK). */
        private val XHR_HEADERS = mapOf(
            "Accept" to "application/json, text/javascript, */*; q=0.01",
            "X-Requested-With" to "XMLHttpRequest",
            "Referer" to "https://anizm.net/",
        )

        private const val SEARCH_PATH = "/searchAnime"

        /** Detay sayfasındaki bölüm linkleri (keşif: -N-bolum ve -N-bolum-izle formları). */
        private val EPISODE_HREF_REGEX =
            Regex("""href="https?://anizm\.(?:net|com\.tr)/([a-z0-9\-]+-\d+-bolum(?:-[a-z0-9]+)?)""", RegexOption.IGNORE_CASE)
        private val EPISODE_NO_REGEX = Regex("""-(\d+)-bolum""", RegexOption.IGNORE_CASE)
        private val TITLE_REGEX = Regex("""<title>(.*?)</title>""", RegexOption.DOT_MATCHES_ALL)
    }

    // --------------------------------------------------------------- SEARCH

    override suspend fun search(query: String): List<SearchResponse> {
        // §99 kanıtı: XHR header'sız/yanlış type → {"data":[],"error":"Geçersiz arama parametreleri"}.
        // in-app doğrulama (kullanıcı testi): arama .net üzerinde çalışıyor → endpoint korunur.
        val requestUrl = mainUrl + SEARCH_PATH +
            "?query=" + URLEncoder.encode(query, "UTF-8") +
            "&page=1&type=detailed&limit=10&priorityField=info_title&orderBy=info_year&orderDirection=ASC"
        val body = try {
            app.get(requestUrl, headers = XHR_HEADERS).text
        } catch (_: Exception) {
            return emptyList()
        }

        val outer = try {
            JSONObject(body)
        } catch (_: Exception) {
            return emptyList()
        }
        val result = outer.optJSONArray("data") ?: return emptyList()

        val responses = ArrayList<SearchResponse>()
        for (i in 0 until result.length()) {
            val item = result.optJSONObject(i) ?: continue
            val title = item.optString("info_title").trim()
            val slug = item.optString("info_slug").trim()
            if (title.isBlank() || slug.isBlank()) continue
            val posterPath = item.optString("info_poster").trim()

            responses += newTvSeriesSearchResponse(title, "$mainUrl/$slug", TvType.TvSeries) {
                if (posterPath.isNotBlank()) this.posterUrl = "$mainUrl/storage/pcovers/$posterPath"
            }
        }
        return responses.distinctBy { it.url }
    }

    override suspend fun quickSearch(query: String): List<SearchResponse> = search(query)
    // --------------------------------------------------------------- LOADLINKS (§129)

    /**
     * §129 güncel zincir (hexated/yuzono canlı kanıtlı):
     * data (bölüm URL) → bölüm sayfası div.episodeTranslators div#fansec a[translator] →
     * GET translator (XHR) → JSON {data} HTML'inde a[video] →
     * GET /video/<id> (XHR) → JSON {player} iframe src →
     *  - https://anizmplayer.com: player sayfası FirePlayer packed JS → key →
     *    POST /player/index.php?data=<key>&do=getVideo → {videoSource|securedLink} m3u8
     *    → M3u8Helper GERÇEK varyantlar
     *  - diğer hostlar → loadExtractor
     * CloudflareKiller YOK (§128: iki bağımsız çalışan referansta da yok).
     */
    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ): Boolean {
        val document = app.get(data).document
        document.select("div.episodeTranslators div#fansec").map {
            Pair(it.select("a").attr("translator"), it.select("div.title").text())
        }.forEach { (url, translator) ->
            try {
                app.get(
                    url,
                    referer = data,
                    headers = XHR_HEADERS,
                ).text.let { body ->
                    tryParseJson<Translators>(body)?.data?.let { dataHtml ->
                        Jsoup.parse(dataHtml).select("a").forEach { video ->
                            try {
                                app.get(
                                    video.attr("video"),
                                    referer = data,
                                    headers = XHR_HEADERS,
                                ).text.let { vBody ->
                                    tryParseJson<Videos>(vBody)?.player?.let { iframe ->
                                        Jsoup.parse(iframe).select("iframe").attr("src").let { link ->
                                            when {
                                                link.startsWith(MAIN_SERVER) ->
                                                    invokeLokalSource(link, translator, callback)
                                                else ->
                                                    loadExtractor(
                                                        fixUrl(link),
                                                        "$mainUrl/",
                                                        subtitleCallback,
                                                        callback,
                                                    )
                                            }
                                        }
                                    }
                                }
                            } catch (_: Exception) {
                                // Tek ölü video kaynağı diğerlerini engellemesin.
                            }
                        }
                    }
                }
            } catch (_: Exception) {
                // Tek ölü translator diğerlerini engellemesin.
            }
        }
        return true
    }

    /** Takvim g├╝n ba┼şl─▒klar─▒ (canl─▒ kan─▒t: /takvim'de Pazartesi..Pazar ba┼şl─▒klar─▒). */
    private val WEEKDAY_REGEX =
        Regex("""^(Pazartesi|Sal─▒|├çar┼şamba|Per┼şembe|Cuma|Cumartesi|Pazar)$""")

    private val EPISODE_HREF_FRAGMENT = "-bolum"
    private val CARD_EPISODE_REGEX = Regex("""(\d+)\.\s?B[├Âo]l[├╝u]m""", RegexOption.IGNORE_CASE)

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val document = app.get(request.data).document

        // Kartlar + g├╝n ba┼şl─▒klar─▒ belge s─▒ras─▒nda taran─▒r. Se├ğiciler YALNIZ canl─▒
        // do─şrulanm─▒┼ş desenlere dayan─▒r: b├Âl├╝m linkli <a> (href'te "-bolum") + i├ğinde
        // poster <img>. G├╝n ba┼şl─▒klar─▒ etiket-ba─ş─▒ms─▒z (ownText birebir g├╝n ad─▒) bulunur
        // ÔåÆ class de─şi┼şikliklerine dayan─▒kl─▒; g├╝n bulunamazsa tek liste (request.name).
        val sections = LinkedHashMap<String, MutableList<SearchResponse>>()
        var currentSection = request.name
        for (element in document.body().select("*")) {
            val ownText = element.ownText().trim()
            if (ownText.matches(WEEKDAY_REGEX)) {
                currentSection = ownText
                continue
            }
            if (element.tagName() != "a") continue
            val href = element.attr("href")
            if (!href.contains(EPISODE_HREF_FRAGMENT)) continue
            val posterImg = element.selectFirst("img") ?: continue

            val detailUrl = toAnimeDetailUrl(href) ?: continue
            val section = sections.getOrPut(currentSection) { mutableListOf() }
            if (section.any { it.url == detailUrl }) continue

            val title = cardTitle(element, detailUrl) ?: continue
            val episodeNo = CARD_EPISODE_REGEX.find(element.text())?.groupValues?.get(1)?.toIntOrNull()
            section += newAnimeSearchResponse(title, detailUrl, TvType.Anime) {
                posterUrl = fixUrlNull(posterImg.attr("src").ifBlank { posterImg.attr("data-src") })
                if (episodeNo != null) addSub(episodeNo)
            }
        }

        val lists = sections.map { (sectionName, items) -> HomePageList(sectionName, items) }
            .filter { it.list.isNotEmpty() }
        if (lists.isEmpty()) {
            throw ErrorLoadingException("Anizm: ana sayfa listesi bo┼ş d├Ând├╝ (${request.data})")
        }
        // ─░ki kaynak da sayfalanamaz (canl─▒ do─şrulama: ?sayfa= parametresi SSR'─▒ de─şi┼ştirmiyor).
        return newHomePageResponse(lists, hasNext = false)
    }

    /**
     * B├Âl├╝m linkli href'i anime DETAY URL'ine ├ğevirir (referans provider'daki
     * getProperAnimeLink deseni): host (anizm.net/anizm.tv) ÔåÆ mainUrl normalize;
     * "-<N>-bolum..." soneki k─▒rp─▒l─▒r (takvim + ana sayfa canl─▒ kan─▒t─▒).
     */
    private fun toAnimeDetailUrl(href: String): String? {
        if (href.isBlank()) return null
        val absolute = fixUrl(href)
        if (!absolute.contains(EPISODE_HREF_FRAGMENT)) return null
        val stripped = absolute.replace(Regex("""-\d+-bolum.*$"""), "")
        val path = stripped.substringAfter("://", missingDelimiterValue = "").substringAfter('/')
        return if (path.isBlank()) null else "\$mainUrl/\$path"
    }

    /** Kart ba┼şl─▒─ş─▒: ├Ânce img alt, sonra anchor metni ("N. B├Âl├╝m", "Son Eklenen: ...", "- Anizm.TV" k─▒rp─▒l─▒r). */
    private fun cardTitle(element: Element, detailUrl: String): String? {
        val candidates = listOfNotNull(
            element.selectFirst("img")?.attr("alt"),
            element.text(),
        )
        for (raw in candidates) {
            val cleaned = raw
                .replace(Regex("""^\s*\d+\.\s*B[├Âo]l[├╝u]m(\s+Final)?\s*""", RegexOption.IGNORE_CASE), "")
                .replace(Regex("""\s*Son\s+Eklenen:\s*\d+\.\s*B[├Âo]l[├╝u]m(\s+Final)?\s*$""", RegexOption.IGNORE_CASE), "")
                .replace(Regex("""\s*-\s*Anizm(\.TV)?\s*$""", RegexOption.IGNORE_CASE), "")
                .trim()
            if (cleaned.isNotBlank()) return cleaned
        }
        return detailUrl.trimEnd('/').substringAfterLast('/').replace('-', ' ')
    }

    // --------------------------------------------------------------- LOAD

    override suspend fun load(url: String): LoadResponse {
        val html = app.get(url).text
        if (html.isBlank()) throw ErrorLoadingException("Anizm: detay sayfas─▒ bo┼ş d├Ând├╝ (\$url)")

        val title = extractTitle(html)
            ?: url.trimEnd('/').substringAfterLast('/').replace('-', ' ')

        val episodes = extractEpisodes(html)
        if (episodes.isEmpty()) throw ErrorLoadingException("Anizm: detay sayfas─▒nda b├Âl├╝m linki yok (\$url)")

        return newTvSeriesLoadResponse(
            title, url, TvType.TvSeries,
            episodes.sortedBy { it.episode ?: 0 }
        ) {
            this.posterUrl = extractMeta(html, "og:image")
            this.plot = extractMeta(html, "og:description")
        }
    }

    /** <title> ÔåÆ " izle | Anizm" / " izle..." sonekleri k─▒rp─▒l─▒r (ke┼şif: her iki form da var). */
    private fun extractTitle(html: String): String? {
        val raw = TITLE_REGEX.find(html)?.groupValues?.get(1)?.trim() ?: return null
        val cleaned = raw
            .replace(Regex("""\s*\|\s*Anizm\s*$"""), "")
            .replace(Regex("""\s+izle(\.\.\.)?\s*$"""), "")
            .trim()
        return cleaned.ifBlank { raw }
    }

    /**
     * Ke┼şif kan─▒t─▒: detay sayfas─▒ hem "-N-bolum" hem "-N-bolum-izle" formlar─▒ i├ğerir;
     * ayn─▒ b├Âl├╝m numaras─▒ i├ğin "-izle" (oynatma formu) tercih edilir. Episode data =
     * b├Âl├╝m sayfas─▒n─▒n tam URL'i; numara URL'den ├ğ─▒kar (final sonekli varyant dahil).
     */
    private fun extractEpisodes(html: String): List<Episode> {
        data class Ep(val slug: String, val no: Int, val isFinal: Boolean, val isIzle: Boolean)

        val found = ArrayList<Ep>()
        for (match in EPISODE_HREF_REGEX.findAll(html)) {
            val slug = match.groupValues[1].trimEnd('/')
            val no = EPISODE_NO_REGEX.find(slug)?.groupValues?.get(1)?.toIntOrNull() ?: continue
            found += Ep(slug, no, slug.contains("-final"), slug.endsWith("-izle"))
        }
        if (found.isEmpty()) return emptyList()

        // Ayn─▒ b├Âl├╝m numaras─▒n─▒n kopyalar─▒n─▒ tekille┼ştir (├Âncelik: -izle formu).
        val best = HashMap<Int, Ep>()
        for (ep in found) {
            val current = best[ep.no]
            if (current == null || (!current.isIzle && ep.isIzle)) best[ep.no] = ep
        }

        return best.values.map { ep ->
            val epName = buildString {
                append(ep.no).append(". B├Âl├╝m")
                if (ep.isFinal) append(" (Final)")
            }
            newEpisode("\$mainUrl/\${ep.slug}") {
                this.name = epName
                this.season = 1
                this.episode = ep.no
            }
        }
    }

    /** og:<name> meta i├ğeri─şi (attribute s─▒ras─▒ndan ba─ş─▒ms─▒z; Dizilla deseni). */
    private fun extractMeta(html: String, metaName: String): String? {
        val name = Regex.escape(metaName)
        val match = Regex("""<meta[^>]+(?:property|name)="\$name"[^>]*content="([^"]*)"""", RegexOption.IGNORE_CASE)
            .find(html)
            ?: Regex("""<meta[^>]+content="([^"]*)"[^>]*(?:property|name)="\$name"""", RegexOption.IGNORE_CASE)
                .find(html)
        return match?.groupValues?.get(1)?.trim()?.takeIf { it.isNotBlank() }
    }
    data class Source(
        @JsonProperty("videoSource") val videoSource: String?,
        @JsonProperty("securedLink") val securedLink: String?,
    )

    data class Videos(
        @JsonProperty("player") val player: String?,
    )

    data class Translators(
        @JsonProperty("data") val data: String?,
    )

    /**
     * anizmplayer.com player sayfasındaki FirePlayer packed JS'ini açar (unpackPackedEvalJs,
     * §107'de PowerShell ile canlı doğrulanmış mantık) ve AincradExtractor/yuzono deseniyle
     * POST do=getVideo ile gerçek m3u8'i çeker. M3u8Helper manifest'teki TÜM gerçek
     * varyantları teslim eder (kalite uydurma yok).
     */
    private suspend fun invokeLokalSource(
        url: String,
        translator: String,
        sourceCallback: (ExtractorLink) -> Unit,
    ) {
        app.get(url, referer = "$mainUrl/").document.select("script").find { script ->
            script.data().contains("eval(function(p,a,c,k,e,d)")
        }?.let {
            val key = unpackPackedEvalJs(it.data()).orEmpty()
                .substringAfter("FirePlayer(\"")
                .substringBefore("\",")
            val referer = "$MAIN_SERVER/video/$key"
            val link = "$MAIN_SERVER/player/index.php?data=$key&do=getVideo"
            app.post(
                link,
                data = mapOf("hash" to key, "r" to "$mainUrl/"),
                referer = referer,
                headers = mapOf(
                    "Accept" to "*/*",
                    "Origin" to MAIN_SERVER,
                    "Content-Type" to "application/x-www-form-urlencoded; charset=UTF-8",
                    "X-Requested-With" to "XMLHttpRequest",
                ),
            ).text.let { body ->
                tryParseJson<Source>(body)?.let { src ->
                    val m3uLink = src.videoSource ?: src.securedLink
                    m3uLink?.let { m3u ->
                        M3u8Helper.generateM3u8(
                            "$name ($translator)",
                            m3u,
                            referer,
                        ).forEach(sourceCallback)
                    }
                }
            }
        }
    }

    /**
     * §107/§108B — packed-eval (p,a,c,k,e,d) JS'ini açar (radix36 + keys; PowerShell'de
     * canlı doğrulandı; getAndUnpack pre-release lib snapshot'ta çözülmediği için yerel
     * yardımcı — davranış değişmez).
     */
    private fun unpackPackedEvalJs(html: String): String? {
        val m = Regex(
            "eval\\(function\\(p,a,c,k,e,d\\)\\{[\\s\\S]*?\\}\\('([\\s\\S]+?)',(\\d+),(\\d+),'([\\s\\S]+?)'\\.split\\('\\|'\\)",
        ).find(html) ?: return null
        var payload = m.groupValues[1]
        val keys = m.groupValues[4].split("|")
        val bs = "\\".single()
        payload = payload
            .replace("$bs$bs", bs.toString())
            .replace("$bs/", "/")
            .replace("$bs" + "\"", "\"")
            .replace("$bs" + "'", "'")
        return Regex("""\b([0-9a-z]+)\b""").replace(payload) { mm ->
            val tok = mm.groupValues[1]
            var n = 0
            var valid = true
            for (ch in tok) {
                when (ch) {
                    in '0'..'9' -> n = n * 36 + (ch - '0')
                    in 'a'..'z' -> n = n * 36 + (ch.code - 87)
                    else -> { valid = false; break }
                }
                if (n < 0) { valid = false; break }
            }
            if (valid && n < keys.size && keys[n].isNotEmpty()) keys[n] else mm.value
        }
    }
}

