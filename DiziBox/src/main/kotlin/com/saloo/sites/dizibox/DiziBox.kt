package com.saloo.sites.dizibox

import android.util.Base64
import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addActors
import com.lagradost.cloudstream3.LoadResponse.Companion.addTrailer
import com.lagradost.cloudstream3.network.CloudflareKiller
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.utils.M3u8Helper
import com.lagradost.cloudstream3.utils.AppUtils.tryParseJson
import com.lagradost.cloudstream3.utils.StringUtils.decodeUri
import okhttp3.Interceptor
import okhttp3.Response
import org.jsoup.Jsoup
import org.jsoup.nodes.Element

class DiziBox : MainAPI() {
    override var mainUrl              = "https://www.dizibox.live"
    override var name                 = "DiziBox [Saloo]"
    override val hasMainPage          = true
    override var lang                 = "tr"
    override val hasQuickSearch       = true
    override val supportedTypes       = setOf(TvType.TvSeries)

    override var sequentialMainPage            = true
    override var sequentialMainPageDelay       = 200L
    override var sequentialMainPageScrollDelay = 50L

    private val baseCookies = mapOf(
        "LockUser"      to "true",
        "isTrustedUser" to "true",
        "dbxu"          to "1744054959089"
    )

    private val cloudflareKiller by lazy { CloudflareKiller() }
    private val interceptor      by lazy { CloudflareInterceptor(cloudflareKiller) }

    class CloudflareInterceptor(private val cloudflareKiller: CloudflareKiller) : Interceptor {
        override fun intercept(chain: Interceptor.Chain): Response {
            val request    = chain.request()
            val response   = chain.proceed(request)
            val bodySample = response.peekBody(1024 * 1024).string()
            if (
                bodySample.contains("Güvenlik taramasından geçiriliyorsunuz")
                || bodySample.contains("cf-browser-verification")
                || bodySample.contains("Checking your browser")
                || bodySample.contains("just a moment", ignoreCase = true)
                || response.code in listOf(403, 503, 429)
            ) {
                response.close()
                return cloudflareKiller.intercept(chain)
            }
            return response
        }
    }

    override val mainPage = mainPageOf(
        "${mainUrl}/tum-bolumler/page/SAYFA/"                        to "Son Bölümler",
        "${mainUrl}/tum-bolumler/page/SAYFA/?tip=populer"            to "Popüler Diziler",
        "${mainUrl}/dizi-arsivi/page/SAYFA/"                         to "Yeni Eklenenler",
        "${mainUrl}/dizi-arsivi/page/SAYFA/?ulke[]=turkiye&yil=&imdb" to "Yerli Diziler",
        "${mainUrl}/dizi-arsivi/page/SAYFA/?tur[0]=aksiyon&yil&imdb" to "Aksiyon",
        "${mainUrl}/dizi-arsivi/page/SAYFA/?tur[0]=bilimkurgu&yil&imdb" to "Bilimkurgu",
        "${mainUrl}/dizi-arsivi/page/SAYFA/?tur[0]=komedi&yil&imdb"  to "Komedi",
        "${mainUrl}/dizi-arsivi/page/SAYFA/?tur[0]=dram&yil&imdb"    to "Dram",
        "${mainUrl}/dizi-arsivi/page/SAYFA/?tur[0]=fantastik&yil&imdb" to "Fantastik",
        "${mainUrl}/dizi-arsivi/page/SAYFA/?tur[0]=gerilim&yil&imdb" to "Gerilim",
        "${mainUrl}/dizi-arsivi/page/SAYFA/?tur[0]=korku&yil&imdb"   to "Korku",
        "${mainUrl}/dizi-arsivi/page/SAYFA/?tur[0]=romantik&yil&imdb" to "Romantik",
        "${mainUrl}/dizi-arsivi/page/SAYFA/?tur[0]=animasyon&yil&imdb" to "Animasyon"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val rawUrl = request.data
        val url = if (page == 1) {
            if (rawUrl.contains("?")) {
                rawUrl.substringBefore("/page/SAYFA/") + "/?" + rawUrl.substringAfter("?")
            } else {
                rawUrl.substringBefore("/page/SAYFA/") + "/"
            }
        } else {
            rawUrl.replace("SAYFA", "$page")
        }

        val document = app.get(
            url,
            cookies     = baseCookies,
            interceptor = interceptor,
            cacheTime   = 60
        ).document

        val home = document.select("article.detailed-article, article.article-episode-card a.figure-link")
            .mapNotNull { it.toMainPageResult() }

        val isHorizontal = request.name.contains("Bölümler") || request.name.contains("Popüler")
        return newHomePageResponse(listOf(HomePageList(request.name, home, isHorizontal)), home.isNotEmpty())
    }

