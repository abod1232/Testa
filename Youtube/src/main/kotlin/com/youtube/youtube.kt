package com.lagradost.cloudstream3.ar.youtube

import android.content.SharedPreferences
import android.util.Log
import androidx.preference.PreferenceManager
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.mvvm.logError
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.utils.AppUtils.parseJson
import com.youtube.CustomSection
import com.youtube.YoutubeExtractor
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.json.JSONObject
import org.schabi.newpipe.extractor.InfoItem
import org.schabi.newpipe.extractor.ListExtractor
import org.schabi.newpipe.extractor.NewPipe
import org.schabi.newpipe.extractor.Page
import org.schabi.newpipe.extractor.ServiceList
import org.schabi.newpipe.extractor.channel.ChannelInfoItem
import org.schabi.newpipe.extractor.channel.tabs.ChannelTabs
import org.schabi.newpipe.extractor.downloader.Downloader
import org.schabi.newpipe.extractor.localization.Localization
import org.schabi.newpipe.extractor.playlist.PlaylistInfoItem
import org.schabi.newpipe.extractor.stream.StreamInfoItem
import java.net.URLDecoder
import java.util.Collections
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import org.schabi.newpipe.extractor.downloader.Request as NpRequest
import org.schabi.newpipe.extractor.downloader.Response as NpResponse

