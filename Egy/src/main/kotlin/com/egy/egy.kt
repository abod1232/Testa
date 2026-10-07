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

            res.seasons?.parallelMap { season ->
                val seasonId = season.id ?: return@parallelMap
                val seasonRes = app.get("$mainUrl/series/season/$seasonId/$apiKey", headers = appHeaders).parsedSafe<SeasonDetail>()
                
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
        val videos = try {
            parseJson<Array<Video>>(data).toList()
        } catch (e: Exception) {
            emptyList()
        }

        val hostsRules = getHostsRules()

        videos.parallelMap { video ->
            val link = video.link ?: return@parallelMap
            val serverName = video.server ?: "سيرفر"
            val customHeader = video.header ?: ""

            // تنظيف واستخراج الترويسات الصحيحة
            val parsedHeaders = parseHeaderString(customHeader, link)
            val cleanReferer = parsedHeaders["Referer"] ?: parsedHeaders["referer"] ?: link

            // 1. فحص الروابط المباشرة (MP4 أو M3U8)
            if (link.contains(".m3u8") || link.contains(".mp4")) {
                callback.invoke(
                    newExtractorLink(name = serverName, source = name, url = link) {
                        this.referer = cleanReferer
                        this.headers = parsedHeaders
                        this.quality = getQualityFromName(serverName)
                    }
                )
                return@parallelMap
            }

            // 2. مطابقة الرابط مع قواعد /hosts/config
            val matchedRule = hostsRules.firstOrNull { rule ->
                val rawPattern = rule.regexPattern ?: return@firstOrNull false
                try {
                    val cleanPattern = rawPattern.replace("\\/", "/")
                    Regex(cleanPattern, RegexOption.IGNORE_CASE).containsMatchIn(link)
                } catch (e: Exception) {
                    false
                }
            }

            var resolved = false

            // 3. فك الرابط عبر محرك BaseVedEasyPlex
            if (matchedRule != null) {
                resolved = resolveWithBaseVedEngine(link, serverName, customHeader, matchedRule, callback)
            }

            // 4. تجربة مستخرجات كلاودستريم المدمجة كبديل
            if (!resolved) {
                resolved = loadExtractor(link, subtitleCallback, callback)
            }

            // 5. Fallback أخير للبحث داخل الـ HTML
            if (!resolved) {
                fallbackRegexExtract(link, serverName, cleanReferer, callback)
            }
        }

        return true
    }

    // ==========================================
    // دالة تنظيف وتفكيك الترويسات (Header Parser)
    // ==========================================

    private fun parseHeaderString(headerStr: String?, defaultReferer: String): Map<String, String> {
        val headersMap = mutableMapOf<String, String>()
        if (headerStr.isNullOrEmpty()) {
            if (defaultReferer.isNotEmpty()) headersMap["Referer"] = defaultReferer
            return headersMap
        }

        // تفكيك ترويسات مثل: origin:https://...|referer:https://...
        if (headerStr.contains("|") || headerStr.contains(":")) {
            val parts = headerStr.split("|")
            for (part in parts) {
                val colonIdx = part.indexOf(":")
                if (colonIdx != -1) {
                    val key = part.substring(0, colonIdx).trim()
                    val value = part.substring(colonIdx + 1).trim()
                    if (key.isNotEmpty() && value.isNotEmpty()) {
                        // تعديل الحروف الكبيرة للترويسات القياسية
                        val formattedKey = when (key.lowercase()) {
                            "referer" -> "Referer"
                            "origin" -> "Origin"
                            "user-agent" -> "User-Agent"
                            else -> key
                        }
                        headersMap[formattedKey] = value
                    }
                }
            }
        }

        // إذا كان الرابط مكتوباً بشكل مباشر
        if (headersMap.isEmpty() && headerStr.startsWith("http")) {
            headersMap["Referer"] = headerStr.trim()
        }

        // التأكد من وجود Referer نظيف دائماً
        if (!headersMap.containsKey("Referer")) {
            headersMap["Referer"] = defaultReferer
        }

        return headersMap
    }

    private suspend fun <A, B> Iterable<A>.parallelMap(f: suspend (A) -> B): List<B> = coroutineScope {
        map { async { f(it) } }.awaitAll()
    }

    // ==========================================
    // محرك الفك عبر mawdhou3.com (BaseVedEasyPlex Engine)
    // ==========================================

    private suspend fun getHostsRules(): List<HostConfigItem> {
        if (cachedHostsConfig != null) return cachedHostsConfig!!
        return try {
            val text = app.get(
                "$mainUrl/hosts/config",
                headers = mapOf("User-Agent" to "okhttp/5.0.0-alpha.6", "Accept" to "application/json")
            ).text
            
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
        customHeader: String,
        rule: HostConfigItem,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        return try {
            val parsedHeaders = parseHeaderString(customHeader, rule.referer ?: link)
            val cleanReferer = parsedHeaders["Referer"] ?: rule.referer ?: link

            val userAgent = rule.useragent?.takeIf { it.isNotEmpty() } 
                ?: "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36"
            
            val requestHeaders = parsedHeaders.toMutableMap()
            requestHeaders["User-Agent"] = userAgent

            val enabledParts = rule.enableded?.split("|") ?: emptyList()
            val isPostEnabled = enabledParts.getOrNull(0) == "TRUE"
            val postUrl = enabledParts.getOrNull(1)?.takeIf { it.startsWith("http") }
            val urlSite = rule.urlsite

            // الحالة 1: الفك عبر GET السريع (مثل Uqload و Vidnest)
            if (!isPostEnabled && !urlSite.isNullOrEmpty() && (urlSite.endsWith("=") || urlSite.endsWith("api="))) {
                val getApiUrl = "$urlSite$link"
                val apiRes = app.get(getApiUrl, headers = mapOf("User-Agent" to "okhttp/5.0.0-alpha.6")).text
                val jsonRes = JSONObject(apiRes)

                if (jsonRes.optString("status") == "success") {
                    val filteredContent = jsonRes.optJSONArray("filtered_content")
                    val qualityArray = jsonRes.optJSONArray("Quality")

                    if (filteredContent != null && filteredContent.length() > 0) {
                        for (i in 0 until filteredContent.length()) {
                            val streamUrl = filteredContent.optString(i)
                            if (streamUrl.isNotEmpty()) {
                                val qualityStr = qualityArray?.optString(i) ?: "Normal"
                                callback.invoke(
                                    newExtractorLink(
                                        name = "$serverName ($qualityStr)",
                                        source = name,
                                        url = streamUrl
                                    ) {
                                        this.referer = cleanReferer
                                        this.headers = parsedHeaders
                                        this.quality = getQualityFromName(qualityStr)
                                    }
                                )
                            }
                        }
                        return true
                    }
                }
            }

            // الحالة 2: الفك عبر POST مع إرسال كود الـ HTML (مثل Vidtube و Upzur)
            if (isPostEnabled && postUrl != null) {
                val html = app.get(link, headers = requestHeaders).text
                val postHeaders = mapOf(
                    "Content-Type" to "application/x-www-form-urlencoded",
                    "User-Agent" to "okhttp/5.0.0-alpha.6"
                )

                val postResponse = app.post(
                    postUrl,
                    data = mapOf("content" to html, "url" to link),
                    headers = postHeaders
                ).text

                val jsonRes = JSONObject(postResponse)
                if (jsonRes.optString("status") == "success") {
                    val filteredContent = jsonRes.optJSONArray("filtered_content")
                    val qualityArray = jsonRes.optJSONArray("Quality")

                    if (filteredContent != null && filteredContent.length() > 0) {
                        for (i in 0 until filteredContent.length()) {
                            val streamUrl = filteredContent.optString(i)
                            if (streamUrl.isNotEmpty()) {
                                val qualityStr = qualityArray?.optString(i) ?: "Normal"
                                callback.invoke(
                                    newExtractorLink(
                                        name = "$serverName ($qualityStr)",
                                        source = name,
                                        url = streamUrl
                                    ) {
                                        this.referer = cleanReferer
                                        this.headers = parsedHeaders
                                        this.quality = getQualityFromName(qualityStr)
                                    }
                                )
                            }
                        }
                        return true
                    }
                }
            }

            // الحالة 3: الاستخراج المحلي المباشر عبر الـ Regex في حقل site
            val sitePattern = rule.site
            if (!sitePattern.isNullOrEmpty()) {
                val html = app.get(link, headers = requestHeaders).text
                val cleanSitePattern = sitePattern.replace("\\/", "/")
                val regex = Regex("""sources.*(https?:[^"']+)"""", RegexOption.IGNORE_CASE)
                val match = regex.find(html) ?: Regex("""file\s*:\s*["']([^"']+)["']""").find(html)

                val streamUrl = match?.groups?.get(1)?.value
                if (!streamUrl.isNullOrEmpty() && (streamUrl.startsWith("http://") || streamUrl.startsWith("https://"))) {
                    callback.invoke(
                        newExtractorLink(name = "$serverName (Direct)", source = name, url = streamUrl) {
                            this.referer = cleanReferer
                            this.headers = parsedHeaders
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

    private suspend fun fallbackRegexExtract(
        link: String,
        serverName: String,
        cleanReferer: String,
        callback: (ExtractorLink) -> Unit
    ) {
        try {
            val headers = mapOf(
                "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36",
                "Referer" to cleanReferer
            )
            val html = app.get(link, headers = headers).text
            val videoRegex = Regex("""(?:file|src)\s*:\s*["'](https?://[^"']+\.(?:m3u8|mp4)[^"']*)["']""")
            val match = videoRegex.find(html)
            val extractedUrl = match?.groups?.get(1)?.value

            if (extractedUrl != null) {
                callback.invoke(
                    newExtractorLink(name = "$serverName (Auto)", source = name, url = extractedUrl) {
                        this.referer = cleanReferer
                        this.quality = getQualityFromName(serverName)
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
