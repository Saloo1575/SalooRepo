package recloudstream

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import com.lagradost.cloudstream3.ErrorLoadingException
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.newMovieLoadResponse
import com.lagradost.cloudstream3.newMovieSearchResponse
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.StringUtils.encodeUri
import com.lagradost.cloudstream3.utils.newExtractorLink

class InternetArchiveProvider : MainAPI() {

    override var mainUrl = "https://archive.org"
    override var name = "Internet Archive"
    override var lang = "en"

    override val supportedTypes = setOf(TvType.Movie)
    override val hasMainPage = false

    private val mapper = jacksonObjectMapper()

    override suspend fun search(query: String): List<SearchResponse> {
        return try {
            val url =
                "$mainUrl/advancedsearch.php" +
                "?q=${query.encodeUri()}%20AND%20mediatype:movies" +
                "&fl[]=identifier&fl[]=title" +
                "&rows=25" +
                "&output=json"

            val json = app.get(url).text
            val result = mapper.readValue<SearchResult>(json)

            result.response.docs.map { item ->
                newMovieSearchResponse(
                    item.title ?: item.identifier,
                    "$mainUrl/details/${item.identifier}",
                    TvType.Movie
                ) {
                    posterUrl = "$mainUrl/services/img/${item.identifier}"
                }
            }
        } catch (_: Exception) {
            emptyList()
        }
    }

    override suspend fun load(url: String): LoadResponse {
        return try {
            val identifier = url.substringAfterLast("/")

            val json = app.get("$mainUrl/metadata/$identifier").text
            val result = mapper.readValue<MetadataResult>(json)

            newMovieLoadResponse(
                result.metadata.title ?: identifier,
                url,
                TvType.Movie,
                identifier
            ) {
                posterUrl = "$mainUrl/services/img/$identifier"
                plot = result.metadata.description
            }
        } catch (e: Exception) {
            throw ErrorLoadingException(
                "Internet Archive metadata could not be loaded: ${e.message}"
            )
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        return try {
            val identifier = data.substringAfterLast("/")

            val json = app.get("$mainUrl/metadata/$identifier").text
            val result = mapper.readValue<MetadataResult>(json)

            result.files
                .filter { file ->
                    val format = file.format.lowercase()

                    format.contains("mpeg") ||
                    format.contains("h.264") ||
                    format.contains("matroska") ||
                    format.contains("ogg video") ||
                    format.endsWith("mp4")
                }
                .forEach { file ->
                    val directUrl =
                        "$mainUrl/download/$identifier/${file.name}"

                    callback(
                        newExtractorLink(
    source = name,
    name = file.name,
    url = directUrl
) {
    referer = "$mainUrl/"
    quality = Qualities.Unknown.value
}
                    )
                }

            true
        } catch (_: Exception) {
            false
        }
    }

    private data class SearchResult(
        val response: SearchResponseData
    )

    private data class SearchResponseData(
        val docs: List<SearchItem>
    )

    private data class SearchItem(
        val identifier: String,
        val title: String? = null
    )

    private data class MetadataResult(
        val metadata: Metadata,
        val files: List<ArchiveFile> = emptyList()
    )

    private data class Metadata(
        val title: String? = null,
        val description: String? = null
    )

    private data class ArchiveFile(
        val name: String = "",
        val format: String = ""
    )
}
