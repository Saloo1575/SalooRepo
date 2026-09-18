// SalooRepo §90 — Anizm provider (canlı doğrulanmış akış, keşif oturumu 2026-09-17).
//
// Kanıt tabanı (canlı curl doğrulamalı, endpoint uydurma YOK):
//  - SEARCH: GET /searchAnime?query=<q>&page=1&type=detailed&limit=10&priorityField=info_title
//    &orderBy=info_year&orderDirection=ASC — X-Requested-With: XMLHttpRequest ZORUNLU;
//    type yalnız detailed|fast (başka değer → "Geçersiz arama parametreleri").
//    data[] alanları: info_id/info_title/info_slug/info_poster/info_year/... ;
//    poster https://anizm.com.tr/storage/pcovers/<info_poster>.
//  - DETAIL: /<info_slug> → SSR HTML'de bölüm linkleri /<slug>-<N>-bolum(-final)?(-izle)?
//    (hem -N-bolum hem -N-bolum-izle formları görüldü; final varyantı "-final" sonekli).
//  - EPISODE→FANSUB: bölüm sayfası <a data-translatorclick translator="<URL>"> ;
//    URL = https://anizm.com.tr/episode/<episodeId>/translator/<translatorId>.
//  - ALTERNATİF: GET translator URL (XHR) → {"data":"<HTML>"} içinde
//    <a video="https://anizm.com.tr/video/<videoId>" data-playerclick data-video-name="<sunucu adı>">
//    (sunucu seti bölüm başına değişiyor: Beta Player, Aincrad, Sistenn*, GDrive, Voe, FireStream, ...).
//  - VIDEO: GET /video/<videoId> (XHR, Referer=bölüm URL) → {"player":'<iframe src="https://anizm.com.tr/player/<videoId>">'}.
//  - BETA PLAYER: /player/<videoId> → pl.puffytr.tr/watch/<hash> → watch HTML config:
//    masterUrl="/stream/<hash>/master.txt" + nativeMasterUrl="/stream/<hash>/native.m3u8".
//    Master manifest (canlı 200; master.txt = taze imza): SADECE 480p(1.4M,854x480)
//    / 720p(2.8M,1280x720) / 1080p(5M,1920x1080), codec avc1.640029,mp4a.40.2.
//    DİKKAT: native.m3u8 içindeki /mn/ tokenları BAYAT dönebiliyor (canlı 403 kanıtı);
//    taze imzalı tokenlar /stream/<hash>/master.txt'te → provider master.txt kullanır.
//    Varyant tokenları /m3/-/mn/<base64 JSON {slug,quality,expires,token,signature}> ≈1 gün
//    imzalı → KODA HARDCODE EDİLMEZ; her açılışta /player/<id> → watch → native.m3u8 zinciri
//    yeniden izlenir. Segmentler st.puffytr.tr/.../<hash>/<kalite>/*.html (decoy uzantı).
//  - Bu sürüm yalnız BETA PLAYER (Anizm kendi altyapısı); Sistenn (şifreli /api/v1/info +
//    /v4/ play-token) ve üçüncü taraf embed'ler (Voe/FireStream/GDrive/...) sonraki aşama.
//  - ALTYAZI: Anizm hardsub; harici VTT/ASS kaynağı keşifte bulunamadı → sahte subtitle YOK.

package com.saloo.sites.anizm

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.M3u8Helper
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.newExtractorLink
import org.json.JSONObject
import org.jsoup.nodes.Element
import java.net.URLEncoder

class Anizm : MainAPI() {
    override var mainUrl = "https://anizm.com.tr"
    // §93: kullanıcıya görünen provider adı — "SiteAdı [Saloo]" ad kuralı.
    override var name = "Anizm [Saloo]"
    // §93: ana sayfa provider listesine girebilmesi için zorunlu (Sourcegraph kanıtı:
    // AppContextUtils.kt:472 hasMainPage filtresi).
    override val hasMainPage = true
    override var lang = "tr"
    // §93: anime sitesi; CizgiMax (çalışan örnek) ve resmi Anizm referansı TvType.Anime
    // beyan eder → Animeler chip'inde de listelenir. TvSeries korunur (search/load
    // TvSeries döndürüyor, §90 akışı bozulmaz).
    override val supportedTypes = setOf(TvType.TvSeries, TvType.Anime)

