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
        "Accept" to "application/json",
        "x-app-id" to "Egywatch-mobile",
        "x-platform" to "android"
    )

    // ذاكرة مؤقتة لحفظ قواعد الفك من /hosts/config
    private var cachedHostsConfig: List<HostConfigItem>? = null

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
                val id = itemObj.optInt("featured_id", 0).takeIf { it > 0 }
                    ?: itemObj.optInt("id", -1).takeIf { it != -1 }
                    ?: continue

                val itemTitle = itemObj.optString("title").ifEmpty { itemObj.optString("name") }
                if (itemTitle.isEmpty()) continue

                val type = itemObj.optString("type").lowercase()
                val posterPath = itemObj.optString("poster_path").takeIf { it.isNotEmpty() }

                val directApiUrl = if (type == "movie") {
                    "$mainUrl/media/detail/$id/$apiKey"
                } else {
                    "$mainUrl/series/show/$id/$apiKey"
                }

                val searchRes = if (type == "movie") {
                    newMovieSearchResponse(itemTitle, directApiUrl, TvType.Movie) {
                        this.posterUrl = posterPath
                    }
                } else {
                    newTvSeriesSearchResponse(itemTitle, directApiUrl, TvType.TvSeries) {
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
        }

        return results
    }

    override suspend fun load(url: String): LoadResponse? {
        return if (url.contains("/media/detail/")) {
            val res = app.get(url, headers = appHeaders).parsedSafe<MediaDetail>() ?: return null
            val videosJson = res.videos?.toJson() ?: ""

            newMovieLoadResponse(res.title ?: res.name ?: "", url, TvType.Movie, videosJson) {
                this.posterUrl = res.posterPath
                this.plot = res.overview
                this.year = res.releaseDate?.substringBefore("-")?.toIntOrNull()
                this.score = res.voteAverage?.let { Score.from10(it) }
            }
        } else {
            val res = app.get(url, headers = appHeaders).parsedSafe<MediaDetail>() ?: return null
            val episodes = mutableListOf<Episode>()

            res.seasons?.forEach { season ->
                val seasonId = season.id ?: return@forEach
                val seasonRes = app.get("$mainUrl/series/season/$seasonId/$apiKey", headers = appHeaders).parsedSafe<SeasonDetail>()
                
                seasonRes?.episodes?.forEach { ep ->
                    val epVideosJson = ep.videos?.toJson() ?: ""

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

            newTvSeriesLoadResponse(res.name ?: res.title ?: "", url, TvType.TvSeries, episodes) {
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
        val hostsRules = getHostsRules()

        videos.forEach { video ->
            val link = video.link ?: return@forEach
            val serverName = video.server ?: "Server"
            val customHeader = video.header ?: ""

            // 1. فحص الروابط المباشرة (MP4 أو M3U8)
            if (link.contains(".m3u8") || link.contains(".mp4")) {
                callback.invoke(
                    newExtractorLink(name = serverName, source = name, url = link) {
                        referer = customHeader
                        quality = getQualityFromName(serverName)
                    }
                )
                return@forEach
            }

            // 2. تجربة مستخرجات كلاودستريم المدمجة أولاً (سريعة جداً)
            val loaded = loadExtractor(link, subtitleCallback, callback)
            if (loaded) return@forEach

            // 3. مطابقة الرابط مع قواعد /hosts/config المخصصة
            val matchedRule = hostsRules.firstOrNull { rule ->
                val pattern = rule.regexPattern ?: return@firstOrNull false
                try {
                    Regex(pattern, RegexOption.IGNORE_CASE).containsMatchIn(link)
                } catch (e: Exception) {
                    false
                }
            }

            if (matchedRule != null) {
                resolveWithHostRule(link, serverName, matchedRule, callback)
            } else {
                // 4. Fallback عام للبحث عن الفيديو داخل الـ JS
                fallbackRegexExtract(link, serverName, customHeader, callback)
            }
        }
        return true
    }

    // ==========================================
    // محرك تنفيذ قواعد /hosts/config
    // ==========================================

    private suspend fun getHostsRules(): List<HostConfigItem> {
        if (cachedHostsConfig != null) return cachedHostsConfig!!
        return try {
            val res = app.get(
                "$mainUrl/hosts/config",
                headers = mapOf("User-Agent" to "okhttp/5.0.0-alpha.6", "Accept" to "application/json")
            ).parsedSafe<List<HostConfigItem>>()
            cachedHostsConfig = res ?: emptyList()
            cachedHostsConfig!!
        } catch (e: Exception) {
            emptyList()
        }
    }

    private suspend fun resolveWithHostRule(
        link: String,
        serverName: String,
        rule: HostConfigItem,
        callback: (ExtractorLink) -> Unit
    ) {
        try {
            val referer = rule.referer?.takeIf { it.isNotEmpty() } ?: link
            val userAgent = rule.useragent?.takeIf { it.isNotEmpty() } ?: "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36"
            val headers = mapOf("User-Agent" to userAgent, "Referer" to referer)

            // أ) الاستخراج عبر Regex من صفحة الـ HTML (حقل site)
            val sitePattern = rule.site
            if (!sitePattern.isNullOrEmpty()) {
                val html = app.get(link, headers = headers).text
                val regex = Regex("""sources.*(https?:[^"']+)"""", RegexOption.IGNORE_CASE)
                val match = regex.find(html) ?: Regex("""file\s*:\s*["']([^"']+)["']""").find(html)

                val streamUrl = match?.groups?.get(1)?.value
                if (!streamUrl.isNullOrEmpty() && (streamUrl.startsWith("http://") || streamUrl.startsWith("https://"))) {
                    callback.invoke(
                        newExtractorLink(name = "$serverName (Direct)", source = name, url = streamUrl) {
                            this.referer = referer
                            this.quality = getQualityFromName(serverName)
                        }
                    )
                    return
                }
            }

            // ب) استدعاء سيرفر الفك المساعد (حقل urlsite)
            val helperUrl = rule.urlsite
            if (!helperUrl.isNullOrEmpty() && helperUrl.startsWith("http")) {
                val targetApi = if (helperUrl.endsWith("=") || helperUrl.endsWith("api=")) {
                    "$helperUrl$link"
                } else {
                    helperUrl
                }
                val apiRes = app.get(targetApi, headers = headers).text
                val match = Regex("""(https?://[^\s"']+\.(?:m3u8|mp4)[^\s"']*)""").find(apiRes)
                val extracted = match?.groups?.get(1)?.value

                if (!extracted.isNullOrEmpty()) {
                    callback.invoke(
                        newExtractorLink(name = "$serverName (Helper)", source = name, url = extracted) {
                            this.referer = referer
                            this.quality = getQualityFromName(serverName)
                        }
                    )
                }
            }
        } catch (e: Exception) {
            // تجاهل الخطأ لتفادي تعطل بقية السيرفرات
        }
    }

    private suspend fun fallbackRegexExtract(
        link: String,
        serverName: String,
        customHeader: String,
        callback: (ExtractorLink) -> Unit
    ) {
        try {
            val headers = mapOf(
                "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36",
                "Referer" to customHeader.ifEmpty { link }
            )
            val html = app.get(link, headers = headers).text
            val videoRegex = Regex("""(?:file|src)\s*:\s*["'](https?://[^"']+\.(?:m3u8|mp4)[^"']*)["']""")
            val match = videoRegex.find(html)
            val extractedUrl = match?.groups?.get(1)?.value

            if (extractedUrl != null) {
                callback.invoke(
                    newExtractorLink(name = "$serverName (Auto)", source = name, url = extractedUrl) {
                        referer = link
                        quality = getQualityFromName(serverName)
                    }
                )
            }
        } catch (e: Exception) {
            // تجاهل الخطأ
        }
    }

    // ==========================================
    // Data Classes
    // ==========================================

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class HostConfigItem(
        @JsonProperty("host_id") val hostId: String?,
        @JsonProperty("regex_pattern") val regexPattern: String?,
        @JsonProperty("resolver_class") val resolverClass: String?,
        @JsonProperty("site") val site: String?,
        @JsonProperty("referer") val referer: String?,
        @JsonProperty("urlsite") val urlsite: String?,
        @JsonProperty("useragent") val useragent: String?
    )

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class MediaDetail(
        @JsonProperty("id") val id: Int?,
        @JsonProperty("title") val title: String?,
        @JsonProperty("name") val name: String?,
        @JsonProperty("overview") val overview: String?,
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
        @JsonProperty("header") val header: String?
    )
}
