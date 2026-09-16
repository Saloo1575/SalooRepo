// SalooRepo §74B — Dizilla provider: search + load (canlı doğrulanmış akış, §72 POST).
// SalooRepo §74C — loadLinks EKLENDİ (canlı kanıt zinciri, §73 + §74C keşif POST):
//  bölüm sayfası _next/data → secureData.RelatedResults.getEpisodeSources.result[] →
//  source_content iframe → openPlayer token → source2.php → m.php→master.m3u8 →
//  M3u8Helper (DiziYou deseni; Referer: iframe URL zorunlu — §73).
// Bölüm adı: contentItem.episode_title → original_title → "<n>. Bölüm" (§74C kanıtı).
//
// Kanıt tabanı (§71/§72 POST — canlı doğrulamalı, endpoint uydurma YOK):
//  - SEARCH: POST /api/bg/searchContent?searchterm=<q> (GET reddedilir; yalnız normal
//    UA) → {"success":true,"response":"<AES-256-CBC base64>"} → deşifre:
//    {"state","code","result":[{object_name, used_slug:"dizi/<slug>", used_type,
//    object_related_imdb_id, object_release_year, object_language, object_poster_url,…}]}
//    AES: key = sha256("!!22xx!!90!!") → base64 → İLK 32 KARAKTER (UTF-8 bayt);
//    IV = 16×0x00; PKCS7.
//  - BUILDID: ana sayfa __NEXT_DATA__ .buildId (hard-code YOK; dizi sayfasındaki aynı
//    değer yalnız fallback — aynı yöntem, aynı değer).
//  - LOAD: /dizi/<slug> SSR HTML → bölüm linkleri /<bolum-slug>
//    (…-<n>-sezon-<n>-bolum; kenar kalıpları: "-bolum-2", "-c06" sonekleri) →
//    GET /_next/data/<buildId>/<bolum-slug>.json → pageProps.secureData → AYNI AES
//    → contentItem (episode_no, başlık/slug/özet, …) + sezon/bölüm bilgileri.
//    RelatedResults.getEpisodeSources.result[] → §74C loadLinks'te parse edilir.

package com.saloo.sites.dizilla

import android.util.Base64
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.M3u8Helper
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.newExtractorLink
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder
import java.security.MessageDigest
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

class Dizilla : MainAPI() {
    override var mainUrl = "https://dizilla.now"
    override var name = "Dizilla"
    override val hasMainPage = false
    override var lang = "tr"
    override val supportedTypes = setOf(TvType.TvSeries)

    companion object {
        /** §72: API yalnız normal UA istiyor (token/özel header yok). */
        private const val UA =
            "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36"

        private const val SEARCH_PATH = "/api/bg/searchContent?searchterm="
        private const val NEXT_DATA_PATH = "_next/data"

        /** §72: AES anahtar türetmesi için sabit seed (canlı doğrulandı). */
        private const val AES_SEED = "!!22xx!!90!!"

        /** Ana sayfa / dizi sayfası __NEXT_DATA__ script bloğu. */
        private val NEXT_DATA_REGEX =
            Regex("""<script[^>]+id="__NEXT_DATA__"[^>]*>(.*?)</script>""", RegexOption.DOT_MATCHES_ALL)

        /** SSR HTML href değerleri. */
        private val HREF_REGEX = Regex("""href="(/[^"#?]+)"""")

        /** Bölüm slug kalıbı (§71): /<slug>-<n>-sezon-<n>-bolum (+ "-c06" gibi varyant sonekleri). */
        private val EPISODE_SLUG_REGEX =
            Regex("""^(?:[a-z0-9]+-)*\d+-sezon-\d+-bolum(?:-[a-z0-9]+)?$""", RegexOption.IGNORE_CASE)
        private val SEASON_REGEX = Regex("""-(\d+)-sezon""")
        private val EPISODE_NO_REGEX = Regex("""-(\d+)-bolum""")
    }

    // --------------------------------------------------------------- AES (§72)

    /** sha256(seed) → base64 → ilk 32 karakter, UTF-8 bayt olarak anahtar (§72). */
    private val aesKey: ByteArray by lazy {
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(AES_SEED.toByteArray(Charsets.UTF_8))
        val base64 = Base64.encodeToString(digest, Base64.NO_WRAP)
        base64.substring(0, 32).toByteArray(Charsets.UTF_8)
    }

    /** §72 kanıtı: AES-256-CBC, IV = 16×0x00, PKCS7 (16 baytlık blokta PKCS5Padding eşdeğerdir). */
    private fun decryptSecureData(encryptedBase64: String): JSONObject? = try {
        val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(aesKey, "AES"), IvParameterSpec(ByteArray(16)))
        val plain = cipher.doFinal(Base64.decode(encryptedBase64, Base64.DEFAULT))
        JSONObject(String(plain, Charsets.UTF_8))
    } catch (_: Exception) {
        null
    }

