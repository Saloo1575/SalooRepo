package recloudstream

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import com.lagradost.cloudstream3.ErrorLoadingException
import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.newMovieLoadResponse
import com.lagradost.cloudstream3.newMovieSearchResponse
import com.lagradost.cloudstream3.utils.StringUtils.encodeUri

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
        val metadata: Metadata
    )

    private data class Metadata(
        val title: String? = null,
        val description: String? = null
    )
}