    private fun Element.toMainPageResult(): SearchResponse? {
        val titleEl = this.selectFirst("h3 a")
        val title   = titleEl?.text() ?: this.selectFirst("img")?.attr("alt") ?: return null
        val imgEl   = this.selectFirst("img")
        val imgUrl  = fixUrlNull(imgEl?.attr("data-src")?.takeIf { it.isNotBlank() } ?: imgEl?.attr("src"))
        val href    = fixUrlNull(titleEl?.attr("href") ?: this.attr("href")) ?: return null
        val rating  = this.selectFirst("span.label-imdb b")?.text()?.trim()

        return newTvSeriesSearchResponse(title, href, TvType.TvSeries) {
            this.posterUrl = imgUrl
            this.score     = Score.from10(rating)
        }
    }

    data class SearchApiResponse(@JsonProperty("results") val results: List<SearchResult> = emptyList())
    data class SearchResult(
        @JsonProperty("post_title")          val postTitle           : String  = "",
        @JsonProperty("permalink")           val permalink           : String  = "",
        @JsonProperty("attachment_thumbnail") val attachmentThumbnail: String? = null
    )

    override suspend fun search(query: String): List<SearchResponse> {
        val url      = "$mainUrl/wp-admin/admin-ajax.php?s=$query&action=dwls_search"
        val response = app.get(
            url,
            headers     = mapOf(
                "X-Requested-With" to "XMLHttpRequest",
                "Referer"          to "$mainUrl/?s=$query"
            ),
            cookies     = baseCookies,
            interceptor = interceptor
        )
        if (!response.isSuccessful) return emptyList()

        val json = tryParseJson<SearchApiResponse>(response.text) ?: return emptyList()
        return json.results.mapNotNull { result ->
            val thumbnail = result.attachmentThumbnail?.replace("50x50", "200x290")
            newTvSeriesSearchResponse(result.postTitle, result.permalink, TvType.TvSeries) {
                this.posterUrl = fixUrlNull(thumbnail)
            }
        }
    }

    override suspend fun quickSearch(query: String): List<SearchResponse> = search(query)

