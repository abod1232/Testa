package com.egy

import android.util.Log
import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.AppUtils.parseJson
import com.lagradost.cloudstream3.utils.AppUtils.toJson
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.getQualityFromName
import com.lagradost.cloudstream3.utils.loadExtractor
import com.lagradost.cloudstream3.utils.newExtractorLink
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import org.json.JSONObject
import java.net.URLEncoder

class EgyWatchProvider : MainAPI() {
    private val primaryUrl = "https://rn62mwg.com/egywatchapp/public/api"
    private val fallbackUrl = "https://gateway.egywatch.live/egywatchapp/public/api"

    override var mainUrl = primaryUrl
    override var name = "EgyWatch"
    override val hasMainPage = true
    override var lang = "ar"
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries, TvType.Anime, TvType.Live)

    private val apiKey = "p2lbgWkFrykA4QyUmpHihzmc5BNzIABq"

    private val appHeaders = mapOf(
        "User-Agent" to "EasyPlex (Android 16; RMX5061; realme RE60ADL1; ar)",
        "packagename" to "com.linkletter.app",
        "Accept" to "application/json",
        "x-app-id" to "Egywatch-mobile",
        "x-platform" to "android"
    )

    private var cachedHostsConfig: List<HostConfigItem>? = null
    private suspend fun fetchApi(url: String, headers: Map<String, String> = appHeaders): String? {
        return try {
            val response = app.get(url, headers = headers)
            if (response.isSuccessful && response.text.isNotEmpty()) {
                response.text
            } else {
                throw Exception("Response unsuccessful: ${response.code}")
            }
        } catch (e: Exception) {
            val altUrl = when {
                url.startsWith(primaryUrl) -> url.replace(primaryUrl, fallbackUrl)
                url.startsWith(fallbackUrl) -> url.replace(fallbackUrl, primaryUrl)
                else -> null
            }

            if (altUrl != null) {
                try {
                    val fallbackResponse = app.get(altUrl, headers = headers)
                    if (fallbackResponse.isSuccessful) fallbackResponse.text else null
                } catch (e2: Exception) {
                    null
                }
            } else {
                null
            }
        }
    }

    private suspend inline fun <reified T : Any> fetchApiParsed(url: String, headers: Map<String, String> = appHeaders): T? {
        val text = fetchApi(url, headers) ?: return null
        return try {
            parseJson<T>(text)
        } catch (e: Exception) {
            null
        }
    }

    override val mainPage = mainPageOf(
        "$mainUrl/media/homecontent/$apiKey" to "الصفحة الرئيسية"
    )

    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse? {
        val responseText = fetchApi(request.data) ?: return null
        val json = JSONObject(responseText)
        val sectionsArray = json.optJSONArray("sections") ?: return null

        val homeItems = mutableListOf<HomePageList>()

        for (i in 0 until sectionsArray.length()) {
            val sectionObj = sectionsArray.optJSONObject(i) ?: continue
            val title = sectionObj.optString("title").trim().ifEmpty { sectionObj.optString("type") }
            val sectionType = sectionObj.optString("type").lowercase()
            val dataArray = sectionObj.optJSONArray("data") ?: continue

            val searchResponses = mutableListOf<SearchResponse>()
            for (j in 0 until dataArray.length()) {
                val itemObj = dataArray.optJSONObject(j) ?: continue
                
                val id = itemObj.optInt("featured_id", 0).takeIf { it > 0 }
                    ?: itemObj.optInt("id", -1).takeIf { it != -1 }
                    ?: continue

                val itemTitle = itemObj.optString("title").ifEmpty { itemObj.optString("name") }
                if (itemTitle.isEmpty()) continue

                val rawType = itemObj.optString("type").lowercase()
                val posterPath = itemObj.optString("poster_path").ifEmpty { 
                    itemObj.optString("profile_path") 
                }.takeIf { it.isNotEmpty() }

                val directApiUrl = when {
                    sectionType == "channels" || rawType == "channel" -> "$mainUrl/stream/show/$id/$apiKey"
                    sectionType == "collections" -> "$mainUrl/collections/media/show/$id"
                    sectionType == "actors" -> "$mainUrl/filmographie/detail/$id/$apiKey"
                    rawType == "movie" -> "$mainUrl/media/detail/$id/$apiKey"
                    else -> "$mainUrl/series/show/$id/$apiKey"
                }

                val searchRes = when {
                    sectionType == "channels" -> newMovieSearchResponse(itemTitle, directApiUrl, TvType.Live) {
                        this.posterUrl = posterPath
                    }
                    sectionType == "collections" || sectionType == "actors" -> newTvSeriesSearchResponse(itemTitle, directApiUrl, TvType.TvSeries) {
                        this.posterUrl = posterPath
                    }
                    rawType == "movie" -> newMovieSearchResponse(itemTitle, directApiUrl, TvType.Movie) {
                        this.posterUrl = posterPath
                    }
                    else -> newTvSeriesSearchResponse(itemTitle, directApiUrl, TvType.TvSeries) {
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
        val responseText = fetchApi(searchUrl) ?: return null
        val json = JSONObject(responseText)

        val results = mutableListOf<SearchResponse>()
        val searchArray = json.optJSONArray("search") ?: return results

        for (i in 0 until searchArray.length()) {
            val item = searchArray.optJSONObject(i) ?: continue
            val id = item.optInt("id", -1).takeIf { it != -1 } ?: continue
            val title = item.optString("title").ifEmpty { item.optString("name") }
            if (title.isEmpty()) continue

            val type = item.optString("type").lowercase()
            val posterPath = item.optString("poster_path").takeIf { it.isNotEmpty() }

            val directApiUrl = if (type == "movie") {
                "$mainUrl/media/detail/$id/$apiKey"
            } else {
                "$mainUrl/series/show/$id/$apiKey"
            }

            val res = if (type == "movie") {
                newMovieSearchResponse(title, directApiUrl, TvType.Movie) {
                    this.posterUrl = posterPath
                }
            } else {
                newTvSeriesSearchResponse(title, directApiUrl, TvType.TvSeries) {
                    this.posterUrl = posterPath
                }
            }
            results.add(res)
        }

        return results
    }

    override suspend fun load(url: String): LoadResponse? {
        if (url.contains("/stream/show/")) {
            val res = fetchApiParsed<MediaDetail>(url) ?: return null
            val videosJson = res.videos?.toJson() ?: ""

            return newMovieLoadResponse(res.name ?: res.title ?: "بث مباشر", url, TvType.Live, videosJson) {
                this.posterUrl = res.posterPath
                this.plot = res.overview
            }
        }
        if (url.contains("/collections/media/show/")) {
            val (episodes, poster) = fetchPaginatedMediaList(url)
            return newTvSeriesLoadResponse("سلسلة أفلام", url, TvType.Movie, episodes) {
                this.posterUrl = poster
            }
        }
        if (url.contains("/filmographie/detail/")) {
            val actorId = url.substringAfter("/detail/").substringBefore("/")
            val castInfo = fetchApiParsed<MediaDetail>("$mainUrl/cast/detail/$actorId/$apiKey")
            val (episodes, _) = fetchPaginatedMediaList(url)

            return newTvSeriesLoadResponse(castInfo?.name ?: "أفلام الممثل", url, TvType.TvSeries, episodes) {
                this.posterUrl = castInfo?.profilePath ?: castInfo?.posterPath
                this.plot = castInfo?.biography ?: "أعمال ومشاركات الممثل"
            }
        }
        if (url.contains("/media/detail/")) {
            val res = fetchApiParsed<MediaDetail>(url) ?: return null
            val videosJson = res.videos?.toJson() ?: ""

            return newMovieLoadResponse(res.title ?: res.name ?: "", url, TvType.Movie, videosJson) {
                this.posterUrl = res.posterPath
                this.plot = res.overview
                this.year = res.releaseDate?.substringBefore("-")?.toIntOrNull()
                this.score = res.voteAverage?.let { Score.from10(it) }
            }
        }
        val res = fetchApiParsed<MediaDetail>(url) ?: return null
        val episodes = mutableListOf<Episode>()

        res.seasons?.parallelMap { season ->
            val seasonId = season.id ?: return@parallelMap
            val seasonRes = fetchApiParsed<SeasonDetail>("$mainUrl/series/season/$seasonId/$apiKey")
            
            seasonRes?.episodes?.forEach { ep ->
                val epVideosJson = ep.videos?.toJson() ?: ""

                synchronized(episodes) {
                    episodes.add(
                        newEpisode(epVideosJson) {
                            this.name = ep.name ?: ep.episodeName ?: "الحلقة ${ep.episodeNumber}"
                            this.season = season.seasonNumber
                            this.episode = ep.episodeNumber
                            this.posterUrl = ep.stillPath ?: ep.posterPath
                        }
                    )
                }
            }
        }
        episodes.sortBy { ((it.season ?: 1) * 10000) + (it.episode ?: 0) }

        return newTvSeriesLoadResponse(res.name ?: res.title ?: "", url, TvType.TvSeries, episodes) {
            this.posterUrl = res.posterPath
            this.plot = res.overview
            this.score = res.voteAverage?.let { Score.from10(it) }
        }
    }
    private suspend fun fetchPaginatedMediaList(url: String, maxPages: Int = 3): Pair<List<Episode>, String?> {
        val episodes = mutableListOf<Episode>()
        var poster: String? = null

        for (page in 1..maxPages) {
            val pageRes = fetchApi("$url?page=$page") ?: break
            val json = try { JSONObject(pageRes) } catch (e: Exception) { break }
            val dataArray = json.optJSONArray("data") ?: break
            if (dataArray.length() == 0) break

            for (i in 0 until dataArray.length()) {
                val m = dataArray.optJSONObject(i) ?: continue
                val mId = m.optInt("id")
                val mTitle = m.optString("name").ifEmpty { m.optString("title") }
                val mPoster = m.optString("poster_path").takeIf { it.isNotEmpty() }
                if (poster == null) poster = mPoster

                val movieApiUrl = "$mainUrl/media/detail/$mId/$apiKey"
                episodes.add(
                    newEpisode(movieApiUrl) {
                        this.name = mTitle
                        this.episode = episodes.size + 1
                        this.posterUrl = mPoster
                    }
                )
            }
            val lastPage = json.optInt("last_page", 1)
            if (page >= lastPage) break
        }
        return Pair(episodes, poster)
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val videos = if (data.startsWith("http")) {
            val res = fetchApiParsed<MediaDetail>(data)
            res?.videos ?: emptyList()
        } else {
            try {
                parseJson<Array<Video>>(data).toList()
            } catch (e: Exception) {
                emptyList()
            }
        }

        val hostsRules = getHostsRules()

        videos.parallelMap { video ->
            val link = video.link ?: return@parallelMap
            val serverName = video.server ?: "سيرفر"
            val customHeader = video.header ?: ""
            val videoUserAgent = video.useragent ?: ""

            if (link.contains("cdnlink.developer-pro.workers.dev")) {
                callback.invoke(
                    newExtractorLink(name = serverName, source = name, url = link) {
                        this.quality = getQualityFromName(serverName)
                    }
                )
                return@parallelMap
            }

            val matchedRule = hostsRules.firstOrNull { rule ->
                val rawPattern = rule.regexPattern ?: return@firstOrNull false
                try {
                    val cleanPattern = rawPattern
                        .replace("\\/", "/")
                        .replace("googlefasV", "googlefas[A-Za-z]")
                    Regex(cleanPattern, RegexOption.IGNORE_CASE).containsMatchIn(link)
                } catch (e: Exception) {
                    false
                }
            }

            val cleanHeaders = getCleanHeaders(customHeader, videoUserAgent, matchedRule?.referer)

            if (matchedRule == null && (link.contains(".m3u8") || link.contains(".mp4"))) {
                callback.invoke(
                    newExtractorLink(name = serverName, source = name, url = link) {
                        this.referer = cleanHeaders["referer"] ?: ""
                        this.headers = cleanHeaders
                        this.quality = getQualityFromName(serverName)
                    }
                )
                return@parallelMap
            }

            var resolved = false
            if (matchedRule != null) {
                resolved = resolveWithBaseVedEngine(link, serverName, cleanHeaders, matchedRule, callback)
            }
            if (!resolved) {
                resolved = loadExtractor(link, subtitleCallback, callback)
            }
            if (!resolved) {
                fallbackRegexExtract(link, serverName, cleanHeaders, callback)
            }
        }

        return true
    }

    private fun getCleanHeaders(
        rawHeader: String?,
        rawUserAgent: String?,
        ruleReferer: String?
    ): Map<String, String> {
        val headers = mutableMapOf<String, String>()

        val ua = rawUserAgent?.takeIf { it.isNotBlank() }
            ?: "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36"
        headers["user-agent"] = ua.trim()

        if (!rawHeader.isNullOrBlank()) {
            for (part in rawHeader.split("|")) {
                val token = part.trim()
                if (token.isEmpty()) continue

                if (token.startsWith("http://", true) || token.startsWith("https://", true)) {
                    headers["referer"] = token
                } else if (token.contains(":")) {
                    val key = token.substringBefore(":").trim().lowercase()
                    val value = token.substringAfter(":").trim()
                    if (key.isNotEmpty() && value.isNotEmpty()) {
                        headers[key] = value
                    }
                }
            }
        }

        if (!headers.containsKey("referer") && !ruleReferer.isNullOrBlank() && ruleReferer.startsWith("http", true)) {
            headers["referer"] = ruleReferer.trim()
        }

        return headers
    }

    private suspend fun <A, B> Iterable<A>.parallelMap(f: suspend (A) -> B): List<B> = coroutineScope {
        map { async { f(it) } }.awaitAll()
    }

    private suspend fun getHostsRules(): List<HostConfigItem> {
        if (cachedHostsConfig != null) return cachedHostsConfig!!
        return try {
            val text = fetchApi(
                "$mainUrl/hosts/config",
                headers = mapOf("User-Agent" to "okhttp/5.0.0-alpha.6", "Accept" to "application/json")
            ) ?: return emptyList()
            
            val array = parseJson<Array<HostConfigItem>>(text).toList()
            cachedHostsConfig = array
            cachedHostsConfig!!
        } catch (e: Exception) {
            emptyList()
        }
    }

    private suspend fun resolveWithBaseVedEngine(
        link: String,
        serverName: String,
        cleanHeaders: Map<String, String>,
        rule: HostConfigItem,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        return try {
            val enabledParts = rule.enableded?.split("|") ?: emptyList()
            val postUrl = enabledParts.getOrNull(1)?.takeIf { it.startsWith("http") }
            val urlSite = rule.urlsite

            if (postUrl != null) {
                val html = app.get(link, headers = cleanHeaders).text
                val postHeaders = mapOf(
                    "Content-Type" to "application/x-www-form-urlencoded",
                    "User-Agent" to "okhttp/5.0.0-alpha.6"
                )

                val postResponse = app.post(
                    postUrl,
                    data = mapOf("content" to html, "url" to link),
                    headers = postHeaders
                ).text

                if (parseAndEmitLinks(postResponse, serverName, cleanHeaders, callback)) {
                    return true
                }
            }

            if (!urlSite.isNullOrEmpty() && (urlSite.endsWith("=") || urlSite.endsWith("api="))) {
                val getApiUrl = "$urlSite$link"
                val apiRes = app.get(getApiUrl, headers = mapOf("User-Agent" to "okhttp/5.0.0-alpha.6")).text
                if (parseAndEmitLinks(apiRes, serverName, cleanHeaders, callback)) {
                    return true
                }
            }

            val sitePattern = rule.site
            if (!sitePattern.isNullOrEmpty()) {
                val html = app.get(link, headers = cleanHeaders).text
                val regex = Regex("""sources.*(https?:[^"']+)"""", RegexOption.IGNORE_CASE)
                val match = regex.find(html) ?: Regex("""file\s*:\s*["']([^"']+)["']""").find(html)

                val streamUrl = match?.groups?.get(1)?.value
                if (!streamUrl.isNullOrEmpty() && (streamUrl.startsWith("http://") || streamUrl.startsWith("https://"))) {
                    callback.invoke(
                        newExtractorLink(name = "$serverName (Direct)", source = name, url = streamUrl) {
                            this.referer = cleanHeaders["referer"] ?: ""
                            this.headers = cleanHeaders
                            this.quality = getQualityFromName(serverName)
                        }
                    )
                    return true
                }
            }

            false
        } catch (e: Exception) {
            false
        }
    }

    private suspend fun parseAndEmitLinks(
        responseBody: String,
        serverName: String,
        cleanHeaders: Map<String, String>,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        var foundAny = false
        val cleanBody = responseBody.replace("\\/", "/").trim()
        val labelMatches = Regex("""file\s*:\s*["']([^"']+)["']\s*,\s*label\s*:\s*["']([^"']+)["']""", RegexOption.IGNORE_CASE)
            .findAll(cleanBody).toList()

        if (labelMatches.isNotEmpty()) {
            for (match in labelMatches) {
                val streamUrl = match.groups[1]?.value ?: continue
                val qualityStr = match.groups[2]?.value ?: "Auto"

                callback.invoke(
                    newExtractorLink(name = "$serverName ($qualityStr)", source = name, url = streamUrl) {
                        this.referer = cleanHeaders["referer"] ?: ""
                        this.headers = cleanHeaders
                        this.quality = getQualityFromName(qualityStr)
                    }
                )
                foundAny = true
            }
            if (foundAny) return true
        }

        try {
            val jsonRes = JSONObject(cleanBody)
            if (jsonRes.optString("status") == "success") {
                val filteredContent = jsonRes.optJSONArray("filtered_content")
                val qualityArray = jsonRes.optJSONArray("Quality")

                if (filteredContent != null && filteredContent.length() > 0) {
                    for (i in 0 until filteredContent.length()) {
                        val streamUrl = filteredContent.optString(i)
                        if (streamUrl.isNotEmpty()) {
                            val qualityStr = qualityArray?.optString(i) ?: "Normal"
                            callback.invoke(
                                newExtractorLink(name = "$serverName ($qualityStr)", source = name, url = streamUrl) {
                                    this.referer = cleanHeaders["referer"] ?: ""
                                    this.headers = cleanHeaders
                                    this.quality = getQualityFromName(qualityStr)
                                }
                            )
                            foundAny = true
                        }
                    }
                }
            } else if (jsonRes.has("file")) {
                val streamUrl = jsonRes.optString("file")
                if (streamUrl.isNotEmpty()) {
                    callback.invoke(
                        newExtractorLink(name = "$serverName (Auto)", source = name, url = streamUrl) {
                            this.referer = cleanHeaders["referer"] ?: ""
                            this.headers = cleanHeaders
                            this.quality = getQualityFromName(serverName)
                        }
                    )
                    foundAny = true
                }
            }
        } catch (e: Exception) {
        }

        if (foundAny) return true

        val universalVideoRegex = Regex("""https?://[^\s"'<>\\]+?\.(?:m3u8|mp4|mkv)(?:\?[^\s"'<>\\]*)?""", RegexOption.IGNORE_CASE)
        val extractedVideoUrls = universalVideoRegex.findAll(cleanBody)
            .map { it.value }
            .distinct()
            .toList()

        if (extractedVideoUrls.isNotEmpty()) {
            for (streamUrl in extractedVideoUrls) {
                callback.invoke(
                    newExtractorLink(
                        name = "$serverName (Direct)",
                        source = name,
                        url = streamUrl
                    ) {
                        this.referer = cleanHeaders["referer"] ?: ""
                        this.headers = cleanHeaders
                        this.quality = getQualityFromName(serverName)
                    }
                )
                foundAny = true
            }
        }

        return foundAny
    }

    private suspend fun fallbackRegexExtract(
        link: String,
        serverName: String,
        cleanHeaders: Map<String, String>,
        callback: (ExtractorLink) -> Unit
    ) {
        try {
            val html = app.get(link, headers = cleanHeaders).text
            val videoRegex = Regex("""(?:file|src)\s*:\s*["'](https?://[^"']+\.(?:m3u8|mp4)[^"']*)["']""")
            val match = videoRegex.find(html)
            val extractedUrl = match?.groups?.get(1)?.value

            if (extractedUrl != null) {
                callback.invoke(
                    newExtractorLink(name = "$serverName (Auto)", source = name, url = extractedUrl) {
                        this.referer = cleanHeaders["referer"] ?: ""
                        this.headers = cleanHeaders
                        this.quality = getQualityFromName(serverName)
                    }
                )
            }
        } catch (e: Exception) {
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class HostConfigItem(
        @JsonProperty("host_id") val hostId: String?,
        @JsonProperty("regex_pattern") val regexPattern: String?,
        @JsonProperty("resolver_class") val resolverClass: String?,
        @JsonProperty("site") val site: String?,
        @JsonProperty("referer") val referer: String?,
        @JsonProperty("enableded") val enableded: String?,
        @JsonProperty("urlsite") val urlsite: String?,
        @JsonProperty("useragent") val useragent: String?
    )

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class MediaDetail(
        @JsonProperty("id") val id: Int?,
        @JsonProperty("title") val title: String?,
        @JsonProperty("name") val name: String?,
        @JsonProperty("overview") val overview: String?,
        @JsonProperty("biography") val biography: String?,
        @JsonProperty("profile_path") val profilePath: String?,
        @JsonProperty("poster_path") val posterPath: String?,
        @JsonProperty("release_date") val releaseDate: String?,
        @JsonProperty("first_air_date") val firstAirDate: String?,
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
        @JsonProperty("name") val name: String?,
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
        @JsonProperty("header") val header: String?,
        @JsonProperty("useragent") val useragent: String?
    )
}
