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

    companion object {
        // الروابط المتاحة (الأساسي والاحتياطي)
        private val API_HOSTS = listOf(
            "https://rn62mwg.com/egywatchapp/public/api",
            "https://gateway.egywatch.live/egywatchapp/public/api"
        )
    }

    override var mainUrl = API_HOSTS[0]
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

    // استخراج المسار الصافي للطلب دون الدومين
    private fun getCleanPath(urlOrPath: String): String {
        return when {
            urlOrPath.contains("/public/api/") -> urlOrPath.substringAfter("/public/api/").removePrefix("/")
            urlOrPath.contains("/api/") -> urlOrPath.substringAfter("/api/").removePrefix("/")
            else -> urlOrPath.removePrefix("/")
        }
    }

    // دالة فحص وتجربة الرابط الاحتياطي إذا كان الرد خاطئاً أو فارغاً
    private suspend fun fetchApiWithFallback(
        urlOrPath: String,
        validate: (String) -> Boolean = { it.isNotBlank() && !it.contains("not found", ignoreCase = true) }
    ): String? {
        val path = getCleanPath(urlOrPath)

        for (host in API_HOSTS) {
            val fullUrl = "$host/$path"
            try {
                val res = app.get(fullUrl, headers = appHeaders, timeout = 12L)
                if (res.isSuccessful && validate(res.text)) {
                    return res.text
                }
            } catch (e: Exception) {
                Log.d("EgyWatch", "Failed fetching from $host: ${e.message}")
            }
        }
        return null
    }

    private suspend inline fun <reified T : Any> fetchApiParsed(
        urlOrPath: String,
        crossinline validate: (String) -> Boolean = { it.isNotBlank() && !it.contains("not found", ignoreCase = true) }
    ): T? {
        val text = fetchApiWithFallback(urlOrPath) { validate(it) } ?: return null
        return try {
            parseJson<T>(text)
        } catch (e: Exception) {
            null
        }
    }

    override val mainPage = mainPageOf(
        "media/homecontent/$apiKey" to "الصفحة الرئيسية"
    )

    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse? {
        val path = getCleanPath(request.data)

        // محاولة جلب الأقسام؛ إذا كانت فارغة في السيرفر الأول يجرب الاحتياطي
        for (host in API_HOSTS) {
            try {
                val responseText = app.get("$host/$path", headers = appHeaders, timeout = 12L).text
                if (responseText.contains("not found", ignoreCase = true)) continue

                val json = JSONObject(responseText)
                val sectionsArray = json.optJSONArray("sections") ?: continue
                if (sectionsArray.length() == 0) continue

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
                        val isAnime = sectionType == "animes" || rawType == "anime" || itemObj.optInt("is_anime", 0) == 1

                        val posterPath = itemObj.optString("poster_path").ifEmpty { 
                            itemObj.optString("profile_path") 
                        }.takeIf { it.isNotEmpty() }

                        val targetPath = when {
                            sectionType == "channels" || rawType == "channel" -> "stream/show/$id/$apiKey"
                            sectionType == "collections" -> "collections/media/show/$id"
                            sectionType == "actors" -> "filmographie/detail/$id/$apiKey"
                            rawType == "movie" -> "media/detail/$id/$apiKey"
                            isAnime -> "animes/show/$id/$apiKey"
                            else -> "series/show/$id/$apiKey"
                        }

                        val directApiUrl = "$host/$targetPath"

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
                            isAnime -> newTvSeriesSearchResponse(itemTitle, directApiUrl, TvType.Anime) {
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

                if (homeItems.isNotEmpty()) {
                    return newHomePageResponse(homeItems)
                }
            } catch (e: Exception) {
                continue
            }
        }
        return null
    }

    override suspend fun search(query: String): List<SearchResponse>? {
        val encodedQuery = URLEncoder.encode("$query|vide", "UTF-8")
        val searchPath = "search/$encodedQuery/$apiKey"

        // إذا لم يعثر على نتائج في السيرفر الأول، يجرب الاحتياطي فوراً
        for (host in API_HOSTS) {
            try {
                val responseText = app.get("$host/$searchPath", headers = appHeaders, timeout = 12L).text
                val json = JSONObject(responseText)
                val searchArray = json.optJSONArray("search") ?: continue
                if (searchArray.length() == 0) continue

                val results = mutableListOf<SearchResponse>()
                for (i in 0 until searchArray.length()) {
                    val item = searchArray.optJSONObject(i) ?: continue
                    val id = item.optInt("id", -1).takeIf { it != -1 } ?: continue
                    val title = item.optString("title").ifEmpty { item.optString("name") }
                    if (title.isEmpty()) continue

                    val type = item.optString("type").lowercase()
                    val isAnime = type == "anime" || item.optInt("is_anime", 0) == 1
                    val posterPath = item.optString("poster_path").takeIf { it.isNotEmpty() }

                    val (targetPath, tvType) = when {
                        type == "movie" -> "media/detail/$id/$apiKey" to TvType.Movie
                        isAnime -> "animes/show/$id/$apiKey" to TvType.Anime
                        else -> "series/show/$id/$apiKey" to TvType.TvSeries
                    }

                    val directApiUrl = "$host/$targetPath"

                    val res = if (tvType == TvType.Movie) {
                        newMovieSearchResponse(title, directApiUrl, tvType) {
                            this.posterUrl = posterPath
                        }
                    } else {
                        newTvSeriesSearchResponse(title, directApiUrl, tvType) {
                            this.posterUrl = posterPath
                        }
                    }
                    results.add(res)
                }

                if (results.isNotEmpty()) {
                    return results
                }
            } catch (e: Exception) {
                continue
            }
        }

        return emptyList()
    }

    override suspend fun load(url: String): LoadResponse? {
        val path = getCleanPath(url)

        // 1. معالجة القنوات والبث المباشر
        if (path.contains("stream/show/")) {
            val res = fetchApiParsed<MediaDetail>(path) { !it.contains("not found", true) } ?: return null
            val videosJson = res.videos?.toJson() ?: ""
            return newMovieLoadResponse(res.name ?: res.title ?: "بث مباشر", url, TvType.Live, videosJson) {
                this.posterUrl = res.posterPath
                this.plot = res.overview
            }
        }

        // 2. معالجة سلاسل الأفلام والمجموعات
        if (path.contains("collections/media/show/")) {
            val (episodes, poster) = fetchPaginatedMediaList(path)
            if (episodes.isNotEmpty()) {
                return newTvSeriesLoadResponse("سلسلة أفلام", url, TvType.Movie, episodes) {
                    this.posterUrl = poster
                }
            }
        }

        // 3. معالجة أعمال الممثلين
        if (path.contains("filmographie/detail/")) {
            val actorId = path.substringAfter("/detail/").substringBefore("/")
            val castInfo = fetchApiParsed<MediaDetail>("cast/detail/$actorId/$apiKey")
            val (episodes, _) = fetchPaginatedMediaList(path)

            if (episodes.isNotEmpty()) {
                return newTvSeriesLoadResponse(castInfo?.name ?: "أفلام الممثل", url, TvType.TvSeries, episodes) {
                    this.posterUrl = castInfo?.profilePath ?: castInfo?.posterPath
                    this.plot = castInfo?.biography ?: "أعمال ومشاركات الممثل"
                }
            }
        }

        // 4. معالجة الأفلام مع التحقق من وجود سيرفرات
        if (path.contains("media/detail/")) {
            for (host in API_HOSTS) {
                val res = try {
                    val text = app.get("$host/$path", headers = appHeaders, timeout = 12L).text
                    if (text.contains("not found", true)) null else parseJson<MediaDetail>(text)
                } catch (e: Exception) {
                    null
                }

                if (res != null && (res.title != null || res.name != null)) {
                    val videosJson = res.videos?.toJson() ?: ""
                    return newMovieLoadResponse(res.title ?: res.name ?: "", url, TvType.Movie, videosJson) {
                        this.posterUrl = res.posterPath
                        this.plot = res.overview
                        this.year = res.releaseDate?.substringBefore("-")?.toIntOrNull()
                        this.score = res.voteAverage?.let { Score.from10(it) }
                    }
                }
            }
        }

        // 5. معالجة المسلسلات والأنمي
        val isAnime = path.contains("animes/")
        val seasonEndpoint = if (isAnime) "animes/season" else "series/season"
        val mediaType = if (isAnime) TvType.Anime else TvType.TvSeries

        for (host in API_HOSTS) {
            try {
                val fullUrl = "$host/$path"
                val resText = app.get(fullUrl, headers = appHeaders, timeout = 12L).text

                // التحقق من أن العمل موجود وليس صفحة خطأ
                if (resText.contains("not found", ignoreCase = true)) {
                    continue
                }

                val res = parseJson<MediaDetail>(resText)
                if (res.seasons.isNullOrEmpty()) continue

                val episodes = mutableListOf<Episode>()

                res.seasons.parallelMap { season ->
                    val seasonId = season.id ?: return@parallelMap
                    val seasonRes = fetchApiParsed<SeasonDetail>("$seasonEndpoint/$seasonId/$apiKey")
                    
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

                if (episodes.isNotEmpty()) {
                    episodes.sortBy { ((it.season ?: 1) * 10000) + (it.episode ?: 0) }

                    return newTvSeriesLoadResponse(res.name ?: res.title ?: "", url, mediaType, episodes) {
                        this.posterUrl = res.posterPath
                        this.plot = res.overview
                        this.score = res.voteAverage?.let { Score.from10(it) }
                    }
                }
            } catch (e: Exception) {
                continue
            }
        }

        return null
    }

    private suspend fun fetchPaginatedMediaList(basePath: String, maxPages: Int = 3): Pair<List<Episode>, String?> {
        val episodes = mutableListOf<Episode>()
        var poster: String? = null

        for (host in API_HOSTS) {
            episodes.clear()
            for (page in 1..maxPages) {
                val pageUrl = "$host/$basePath?page=$page"
                val pageRes = try {
                    app.get(pageUrl, headers = appHeaders, timeout = 10L).text
                } catch (e: Exception) {
                    break
                }

                val json = try { JSONObject(pageRes) } catch (e: Exception) { break }
                val dataArray = json.optJSONArray("data") ?: break
                if (dataArray.length() == 0) break

                for (i in 0 until dataArray.length()) {
                    val m = dataArray.optJSONObject(i) ?: continue
                    val mId = m.optInt("id")
                    val mTitle = m.optString("name").ifEmpty { m.optString("title") }
                    val mPoster = m.optString("poster_path").takeIf { it.isNotEmpty() }
                    if (poster == null) poster = mPoster

                    val movieApiUrl = "$host/media/detail/$mId/$apiKey"
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

            if (episodes.isNotEmpty()) {
                break
            }
        }

        return Pair(episodes, poster)
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        var videos: List<Video> = emptyList()

        if (data.startsWith("http")) {
            val path = getCleanPath(data)
            // تجربة الرابط الاحتياطي إذا لم تكن هناك فيديوهات في الرابط الأساسي
            for (host in API_HOSTS) {
                val res = fetchApiParsed<MediaDetail>("$host/$path")
                if (!res?.videos.isNullOrEmpty()) {
                    videos = res!!.videos!!
                    break
                }
            }
        } else {
            videos = try {
                parseJson<Array<Video>>(data).toList()
            } catch (e: Exception) {
                emptyList()
            }
        }

        if (videos.isEmpty()) return false

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
            val text = fetchApiWithFallback("hosts/config") ?: return emptyList()
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
