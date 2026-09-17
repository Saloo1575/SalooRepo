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
import java.net.URLEncoder

class Anizm : MainAPI() {
    override var mainUrl = "https://anizm.com.tr"
    override var name = "Anizm"
    override val hasMainPage = false
    override var lang = "tr"
    override val supportedTypes = setOf(TvType.TvSeries)

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
