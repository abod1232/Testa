package com.lagradost.cloudstream3.ar

import android.util.Base64
import android.util.Log
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.mvvm.logError
import com.lagradost.cloudstream3.network.CloudflareKiller
import com.lagradost.cloudstream3.utils.*
import okhttp3.Interceptor
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element

class WecimaProvider : MainAPI() {
    override var mainUrl = "https://wecima.lifestyle"
    override var name = "We Cima"
    override val hasMainPage = true
    override var lang = "ar"
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries)

    private val logTag = "WecimaProvider1"

    @Volatile
    private var resolvedSearchBase: String? = null

    private suspend fun resolveSearchBase(): String {
        resolvedSearchBase?.let { return it }

        val resp = app.get(
            "$mainUrl/search",
            allowRedirects = true,
            interceptor = cloudflareKiller
        )

        val finalUrl = resp.url.substringBefore("/search")
        resolvedSearchBase = finalUrl

        Log.d(logTag, "✅ Resolved search base -> $finalUrl")
        return finalUrl
    }

    @Volatile
    private var currentCfCookie: String? =
        "cf_clearance=ED0NVzmNRSWM57kanLn3lwlIaUBtZI2vPk1ZAP6zyfA-1763125072-1.2.1.1-eWAxOeBZN.zpvnXMgT46AJocpEalzqZ3WXlNZzjhIhJEvbNkRhY1Mz84sv6onyU7zfGBygnr3XbAw955PxJHjnRFUMqcp6o0rbp5wT4hJMMFiUaOSABpAAx.9G7sYlmslO5bpns2BZxvxUlV3onCRMAQY7lUHW.MCZCH10MtRNaPKsDmxvtZo14sQDLEXyu8tEDOAkjElHCHAW3iyseTRNN2j0Zd7ceblYh28.E7PwA;"

    private val cloudflareKiller by lazy { CloudflareKiller() }

    private fun getInterceptor(): Interceptor? {
        return if (currentCfCookie == null) cloudflareKiller else null
    }

    private fun getHeaders(isPost: Boolean = false): Map<String, String> {
        val baseHeaders = if (isPost) postHeaders else standardHeaders
        return currentCfCookie?.let {
            baseHeaders.plus("Cookie" to it)
        } ?: baseHeaders
    }

    private fun parseCfClearance(headers: okhttp3.Headers) {
        headers["Set-Cookie"]?.let { cookieHeader ->
            val newCookie = cookieHeader.split(';').find { it.trim().startsWith("cf_clearance=") }
            if (newCookie != null) {
                Log.d(logTag, "CloudflareKiller obtained a new cookie. Saving it for future requests.")
                currentCfCookie = newCookie
            }
        }
    }

    override val mainPage = mainPageOf(
        "$mainUrl/seriestv" to "أحدث المسلسلات",
        "$mainUrl/movies" to "أحدث الأفلام",
        "$mainUrl/category/arabic-movies" to "أفلام عربية",
        "$mainUrl/category/foreign-movies" to "أفلام أجنبية",
        "$mainUrl/category/arabic-series" to "مسلسلات عربية",
        "$mainUrl/category/foreign-series" to "مسلسلات أجنبية"
    )

    override var sequentialMainPage = true

    private val standardHeaders = mapOf(
        "User-Agent" to "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/142.0.0.0 Mobile Safari/537.36",
        "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,image/apng,*/*;q=0.8",
        "Accept-Language" to "ar-EG,ar;q=0.9,en-US;q=0.8,en;q=0.7",
        "Referer" to "$mainUrl/"
    )

    private val postHeaders = standardHeaders.plus(
        mapOf(
            "Accept" to "application/json, text/javascript, */*; q=0.01",
            "X-Requested-With" to "XMLHttpRequest",
            "Content-Type" to "application/x-www-form-urlencoded; charset=UTF-8"
        )
    )

    private suspend fun httpGet(url: String, referer: String? = null): Document {
        if (currentCfCookie != null) {
            try {
                Log.d(logTag, "Attempting GET with current cookie...")
                return app.get(
                    url,
                    referer = referer ?: mainUrl,
                    headers = getHeaders(isPost = false),
                    interceptor = getInterceptor()
                ).document
            } catch (e: Exception) {
                Log.w(logTag, "GET with current cookie failed. It has likely expired. Error: ${e.message}")
                currentCfCookie = null
            }
        }

        Log.d(logTag, "Executing GET with CloudflareKiller to get a new session...")
        val response = app.get(
            url,
            referer = referer ?: mainUrl,
            headers = getHeaders(isPost = false),
            interceptor = getInterceptor()
        )

        parseCfClearance(response.headers)
        return response.document
    }

    private suspend fun httpPost(url: String, data: Map<String, String>, referer: String? = null): String {
        if (currentCfCookie != null) {
            try {
                Log.d(logTag, "Attempting POST with current cookie...")
                return app.post(
                    url,
                    data = data,
                    referer = referer ?: mainUrl,
                    headers = getHeaders(isPost = true),
                    interceptor = getInterceptor()
                ).text
            } catch (e: Exception) {
                Log.w(logTag, "POST with current cookie failed. It has likely expired. Error: ${e.message}")
                currentCfCookie = null
            }
        }

        Log.d(logTag, "Executing POST with CloudflareKiller to get a new session...")
        val response = app.post(
            url,
            data = data,
            referer = referer ?: mainUrl,
            headers = getHeaders(isPost = true),
            interceptor = getInterceptor()
        )
        parseCfClearance(response.headers)
        return response.text
    }

    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse {
        val url = if (page > 1) "${request.data.trimEnd('/')}/page/$page/" else request.data
        val document = httpGet(url)
        val home = document.select("div#MainFiltar div.GridItem").mapNotNull {
            it.toSearchResult()
        }
        return newHomePageResponse(request.name, home)
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val anchor = this.selectFirst("a") ?: return null
        val href = fixUrl(anchor.attr("href"))

        val titleElement = this.selectFirst("h2, strong")
        val year = titleElement?.selectFirst("span.year")?.text()?.let {
            Regex("(\\d{4})").find(it)?.groupValues?.get(1)?.toIntOrNull()
        }
        val title = anchor.attr("title").ifBlank {
            titleElement?.text()
        }?.trim() ?: return null

        val posterSpan = this.selectFirst("span.BG--GridItem")
        val styleAttr = posterSpan?.attr("data-lazy-style")?.ifBlank { null }
            ?: posterSpan?.attr("style")?.ifBlank { null }

        val rawPosterUrl = posterSpan?.attr("data-src")?.ifBlank { null }
            ?: styleAttr?.let { style ->
                Regex("url\\(['\"]?(.*?)['\"]?\\)").find(style)?.groupValues?.get(1)
            }?.trim()?.ifBlank { null }

        val posterUrl = rawPosterUrl?.let { fixUrl(it) }

        val isSeries = this.selectFirst(".Episode--number") != null ||
            title.contains("مسلسل") ||
            title.contains("الحلقة")

        val type = if (isSeries) TvType.TvSeries else TvType.Movie

        return newMovieSearchResponse(title, href, type) {
            this.posterUrl = posterUrl
            this.year = year
            this.posterHeaders = imageHeaders()
        }
    }

    private fun imageHeaders(): Map<String, String> {
        val headers = mutableMapOf(
            "User-Agent" to (standardHeaders["User-Agent"] ?: ""),
            "Referer" to "$mainUrl/"
        )
        currentCfCookie?.let { headers["Cookie"] = it }
        return headers
    }

    private suspend fun httpGetRaw(url: String): com.lagradost.nicehttp.NiceResponse {
        if (currentCfCookie != null) {
            try {
                Log.d(logTag, "⚡ Fast Check: Attempting GET with cached cookie...")
                val response = app.get(
                    url,
                    referer = mainUrl,
                    headers = getHeaders(isPost = false),
                    interceptor = null
                )

                if (response.code == 200) {
                    return response
                }
                Log.w(logTag, "Cookie expired or invalid (Code: ${response.code})")
            } catch (e: Exception) {
                Log.w(logTag, "Fast check failed: ${e.message}")
                currentCfCookie = null
            }
        }
        Log.d(logTag, "🐢 Slow Check: Triggering CloudflareKiller...")
        val response = app.get(
            url,
            referer = mainUrl,
            headers = getHeaders(isPost = false),
            interceptor = cloudflareKiller
        )
        parseCfClearance(response.headers)
        return response
    }

    private suspend fun getFreshDomainAndCookies(): String {
        return try {
            val response = httpGetRaw(mainUrl)
            val finalUrl = response.url.trimEnd('/')

            if (finalUrl.isNotEmpty() && finalUrl != mainUrl && finalUrl.startsWith("http")) {
                Log.d(logTag, "🚨 Domain Redirect Detected! Updating: $mainUrl -> $finalUrl")
                return finalUrl
            }

            Log.d(logTag, "✅ Domain is valid & Cookies worked.")
            mainUrl
        } catch (e: Exception) {
            Log.e(logTag, "⚠️ Domain Check Failed: ${e.message}")
            mainUrl
        }
    }

    private suspend fun performSearch(query: String): List<SearchResponse> {
        val activeUrl = getFreshDomainAndCookies()

        if (mainUrl != activeUrl) {
            mainUrl = activeUrl
        }

        val encodedQuery = java.net.URLEncoder.encode(query, "UTF-8")
        val url = "$activeUrl/filtering/?keywords=$encodedQuery&Rated=&genre=&release-years=&category=&quality=&country=&language="
        Log.d(logTag, "🚀 Executing Search GET to: $url")

        return try {
            val document = httpGet(url, referer = activeUrl)
            document.select("div#MainFiltar div.GridItem").mapNotNull {
                it.toSearchResult()
            }
        } catch (e: Exception) {
            Log.e(logTag, "Search Error: ${e.message}")
            emptyList()
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val initialResults = performSearch(query)
        if (initialResults.isEmpty() && query.trim() == "مسلسل") {
            Log.d(logTag, "🔄 Fallback triggered: No results for 'مسلسل', searching for 'game of' instead.")
            return performSearch("game of")
        }
        return initialResults
    }

    private fun parseSeasonNumber(text: String): Int? {
        Regex("(\\d+)").find(text)?.groupValues?.get(1)?.toIntOrNull()?.let { return it }

        val normalized = text
            .replace("أ", "ا")
            .replace("إ", "ا")
            .replace("آ", "ا")
            .trim()

        val ordinals = listOf(
            "الحادي عشر" to 11,
            "الثاني عشر" to 12,
            "الثالث عشر" to 13,
            "الرابع عشر" to 14,
            "الخامس عشر" to 15,
            "السادس عشر" to 16,
            "السابع عشر" to 17,
            "الثامن عشر" to 18,
            "التاسع عشر" to 19,
            "العشرون" to 20,
            "العاشر" to 10,
            "الاول" to 1,
            "الثاني" to 2,
            "الثالث" to 3,
            "الرابع" to 4,
            "الخامس" to 5,
            "السادس" to 6,
            "السابع" to 7,
            "الثامن" to 8,
            "التاسع" to 9
        )

        return ordinals.firstOrNull { normalized.contains(it.first) }?.second
    }

    override suspend fun load(url: String): LoadResponse {
        Log.d(logTag, "load() initiated with URL: $url")
        val document = httpGet(url)

        val isSeries = url.contains("/series/") ||
            document.selectFirst("div.Seasons--Episodes, div.SeasonsList, singlesection.Series--Section") != null

        if (isSeries) {
            val seriesUrl = document.selectFirst("singlesection.Series--Section > a")?.attr("href")
                ?.let { fixUrl(it) }
                ?: url

            val title = document.selectFirst("singlesection.Series--Section h2")?.text()
                ?.replace("حلقات ", "")
                ?.trim()
                ?: document.selectFirst("div.Title--Content--Single-begin h1")?.text()?.trim()
                ?: "TV Series Title"

            val posterUrl = document.selectFirst("wecima.separated--top")?.attr("style")?.let {
                Regex("--img:url\\((.*?)\\)").find(it)?.groupValues?.get(1)
            }?.ifBlank { null }
                ?: document.selectFirst("meta[property=og:image]")?.attr("content")?.ifBlank { null }

            val plot = document.selectFirst("div.StoryMovieContent")?.text()?.trim()

            val year = document.selectFirst("div.Title--Content--Single-begin a[href*='/release-year/']")?.text()
                ?.trim()?.toIntOrNull()
                ?: document.select("ul.Terms--Content--Single-begin li")
                    .find { it.selectFirst("span")?.text()?.contains("السنة") == true }
                    ?.selectFirst("p")?.text()?.trim()?.toIntOrNull()

            val htmlText = document.html()
            val ajaxBase = Regex("""var\s+AjaxtURL\s*=\s*"([^"]+)"""")
                .find(htmlText)?.groupValues?.get(1)
                ?: "$mainUrl/wp-content/themes/mycima/Ajaxt/"

            val postId = Regex("""Single/Episodes\.php.*?post_id:\s*['"](\d+)['"]""", RegexOption.DOT_MATCHES_ALL)
                .find(htmlText)?.groupValues?.get(1)
                ?: document.selectFirst("link[rel=shortlink]")?.attr("href")
                    ?.let { Regex("p=(\\d+)").find(it)?.groupValues?.get(1) }
                ?: ""

            val seasonElements = document.select("div.SeasonsList ul li a")

            fun parseEpisodesFromDoc(
                doc: Document,
                seasonTitle: String,
                seasonNum: Int
            ): List<Episode> {
                return doc.select("a").mapNotNull { epEl ->
                    val epHref = epEl.attr("href").ifBlank { return@mapNotNull null }
                    val epRawName = epEl.selectFirst("episodetitle")?.text()?.trim()
                        ?: epEl.text().trim()
                    val epNum = Regex("(\\d+)").find(epRawName)?.groupValues?.get(1)?.toIntOrNull()

                    val formattedName = if (epNum != null) {
                        "$seasonTitle الحلقة $epNum"
                    } else {
                        "$seasonTitle $epRawName".trim()
                    }

                    newEpisode(fixUrl(epHref)) {
                        this.name = formattedName
                        this.season = seasonNum
                        this.episode = epNum
                        this.posterUrl = posterUrl
                    }
                }
            }

            val episodes: List<Episode> = if (seasonElements.isNotEmpty()) {
                Log.d(logTag, "Found ${seasonElements.size} seasons. Processing...")
                seasonElements.mapIndexed { idx, el -> idx to el }.amap { (idx, seasonEl) ->
                    val seasonNum = parseSeasonNumber(seasonEl.text()) ?: (idx + 1)
                    val seasonTitle = seasonEl.text()
                        .replace(Regex("\\s*(مترجم|مدبلج).*"), "")
                        .trim()
                        .ifBlank { "الموسم $seasonNum" }
                    val dataSeason = seasonEl.attr("data-season")

                    try {
                        val seasonHtml = httpPost(
                            "${ajaxBase.trimEnd('/')}/Single/Episodes.php",
                            data = mapOf("season" to dataSeason, "post_id" to postId),
                            referer = url
                        )
                        parseEpisodesFromDoc(Jsoup.parse(seasonHtml), seasonTitle, seasonNum)
                    } catch (e: Exception) {
                        logError(e)
                        if (seasonEl.parent()?.hasClass("active") == true) {
                            document.selectFirst("div.EpisodesList")?.let { listEl ->
                                parseEpisodesFromDoc(Jsoup.parse(listEl.html()), seasonTitle, seasonNum)
                            } ?: emptyList()
                        } else {
                            emptyList()
                        }
                    }
                }.flatten()
            } else {
                document.selectFirst("div.EpisodesList")?.let { listEl ->
                    parseEpisodesFromDoc(Jsoup.parse(listEl.html()), "الموسم الأول", 1)
                } ?: emptyList()
            }

            val sortedEpisodes = episodes
                .distinctBy { it.data }
                .sortedWith(compareBy({ it.season }, { it.episode }))

            return newTvSeriesLoadResponse(title, seriesUrl, TvType.TvSeries, sortedEpisodes) {
                this.posterUrl = posterUrl
                this.posterHeaders = imageHeaders()
                this.plot = plot
                this.year = year
            }
        } else {
            val title = document.selectFirst("div.Title--Content--Single-begin h1")?.text()?.trim() ?: "Movie Title"
            val posterUrl = document.select("meta[property=og:image]").attr("content").ifBlank {
                document.selectFirst("wecima.separated--top")?.let { element ->
                    val style = element.attr("data-lazy-style").ifBlank { element.attr("style") }
                    Regex("url\\((.*?)\\)").find(style)?.groupValues?.get(1)
                }
            }
            val plot = document.selectFirst("div.StoryMovieContent")?.text()?.trim()
            val year = document.selectFirst("div.Title--Content--Single-begin a[href*='/release-year/']")?.text()
                ?.trim()?.toIntOrNull()
                ?: document.select("ul.Terms--Content--Single-begin li")
                    .find { it.selectFirst("span")?.text()?.contains("السنة") == true }
                    ?.selectFirst("p")?.text()?.trim()?.toIntOrNull()

            return newMovieLoadResponse(title, url, TvType.Movie, url) {
                this.posterUrl = posterUrl
                this.posterHeaders = imageHeaders()
                this.plot = plot
                this.year = year
            }
        }
    }

    private fun decodeWecimaUrl(encodedStr: String): String? {
        return try {
            if (encodedStr.isBlank()) return null
            val cleanedStr = encodedStr.replace("+", "").trim()
            val finalB64Str = if (!cleanedStr.startsWith("aHR0c")) "aHR0c$cleanedStr" else cleanedStr
            String(Base64.decode(finalB64Str, Base64.DEFAULT))
        } catch (e: Exception) {
            logError(e)
            null
        }
    }

    override suspend fun loadLinks(
    data: String,
    isCasting: Boolean,
    subtitleCallback: (SubtitleFile) -> Unit,
    callback: (ExtractorLink) -> Unit
): Boolean {
    Log.d(logTag, "🔗 loadLinks initiated for: $data")
    val document = httpGet(data)
    val collectedUrls = linkedSetOf<String>()
    document.select("ul#watch li[data-watch]").forEach { serverEl ->
        val watchUrl = serverEl.attr("data-watch").trim()
        if (watchUrl.startsWith("http")) {
            Log.d(logTag, "📺 Watch URL: $watchUrl")
            collectedUrls.add(watchUrl)
        }
    }
    document.select("ul.List--Download--Wecima--Single li a[href]").forEach { downloadEl ->
        val downloadUrl = downloadEl.attr("href").trim()
        if (downloadUrl.startsWith("http")) {
            Log.d(logTag, "📥 Download URL: $downloadUrl")
            collectedUrls.add(downloadUrl)
        }
    }
    document.select("ul.WatchServersList li btn[data-url]").forEach { serverBtn ->
        decodeWecimaUrl(serverBtn.attr("data-url"))?.let { decodedUrl ->
            if (decodedUrl.startsWith("http")) {
                Log.d(logTag, "📺 Decoded Watch URL: $decodedUrl")
                collectedUrls.add(decodedUrl)
            }
        }
    }

    document.select(".openLinkDown[data-href]").forEach { downloadBtn ->
        decodeWecimaUrl(downloadBtn.attr("data-href"))?.let { decodedUrl ->
            if (decodedUrl.startsWith("http")) {
                Log.d(logTag, "📥 Decoded Download URL: $decodedUrl")
                collectedUrls.add(decodedUrl)
            }
        }
    }

    collectedUrls.toList().amap { link ->
        loadExtractor(link, mainUrl, subtitleCallback, callback)
    }

    return collectedUrls.isNotEmpty()
}
}
