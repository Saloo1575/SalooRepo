// ! IPTV-org Türkiye — https://iptv-org.github.io/iptv/countries/tr.m3u
// ! CanliTV (FreeTVProvider) referans alınarak güncel CloudStream API'sine göre sıfırdan yazıldı.

package com.saloo.iptvorgtr

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.utils.AppUtils.parseJson
import com.lagradost.cloudstream3.utils.AppUtils.toJson

class IPTVOrgTR : MainAPI() {
    override var mainUrl            = "https://iptv-org.github.io/iptv/countries/tr.m3u"
    override var name               = "IPTV-org Türkiye"
    override val hasMainPage        = true
    override var lang               = "tr"
    override val hasQuickSearch     = true
    override val hasDownloadSupport = false
    override val supportedTypes     = setOf(TvType.Live)

    /** search/load/loadLinks arasında taşınan kanal verisi (JSON olarak serileştirilir). */
    data class LoadData(val url: String, val title: String, val poster: String, val group: String)

    private suspend fun getPlaylist(): Playlist = IptvPlaylistParser().parseM3U(app.get(mainUrl).text)

    private fun PlaylistItem.toLoadData(): LoadData {
        return LoadData(
            url    = url.orEmpty(),
            title  = title.orEmpty(),
            poster = attributes["tvg-logo"].orEmpty(),
            group  = attributes["group-title"] ?: "Diğer"
        )
    }

    private fun PlaylistItem.toLiveSearchResponse(): LiveSearchResponse {
        val data = toLoadData()
        return newLiveSearchResponse(
            data.title,
            data.toJson(),
            type = TvType.Live
        ) {
            this.posterUrl = data.poster
            this.lang      = this@IPTVOrgTR.lang
        }
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val kanallar = getPlaylist()

        return newHomePageResponse(
            kanallar.items
                .groupBy { it.attributes["group-title"] ?: "Diğer" }
                .map { group ->
                    HomePageList(group.key, group.value.map { it.toLiveSearchResponse() }, isHorizontalImages = true)
                },
            hasNext = false
        )
    }

    override suspend fun search(query: String): List<SearchResponse> {
        return getPlaylist().items
            .filter { it.title.orEmpty().contains(query, ignoreCase = true) }
            .map { it.toLiveSearchResponse() }
    }

    override suspend fun quickSearch(query: String): List<SearchResponse> = search(query)

    override suspend fun load(url: String): LoadResponse {
        val loadData = fetchDataFromUrlOrJson(url)
        val kanallar = getPlaylist()
        val group    = loadData.group

        val recommendations = mutableListOf<LiveSearchResponse>()
        for (kanal in kanallar.items) {
            if ((kanal.attributes["group-title"] ?: "Diğer") == group && kanal.title != loadData.title) {
                recommendations.add(kanal.toLiveSearchResponse())
            }
        }

        return newLiveStreamLoadResponse(loadData.title, loadData.url, url) {
            this.posterUrl       = loadData.poster
            this.plot            = "» $group «"
            this.tags            = listOfNotNull(group.takeIf { it.isNotBlank() })
            this.recommendations = recommendations
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val loadData = fetchDataFromUrlOrJson(data)
        val headers  = getPlaylist().items.firstOrNull { it.url == loadData.url }?.headers ?: emptyMap()

        callback.invoke(
            newExtractorLink(
                source = this.name,
                name   = loadData.title,
                url    = loadData.url,
                type   = if (loadData.url.contains(".m3u8")) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
            ) {
                this.referer = headers["referrer"] ?: ""
                this.headers = headers
                quality      = Qualities.Unknown.value
            }
        )

        return true
    }

    /** Veri JSON ise ayrıştır; değilse (eski sürüm uyumluluğu) ham akış adresini listede ara. */
    private suspend fun fetchDataFromUrlOrJson(data: String): LoadData {
        if (data.startsWith("{")) {
            return parseJson<LoadData>(data)
        }

        val kanal = getPlaylist().items.firstOrNull { it.url == data }
            ?: return LoadData(data, data.substringAfterLast('/'), "", "Diğer")

        return kanal.toLoadData()
    }
}