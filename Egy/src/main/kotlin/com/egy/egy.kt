package com.egy

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.AppUtils.parseJson
import com.lagradost.cloudstream3.utils.AppUtils.toJson
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.getQualityFromName
import com.lagradost.cloudstream3.utils.loadExtractor
import com.lagradost.cloudstream3.utils.newExtractorLink
import org.json.JSONObject
import java.net.URLEncoder

class EgyWatchProvider : MainAPI() {
    override var mainUrl = "https://rn62mwg.com/egywatchapp/public/api"
    override var name = "EgyWatch"
    override val hasMainPage = true
    override var lang = "ar"
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries, TvType.Anime)

    private val apiKey = "p2lbgWkFrykA4QyUmpHihzmc5BNzIABq"

    private val appHeaders = mapOf(
        "User-Agent" to "EasyPlex (Android 16; RMX5061; realme RE60ADL1; ar)",
        "packagename" to "com.linkletter.app",
        "Accept" to "application/json"
    )

    override val mainPage = mainPageOf(
        "$mainUrl/media/homecontent/$apiKey" to "الصفحة الرئيسية"
    )

    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse? {
        val responseText = app.get(request.data, headers = appHeaders).text
        val json = JSONObject(responseText)
        val sectionsArray = json.optJSONArray("sections") ?: return null

        val homeItems = mutableListOf<HomePageList>()

        for (i in 0 until sectionsArray.length()) {
            val sectionObj = sectionsArray.optJSONObject(i) ?: continue
            val title = sectionObj.optString("title").trim().ifEmpty { sectionObj.optString("type") }
            val dataArray = sectionObj.optJSONArray("data") ?: continue

            val searchResponses = mutableListOf<SearchResponse>()
            for (j in 0 until dataArray.length()) {
                val itemObj = dataArray.optJSONObject(j) ?: continue
                val id = itemObj.optInt("id", -1).takeIf { it != -1 } ?: continue
                val itemTitle = itemObj.optString("title").ifEmpty { itemObj.optString("name") }
                if (itemTitle.isEmpty()) continue

                val type = itemObj.optString("type").lowercase()
                val posterPath = itemObj.optString("poster_path").takeIf { it.isNotEmpty() }

                val url = "egywatch://$type/$id"
                val searchRes = if (type == "movie") {
                    newMovieSearchResponse(itemTitle, url, TvType.Movie) {
                        this.posterUrl = posterPath
                    }
                } else {
                    newTvSeriesSearchResponse(itemTitle, url, TvType.TvSeries) {
                        this.posterUrl = posterPath
                    }
                }
                searchResponses.add(searchRes)
            }

            if (searchResponses.isNotEmpty()) {
                homeItems.add(HomePageList(title, searchResponses))
            }
        }

        return newHomePageResponse(homeItems)
    }

    override suspend fun search(query: String): List<SearchResponse>? {
        val encodedQuery = URLEncoder.encode("$query|vide", "UTF-8")
        val searchUrl = "$mainUrl/search/$encodedQuery/$apiKey"
        val responseText = app.get(searchUrl, headers = appHeaders).text
        val json = JSONObject(responseText)

        val results = mutableListOf<SearchResponse>()
        val categories = listOf("movies" to "movie", "series" to "serie", "animes" to "anime")

        for ((key, defaultType) in categories) {
            val array = json.optJSONArray(key) ?: continue
            for (i in 0 until array.length()) {
                val item = array.optJSONObject(i) ?: continue
                val id = item.optInt("id", -1).takeIf { it != -1 } ?: continue
                val title = item.optString("title").ifEmpty { item.optString("name") }
                if (title.isEmpty()) continue

                val type = item.optString("type").lowercase().ifEmpty { defaultType }
                val posterPath = item.optString("poster_path").takeIf { it.isNotEmpty() }

                val url = "egywatch://$type/$id"
                val res = if (type == "movie") {
                    newMovieSearchResponse(title, url, TvType.Movie) {
                        this.posterUrl = posterPath
                    }
                } else {
                    newTvSeriesSearchResponse(title, url, TvType.TvSeries) {
                        this.posterUrl = posterPath
                    }
                }
                results.add(res)
            }
        }

        return results
    }

    override suspend fun load(url: String): LoadResponse? {
        val type = url.split("/")[2]
        val id = url.split("/")[3]

        return if (type == "movie") {
            val res = app.get("$mainUrl/media/detail/$id/$apiKey", headers = appHeaders).parsedSafe<MediaDetail>() ?: return null
            val videosJson = res.videos?.toJson() ?: ""

            newMovieLoadResponse(res.title ?: "", url, TvType.Movie, videosJson) {
                this.posterUrl = res.posterPath
                this.plot = res.overview
                this.year = res.releaseDate?.substringBefore("-")?.toIntOrNull()
                this.score = res.voteAverage?.let { Score.from10(it) }
            }
        } else {
            val res = app.get("$mainUrl/series/show/$id/$apiKey", headers = appHeaders).parsedSafe<MediaDetail>() ?: return null
            val episodes = mutableListOf<Episode>()

            res.seasons?.forEach { season ->
                val seasonRes = app.get("$mainUrl/series/season/${season.id}/$apiKey", headers = appHeaders).parsedSafe<SeasonDetail>()
                seasonRes?.episodes?.forEach { ep ->
                    val epVideosJson = ep.videos?.toJson() ?: ""

                    episodes.add(
                        newEpisode(epVideosJson) {
                            this.name = ep.episodeName
                            this.season = season.seasonNumber
                            this.episode = ep.episodeNumber
                            this.posterUrl = ep.stillPath ?: ep.posterPath
                        }
                    )
                }
            }

            newTvSeriesLoadResponse(res.title ?: "", url, TvType.TvSeries, episodes) {
                this.posterUrl = res.posterPath
                this.plot = res.overview
                this.score = res.voteAverage?.let { Score.from10(it) }
            }
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val videos = parseJson<List<Video>>(data)

        videos.forEach { video ->
            val link = video.link ?: return@forEach
            val serverName = video.server ?: "Server"

            if (link.contains(".m3u8") || link.contains(".mp4")) {
                callback.invoke(
                    newExtractorLink(
                        name = serverName,
                        source = name,
                        url = link,
                    ) {
                        referer = video.header ?: ""
                        quality = getQualityFromName(serverName)
                    }
                )
            } else {
                loadExtractor(link, subtitleCallback, callback)
            }
        }
        return true
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class MediaDetail(
        @JsonProperty("id") val id: Int?,
        @JsonProperty("title") val title: String?,
        @JsonProperty("name") val name: String?,
        @JsonProperty("overview") val overview: String?,
        @JsonProperty("poster_path") val posterPath: String?,
        @JsonProperty("release_date") val releaseDate: String?,
        @JsonProperty("vote_average") val voteAverage: Double?,
        @JsonProperty("videos") val videos: List<Video>?,
        @JsonProperty("seasons") val seasons: List<Season>?
    )

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class Season(
        @JsonProperty("id") val id: Int?,
        @JsonProperty("season_number") val seasonNumber: Int?
    )

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class SeasonDetail(@JsonProperty("episodes") val episodes: List<EpisodeItem>?)

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class EpisodeItem(
        @JsonProperty("episode_name") val episodeName: String?,
        @JsonProperty("episode_number") val episodeNumber: Int?,
        @JsonProperty("still_path") val stillPath: String?,
        @JsonProperty("poster_path") val posterPath: String?,
        @JsonProperty("videos") val videos: List<Video>?
    )

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class Video(
        @JsonProperty("server") val server: String?,
        @JsonProperty("link") val link: String?,
        @JsonProperty("header") val header: String?
    )
}