    override suspend fun load(url: String): LoadResponse? {
        var document = app.get(
            url,
            cookies     = baseCookies,
            interceptor = interceptor
        ).document

        val archiveLink = document.selectFirst("div#archive-box a.archive-title")?.attr("href")
        if (!archiveLink.isNullOrBlank()) {
            val redirectUrl = fixUrlNull(archiveLink) ?: url
            document = app.get(
                redirectUrl,
                cookies     = baseCookies,
                interceptor = interceptor
            ).document
        }

        val title    = document.selectFirst("div.tv-overview h1 a")?.text()?.trim() ?: return null
        val poster   = fixUrlNull(document.selectFirst("div.tv-overview figure img")?.attr("src"))
        val plot     = document.selectFirst("div.tv-story p")?.text()?.trim()
        val year     = document.selectFirst("a[href*='/yil/']")?.text()?.trim()?.toIntOrNull()
        val tags     = document.select("a[href*='/tur/']").map { it.text() }
        val actors   = document.select("a[href*='/oyuncu/']").map { Actor(it.text()) }
        val trailer  = document.selectFirst("div.tv-overview iframe")?.attr("src")
        val rating   = document.selectFirst("span.label-imdb b")?.text()?.trim()

        val seasonLinks = document.select("div#seasons-list a").mapNotNull {
            fixUrlNull(it.attr("href"))
        }

        val episodeList = mutableListOf<Episode>()
        seasonLinks.forEach { seasonUrl ->
            val seasonDoc = app.get(
                seasonUrl,
                cookies     = baseCookies,
                interceptor = interceptor
            ).document

            seasonDoc.select("article.grid-box").forEach epLoop@{ epElem ->
                val epTitleEl = epElem.selectFirst("div.post-title a.season-episode") ?: return@epLoop
                val epRawTitle = epTitleEl.text().trim()
                val epHref     = fixUrlNull(epTitleEl.attr("href")) ?: return@epLoop

                val dateEl   = epElem.selectFirst("small.date")
                val dateText = dateEl?.text()?.trim()?.let { d ->
                    d.replace("Ocak", "01").replace("Şubat", "02").replace("Mart", "03")
                     .replace("Nisan", "04").replace("Mayıs", "05").replace("Haziran", "06")
                     .replace("Temmuz", "07").replace("Ağustos", "08").replace("Eylül", "09")
                     .replace("Ekim", "10").replace("Kasım", "11").replace("Aralık", "12")
                }

                val sNum = Regex("""(\d+)\.? ?Sezon""").find(epRawTitle)?.groupValues?.get(1)?.toIntOrNull() ?: 1
                val eNum = Regex("""(\d+)\.? ?Bölüm""").find(epRawTitle)?.groupValues?.get(1)?.toIntOrNull()

                episodeList.add(newEpisode(epHref) {
                    this.name      = "Bölüm"
                    this.season    = sNum
                    this.episode   = eNum
                    this.posterUrl = poster
                    if (dateText != null) {
                        val parts = dateText.split(" ")
                        if (parts.size >= 3) {
                            val formatted = "${parts[2]}-${parts[1].padStart(2, '0')}-${parts[0].padStart(2, '0')}"
                            addDate(formatted)
                        }
                    }
                })
            }
        }

        val sortedEpisodes = episodeList.sortedWith(compareBy({ it.season }, { it.episode }))

        return newTvSeriesLoadResponse(title, url, TvType.TvSeries, sortedEpisodes) {
            this.posterUrl = poster
            this.plot      = plot
            this.year      = year
            this.tags      = tags
            this.score     = Score.from10(rating)
            addActors(actors)
            addTrailer(trailer)
        }
    }

    override suspend fun loadLinks(
        data             : String,
        isCasting        : Boolean,
        subtitleCallback : (SubtitleFile) -> Unit,
        callback         : (ExtractorLink) -> Unit
    ): Boolean {
        val document = app.get(
            data,
            cookies     = baseCookies,
            interceptor = interceptor
        ).document

        val iframeUrl = document.selectFirst("div#video-area iframe")?.attr("src")
            ?: return false

        val sources = mutableListOf(data)
        document.select("div.video-toolbar option[value]").forEach { opt ->
            val valUrl = opt.attr("value")
            if (valUrl.isNotBlank() && !sources.contains(valUrl)) {
                // §117: .live toolbar option value'ları MUTLAK sayfa URL'leri
                // (…-izle/ Moly+ /2/, Odnok /3/); eski "!startsWith(http)" filtresi
                // bunları yutuyordu. Relative değerler fixUrl ile absolute olur
                // (.lol uyumu korunur).
                sources.add(fixUrl(valUrl))
            }
        }

        var delivered = false
        sources.forEach { sourceUrl ->
            val sourceDoc = app.get(
                sourceUrl,
                cookies     = baseCookies,
                interceptor = interceptor
            ).document

            // §114: iframe src relative olabilir → absolute'a çevir (görev şartı;
            // mutlak URL'lerde fixUrl no-op kalır).
            val srcIframe = sourceDoc.selectFirst("div#video-area iframe")?.attr("src")
                ?.let { fixUrl(it) }
                ?: return@forEach

            if (processIframe(srcIframe, sourceUrl, data, subtitleCallback, callback)) {
                delivered = true
            }
        }

        // §114: hiçbir gerçek kaynak callback edilmediyse false döndür (sahte link YOK).
        return delivered
    }