    companion object {
        private const val UA =
            "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Mobile Safari/537.36"

        /** Bölüm/alternatif/video XHR endpointleri için ortak header (keşif kanıtı). */
        private val XHR_HEADERS = mapOf(
            "User-Agent" to UA,
            "X-Requested-With" to "XMLHttpRequest",
        )

        private const val SEARCH_PATH = "/searchAnime"
        private const val BETA_PLAYER_NAME = "Beta Player"
        private const val PUFFY_HOST = "https://pl.puffytr.tr"

        /** Detay sayfasındaki bölüm linkleri (keşif: -N-bolum ve -N-bolum-izle formları). */
        private val EPISODE_HREF_REGEX =
            Regex("""href="https?://anizm\.com\.tr/([a-z0-9\-]+-\d+-bolum(?:-[a-z0-9]+)?)"""", RegexOption.IGNORE_CASE)
        private val EPISODE_NO_REGEX = Regex("""-(\d+)-bolum""", RegexOption.IGNORE_CASE)

        /** Bölüm sayfasındaki fansub/çevirmen endpoint'leri. */
        private val TRANSLATOR_REGEX = Regex("""translator="(https://anizm\.com\.tr/episode/\d+/translator/\d+)"""")

        /** Alternatif (video sunucusu) butonları. */
        private val ALTERNATIVE_REGEX =
            Regex("""video="(https://anizm\.com\.tr/video/\d+)" data-playerclick data-video-name="([^"]+)"""")

        private val IFRAME_SRC_REGEX =
            Regex("""<iframe[^>]*src="(https?://[^"]+)"""", RegexOption.IGNORE_CASE)

        /** watch HTML config'indeki master playlist (keşif: 32 hex hash; master.txt = TAZE imzalı
         *  token kaynağı — native.m3u8'in /mn/ tokenları bayat dönebiliyor → 403, canlı kanıt). */
        private val STREAM_REGEX = Regex("""/stream/([0-9a-f]{32})/master\.txt""")

        private val TITLE_REGEX = Regex("""<title>(.*?)</title>""", RegexOption.DOT_MATCHES_ALL)
    }

    // --------------------------------------------------------------- SEARCH

    override suspend fun search(query: String): List<SearchResponse> {
        // Keşif kanıtı: XHR header'sız/yanlış type → {"data":[],"error":"Geçersiz arama parametreleri"}.
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

    // ------------------------------------------------- MAIN PAGE (§93, canlı doğrulamalı)

    /**
     * §93 canlı doğrulama (2026-09-18, anizm.com.tr):
     *  - Eski referans provider'ın listesi (anizm.net/anime-izle?sayfa=) YENİ sitede ÖLÜ:
     *    /animeler ve /populer-animeler = 404; /anime-izle?sayfa=N ana sayfanın birebir
     *    kopyası döndürüyor (grid AJAX "Anime yükleniyor..." — SSR liste YOK). KULLANILMAZ.
     *  - GERÇEK SSR listeler (canlı 200 + içerik kanıtlı):
     *      "/"  → "Bu Sezon Popüler" slider'ı (bölüm linkli kartlar)
     *      "/takvim" → yayın takvimi (Pazartesi..Pazar gün başlıkları, ~180 bölüm linkli kart;
     *      kart linkleri anizm.net host'lu → mainUrl'e normalize edilir)
     *  - Kart link deseni (canlı kanıt): /<slug>-<N>-bolum(-final)?(-izle)? → "-<N>-bolum..."
     *    kırpılıp anime DETAY sayfasına bağlanır (detay zinciri §90'da doğrulanmış;
     *    /grand-blue-season-3 canlı 200 + 1..11 bölüm listeli).
     */
    override val mainPage = mainPageOf(
        "$mainUrl/" to "Bu Sezon Popüler",
        "$mainUrl/takvim" to "Yayın Takvimi",
    )

    /** Takvim gün başlıkları (canlı kanıt: /takvim'de Pazartesi..Pazar başlıkları). */
    private val WEEKDAY_REGEX =
        Regex("""^(Pazartesi|Salı|Çarşamba|Perşembe|Cuma|Cumartesi|Pazar)$""")

    private val EPISODE_HREF_FRAGMENT = "-bolum"
    private val CARD_EPISODE_REGEX = Regex("""(\d+)\.\s?B[öo]l[üu]m""", RegexOption.IGNORE_CASE)

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val document = app.get(request.data, headers = mapOf("User-Agent" to UA)).document

        // Kartlar + gün başlıkları belge sırasında taranır. Seçiciler YALNIZ canlı
        // doğrulanmış desenlere dayanır: bölüm linkli <a> (href'te "-bolum") + içinde
        // poster <img>. Gün başlıkları etiket-bağımsız (ownText birebir gün adı) bulunur
        // → class değişikliklerine dayanıklı; gün bulunamazsa tek liste (request.name).
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
            throw ErrorLoadingException("Anizm: ana sayfa listesi boş döndü (${request.data})")
        }
        // İki kaynak da sayfalanamaz (canlı doğrulama: ?sayfa= parametresi SSR'ı değiştirmiyor).
        return newHomePageResponse(lists, hasNext = false)
    }

    /**
     * Bölüm linkli href'i anime DETAY URL'ine çevirir (referans provider'daki
     * getProperAnimeLink deseni): host (anizm.net/anizm.tv) → mainUrl normalize;
     * "-<N>-bolum..." soneki kırpılır (takvim + ana sayfa canlı kanıtı).
     */
    private fun toAnimeDetailUrl(href: String): String? {
        if (href.isBlank()) return null
        val absolute = fixUrl(href)
        if (!absolute.contains(EPISODE_HREF_FRAGMENT)) return null
        val stripped = absolute.replace(Regex("""-\d+-bolum.*$"""), "")
        val path = stripped.substringAfter("://", missingDelimiterValue = "").substringAfter('/')
        return if (path.isBlank()) null else "$mainUrl/$path"
    }

    /** Kart başlığı: önce img alt, sonra anchor metni ("N. Bölüm", "Son Eklenen: ...", "- Anizm.TV" kırpılır). */
    private fun cardTitle(element: Element, detailUrl: String): String? {
        val candidates = listOfNotNull(
            element.selectFirst("img")?.attr("alt"),
            element.text(),
        )
        for (raw in candidates) {
            val cleaned = raw
                .replace(Regex("""^\s*\d+\.\s*B[öo]l[üu]m(\s+Final)?\s*""", RegexOption.IGNORE_CASE), "")
                .replace(Regex("""\s*Son\s+Eklenen:\s*\d+\.\s*B[öo]l[üu]m(\s+Final)?\s*$""", RegexOption.IGNORE_CASE), "")
                .replace(Regex("""\s*-\s*Anizm(\.TV)?\s*$""", RegexOption.IGNORE_CASE), "")
                .trim()
            if (cleaned.isNotBlank()) return cleaned
        }
        return detailUrl.trimEnd('/').substringAfterLast('/').replace('-', ' ')
    }

    // --------------------------------------------------------------- LOAD

    override suspend fun load(url: String): LoadResponse {
        val html = app.get(url, headers = mapOf("User-Agent" to UA)).text
        if (html.isBlank()) throw ErrorLoadingException("Anizm: detay sayfası boş döndü ($url)")

        val title = extractTitle(html)
            ?: url.trimEnd('/').substringAfterLast('/').replace('-', ' ')

        val episodes = extractEpisodes(html)
        if (episodes.isEmpty()) throw ErrorLoadingException("Anizm: detay sayfasında bölüm linki yok ($url)")

        return newTvSeriesLoadResponse(
            title, url, TvType.TvSeries,
            episodes.sortedBy { it.episode ?: 0 }
        ) {
            this.posterUrl = extractMeta(html, "og:image")
            this.plot = extractMeta(html, "og:description")
        }
    }

    /** <title> → " izle | Anizm" / " izle..." sonekleri kırpılır (keşif: her iki form da var). */
    private fun extractTitle(html: String): String? {
        val raw = TITLE_REGEX.find(html)?.groupValues?.get(1)?.trim() ?: return null
        val cleaned = raw
            .replace(Regex("""\s*\|\s*Anizm\s*$"""), "")
            .replace(Regex("""\s+izle(\.\.\.)?\s*$"""), "")
            .trim()
        return cleaned.ifBlank { raw }
    }

    /**
     * Keşif kanıtı: detay sayfası hem "-N-bolum" hem "-N-bolum-izle" formları içerir;
     * aynı bölüm numarası için "-izle" (oynatma formu) tercih edilir. Episode data =
     * bölüm sayfasının tam URL'i; numara URL'den çıkar (final sonekli varyant dahil).
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

        // Aynı bölüm numarasının kopyalarını tekilleştir (öncelik: -izle formu).
        val best = HashMap<Int, Ep>()
        for (ep in found) {
            val current = best[ep.no]
            if (current == null || (!current.isIzle && ep.isIzle)) best[ep.no] = ep
        }

        return best.values.map { ep ->
            val epName = buildString {
                append(ep.no).append(". Bölüm")
                if (ep.isFinal) append(" (Final)")
            }
            newEpisode("$mainUrl/${ep.slug}") {
                this.name = epName
                this.season = 1
                this.episode = ep.no
            }
        }
    }

    /** og:<name> meta içeriği (attribute sırasından bağımsız; Dizilla deseni). */
    private fun extractMeta(html: String, metaName: String): String? {
        val name = Regex.escape(metaName)
        val match = Regex("""<meta[^>]+(?:property|name)="$name"[^>]*content="([^"]*)"""", RegexOption.IGNORE_CASE)
            .find(html)
            ?: Regex("""<meta[^>]+content="([^"]*)"[^>]*(?:property|name)="$name"""", RegexOption.IGNORE_CASE)
                .find(html)
        return match?.groupValues?.get(1)?.trim()?.takeIf { it.isNotBlank() }
    }

    // --------------------------------------------------------------- LOADLINKS (§90)

    /**
     * Keşif kanıt zinciri (yalnız BETA PLAYER):
     * data (bölüm URL) → bölüm sayfası translator="..." →
     * GET translator URL (XHR) → data HTML'de data-playerclick + video="..." →
     * "Beta Player" → GET /video/<videoId> (XHR, Referer=bölüm URL) → player iframe →
     * GET /player/<videoId> → pl.puffytr.tr/watch/<hash> → /stream/<hash>/native.m3u8 →
     * M3u8Helper ile GERÇEK varyantlar (480p/720p/1080p; tokenlar her açılışta taze).
     */
    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ): Boolean {
        val episodeUrl = data.trim()
        if (episodeUrl.isBlank()) return false

        // 1) Bölüm sayfası → translator endpoint'leri (birden fazla fansub olabilir;
        //    site da ilkini otomatik seçiyor — keşif: EP1'de tek fansub LeoSubs).
        val episodeHtml = try {
            app.get(episodeUrl, headers = mapOf("User-Agent" to UA)).text
        } catch (_: Exception) {
            return false
        }
        if (episodeHtml.isBlank()) return false

        for (translatorUrl in TRANSLATOR_REGEX.findAll(episodeHtml).map { it.groupValues[1] }.distinct()) {
            val betaVideoUrl = fetchBetaPlayerVideoUrl(translatorUrl) ?: continue

            // 2) /video/<videoId> → player iframe.
            val playerHtml = try {
                val body = app.get(
                    betaVideoUrl,
                    headers = XHR_HEADERS + mapOf("Referer" to episodeUrl)
                ).text
                JSONObject(body).optString("player")
            } catch (_: Exception) {
                null
            } ?: continue
            if (playerHtml.isBlank()) continue

            val playerUrl = IFRAME_SRC_REGEX.find(playerHtml)?.groupValues?.get(1) ?: continue

            // 3) /player/<videoId> → pl.puffytr.tr/watch/<hash> (redirect takibi).
            val watchHtml = try {
                app.get(playerUrl, headers = mapOf("User-Agent" to UA, "Referer" to episodeUrl)).text
            } catch (_: Exception) {
                null
            } ?: continue

            val hash = STREAM_REGEX.find(watchHtml)?.groupValues?.get(1) ?: continue
            // Keşif + canlı test kanıtı: taze imzalı varyant tokenları yalnız master.txt
            // üretir (native.m3u8 bayat /mn/ token → 403). watch sayfasının VHS yolu master.txt.
            val masterUrl = "$PUFFY_HOST/stream/$hash/master.txt"
            val watchUrl = "$PUFFY_HOST/watch/$hash"

            // 4) GERÇEK varyantlar (480p/720p/1080p) M3u8Helper ile; kalite uydurulmaz.
            var delivered = false
            try {
                callback.invoke(
                    newExtractorLink(
                        source = name,
                        name = "$name · $BETA_PLAYER_NAME",
                        url = masterUrl,
                        type = ExtractorLinkType.M3U8,
                    ) {
                        this.headers = mapOf("User-Agent" to UA, "Referer" to watchUrl)
                        this.quality = Qualities.Unknown.value
                    }
                )
                M3u8Helper.generateM3u8("$name · $BETA_PLAYER_NAME", masterUrl, watchUrl).forEach(callback)
                delivered = true
            } catch (_: Exception) {
                // Varyantlar çözülemezse master link zaten sunuldu.
            }
            if (delivered) return true
        }
        return false
    }

    /**
     * Translator endpoint'i → alternatif listesi → "Beta Player" alternatifinin
     * /video/<videoId> URL'i (keşif: data-video-name="Beta Player"; bulunamazsa null).
     */
    private suspend fun fetchBetaPlayerVideoUrl(translatorUrl: String): String? {
        val body = try {
            app.get(translatorUrl, headers = XHR_HEADERS).text
        } catch (_: Exception) {
            return null
        }
        val dataHtml = try {
            JSONObject(body).optString("data")
        } catch (_: Exception) {
            return null
        }
        if (dataHtml.isBlank()) return null

        for (match in ALTERNATIVE_REGEX.findAll(dataHtml)) {
            if (match.groupValues[2].trim().equals(BETA_PLAYER_NAME, ignoreCase = true)) {
                return match.groupValues[1]
            }
        }
        return null
    }
}
