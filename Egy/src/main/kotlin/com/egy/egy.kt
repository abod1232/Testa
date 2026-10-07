package com.egy

import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.AppUtils.parseJson
import com.lagradost.cloudstream3.utils.AppUtils.toJson
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.getQualityFromName
import com.lagradost.cloudstream3.utils.loadExtractor
import com.lagradost.cloudstream3.utils.newExtractorLink

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
        val response = app.get(request.data, headers = appHeaders).parsedSafe<HomeResponse>()
        val homeItems = mutableListOf<HomePageList>()

        response?.sections?.forEach { section ->
            val title = section.title.takeIf { !it.isNullOrEmpty() } ?: section.type ?: "أخرى"
            val elements = section.data ?: return@forEach

            val searchResponses = elements.mapNotNull { item ->
                toSearchResponse(item)
            }

            if (searchResponses.isNotEmpty()) {
                homeItems.add(HomePageList(title, searchResponses))
            }
        }

        return HomePageResponse(homeItems)
    }

    override suspend fun search(query: String): List<SearchResponse>? {
        val searchUrl = "$mainUrl/search/${query}|vide/$apiKey"
        val response = app.get(searchUrl, headers = appHeaders).parsedSafe<SearchData>()

        val results = mutableListOf<SearchResponse>()
        response?.movies?.forEach { toSearchResponse(it, "movie")?.let { res -> results.add(res) } }
        response?.series?.forEach { toSearchResponse(it, "serie")?.let { res -> results.add(res) } }
        response?.animes?.forEach { toSearchResponse(it, "anime")?.let { res -> results.add(res) } }

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
                this.rating = res.voteAverage?.times(1000)?.toInt()
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
                this.rating = res.voteAverage?.times(1000)?.toInt()
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

    private fun toSearchResponse(item: MediaItem, defaultType: String = ""): SearchResponse? {
        val title = item.title ?: item.name ?: return null
        val id = item.id ?: return null
        val type = item.type?.lowercase() ?: defaultType

        val url = "egywatch://$type/$id"

        return if (type == "movie") {
            newMovieSearchResponse(title, url, TvType.Movie) {
                this.posterUrl = item.posterPath
            }
        } else {
            newTvSeriesSearchResponse(title, url, TvType.TvSeries) {
                this.posterUrl = item.posterPath
            }
        }
    }

    data class HomeResponse(@JsonProperty("sections") val sections: List<Section>?)
    data class Section(@JsonProperty("title") val title: String?, @JsonProperty("type") val type: String?, @JsonProperty("data") val data: List<MediaItem>?)

    data class SearchData(
        @JsonProperty("movies") val movies: List<MediaItem>?,
        @JsonProperty("series") val series: List<MediaItem>?,
        @JsonProperty("animes") val animes: List<MediaItem>?
    )

    data class MediaItem(
        @JsonProperty("id") val id: Int?,
        @JsonProperty("title") val title: String?,
        @JsonProperty("name") val name: String?,
        @JsonProperty("type") val type: String?,
        @JsonProperty("poster_path") val posterPath: String?
    )

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

    data class Season(
        @JsonProperty("id") val id: Int?,
        @JsonProperty("season_number") val seasonNumber: Int?
    )

    data class SeasonDetail(@JsonProperty("episodes") val episodes: List<EpisodeItem>?)

    data class EpisodeItem(
        @JsonProperty("episode_name") val episodeName: String?,
        @JsonProperty("episode_number") val episodeNumber: Int?,
        @JsonProperty("still_path") val stillPath: String?,
        @JsonProperty("poster_path") val posterPath: String?,
        @JsonProperty("videos") val videos: List<Video>?
    )

    data class Video(
        @JsonProperty("server") val server: String?,
        @JsonProperty("link") val link: String?,
        @JsonProperty("header") val header: String?
    )
}