    // --------------------------------------------------------------- SEARCH

    override suspend fun search(query: String): List<SearchResponse> {
        val requestUrl = mainUrl + SEARCH_PATH + URLEncoder.encode(query, "UTF-8")
        val body = app.post(requestUrl, headers = mapOf("User-Agent" to UA)).text

        val outer = JSONObject(body)
        if (!outer.optBoolean("success", false)) return emptyList()
        val decrypted = decryptSecureData(outer.optString("response")) ?: return emptyList()
        val result: JSONArray = decrypted.optJSONArray("result") ?: return emptyList()

        val responses = ArrayList<SearchResponse>()
        for (i in 0 until result.length()) {
            val item = result.optJSONObject(i) ?: continue
            val title = item.optString("object_name").trim()
            if (title.isBlank()) continue
            val slug = item.optString("used_slug").trim().trimStart('/')
            if (slug.isBlank()) continue
            val poster = item.optString("object_poster_url").trim().takeIf { it.isNotBlank() }

            responses += newTvSeriesSearchResponse(title, "$mainUrl/$slug", TvType.TvSeries) {
                this.posterUrl = poster
            }
        }
        return responses.distinctBy { it.url }
    }

    override suspend fun quickSearch(query: String): List<SearchResponse> = search(query)

    // --------------------------------------------------------------- LOAD

    override suspend fun load(url: String): LoadResponse {
        val seriesHtml = app.get(url, headers = mapOf("User-Agent" to UA)).text
        if (seriesHtml.isBlank()) throw ErrorLoadingException("Dizilla: dizi sayfası boş döndü ($url)")

        val buildId = fetchBuildId(seriesHtml)
            ?: throw ErrorLoadingException("Dizilla: __NEXT_DATA__ buildId bulunamadı")

        val title = extractMeta(seriesHtml, "og:title")?.substringBeforeLast(" - Dizilla")?.trim()
            ?: url.trimEnd('/').substringAfterLast('/').replace('-', ' ')
        val poster = extractMeta(seriesHtml, "og:image")
        val plot = extractMeta(seriesHtml, "og:description")

        val episodeSlugs = extractEpisodeSlugs(seriesHtml)
        if (episodeSlugs.isEmpty()) throw ErrorLoadingException("Dizilla: SSR'de bölüm linki yok ($url)")

        val episodes = ArrayList<Episode>()
        val seen = HashSet<Pair<Int, Int>>()
        for (slug in episodeSlugs) {
            val episode = loadEpisode(buildId, slug) ?: continue
            // "-c06" gibi varyant sonekleri aynı bölümün kopyasıdır → tekilleştir.
            val key = (episode.season ?: 1) to (episode.episode ?: 0)
            if (!seen.add(key)) continue
            episodes += episode
        }
        if (episodes.isEmpty()) throw ErrorLoadingException("Dizilla: bölüm verisi çözülemedi ($url)")

        return newTvSeriesLoadResponse(
            title, url, TvType.TvSeries,
            episodes.sortedWith(compareBy({ it.season }, { it.episode }))
        ) {
            this.posterUrl = poster
            this.plot = plot
        }
    }

    /** buildId: ÖNCE ana sayfa __NEXT_DATA__ (§72 kanıtı); olmazsa dizi sayfasındaki aynı değer. */
    private suspend fun fetchBuildId(seriesHtml: String): String? =
        extractBuildId(app.get("$mainUrl/", headers = mapOf("User-Agent" to UA)).text)
            ?: extractBuildId(seriesHtml)

    private fun extractBuildId(html: String): String? = try {
        NEXT_DATA_REGEX.find(html)?.groupValues?.get(1)
            ?.let { json -> JSONObject(json).optString("buildId").takeIf { it.isNotBlank() } }
    } catch (_: Exception) {
        null
    }

