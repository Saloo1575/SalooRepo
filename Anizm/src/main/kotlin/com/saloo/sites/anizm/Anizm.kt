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

import android.util.Log
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.network.CloudflareKiller
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.M3u8Helper
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.newExtractorLink
import okhttp3.Interceptor
import okhttp3.Response
import org.json.JSONObject
import org.jsoup.nodes.Element
import java.net.URLEncoder

class Anizm : MainAPI() {
    override var mainUrl = "https://anizm.net"
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

    // §119: anizm.net Cloudflare challenge'lı (canlı 403 kanıtı: ana sayfa, admin-ajax,
    // searchAnime — hem desktop hem mobile UA; .com.tr hâlâ 200 ama .net yeni kanonik
    // domain — .com.tr ana sayfası kendisi anizm.net'e link veriyor). DDizi deseni:
    // yalnız challenge/403/503 yanıtında CloudflareKiller devreye girer; normal yanıtlar
    // aynen geçer (mevcut .com.tr/.net akışını bozmaz).
    private val cloudflareKiller by lazy { CloudflareKiller() }
    private val interceptor by lazy { CloudflareInterceptor(cloudflareKiller) }

    class CloudflareInterceptor(private val cloudflareKiller: CloudflareKiller) : Interceptor {
        override fun intercept(chain: Interceptor.Chain): Response {
            val request = chain.request()
            val response = chain.proceed(request)
            val bodySample = try {
                response.peekBody(1024 * 1024).string()
            } catch (_: Exception) {
                ""
            }
            if (
                bodySample.contains("Just a moment", ignoreCase = true)
                || bodySample.contains("cf-browser-verification")
                || bodySample.contains("Checking your browser")
                || response.code in listOf(403, 503, 429)
            ) {
                response.close()
                return cloudflareKiller.intercept(chain)
            }
            return response
        }
    }