    private suspend fun processIframe(
        iframeUrl        : String,
        sourceUrl        : String,
        referer          : String,
        subtitleCallback : (SubtitleFile) -> Unit,
        callback         : (ExtractorLink) -> Unit
    ): Boolean {
        when {
            // §117: generic moly.php embed'i loadExtractor'a; /player/moly/moly.php
            // WRAPPER'ı ise aşağıdaki decode dalına düşmeli (wrapper URL'i
            // loadExtractor'a gönderilemez — .live'da Moly+ iframe'i wrapper).
            iframeUrl.contains("moly.php") && !iframeUrl.contains("/player/moly/moly.php") -> {
                return loadExtractor(iframeUrl, sourceUrl, subtitleCallback, callback)
            }

            // §114: SpidyPro embed (görev iskeletinde doğrulanmış iframe host'u).
            // Embed'in video ucu canlı doğrulanamadı (görev notu + bu oturumda 2x
            // fetch timeout) → SAHTE link/kalite ÜRETİLMEZ: embed HTML'inde GERÇEK
            // m3u8/mp4 sinyali aranır; bulunamazsa hiçbir ExtractorLink verilmez
            // ve loadLinks false döner.
            iframeUrl.contains("spidypro") -> {
                val embedBody = runCatching {
                    app.get(
                        iframeUrl,
                        referer     = referer,
                        cookies     = baseCookies,
                        interceptor = interceptor
                    ).text
                }.getOrNull() ?: return false

                val videoUrl = Regex("""https?://[^\s"'<>\\]+?\.m3u8[^\s"'<>\\]*""").find(embedBody)?.value
                    ?: Regex("""https?://[^\s"'<>\\]+?\.mp4[^\s"'<>\\]*""").find(embedBody)?.value
                    ?: Regex("""(?:file|source|playlist)\s*[:=]\s*["'](https?://[^"']+?\.(?:m3u8|mp4)[^"']*)["']""").find(embedBody)?.groupValues?.get(1)
                    ?: return false

                val isHls = videoUrl.contains(".m3u8")
                callback.invoke(
                    newExtractorLink(
                        source = name,
                        name   = name,
                        url    = videoUrl,
                        type   = if (isHls) ExtractorLinkType.M3U8 else INFER_TYPE
                    ) {
                        // Header ihtiyacı canlı doğrulanamadı: yalnız embed sayfası
                        // Referer'i (zararsız varsayım); fazladan header EKLENMEDİ.
                        // Gerçek kalite listesi doğrulanamadığından uydurma kalite YOK.
                        this.headers = mapOf("Referer" to iframeUrl)
                        this.quality = Qualities.Unknown.value
                    }
                )
                // HLS ise master playlist'teki GERÇEK variantların tamamı aktarılır
                // (kalite kaybı yok); URL bir media playlist ise helper boş döner ve
                // tek link oynatmaya kalır (ExoPlayer her iki tipi çözer).
                if (isHls) {
                    M3u8Helper.generateM3u8(name, videoUrl, iframeUrl).forEach(callback)
                }
                return true
            }

            iframeUrl.contains("php?v=") -> {
                val playerUrl = iframeUrl.replace("php?v=", "php?wmode=opaque&v=")
                val playerDoc = app.get(
                    playerUrl,
                    referer     = sourceUrl,
                    cookies     = baseCookies,
                    interceptor = interceptor
                ).document

                val finalEmbed = playerDoc.selectFirst("div#Player iframe")?.attr("src")
                    ?: return false

                val sheila = finalEmbed
                    .replace("/embed/", "/embed/sheila/")
                    .replace("vidmoly.me", "vidmoly.net")

                if (sheila.contains("dbx.molystream")) {
                    val m3u8Data = app.get(
                        sheila,
                        referer     = finalEmbed,
                        interceptor = interceptor
                    ).text
                    val m3u8Url = m3u8Data.lineSequence().firstOrNull { it.startsWith("http") }
                        ?: return false

                    // §117: sheila yanıtı GERÇEK master playlist (canlı doğrulandı:
                    // RESOLUTION=1280x720 tek variant, segmentler dönen db5.*.xyz
                    // hostlarında ve .png maskeli TS — uzantı kontrolü YOK,
                    // ExoPlayer içerikten çözer). Master'daki TÜM gerçek variantlar
                    // gerçek RESOLUTION kalitesi ile aktarılır (kalite uydurma YOK).
                    val linkHeaders = mapOf(
                        "Referer" to finalEmbed,
                        // §117 kanıtı: kısa UA ile segment CDN'i 403, tam Chrome UA +
                        // Referer ile 206 → UA zorunlu.
                        "user-agent" to USER_AGENT
                    )

                    val variantRegex = Regex(
                        """#EXT-X-STREAM-INF:[^\r\n]*RESOLUTION=(\d+)x(\d+)[^\r\n]*\r?\n([^\r\n#][^\r\n]*)"""
                    )
                    val variants = variantRegex.findAll(m3u8Data).toList()

                    if (variants.isEmpty()) {
                        // Master parse edilemedi → ham ilk URL (Unknown) ile fallback.
                        callback.invoke(
                            newExtractorLink(
                                source = name,
                                name   = name,
                                url    = m3u8Url,
                                type   = ExtractorLinkType.M3U8
                            ) {
                                this.headers = linkHeaders
                                this.quality = Qualities.Unknown.value
                            }
                        )
                        return true
                    }

                    variants.forEach { match ->
                        val height = match.groupValues[2].toIntOrNull() ?: return@forEach
                        val variantUrl = fixUrl(match.groupValues[3].trim())
                        callback.invoke(
                            newExtractorLink(
                                source = name,
                                name   = name,
                                url    = variantUrl,
                                type   = ExtractorLinkType.M3U8
                            ) {
                                this.headers = linkHeaders
                                // GERÇEK kalite: master'daki RESOLUTION'dan.
                                this.quality = getQualityFromName("${height}p")
                            }
                        )
                    }
                    return true
                } else {
                    return loadExtractor(sheila, sourceUrl, subtitleCallback, callback)
                }
            }

            iframeUrl.contains("/player/king/king.php") -> {
                val kingUrl = iframeUrl.replace("king.php?v=", "king.php?wmode=opaque&v=")
                val subDoc  = app.get(
                    kingUrl,
                    referer     = referer,
                    cookies     = baseCookies,
                    interceptor = interceptor
                ).document
                val subFrame = subDoc.selectFirst("div#Player iframe")?.attr("src") ?: return false

                val iDoc      = app.get(subFrame, referer = "$mainUrl/").text
                val cryptData = Regex("""CryptoJS\.AES\.decrypt\("(.*)","""""").find(iDoc)?.groupValues?.get(1) ?: return false
                val cryptPass = Regex(""""","(.*)"\);""").find(iDoc)?.groupValues?.get(1) ?: return false
                val decrypted = CryptoJS.decrypt(cryptPass, cryptData)
                val vidUrl    = Regex("""file: '(.*)',""").find(Jsoup.parse(decrypted).html())?.groupValues?.get(1) ?: return false

                callback.invoke(
                    newExtractorLink(
                        source = name,
                        name   = name,
                        url    = vidUrl,
                        type   = ExtractorLinkType.M3U8
                    ) {
                        this.headers = mapOf("Referer" to vidUrl)
                        // §114 kalite kuralı: gerçek kalite doğrulanamadı → uydurma YOK.
                        this.quality = Qualities.Unknown.value
                    }
                )
                return true
            }

            iframeUrl.contains("/player/moly/moly.php") || iframeUrl.contains("/player/haydi.php") -> {
                val molyUrl = when {
                    iframeUrl.contains("moly.php") -> iframeUrl.replace("moly.php?h=", "moly.php?wmode=opaque&h=")
                    else                           -> iframeUrl.replace("haydi.php?v=", "haydi.php?wmode=opaque&v=")
                }
                var subDoc = app.get(
                    molyUrl,
                    referer     = referer,
                    cookies     = baseCookies,
                    interceptor = interceptor
                ).document

                val atobData = Regex("""unescape\("(.*)"\)""").find(subDoc.html())?.groupValues?.get(1)
                if (atobData != null) {
                    val decoded = String(Base64.decode(atobData.decodeUri(), Base64.DEFAULT), Charsets.UTF_8)
                    subDoc = Jsoup.parse(decoded)
                }

                val subFrame = subDoc.selectFirst("div#Player iframe")?.attr("src") ?: return false
                return loadExtractor(subFrame, "$mainUrl/", subtitleCallback, callback)
            }

            else -> {
                return loadExtractor(iframeUrl, sourceUrl, subtitleCallback, callback)
            }
        }
    }
}