    /** og:<name> / name="<name>" meta içeriği (attribute sırasından bağımsız). */
    private fun extractMeta(html: String, metaName: String): String? {
        val name = Regex.escape(metaName)
        val match = Regex("""<meta[^>]+(?:property|name)="$name"[^>]*content="([^"]*)"""", RegexOption.IGNORE_CASE)
            .find(html)
            ?: Regex("""<meta[^>]+content="([^"]*)"[^>]*(?:property|name)="$name"""", RegexOption.IGNORE_CASE)
                .find(html)
        return match?.groupValues?.get(1)?.trim()?.takeIf { it.isNotBlank() }
    }

    /** SSR HTML'den bölüm slug'ları (§71/§72: kök düzey /<bolum-slug>). */
    private fun extractEpisodeSlugs(html: String): List<String> =
        HREF_REGEX.findAll(html)
            .mapNotNull { it.groupValues[1].trimEnd('/').removePrefix("/") }
            .filter { slug ->
                slug.contains("-sezon") && slug.contains("-bolum") && EPISODE_SLUG_REGEX.matches(slug)
            }
            .distinct()
            .toList()

    /** /_next/data/<buildId>/<bolum-slug>.json → pageProps.secureData → AES → Episode. */
    private suspend fun loadEpisode(buildId: String, bolumSlug: String): Episode? {
        return try {
            val dataUrl = "$mainUrl/$NEXT_DATA_PATH/$buildId/$bolumSlug.json"
            val page = JSONObject(app.get(dataUrl, headers = mapOf("User-Agent" to UA)).text)
            val secureData = page.optJSONObject("pageProps")?.optString("secureData")
            if (secureData.isNullOrBlank()) return null

            val decrypted = decryptSecureData(secureData) ?: return null
            val contentItem = decrypted.optJSONObject("contentItem")

            val seasonNo = contentItem?.optInt("season_no")?.takeIf { it > 0 }
                ?: SEASON_REGEX.find(bolumSlug)?.groupValues?.get(1)?.toIntOrNull()
                ?: 1
            val episodeNo = contentItem?.optInt("episode_no")?.takeIf { it > 0 }
                ?: EPISODE_NO_REGEX.find(bolumSlug)?.groupValues?.get(1)?.toIntOrNull()
            // §74C kanıtı: gerçek başlık alanları episode_title / original_title
            // (title/object_name/name/post_title canlı veride boş geliyor).
            val name = firstNonBlank(contentItem, "episode_title", "original_title")
                ?.let(::cleanEpisodeTitle)
                ?: "${episodeNo ?: 0}. Bölüm"

            newEpisode("$mainUrl/$bolumSlug") {
                this.name = name
                this.season = seasonNo
                this.episode = episodeNo
            }
        } catch (_: Exception) {
            null
        }
    }

    /** §74C kanıtı: bölüm adı alanları episode_title → original_title (title/object_name boş). */
    private fun firstNonBlank(obj: JSONObject?, vararg keys: String): String? {
        if (obj == null) return null
        for (key in keys) {
            val value = obj.optString(key).trim()
            if (value.isNotBlank()) return value
        }
        return null
    }

    /** og:title deseniyle aynı: " - Dizilla" soneki kırpılır (episode_title uzun SEO adı). */
    private fun cleanEpisodeTitle(raw: String): String {
        val title = raw.trim()
        val cleaned = title.removeSuffix(" - Dizilla").trim()
        return cleaned.ifBlank { title }
    }

    /** §74C kanıtı: source_content iframe HTML'i → tam https iframe URL'i. */
    private fun extractIframeUrl(sourceContent: String): String? {
        if (sourceContent.isBlank()) return null
        val match = Regex("""//([a-z0-9.-]+/iframe\.php\?v=[A-Za-z0-9]+)""", RegexOption.IGNORE_CASE)
            .find(sourceContent) ?: return null
        val path = match.groupValues[1]
        return path.takeIf { it.isNotBlank() }?.let { "https://$it" }
    }

    // --------------------------------------------------------------- LOADLINKS (§74C)

    /**
     * §74C kanıt zinciri (keşif POST birebir):
     * data (bölüm sayfası URL) → buildId (homepage __NEXT_DATA__) →
     * /_next/data/<buildId>/<bolum-slug>.json → pageProps.secureData → AES →
     * RelatedResults.getEpisodeSources.result[] → her kayıt:
     * source_content iframe URL → iframe GET (Referer: dizilla.now/) →
     * openPlayer('<token>') → source2.php?v=<token> (Referer: iframe URL) →
     * playlist[].sources[] type=hls → m.php → master.m3u8 → M3u8Helper.
     * Kaynak sayısı sabit varsayılmaz; tek kaynağın hatası diğerlerini durdurmaz.
     */
    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ): Boolean {
        val bolumSlug = data.trimEnd('/').substringAfterLast('/')
        if (bolumSlug.isBlank()) return false

        val buildId = fetchBuildId("") ?: return false

        val page = try {
            JSONObject(
                app.get("$mainUrl/$NEXT_DATA_PATH/$buildId/$bolumSlug.json", headers = mapOf("User-Agent" to UA)).text
            )
        } catch (_: Exception) {
            return false
        }

        val secureData = page.optJSONObject("pageProps")?.optString("secureData")
        if (secureData.isNullOrBlank()) return false
        val decrypted = decryptSecureData(secureData) ?: return false

        // §74C kanıtı: kaynaklar RelatedResults altında (kopya: content.result.RelatedResults).
        val sources = decrypted.optJSONObject("RelatedResults")
            ?.optJSONObject("getEpisodeSources")
            ?.optJSONArray("result")
            ?: decrypted.optJSONObject("content")
                ?.optJSONObject("result")
                ?.optJSONObject("RelatedResults")
                ?.optJSONObject("getEpisodeSources")
                ?.optJSONArray("result")
            ?: return false

        var delivered = false
        for (i in 0 until sources.length()) {
            val item = sources.optJSONObject(i) ?: continue
            try {
                val iframeUrl = extractIframeUrl(item.optString("source_content")) ?: continue
                val label = buildLabel(item)
                val quality = item.optString("quality_name").trim()
                if (extractAndDeliver(iframeUrl, label, quality, callback)) delivered = true
            } catch (_: Exception) {
                continue // bir kaynağın hatası diğerlerini durdurmaz (§74C).
            }
        }
        return delivered
    }

    /** Kaynak etiketi: Dizilla · source_name · language_name · quality_name. */
    private fun buildLabel(item: JSONObject): String = buildString {
        append(name)
        for (key in listOf("source_name", "language_name", "quality_name")) {
            val part = item.optString(key).trim()
            if (part.isNotBlank()) append(" · ").append(part)
        }
    }

    /**
     * §73/§74C zinciri tek kaynak için: iframe → token → source2 → master.m3u8 → linkler.
     * Referer zorunlu (§73): iframe için dizilla.now, source2/m3u8 için iframe URL.
     */
    private suspend fun extractAndDeliver(
        iframeUrl: String,
        label: String,
        qualityName: String,
        callback: (ExtractorLink) -> Unit,
    ): Boolean {
        val playerHtml = try {
            app.get(iframeUrl, headers = mapOf("User-Agent" to UA, "Referer" to "$mainUrl/")).text
        } catch (_: Exception) {
            return false
        }

        val token = Regex("""openPlayer\('([A-Za-z0-9+/=]{20,})""").find(playerHtml)
            ?.groupValues?.get(1)
            ?: return false

        val host = iframeUrl.removePrefix("https://").substringBefore('/')

        val source2 = try {
            JSONObject(
                app.get("https://$host/source2.php?v=$token", headers = mapOf("User-Agent" to UA, "Referer" to iframeUrl)).text
            )
        } catch (_: Exception) {
            return false
        }
        if (!source2.optBoolean("state", false)) return false

        val playlist = source2.optJSONArray("playlist") ?: return false

        var delivered = false
        for (p in 0 until playlist.length()) {
            val sources = playlist.optJSONObject(p)?.optJSONArray("sources") ?: continue
            for (s in 0 until sources.length()) {
                val src = sources.optJSONObject(s) ?: continue
                val type = src.optString("type").trim().lowercase()
                if (type != "hls" && type != "m3u8") continue

                var file = src.optString("file").trim()
                if (file.isBlank()) continue
                if (!file.startsWith("http")) file = "https://$host/" + file.trimStart('/')

                // §73 kanıtı: player file.replace("m.php","master.m3u8") dönüşümü.
                val master = file.replace("m.php", "master.m3u8")

                try {
                    callback.invoke(
                        newExtractorLink(
                            source = name,
                            name = label,
                            url = master,
                            type = ExtractorLinkType.M3U8,
                        ) {
                            this.headers = mapOf("User-Agent" to UA, "Referer" to iframeUrl)
                            // Kalite sabitlenmez; varyantlar M3u8Helper'da çözülür (§74C).
                            this.quality = qualityName.filter { it.isDigit() }.toIntOrNull()
                                ?: Qualities.Unknown.value
                        }
                    )
                    M3u8Helper.generateM3u8(label, master, iframeUrl).forEach(callback)
                    delivered = true
                } catch (_: Exception) {
                    // Varyantlar üretilmezse master link zaten sunuldu; diğer kaynaklara devam.
                }
            }
        }
        return delivered
    }
}