class YoutubeProvider(
    private val sharedPref: SharedPreferences? = null
) : MainAPI() {

    override var mainUrl = "https://www.youtube.com"
    override var name = "YouTube"
    override val hasMainPage = true
    override val supportedTypes = setOf(TvType.Movie, TvType.Live)

    override var lang: String
        get() = sharedPref?.getString("youtube_language", "ar") ?: "ar"
        set(_) {}

    companion object {
        private const val TAG = "YT_DEBUG"
        private val homeShorts: MutableList<Episode> = Collections.synchronizedList(mutableListOf())
        private val searchShorts: MutableList<Episode> = Collections.synchronizedList(mutableListOf())
    }

    private data class PagedExtractorState(
        val extractor: ListExtractor<out InfoItem>,
        val nextPage: Page
    )

    private val homePageStates = ConcurrentHashMap<String, PagedExtractorState>()
    private var searchPageState: PagedExtractorState? = null

    private val personalizedTokens = ConcurrentHashMap<String, String>()
    private var savedVisitorData: String? = null
    private var savedApiKey: String? = null
    private var savedClientVersion: String? = null

    class YouTubeInterceptor(private val prefs: SharedPreferences?) : Interceptor {
        override fun intercept(chain: Interceptor.Chain): Response {
            val requestBuilder = chain.request().newBuilder()
                .header(
                    "User-Agent",
                    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/116.0.0.0 Safari/537.36"
                )

            val cookieBuilder = StringBuilder()
            val visitor = prefs?.getString("VISITOR_INFO1_LIVE", null)
            if (!visitor.isNullOrBlank()) {
                cookieBuilder.append("VISITOR_INFO1_LIVE=$visitor; ")
            } else {
                cookieBuilder.append("VISITOR_INFO1_LIVE=_Mk3UVhY40g; ")
            }

            val authKeys = listOf("SID", "HSID", "SSID", "APISID", "SAPISID")
            authKeys.forEach { key ->
                val value = prefs?.getString(key, null)
                if (!value.isNullOrBlank()) {
                    cookieBuilder.append("$key=$value; ")
                }
            }

            val currentLang = prefs?.getString("youtube_language", "ar") ?: "ar"
            cookieBuilder.append("PREF=f6=40000000&hl=$currentLang; CONSENT=YES+fx.456722336;")

            requestBuilder.header("Cookie", cookieBuilder.toString())
            return chain.proceed(requestBuilder.build())
        }
    }

    private val ytInterceptor = YouTubeInterceptor(sharedPref)
    private class SimpleNewPipeDownloader(
        private val ytInterceptor: Interceptor
    ) : Downloader() {
        private val client = OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .addInterceptor { chain ->
                val host = chain.request().url.host
                if (host.contains("youtube.com") || host.contains("youtu.be")) {
                    ytInterceptor.intercept(chain)
                } else {
                    chain.proceed(chain.request())
                }
            }
            .build()

        override fun execute(request: NpRequest): NpResponse {
            val method = request.httpMethod()
            val data = request.dataToSend()
            val builder = Request.Builder().url(request.url())

            request.headers().forEach { (key, values) ->
                values.forEach { value ->
                    builder.addHeader(key, value)
                }
            }

            val body = when {
                data != null -> {
                    val contentType = request.headers().entries
                        .firstOrNull { it.key.equals("Content-Type", ignoreCase = true) }
                        ?.value?.firstOrNull()
                    data.toRequestBody(contentType?.toMediaTypeOrNull())
                }
                method.equals("POST", ignoreCase = true) ||
                    method.equals("PUT", ignoreCase = true) ||
                    method.equals("PATCH", ignoreCase = true) -> ByteArray(0).toRequestBody(null)
                else -> null
            }

            builder.method(method, body)

            client.newCall(builder.build()).execute().use { okResponse ->
                val bodyStr = okResponse.body?.string()
                return NpResponse(
                    okResponse.code,
                    okResponse.message,
                    okResponse.headers.toMultimap(),
                    bodyStr,
                    okResponse.request.url.toString()
                )
            }
        }
    }

    private fun ensureNewPipeInitialized() {
        if (NewPipe.getDownloader() == null) {
            Log.w(TAG, "NewPipe downloader was NULL! Initializing SimpleNewPipeDownloader...")
            NewPipe.init(SimpleNewPipeDownloader(ytInterceptor))
        } else {
            Log.d(TAG, "NewPipe downloader already initialized: ${NewPipe.getDownloader()?.javaClass?.name}")
        }
    }

    private fun getNewPipeLocalization(): Localization {
        return try {
            Localization.fromLocale(Locale(lang))
        } catch (e: Exception) {
            Log.w(TAG, "Localization fallback for lang=$lang: ${e.message}")
            Localization.DEFAULT
        }
    }

    override val mainPage: List<MainPageData>
        get() {
            val list = mutableListOf<MainPageData>()
            val isEn = lang == "en"
            if (sharedPref?.getBoolean("show_trending_home", true) == true) {
                val hasLogin = !sharedPref?.getString("SID", null).isNullOrBlank()
                val homeTitle = when {
                    hasLogin && isEn -> "Home"
                    hasLogin -> "الرئيسية"
                    isEn -> "Trending"
                    else -> "الرائج (Trending)"
                }
                list.add(MainPageData(homeTitle, "Home"))
            }

            getCustomHomepages()
                .filter { it.isEnabled }
                .forEach { section ->
                    val title = section.name.ifBlank { extractNameFromUrl(section.url) }
                    list.add(MainPageData(title, section.url))
                }

            return list
        }

    private fun getCustomHomepages(): List<CustomSection> {
        val json = sharedPref?.getString("custom_homepages_v3", "[]") ?: "[]"
        return try {
            parseJson<List<CustomSection>>(json)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse custom_homepages_v3", e)
            emptyList()
        }
    }

    private fun extractNameFromUrl(url: String): String {
        return when {
            url.contains("/@") -> "@" + url.substringAfter("/@").substringBefore("/")
            url.contains("/c/") -> url.substringAfter("/c/").substringBefore("/")
            url.contains("/channel/") -> "Channel " + url.substringAfter("/channel/").take(8)
            url.contains("list=") -> "Playlist: " + url.substringAfter("list=").take(8)
            else -> "Custom Section"
        }
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        ensureNewPipeInitialized()
        val requestData = request.data
        val results = mutableListOf<SearchResponse>()
        val seenUrls = mutableSetOf<String>()
        var hasNext = false

        Log.d(TAG, "getMainPage START -> page=$page, name=${request.name}, data=$requestData")

        if (page == 1 && requestData == "Home") {
            homeShorts.clear()
        }

        try {
            val isLoggedInHome = requestData == "Home" &&
                !sharedPref?.getString("SID", null).isNullOrBlank()

            if (isLoggedInHome) {
                Log.d(TAG, "getMainPage -> using PersonalizedHome (logged in)")
                hasNext = fetchPersonalizedHome(page, requestData, results, seenUrls)
                if (page == 1 && results.isEmpty()) {
                    Log.w(TAG, "PersonalizedHome returned 0 items, falling back to NewPipe Trending")
                    hasNext = fetchNewPipeMainSection(page, requestData, results, seenUrls)
                }
            } else {
                hasNext = fetchNewPipeMainSection(page, requestData, results, seenUrls)
                if (page == 1 && requestData == "Home" && results.isEmpty()) {
                    Log.w(TAG, "NewPipe Trending returned 0 items, falling back to fetchPersonalizedHome")
                    hasNext = fetchPersonalizedHome(page, requestData, results, seenUrls)
                }
            }
        } catch (e: Throwable) {
            Log.e(TAG, "getMainPage ERROR for data=$requestData, page=$page", e)
            logError(e)
        }

        Log.d(TAG, "getMainPage END -> data=$requestData, items=${results.size}, hasNext=$hasNext")
        return newHomePageResponse(request, results, hasNext = hasNext)
    }

    private fun fetchNewPipeMainSection(
        page: Int,
        requestData: String,
        results: MutableList<SearchResponse>,
        seenUrls: MutableSet<String>
    ): Boolean {
        if (page == 1) {
            homePageStates.remove(requestData)

            val extractor: ListExtractor<out InfoItem> = when {
                requestData == "Home" -> {
                    Log.d(TAG, "fetchNewPipeMainSection -> Kiosk defaultKioskExtractor")
                    ServiceList.YouTube.kioskList.defaultKioskExtractor
                }
                requestData.contains("list=") -> {
                    val pUrl = normalizePlaylistUrl(requestData)
                    Log.d(TAG, "fetchNewPipeMainSection -> PlaylistExtractor: $pUrl")
                    ServiceList.YouTube.getPlaylistExtractor(pUrl)
                }
                else -> {
                    val channelUrl = normalizeChannelUrl(requestData)
                    Log.d(TAG, "fetchNewPipeMainSection -> ChannelExtractor: $channelUrl")
                    val channelExtractor = ServiceList.YouTube.getChannelExtractor(channelUrl)
                    channelExtractor.forceLocalization(getNewPipeLocalization())
                    channelExtractor.fetchPage()
                    val videosTab = channelExtractor.tabs.firstOrNull {
                        it.contentFilters.contains(ChannelTabs.VIDEOS)
                    } ?: channelExtractor.tabs.firstOrNull()
                    if (videosTab == null) {
                        Log.w(TAG, "No tabs found for channel: $channelUrl")
                        return false
                    }
                    ServiceList.YouTube.getChannelTabExtractor(videosTab)
                }
            }

            extractor.forceLocalization(getNewPipeLocalization())
            extractor.fetchPage()
            val initial = extractor.initialPage
            if (initial.errors.isNotEmpty()) {
                Log.w(TAG, "NewPipe initialPage partial errors (${initial.errors.size}): ${initial.errors.firstOrNull()?.message}")
            }
            Log.d(TAG, "fetchNewPipeMainSection page 1 raw items=${initial.items.size}")

            initial.items.forEach { item ->
                convertInfoItem(item, isSearch = false, seenUrls)?.let { results.add(it) }
            }

            val nextPage = initial.nextPage
            return if (initial.hasNextPage() && nextPage != null) {
                homePageStates[requestData] = PagedExtractorState(extractor, nextPage)
                true
            } else {
                false
            }
        } else {
            val state = homePageStates[requestData]
            if (state == null) {
                Log.w(TAG, "No saved PagedExtractorState for $requestData at page=$page")
                return false
            }
            val next = state.extractor.getPage(state.nextPage)
            Log.d(TAG, "fetchNewPipeMainSection page $page raw items=${next.items.size}")
            next.items.forEach { item ->
                convertInfoItem(item, isSearch = false, seenUrls)?.let { results.add(it) }
            }

            val newNextPage = next.nextPage
            return if (next.hasNextPage() && newNextPage != null) {
                homePageStates[requestData] = PagedExtractorState(state.extractor, newNextPage)
                true
            } else {
                homePageStates.remove(requestData)
                false
            }
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        return search(query, 1)?.items ?: emptyList()
    }

    override suspend fun search(query: String, page: Int): SearchResponseList? {
        ensureNewPipeInitialized()
        val results = mutableListOf<SearchResponse>()
        val seenUrls = mutableSetOf<String>()

        if (page == 1) {
            searchShorts.clear()
            searchPageState = null
        }

        val playlistTag = sharedPref?.getString("playlist_search_tag", "{p}") ?: "{p}"
        var actualQuery = query
        var playlistMode = false
        if (query.contains(playlistTag)) {
            actualQuery = query.replace(playlistTag, "").trim()
            playlistMode = true
        }

        Log.d(TAG, "search START -> query='$actualQuery', page=$page, playlistMode=$playlistMode")

        return try {
            val hasNext: Boolean
            if (page == 1) {
                val filters = if (playlistMode) listOf("playlists") else emptyList()
                val searchExtractor = ServiceList.YouTube.getSearchExtractor(actualQuery, filters, "")
                searchExtractor.forceLocalization(getNewPipeLocalization())
                searchExtractor.fetchPage()

                val initial = searchExtractor.initialPage
                if (initial.errors.isNotEmpty()) {
                    Log.w(TAG, "search partial errors (${initial.errors.size}): ${initial.errors.firstOrNull()?.message}")
                }
                Log.d(TAG, "search page 1 raw items=${initial.items.size}")

                initial.items.forEach { item ->
                    if (!playlistMode || item is PlaylistInfoItem) {
                        convertInfoItem(item, isSearch = true, seenUrls)?.let { results.add(it) }
                    }
                }

                val nextPage = initial.nextPage
                if (initial.hasNextPage() && nextPage != null) {
                    searchPageState = PagedExtractorState(searchExtractor, nextPage)
                    hasNext = true
                } else {
                    hasNext = false
                }
            } else {
                val state = searchPageState
                if (state != null) {
                    val next = state.extractor.getPage(state.nextPage)
                    Log.d(TAG, "search page $page raw items=${next.items.size}")
                    next.items.forEach { item ->
                        if (!playlistMode || item is PlaylistInfoItem) {
                            convertInfoItem(item, isSearch = true, seenUrls)?.let { results.add(it) }
                        }
                    }

                    val newNextPage = next.nextPage
                    if (next.hasNextPage() && newNextPage != null) {
                        searchPageState = PagedExtractorState(state.extractor, newNextPage)
                        hasNext = true
                    } else {
                        searchPageState = null
                        hasNext = false
                    }
                } else {
                    Log.w(TAG, "searchPageState is null at page=$page")
                    hasNext = false
                }
            }

            Log.d(TAG, "search END -> returned=${results.size}, hasNext=$hasNext")
            newSearchResponseList(results, hasNext)
        } catch (e: Throwable) {
            Log.e(TAG, "search ERROR for query='$actualQuery', page=$page", e)
            logError(e)
            newSearchResponseList(emptyList(), false)
        }
    }

    override suspend fun load(url: String): LoadResponse {
        ensureNewPipeInitialized()
        Log.d(TAG, "load START -> url=$url")

        try {
            if (url.contains("/shorts/")) {
                val videoId = url.extractYoutubeId() ?: "video"
                val useSearchList = url.contains("&ctx=search")
                val sourceList = if (useSearchList) searchShorts else homeShorts
                val targetEpisodes = synchronized(sourceList) { sourceList.toMutableList() }

                var currentEp = targetEpisodes.find { it.data.extractYoutubeId() == videoId }
                if (currentEp == null) {
                    val fallbackEp = newEpisode(url) {
                        this.name = "Shorts Video"
                        this.posterUrl = buildThumbnailFromId(videoId)
                        this.episode = targetEpisodes.size + 1
                    }
                    targetEpisodes.add(0, fallbackEp)
                    currentEp = fallbackEp
                }

                Log.d(TAG, "load Shorts Feed -> count=${targetEpisodes.size}")
                return newTvSeriesLoadResponse("Shorts Feed", url, TvType.TvSeries, targetEpisodes) {
                    this.posterUrl = currentEp.posterUrl ?: buildThumbnailFromId(videoId)
                    this.plot = "قائمة تشغيل تلقائية من الشورتس (${targetEpisodes.size} فيديو)"
                    this.tags = listOf("Shorts", "Feed")
                }
            }

            val maxPages = sharedPref?.getInt("channel_pages_limit", 6) ?: 6
            if (url.contains("/@") || url.contains("/channel/") || url.contains("/c/") || url.contains("/user/")) {
                val cleanChannelUrl = normalizeChannelUrl(url)
                Log.d(TAG, "load Channel -> $cleanChannelUrl, maxPages=$maxPages")
                val channelExtractor = ServiceList.YouTube.getChannelExtractor(cleanChannelUrl)
                channelExtractor.forceLocalization(getNewPipeLocalization())
                channelExtractor.fetchPage()

                val title = channelExtractor.name.ifBlank { "YouTube Channel" }
                val poster = channelExtractor.avatars.lastOrNull()?.url
                val subCount = if (channelExtractor.subscriberCount >= 0) {
                    formatViews(channelExtractor.subscriberCount)
                } else "N/A"

                val allEpisodes = mutableListOf<Episode>()
                val videosTab = channelExtractor.tabs.firstOrNull {
                    it.contentFilters.contains(ChannelTabs.VIDEOS)
                } ?: channelExtractor.tabs.firstOrNull()

                if (videosTab != null) {
                    val tabExtractor = ServiceList.YouTube.getChannelTabExtractor(videosTab)
                    tabExtractor.forceLocalization(getNewPipeLocalization())
                    tabExtractor.fetchPage()

                    var currentPage = tabExtractor.initialPage
                    var pagesFetched = 1

                    fun collectTabItems(items: List<InfoItem>) {
                        items.filterIsInstance<StreamInfoItem>().forEach { item ->
                            val vId = item.url.extractYoutubeId() ?: return@forEach
                            val views = if (item.viewCount >= 0) formatViews(item.viewCount) else null
                            val date = item.textualUploadDate
                            allEpisodes.add(newEpisode("$mainUrl/watch?v=$vId") {
                                this.name = item.name
                                this.posterUrl = item.thumbnails.lastOrNull()?.url ?: buildThumbnailFromId(vId)
                                this.description = listOfNotNull(views, date).joinToString(" • ").ifBlank { null }
                            })
                        }
                    }

                    collectTabItems(currentPage.items)

                    while (currentPage.hasNextPage() && pagesFetched < maxPages) {
                        val next = currentPage.nextPage ?: break
                        pagesFetched++
                        currentPage = tabExtractor.getPage(next)
                        collectTabItems(currentPage.items)
                    }
                } else {
                    Log.w(TAG, "load Channel -> no tabs found for $cleanChannelUrl")
                }

                Log.d(TAG, "load Channel END -> title='$title', episodes=${allEpisodes.size}")
                return newTvSeriesLoadResponse(title, url, TvType.TvSeries, allEpisodes) {
                    this.posterUrl = poster
                    this.plot = "Channel: $title\nSubscribers: $subCount\nVideos Fetched: ${allEpisodes.size}"
                    this.tags = listOf(title, "Channel")
                }
            }
            if (url.contains("list=")) {
                val playlistUrl = normalizePlaylistUrl(url)
                Log.d(TAG, "load Playlist -> $playlistUrl, maxPages=$maxPages")
                val playlistExtractor = ServiceList.YouTube.getPlaylistExtractor(playlistUrl)
                playlistExtractor.forceLocalization(getNewPipeLocalization())
                playlistExtractor.fetchPage()

                val title = playlistExtractor.name.ifBlank { "YouTube Playlist" }
                val author = playlistExtractor.uploaderName?.ifBlank { "Unknown Channel" } ?: "Unknown Channel"
                val poster = playlistExtractor.thumbnails.lastOrNull()?.url

                val episodes = mutableListOf<Episode>()
                var currentPage = playlistExtractor.initialPage
                var pagesFetched = 1

                fun collectPlaylistItems(items: List<StreamInfoItem>) {
                    items.forEach { item ->
                        val vId = item.url.extractYoutubeId() ?: return@forEach
                        val epNum = episodes.size + 1
                        val durationStr = formatDuration(item.duration)
                        episodes.add(newEpisode("$mainUrl/watch?v=$vId") {
                            this.name = item.name.ifBlank { "Episode $epNum" }
                            this.episode = epNum
                            this.posterUrl = item.thumbnails.lastOrNull()?.url ?: buildThumbnailFromId(vId)
                            this.description = if (durationStr != null) "Duration: $durationStr" else null
                        })
                    }
                }

                collectPlaylistItems(currentPage.items)

                while (currentPage.hasNextPage() && pagesFetched < maxPages) {
                    val next = currentPage.nextPage ?: break
                    pagesFetched++
                    currentPage = playlistExtractor.getPage(next)
                    collectPlaylistItems(currentPage.items)
                }

                Log.d(TAG, "load Playlist END -> title='$title', episodes=${episodes.size}")
                return newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
                    this.posterUrl = poster ?: episodes.firstOrNull()?.posterUrl
                    this.plot = "Channel: $author\nVideos: ${episodes.size}"
                    this.tags = listOf(author)
                }
            }
            val videoId = url.extractYoutubeId() ?: throw ErrorLoadingException("Invalid YouTube URL: $url")
            val watchUrl = "$mainUrl/watch?v=$videoId"
            Log.d(TAG, "load Video -> $watchUrl")

            val streamExtractor = ServiceList.YouTube.getStreamExtractor(watchUrl)
            streamExtractor.forceLocalization(getNewPipeLocalization())
            streamExtractor.fetchPage()

            val title = streamExtractor.name.ifBlank { "YouTube Video" }
            val poster = streamExtractor.thumbnails.lastOrNull()?.url ?: buildThumbnailFromId(videoId)
            val uploadDate = streamExtractor.textualUploadDate
            val descContent = streamExtractor.description?.content ?: ""
            val plot = buildString {
                if (!uploadDate.isNullOrBlank()) append("$uploadDate\n\n")
                append(descContent)
            }

            val channelName = streamExtractor.uploaderName ?: ""
            val channelUrl = streamExtractor.uploaderUrl ?: ""
            val channelAvatar = streamExtractor.uploaderAvatars.lastOrNull()?.url

            val recommendations = mutableListOf<SearchResponse>()
            val seenRecUrls = mutableSetOf<String>()

            if (channelName.isNotBlank() && channelUrl.isNotBlank()) {
                recommendations.add(
                    newMovieSearchResponse("Channel: $channelName", channelUrl, TvType.Live) {
                        this.posterUrl = channelAvatar
                    }
                )
            }

            streamExtractor.relatedItems?.items?.forEach { item ->
                val isShort = (item is StreamInfoItem && item.isShortFormContent) || item.url.contains("/shorts/")
                if (!isShort) {
                    convertInfoItem(item, isSearch = false, seenUrls = seenRecUrls)?.let {
                        recommendations.add(it)
                    }
                }
            }

            Log.d(TAG, "load Video END -> title='$title', recs=${recommendations.size}")
            return newMovieLoadResponse(title, url, TvType.Movie, videoId) {
                this.posterUrl = poster
                this.plot = plot
                if (channelName.isNotBlank()) {
                    this.tags = listOf(channelName)
                }
                this.recommendations = recommendations
            }
        } catch (e: Throwable) {
            Log.e(TAG, "load ERROR for url=$url", e)
            logError(e)
            throw e
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        ensureNewPipeInitialized()
        val videoId = data.extractYoutubeId() ?: data
        val fullUrl = "$mainUrl/watch?v=$videoId"
        val context = CloudStreamApp.context
        val playerType = if (context != null) {
            PreferenceManager.getDefaultSharedPreferences(context)
                .getString("youtube_player_type", "advanced")
        } else {
            "advanced"
        }

        Log.d(TAG, "loadLinks START -> videoId=$videoId, playerType=$playerType")

        try {
            if (playerType == "classic") {
                loadExtractor(fullUrl, subtitleCallback, callback)
            } else {
                YoutubeExtractor().getUrl(fullUrl, null, subtitleCallback, callback)
            }
        } catch (e: Throwable) {
            Log.e(TAG, "loadLinks extractor ERROR for $fullUrl", e)
            logError(e)
        }

        try {
            val apiUrl = "$mainUrl/youtubei/v1/player"
            val payload = mapOf(
                "context" to mapOf(
                    "client" to mapOf(
                        "clientName" to "WEB",
                        "clientVersion" to "2.20240725.01.00",
                        "hl" to lang,
                        "gl" to "US"
                    )
                ),
                "videoId" to videoId
            )

            val responseText = app.post(apiUrl, json = payload, interceptor = ytInterceptor).text
            if (responseText.isNotBlank()) {
                val root = JSONObject(responseText)
                val captionTracks = root.optJSONObject("captions")
                    ?.optJSONObject("playerCaptionsTracklistRenderer")
                    ?.optJSONArray("captionTracks")

                Log.d(TAG, "loadLinks captions found=${captionTracks?.length() ?: 0}")

                if (captionTracks != null && captionTracks.length() > 0) {
                    val seenSubs = mutableSetOf<String>()
                    var baseTrack = captionTracks.optJSONObject(0)

                    for (i in 0 until captionTracks.length()) {
                        val t = captionTracks.optJSONObject(i)
                        if (t.optString("languageCode") == "en") {
                            baseTrack = t
                            break
                        }
                    }

                    val baseUrl = baseTrack?.optString("baseUrl", "").orEmpty()
                    val baseLangCode = baseTrack?.optString("languageCode", "original")?.lowercase() ?: "original"

                    for (i in 0 until captionTracks.length()) {
                        val track = captionTracks.optJSONObject(i)
                        val trackName = track.optJSONObject("name")?.optString("simpleText") ?: ""
                        val trackLang = track.optString("languageCode")
                        val trackUrl = track.optString("baseUrl")

                        val vttUrl = "$trackUrl&fmt=vtt"
                        if (trackUrl.isNotBlank() && seenSubs.add(vttUrl)) {
                            subtitleCallback(SubtitleFile("$trackName ($trackLang)", vttUrl))
                        }
                    }

                    if (baseUrl.isNotEmpty()) {
                        val autoLangs = listOf(
                            "ar", "en", "fr", "es", "de", "tr", "ru", "ja", "ko", "zh-Hans",
                            "it", "pt", "hi", "id", "ur", "fa", "nl", "pl", "sv", "uk"
                        )

                        for (targetLang in autoLangs) {
                            if (targetLang.equals(baseLangCode, true)) continue
                            val autoUrl = "$baseUrl&fmt=vtt&tlang=$targetLang"
                            if (seenSubs.add(autoUrl)) {
                                subtitleCallback(SubtitleFile("$baseLangCode → $targetLang", autoUrl))
                            }
                        }
                    }
                }
            }
        } catch (e: Throwable) {
            Log.e(TAG, "loadLinks captions ERROR for $videoId", e)
        }

        return true
    }

    private fun convertInfoItem(
        item: InfoItem,
        isSearch: Boolean,
        seenUrls: MutableSet<String>
    ): SearchResponse? {
        return when (item) {
            is StreamInfoItem -> {
                val videoId = item.url.extractYoutubeId() ?: return null
                if (!seenUrls.add(videoId)) return null

                val title = item.name.ifBlank { "Video" }
                val views = if (item.viewCount >= 0) formatViews(item.viewCount) else "N/A"
                val channel = item.uploaderName ?: ""
                val isShort = item.isShortFormContent || item.url.contains("/shorts/")

                if (isShort) {
                    val poster = item.thumbnails.lastOrNull()?.url ?: "https://i.ytimg.com/vi/$videoId/oar2.jpg"
                    val currentList = if (isSearch) searchShorts else homeShorts
                    val contextTag = if (isSearch) "&ctx=search" else "&ctx=home"
                    val finalUrl = "$mainUrl/shorts/$videoId$contextTag"

                    val epNum = synchronized(currentList) {
                        val existing = currentList.indexOfFirst { it.data == finalUrl }
                        if (existing >= 0) {
                            existing + 1
                        } else {
                            val num = currentList.size + 1
                            currentList.add(newEpisode(finalUrl) {
                                this.name = title
                                this.posterUrl = poster
                                this.episode = num
                            })
                            num
                        }
                    }

                    newMovieSearchResponse("#$epNum {$views} $title", finalUrl, TvType.Movie) {
                        this.posterUrl = poster
                    }
                } else {
                    val poster = item.thumbnails.lastOrNull()?.url ?: buildThumbnailFromId(videoId)
                    val finalTitle = if (channel.isNotBlank()) "{$channel | $views} $title" else "{$views} $title"
                    newMovieSearchResponse(finalTitle, "$mainUrl/watch?v=$videoId", TvType.Movie) {
                        this.posterUrl = poster
                    }
                }
            }

            is PlaylistInfoItem -> {
                val playlistUrl = item.url ?: return null
                if (!seenUrls.add(playlistUrl)) return null
                val title = item.name.ifBlank { "Playlist" }
                val count = if (item.streamCount >= 0) " (${item.streamCount})" else ""
                val poster = item.thumbnails.lastOrNull()?.url
                newTvSeriesSearchResponse("$title$count", playlistUrl, TvType.TvSeries) {
                    this.posterUrl = poster
                }
            }

            is ChannelInfoItem -> {
                val channelUrl = item.url ?: return null
                if (!seenUrls.add(channelUrl)) return null
                val title = item.name.ifBlank { "Channel" }
                val stats = if (item.subscriberCount >= 0) " (${formatViews(item.subscriberCount)})" else ""
                val poster = item.thumbnails.lastOrNull()?.url
                newMovieSearchResponse("$title$stats", channelUrl, TvType.Live) {
                    this.posterUrl = poster
                }
            }

            else -> null
        }
    }

    private fun normalizeChannelUrl(url: String): String {
        var clean = url.substringBefore("?").trimEnd('/')
        val suffixes = listOf("/videos", "/shorts", "/streams", "/playlists", "/featured", "/about")
        for (suffix in suffixes) {
            if (clean.endsWith(suffix)) {
                clean = clean.removeSuffix(suffix)
                break
            }
        }
        return clean
    }

    private fun normalizePlaylistUrl(url: String): String {
        val listId = url.substringAfter("list=").substringBefore("&")
        return "$mainUrl/playlist?list=$listId"
    }

    private fun formatViews(count: Long): String {
        if (count < 0) return "N/A"
        return when {
            count < 1000 -> count.toString()
            count < 1_000_000 -> String.format(Locale.US, "%.1fK", count / 1000.0).replace(".0K", "K")
            count < 1_000_000_000 -> String.format(Locale.US, "%.1fM", count / 1_000_000.0).replace(".0M", "M")
            else -> String.format(Locale.US, "%.1fB", count / 1_000_000_000.0).replace(".0B", "B")
        }
    }

    private fun formatViews(viewText: String?): String {
        if (viewText.isNullOrBlank()) return "N/A"
        if (viewText.any { it in listOf('K', 'M', 'B', 'k', 'm', 'b') } && viewText.length < 15) {
            return viewText.split("view")[0].split("مشاهدة")[0].trim()
        }
        val digits = viewText.filter { it.isDigit() }
        if (digits.isBlank()) return viewText
        return digits.toLongOrNull()?.let { formatViews(it) } ?: viewText
    }

    private fun formatDuration(seconds: Long): String? {
        if (seconds <= 0) return null
        val h = seconds / 3600
        val m = (seconds % 3600) / 60
        val s = seconds % 60
        return if (h > 0) String.format(Locale.US, "%d:%02d:%02d", h, m, s)
        else String.format(Locale.US, "%d:%02d", m, s)
    }

    private fun buildThumbnailFromId(videoId: String?): String? {
        if (videoId.isNullOrBlank()) return null
        return "https://i.ytimg.com/vi/$videoId/hqdefault.jpg"
    }

    private fun String.extractYoutubeId(): String? {
        val regex = Regex("""(?:v=|/videos/|embed/|youtu\.be/|shorts/)([A-Za-z0-9_-]{11})""")
        return regex.find(this)?.groupValues?.getOrNull(1)
    }

    private suspend fun fetchPersonalizedHome(
        page: Int,
        requestData: String,
        results: MutableList<SearchResponse>,
        seenIds: MutableSet<String>
    ): Boolean {
        var nextContinuation: String? = null

        if (page == 1) {
            personalizedTokens.remove(requestData)
            val html = app.get(mainUrl, interceptor = ytInterceptor).text
            Log.d(TAG, "fetchPersonalizedHome HTML length=${html.length}")
            savedApiKey = Regex(""""INNERTUBE_API_KEY"\s*:\s*"([^"]+)"""").find(html)?.groupValues?.getOrNull(1)
            savedClientVersion = Regex(""""INNERTUBE_CLIENT_VERSION"\s*:\s*"([^"]+)"""").find(html)?.groupValues?.getOrNull(1)
                ?: "2.20240725.01.00"
            savedVisitorData = Regex(""""VISITOR_DATA"\s*:\s*"([^"]+)"""").find(html)?.groupValues?.getOrNull(1)

            val initialMatch = Regex("""(?:var ytInitialData|window\["ytInitialData"\])\s*=\s*(\{.*\});""", RegexOption.DOT_MATCHES_ALL)
                .find(html)?.groupValues?.getOrNull(1)
            val initialData = initialMatch?.let { runCatching { parseJson<Map<String, Any>>(it) }.getOrNull() }

            if (initialData != null) {
                parsePersonalizedRecursive(initialData, results, seenIds)
                nextContinuation = findTokenRecursive(initialData)
                Log.d(TAG, "fetchPersonalizedHome page 1 parsed=${results.size}, hasToken=${!nextContinuation.isNullOrBlank()}")
            } else {
                Log.w(TAG, "fetchPersonalizedHome failed to extract ytInitialData")
            }
        } else {
            val tokenToUse = personalizedTokens[requestData]
            if (!tokenToUse.isNullOrBlank() && !savedApiKey.isNullOrBlank()) {
                val decodedToken = URLDecoder.decode(tokenToUse, "UTF-8")
                val apiUrl = "$mainUrl/youtubei/v1/browse?key=$savedApiKey"
                val payload = mapOf(
                    "context" to mapOf(
                        "client" to mapOf(
                            "visitorData" to (savedVisitorData ?: ""),
                            "clientName" to "WEB",
                            "clientVersion" to (savedClientVersion ?: "2.20240725.01.00"),
                            "platform" to "DESKTOP"
                        )
                    ),
                    "continuation" to decodedToken
                )
                val headers = mapOf(
                    "X-Youtube-Client-Name" to "WEB",
                    "X-Youtube-Client-Version" to (savedClientVersion ?: ""),
                    "Origin" to mainUrl,
                    "Referer" to mainUrl
                )
                val response = app.post(apiUrl, json = payload, headers = headers, interceptor = ytInterceptor)
                    .parsedSafe<Map<String, Any>>()
                if (response != null) {
                    val actions = response["onResponseReceivedActions"]
                        ?: response["onResponseReceivedCommands"]
                        ?: response["continuationContents"]
                        ?: response
                    parsePersonalizedRecursive(actions, results, seenIds)
                    nextContinuation = findTokenRecursive(response)
                    Log.d(TAG, "fetchPersonalizedHome page $page parsed=${results.size}")
                }
            }
        }

        if (!nextContinuation.isNullOrBlank()) {
            personalizedTokens[requestData] = nextContinuation
            return true
        } else {
            personalizedTokens.remove(requestData)
            return false
        }
    }

    private fun Map<*, *>.getMapKey(key: String): Map<*, *>? = this[key] as? Map<*, *>
    private fun Map<*, *>.getListKey(key: String): List<Map<*, *>>? =
        (this[key] as? List<*>)?.filterIsInstance<Map<*, *>>()
    private fun Map<*, *>.getString(key: String): String? = this[key] as? String

    private fun getText(obj: Any?): String {
        if (obj == null) return ""
        if (obj is String) return obj
        if (obj is Map<*, *>) {
            return obj.getString("simpleText")
                ?: obj.getString("text")
                ?: obj.getString("content")
                ?: obj.getString("label")
                ?: obj.getListKey("runs")?.joinToString("") { it.getString("text") ?: "" }
                ?: obj.getMapKey("text")?.let { getText(it) }
                ?: ""
        }
        return ""
    }

    private fun parsePersonalizedRecursive(
        data: Any?,
        outList: MutableList<SearchResponse>,
        seenIds: MutableSet<String>
    ) {
        if (data is Map<*, *>) {
            val videoData = data.getMapKey("videoRenderer")
                ?: data.getMapKey("gridVideoRenderer")
            if (videoData != null) {
                val videoId = videoData.getString("videoId")
                if (!videoId.isNullOrBlank() && seenIds.add(videoId)) {
                    val title = getText(videoData.getMapKey("title")).ifBlank { "Video" }
                    val viewText = getText(videoData.getMapKey("viewCountText"))
                        .ifBlank { getText(videoData.getMapKey("shortViewCountText")) }
                    val channel = getText(videoData.getMapKey("ownerText"))
                        .ifBlank { getText(videoData.getMapKey("shortBylineText")) }
                    val views = formatViews(viewText)
                    val finalTitle = if (channel.isNotBlank()) "{$channel | $views} $title" else "{$views} $title"
                    val thumbs = videoData.getMapKey("thumbnail")?.getListKey("thumbnails")
                    var poster = thumbs?.lastOrNull()?.getString("url")
                    if (poster?.startsWith("//") == true) poster = "https:$poster"
                    if (poster.isNullOrBlank()) poster = buildThumbnailFromId(videoId)

                    outList.add(newMovieSearchResponse(finalTitle, "$mainUrl/watch?v=$videoId", TvType.Movie) {
                        this.posterUrl = poster
                    })
                }
                return
            }

            val lockup = data.getMapKey("lockupViewModel")
            if (lockup != null) {
                val videoId = lockup.getString("contentId")
                    ?: lockup.getMapKey("content")?.getString("videoId")
                if (!videoId.isNullOrBlank() && seenIds.add(videoId)) {
                    val title = getText(lockup.getMapKey("metadata")?.getMapKey("lockupMetadataViewModel")?.getMapKey("title"))
                        .ifBlank { "YouTube Video" }
                    val isShorts = lockup.getMapKey("content")?.containsKey("shortsLockupViewModel") == true
                    if (isShorts) {
                        val finalUrl = "$mainUrl/shorts/$videoId&ctx=home"
                        val poster = "https://i.ytimg.com/vi/$videoId/oar2.jpg"
                        val epNum = synchronized(homeShorts) {
                            val idx = homeShorts.indexOfFirst { it.data == finalUrl }
                            if (idx >= 0) idx + 1 else {
                                val num = homeShorts.size + 1
                                homeShorts.add(newEpisode(finalUrl) {
                                    this.name = title
                                    this.posterUrl = poster
                                    this.episode = num
                                })
                                num
                            }
                        }
                        outList.add(newMovieSearchResponse("#$epNum [Shorts] $title", finalUrl, TvType.Movie) {
                            this.posterUrl = poster
                        })
                    } else {
                        val poster = buildThumbnailFromId(videoId)
                        outList.add(newMovieSearchResponse(title, "$mainUrl/watch?v=$videoId", TvType.Movie) {
                            this.posterUrl = poster
                        })
                    }
                }
                return
            }

            data.values.forEach { v ->
                if (v is Map<*, *> || v is List<*>) {
                    parsePersonalizedRecursive(v, outList, seenIds)
                }
            }
        } else if (data is List<*>) {
            data.forEach { item -> parsePersonalizedRecursive(item, outList, seenIds) }
        }
    }

    private fun findTokenRecursive(data: Any?): String? {
        if (data is Map<*, *>) {
            if (data.containsKey("continuationCommand")) {
                return (data["continuationCommand"] as? Map<*, *>)?.get("token") as? String
            }
            for (v in data.values) {
                val t = findTokenRecursive(v)
                if (t != null) return t
            }
        } else if (data is List<*>) {
            for (i in data) {
                val t = findTokenRecursive(i)
                if (t != null) return t
            }
        }
        return null
    }
}
