package com.saloo.iptvorgtr

import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.LoadData
import com.lagradost.cloudstream3.utils.newExtractorLink
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class IPTVOrgTRProvider : MainAPI() {

    override var mainUrl = "https://iptv-org.github.io"
    override var name = "IPTV-org Türkiye"
    override val supportedTypes = setOf(TvType.Live)

    private val playlistUrl =
        "https://iptv-org.github.io/iptv/countries/tr.m3u"

    override suspend fun search(query: String): List<SearchResponse> {
        return withContext(Dispatchers.IO) {
            val playlist = app.get(playlistUrl).text

            playlist
                .split("#EXTINF:")
                .drop(1)
                .mapNotNull { entry ->
                    val lines = entry.lines()

                    val info = lines.firstOrNull() ?: return@mapNotNull null
                    val streamUrl =
                        lines.drop(1).firstOrNull { it.startsWith("http") }
                            ?: return@mapNotNull null

                    val channelName =
                        info.substringAfterLast(",").trim()

                    if (
                        channelName.contains(
                            query,
                            ignoreCase = true
                        )
                    ) {
                        IPTVOrgTRSearchResponse(
                            channelName,
                            streamUrl
                        )
                    } else {
                        null
                    }
                }
        }
    }

    override suspend fun load(url: String): LoadResponse {
        return newLiveSearchResponse(
            name = url.substringAfter("channel="),
            url = url,
            apiName = this.name
        )
    }

    private fun newLiveSearchResponse(
        name: String,
        url: String,
        apiName: String
    ): LoadResponse {
        return object : LoadResponse() {
            override var name = name
            override var url = url
            override var apiName = apiName
            override var type = TvType.Live
            override var posterUrl: String? = null
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (com.lagradost.cloudstream3.SubtitleFile) -> Unit,
        callback: (com.lagradost.cloudstream3.ExtractorLink) -> Unit
    ): Boolean {

        callback.invoke(
            newExtractorLink(
                source = name,
                name = name,
                url = data,
                type = if (data.contains(".m3u8"))
                    com.lagradost.cloudstream3.utils.getQualityFromName("1080p").let {
                        com.lagradost.cloudstream3.utils.ExtractorLinkType.M3U8
                    }
                else
                    com.lagradost.cloudstream3.utils.ExtractorLinkType.VIDEO
            )
        )

        return true
    }
}

private data class IPTVOrgTRSearchResponse(
    override val name: String,
    private val streamUrl: String
) : SearchResponse() {

    override val url: String
        get() = streamUrl

    override val apiName: String
        get() = "IPTVOrgTRProvider"

    override val type: TvType
        get() = TvType.Live

    override val posterUrl: String?
        get() = null

    override val id: Int
        get() = streamUrl.hashCode()
}