    companion object {
        // §124: geçici teşhis logları (kaldırılacak).
        private const val TAG = "AnizmDiag"
        private const val UA =
            "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Mobile Safari/537.36"

        /** Bölüm/alternatif/video XHR endpointleri için ortak header.
         *  §99 canlı izolasyon kanıtı: /searchAnime için Referer ZORUNLU —
         *  UA+XHR kombinasyonu 200+404-HTML döndürüyor, UA+XHR+Referer GERÇEK JSON.
         *  Accept tek başına yetersiz (T4) → eklenmedi ("gereksiz değişiklik yok").
         *  loadLinks kendi Referer'ını per-request override ediyor (map birleşimi). */
        private val XHR_HEADERS = mapOf(
            "User-Agent" to UA,
            "X-Requested-With" to "XMLHttpRequest",
            // §119: .net'e geçiş — XHR Referer ana domain'e hizalandı.
            "Referer" to "https://anizm.net/",
        )

        private const val SEARCH_PATH = "/searchAnime"
        private const val BETA_PLAYER_NAME = "Beta Player"
        private const val PUFFY_HOST = "https://pl.puffytr.tr"

        /** Detay sayfasındaki bölüm linkleri (keşif: -N-bolum ve -N-bolum-izle formları). */
        private val EPISODE_HREF_REGEX =
            Regex("""href="https?://anizm\.(?:net|com\.tr)/([a-z0-9\-]+-\d+-bolum(?:-[a-z0-9]+)?)"""", RegexOption.IGNORE_CASE)
        private val EPISODE_NO_REGEX = Regex("""-(\d+)-bolum""", RegexOption.IGNORE_CASE)

        /** Bölüm sayfasındaki fansub/çevirmen endpoint'leri. */
        private val TRANSLATOR_REGEX = Regex("""translator="(https://anizm\.(?:net|com\.tr)/episode/\d+/translator/\d+)"""")

        /** Alternatif (video sunucusu) butonları. */
        private val ALTERNATIVE_REGEX =
            Regex("""video="(https://anizm\.(?:net|com\.tr)/video/\d+)" data-playerclick data-video-name="([^"]+)"""")

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
            app.get(requestUrl, headers = XHR_HEADERS, interceptor = interceptor).text
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
        val document = app.get(request.data, headers = mapOf("User-Agent" to UA), interceptor = interceptor).document

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
        val html = app.get(url, headers = mapOf("User-Agent" to UA), interceptor = interceptor).text
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
        Log.d(TAG, "loadLinks: START episodeUrl=$episodeUrl")

        // 1) Bölüm sayfası → translator endpoint'leri (birden fazla fansub olabilir;
        //    site da ilkini otomatik seçiyor — keşif: EP1'de tek fansub LeoSubs).
        val episodeHtml = try {
            app.get(episodeUrl, headers = mapOf("User-Agent" to UA), interceptor = interceptor).text
        } catch (e: Exception) {
            Log.d(TAG, "loadLinks: episode fetch FAILED: ${e.message}")
            return false
        }
        if (episodeHtml.isBlank()) {
            Log.d(TAG, "loadLinks: episodeHtml BLANK")
            return false
        }
        Log.d(TAG, "loadLinks: episodeHtml length=${episodeHtml.length}")

        var hdvidDelivered = false
        var uqloadDelivered = false

        // §124: teslim edilen toplam link sayısını izlemek için sarmalayıcı (davranış aynı).
        var deliveredCount = 0
        val countingCallback: (ExtractorLink) -> Unit = { link ->
            deliveredCount++
            Log.d(TAG, "loadLinks: LINK DELIVERED #$deliveredCount url=${link.url}")
            callback(link)
        }

        val translatorMatches = TRANSLATOR_REGEX.findAll(episodeHtml)
            .map { it.groupValues[1] }.distinct().toList()
        Log.d(TAG, "loadLinks: translator match count=${translatorMatches.size}")
        for (translatorUrl in translatorMatches) {
            Log.d(TAG, "loadLinks: translator=$translatorUrl")
            // §105: HDVid — Beta Player'dan bağımsız AYRI source (tek gerçek kalite 360p;
            // canlı kanıt: /video → /player/<id> iframe-in-iframe → sources file v.mp4 206).
            val hdvidMp4Url = fetchHdvidMp4Url(translatorUrl)
            if (hdvidMp4Url != null) {
                hdvidDelivered = true
                Log.d(TAG, "HDVid: delivering url=$hdvidMp4Url")
                countingCallback.invoke(
                    newExtractorLink(
                        source = name,
                        name = "$name · HDVid",
                        url = hdvidMp4Url,
                        type = ExtractorLinkType.VIDEO,
                    ) {
                        this.headers = mapOf("Referer" to "$mainUrl/")
                        this.quality = Qualities.P360.value
                    }
                )
            } else {
                Log.d(TAG, "HDVid: no MP4 URL for this translator")
            }
            val betaVideoUrl = fetchBetaPlayerVideoUrl(translatorUrl)
            if (betaVideoUrl == null) {
                Log.d(TAG, "Beta: /video URL NOT FOUND for this translator")
                continue
            }
            Log.d(TAG, "Beta: /video URL=$betaVideoUrl")
            val uqloadMasterUrl = fetchUqloadMasterUrl(translatorUrl)
            Log.d(TAG, "UQload: masterUrl=${uqloadMasterUrl ?: "NOT FOUND"}")

            // 2) /video/<videoId> → player iframe.
            val playerHtml = try {
                Log.d(TAG, "Beta: /video request start url=$betaVideoUrl")
                val body = app.get(
                    betaVideoUrl,
                    headers = XHR_HEADERS + mapOf("Referer" to episodeUrl),
                    interceptor = interceptor
                ).text
                Log.d(TAG, "Beta: /video response length=${body.length}")
                JSONObject(body).optString("player")
            } catch (e: Exception) {
                Log.d(TAG, "Beta: /video FAILED: ${e.message}")
                null
            } ?: continue
            if (playerHtml.isBlank()) {
                Log.d(TAG, "Beta: playerHtml BLANK")
                continue
            }

            val playerUrl = IFRAME_SRC_REGEX.find(playerHtml)?.groupValues?.get(1)
            if (playerUrl == null) {
                Log.d(TAG, "Beta: player iframe NOT FOUND in /video response")
                continue
            }
            Log.d(TAG, "Beta: playerUrl=$playerUrl")

            // 3) /player/<videoId> → pl.puffytr.tr/watch/<hash> (redirect takibi).
            val watchHtml = try {
                Log.d(TAG, "Beta: /player request start url=$playerUrl")
                app.get(playerUrl, headers = mapOf("User-Agent" to UA, "Referer" to episodeUrl), interceptor = interceptor).text
            } catch (e: Exception) {
                Log.d(TAG, "Beta: /player FAILED: ${e.message}")
                null
            } ?: continue

            val hash = STREAM_REGEX.find(watchHtml)?.groupValues?.get(1)
            if (hash == null) {
                Log.d(TAG, "Beta: STREAM_REGEX NOT FOUND (watchHtml length=${watchHtml.length})")
                continue
            }
            Log.d(TAG, "Beta: hash=$hash")
            // Keşif + canlı test kanıtı: taze imzalı varyant tokenları yalnız master.txt
            // üretir (native.m3u8 bayat /mn/ token → 403). watch sayfasının VHS yolu master.txt.
            val masterUrl = "$PUFFY_HOST/stream/$hash/master.txt"
            val watchUrl = "$PUFFY_HOST/watch/$hash"

            // 4) GERÇEK varyantlar (480p/720p/1080p) M3u8Helper ile; kalite uydurulmaz.
            var delivered = false
            try {
                Log.d(TAG, "Beta: delivering base master url=$masterUrl (watchUrl=$watchUrl)")
                countingCallback.invoke(
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
                val betaVariants = M3u8Helper.generateM3u8("$name · $BETA_PLAYER_NAME", masterUrl, watchUrl)
                Log.d(TAG, "Beta: M3u8Helper variant count=${betaVariants.size}")
                betaVariants.forEach(countingCallback)
                delivered = true
            } catch (e: Exception) {
                Log.d(TAG, "Beta: delivery FAILED: ${e.message}")
                // Varyantlar çözülemezse master link zaten sunuldu.
            }
            if (delivered) return true
            // §107: UQload — Beta Player'dan bağımsız ayrı HLS source.
            // Zincir (canlı kanıt): /video → /player/<id> → packed-eval JS →
            // getAndUnpack → sources file master.m3u8 (strm*.uqload.vc) → 2×1080p variant.
            uqloadMasterUrl?.let { masterUrl ->
                var uqDelivered = false
                try {
                    Log.d(TAG, "UQload: delivering base master url=$masterUrl")
                    countingCallback.invoke(
                        newExtractorLink(
                            source = name,
                            name = "$name · UQload",
                            url = masterUrl,
                            type = ExtractorLinkType.M3U8,
                        ) {
                            this.headers = mapOf("User-Agent" to UA, "Referer" to "https://uqload.vc/")
                            this.quality = Qualities.Unknown.value
                        }
                    )
                    val uqVariants = M3u8Helper.generateM3u8("$name · UQload", masterUrl, "https://uqload.vc/")
                    Log.d(TAG, "UQload: M3u8Helper variant count=${uqVariants.size}")
                    uqVariants.forEach(countingCallback)
                    uqDelivered = true
                } catch (e: Exception) {
                    Log.d(TAG, "UQload: delivery FAILED: ${e.message}")
                    // Master link zaten sunuldu; varyantlar çözülemezse kayıp yok.
                }
                if (uqDelivered) uqloadDelivered = true
            }
        }
        Log.d(TAG, "loadLinks: END hdvidDelivered=$hdvidDelivered uqloadDelivered=$uqloadDelivered totalLinks=$deliveredCount")
        // Beta Player hiç çalışmazsa bile HDVid/UQload tek kaynak olarak geçerli (§105/§107).
        return hdvidDelivered || uqloadDelivered
    }

    /**
     * Translator endpoint'i → alternatif listesi → "UQload" alternatifinin
     * anizm proxy player'ının (anizm.com.tr/player/<videoId>) packed-eval JS'indeki
     * sources[].file HLS master URL'i.
     * §107 canlı kanıt: master.m3u8 (strm*.uqload.vc) → 2×1080p variant
     * (index-v1-a1.m3u8 1.750.852 bps + frames-v1-a1.m3u8 4.975.172 bps).
     * getAndUnpack (CloudStream built-in) packed eval'ı açar.
     */
    private suspend fun fetchUqloadMasterUrl(translatorUrl: String): String? {
        Log.d(TAG, "UQload: translator fetch start url=$translatorUrl")
        val body = try {
            app.get(translatorUrl, headers = XHR_HEADERS, interceptor = interceptor).text
        } catch (e: Exception) {
            Log.d(TAG, "UQload: translator fetch FAILED: ${e.message}")
            return null
        }
        Log.d(TAG, "UQload: translator response length=${body.length}")
        val dataHtml = try {
            JSONObject(body).optString("data")
        } catch (_: Exception) {
            return null
        }
        if (dataHtml.isBlank()) return null

        var uqloadVideoUrl: String? = null
        for (match in ALTERNATIVE_REGEX.findAll(dataHtml)) {
            Log.d(TAG, "UQload: alternative '${match.groupValues[2].trim()}' -> ${match.groupValues[1]}")
            if (match.groupValues[2].trim().equals("UQload", ignoreCase = true)) {
                uqloadVideoUrl = match.groupValues[1]
                break
            }
        }
        if (uqloadVideoUrl == null) {
            Log.d(TAG, "UQload: 'UQload' alternative NOT FOUND")
            return null
        }
        Log.d(TAG, "UQload: /video URL=$uqloadVideoUrl")

        val playerJson = try {
            Log.d(TAG, "UQload: /video request start")
            JSONObject(app.get(uqloadVideoUrl, headers = XHR_HEADERS, interceptor = interceptor).text)
                .optString("player")
        } catch (e: Exception) {
            Log.d(TAG, "UQload: /video request FAILED: ${e.message}")
            return null
        }
        if (playerJson.isBlank()) {
            Log.d(TAG, "UQload: playerJson BLANK")
            return null
        }

        val playerUrl = IFRAME_SRC_REGEX.find(playerJson)?.groupValues?.get(1)
        if (playerUrl == null) {
            Log.d(TAG, "UQload: player iframe NOT FOUND")
            return null
        }
        Log.d(TAG, "UQload: playerUrl=$playerUrl")
        val playerHtml = try {
            app.get(playerUrl, headers = mapOf("User-Agent" to UA, "Referer" to "$mainUrl/"), interceptor = interceptor).text
        } catch (e: Exception) {
            Log.d(TAG, "UQload: /player fetch FAILED: ${e.message}")
            return null
        }
        val unpacked = unpackPackedEvalJs(playerHtml)
        if (unpacked == null) {
            Log.d(TAG, "UQload: unpackPackedEvalJs returned NULL (playerHtml length=${playerHtml.length})")
            return null
        }
        val masterMatch = Regex("""file\s*:\s*"([^"]+master\.m3u8[^"]*)"""").find(unpacked) ?: run {
            Log.d(TAG, "UQload: master.m3u8 regex NOT FOUND in unpacked JS")
            return null
        }
        Log.d(TAG, "UQload: master URL found=${masterMatch.groupValues[1]}")
        return masterMatch.groupValues[1]
    }

    private suspend fun fetchBetaPlayerVideoUrl(translatorUrl: String): String? {
        Log.d(TAG, "Beta: translator fetch start url=$translatorUrl")
        val body = try {
            app.get(translatorUrl, headers = XHR_HEADERS, interceptor = interceptor).text
        } catch (e: Exception) {
            Log.d(TAG, "Beta: translator fetch FAILED: ${e.message}")
            return null
        }
        Log.d(TAG, "Beta: translator response length=${body.length}")
        val dataHtml = try {
            JSONObject(body).optString("data")
        } catch (e: Exception) {
            Log.d(TAG, "Beta: translator JSON parse FAILED: ${e.message}")
            return null
        }
        if (dataHtml.isBlank()) {
            Log.d(TAG, "Beta: dataHtml BLANK")
            return null
        }

        val altMatches = ALTERNATIVE_REGEX.findAll(dataHtml).toList()
        Log.d(TAG, "Beta: alternative match count=${altMatches.size}")
        for (match in altMatches) {
            Log.d(TAG, "Beta: alternative '${match.groupValues[2].trim()}' -> ${match.groupValues[1]}")
            if (match.groupValues[2].trim().equals(BETA_PLAYER_NAME, ignoreCase = true)) {
                Log.d(TAG, "Beta: 'Beta Player' alternative FOUND")
                return match.groupValues[1]
            }
        }
        Log.d(TAG, "Beta: 'Beta Player' alternative NOT FOUND")
        return null
    }

    /**
     * Translator endpoint'i → alternatif listesi → "HDVid" alternatifinin
     * /player/<videoId> iframe'indeki sources[].file MP4 URL'i.
     * §105 canlı kanıt: /video yanıtı bazen /player/<id>'ye İÇ-İÇE iframe döndürüyor
     * (iki katman GET gerekli); sources[] tek gerçek kalite 360p (etiket HTML'de,
     * uydurma yok). Dönüş: doğrudan MP4 ExtractorLink URL'i (Referer anizm.com.tr).
     */
    private suspend fun fetchHdvidMp4Url(translatorUrl: String): String? {
        Log.d(TAG, "HDVid: translator fetch start url=$translatorUrl")
        val body = try {
            app.get(translatorUrl, headers = XHR_HEADERS, interceptor = interceptor).text
        } catch (e: Exception) {
            Log.d(TAG, "HDVid: translator fetch FAILED: ${e.message}")
            return null
        }
        Log.d(TAG, "HDVid: translator response length=${body.length}")
        val dataHtml = try {
            JSONObject(body).optString("data")
        } catch (e: Exception) {
            Log.d(TAG, "HDVid: translator JSON parse FAILED: ${e.message}")
            return null
        }
        if (dataHtml.isBlank()) {
            Log.d(TAG, "HDVid: dataHtml BLANK")
            return null
        }

        val altMatches = ALTERNATIVE_REGEX.findAll(dataHtml).toList()
        Log.d(TAG, "HDVid: alternative match count=${altMatches.size}")
        for (match in altMatches) {
            Log.d(TAG, "HDVid: alternative '${match.groupValues[2].trim()}' -> ${match.groupValues[1]}")
        }
        var hdvidVideoUrl: String? = null
        for (match in altMatches) {
            if (match.groupValues[2].trim().equals("HDVid", ignoreCase = true)) {
                hdvidVideoUrl = match.groupValues[1]
                break
            }
        }
        if (hdvidVideoUrl == null) {
            Log.d(TAG, "HDVid: 'HDVid' alternative NOT FOUND")
            return null
        }
        Log.d(TAG, "HDVid: /video URL=$hdvidVideoUrl")

        val playerHtml = try {
            Log.d(TAG, "HDVid: /video request start")
            JSONObject(app.get(hdvidVideoUrl, headers = XHR_HEADERS, interceptor = interceptor).text)
                .optString("player")
        } catch (e: Exception) {
            Log.d(TAG, "HDVid: /video request FAILED: ${e.message}")
            return null
        }
        if (playerHtml.isBlank()) {
            Log.d(TAG, "HDVid: playerHtml BLANK")
            return null
        }

        var html = playerHtml
        // iframe-in-iframe: /video yanıtı bazen /player/<id>'yi iframe olarak verir
        // (canlı kanıt §105) → ikinci katmanı da GET et.
        IFRAME_SRC_REGEX.find(playerHtml)?.groupValues?.get(1)?.let { inner ->
            Log.d(TAG, "HDVid: /video response iframe src=$inner")
            if (inner.startsWith("$mainUrl/player/")) {
                html = try {
                    app.get(inner, headers = mapOf("User-Agent" to UA, "Referer" to "$mainUrl/"), interceptor = interceptor).text
                } catch (e: Exception) {
                    Log.d(TAG, "HDVid: inner /player fetch FAILED: ${e.message}")
                    return null
                }
                Log.d(TAG, "HDVid: inner /player HTML length=${html.length}")
            }
        }
        // HDVid'de doğrulanan tek gerçek kalite 360p; birden fazla kalite görürse
        // hepsi eklenir (§105 kalite kuralı — uydurma yok).
        Log.d(TAG, "HDVid: checking sources in html length=${html.length}")
        Regex("""file\s*:\s*"([^"]+\.(?:mp4|m3u8))"\s*,\s*label\s*:\s*"([^"]+)"""", RegexOption.IGNORE_CASE)
            .findAll(html)
            .map { Triple(it.groupValues[1], it.groupValues[2], it) }
            .lastOrNull()?.let { return it.first }
        return null
    }

    /**
     * §107/§108B — UQload proxy player'ının packed-eval (p,a,c,k,e,d) JS'ini açar
     * (radix36 + keys mantığı PowerShell'de canlı doğrulandı; getAndUnpack pre-release
     * lib snapshot'ta çözülmediği için yerel yardımcı — UQload davranışı değişmez).
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
