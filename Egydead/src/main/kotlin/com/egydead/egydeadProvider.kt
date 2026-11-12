package com.egydead

import android.content.Context
import android.webkit.CookieManager
import android.webkit.WebSettings
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.utils.AppUtils.parseJson
import com.lagradost.cloudstream3.Episode as CS3Episode
import org.jsoup.nodes.Element
import org.jsoup.nodes.Document
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import java.util.ArrayDeque
import java.net.URL
import java.net.URLDecoder
@Serializable
data class MegamaxMirror(
    @SerialName("driver") val driver: String? = null,
    @SerialName("link") val link: String? = null
)

@Serializable
data class MegamaxQuality(
    @SerialName("label") val label: String? = null,
    @SerialName("mirrors") val mirrors: List<MegamaxMirror>? = null
)

@Serializable
data class MegamaxStreamsData(
    @SerialName("data") val data: List<MegamaxQuality>? = null
)

@Serializable
data class MegamaxProps(
    @SerialName("streams") val streams: MegamaxStreamsData? = null
)

@Serializable
data class MegamaxInertiaResponse(
    @SerialName("props") val props: MegamaxProps? = null
)

@Serializable
data class MegamaxInitialPage(
    @SerialName("version") val version: String? = null,
    @SerialName("component") val component: String? = null
)

class EgyDead(val context: Context) : MainAPI() {
    override var mainUrl = "https://egydead.life/"
    override var name = "ايجي ديد"
    override val hasMainPage = true
    override var lang = "ar"
    override val supportedTypes = setOf(
        TvType.Movie,
        TvType.TvSeries
    )

    private val userAgent by lazy {
        try {
            WebSettings.getDefaultUserAgent(context)
        } catch (e: Exception) {
            "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/156.0.0.0 Mobile Safari/537.36"
        }
    }

    private var savedCookies: String? = null
    private val cfMutex = Mutex()

    private fun log(tag: String, msg: String) {
        println("EgyDeadDebug | [$tag] -> $msg")
    }

    private fun fixUrlSlash(url: String): String {
        return try {
            val u = URL(url)
            if (u.path.isEmpty() || u.path == "") {
                val query = if (u.query != null) "?${u.query}" else ""
                "${u.protocol}://${u.host}/$query"
            } else {
                url
            }
        } catch (e: Exception) {
            if (!url.endsWith("/")) "$url/" else url
        }
    }

    private val imageHeaders: Map<String, String>
        get() {
            val headers = mutableMapOf(
                "User-Agent" to userAgent,
                "Referer" to "$mainUrl/"
            )
            savedCookies?.let { headers["Cookie"] = it }
            return headers
        }

    private fun buildHeaders(referer: String?): MutableMap<String, String> {
        val headers = mutableMapOf(
            "User-Agent" to userAgent,
            "Referer" to (referer ?: "$mainUrl/"),
            "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,image/apng,*/*;q=0.8",
            "Accept-Language" to "ar-IQ,ar;q=0.9,en-US;q=0.8,en;q=0.7",
            "Upgrade-Insecure-Requests" to "1",
            "sec-ch-ua" to "\"Not:A-Brand\";v=\"8\", \"Chromium\";v=\"156\", \"Google Chrome\";v=\"156\"",
            "sec-ch-ua-mobile" to "?1",
            "sec-ch-ua-platform" to "\"Android\"",
            "sec-fetch-dest" to "document",
            "sec-fetch-mode" to "navigate",
            "sec-fetch-site" to "same-origin",
            "sec-fetch-user" to "?1"
        )
        savedCookies?.let { headers["Cookie"] = it }
        return headers
    }

    private suspend fun httpGet(url: String, referer: String? = null): Document {
        val currentRequestUrl = fixUrlSlash(url)
        log("CF-TRACE", "==================================================")
        log("CF-TRACE", "1. بدء طلب الرابط: $currentRequestUrl")
        var headers = buildHeaders(referer?.let { fixUrlSlash(it) })

        // طباعة تفصيلية لجميع الهيدرات في الطلب الأول
        log("CF-TRACE", "--- [الهيدرات المرسلة مع الطلب الأول] ---")
        headers.forEach { (key, value) ->
            log("CF-TRACE", "  -> $key: $value")
        }

        var res = app.get(currentRequestUrl, headers = headers, timeout = 30)
        log("CF-TRACE", "2. استجابة الطلب الأول: Code=${res.code} | حجم الرد=${res.text.length} حرف")

        if (res.code in listOf(403, 503, 429)) {
            log("CF-TRACE", "3. رصد حماية Cloudflare (Code: ${res.code}). دخول الـ Lock...")
            cfMutex.withLock {
                val currentCookies = try { CookieManager.getInstance().getCookie(currentRequestUrl) } catch (e: Exception) { null }
                if (currentCookies != null && currentCookies.contains("cf_clearance") && currentCookies != savedCookies) {
                    log("CF-TRACE", "4. تم العثور على كوكيز صالحة مسبقاً في CookieManager.")
                    savedCookies = currentCookies
                } else {
                    log("CF-TRACE", "4. تشغيل CloudflareSolver في الخلفية...")
                    val activity = CommonActivity.activity ?: com.lagradost.cloudstream3.CommonActivity.activity

                    if (activity != null) {
                        val solverResult = CloudflareSolver.solve(activity, currentRequestUrl, userAgent)
                        if (solverResult != null) {
                            if (!solverResult.cookies.isNullOrEmpty()) {
                                savedCookies = solverResult.cookies
                                val hasClearance = solverResult.cookies.contains("cf_clearance")
                                log("CF-TRACE", "5. تم صيد الكوكيز بنجاح! تحتوي cf_clearance: $hasClearance")
                            } else {
                                log("CF-TRACE", "5. تحذير: انتهت العملية ولكن الكوكيز فارغة!")
                            }
                            if (solverResult.finalUrl != currentRequestUrl) {
                                try {
                                    val newHost = URL(solverResult.finalUrl).host
                                    mainUrl = "https://$newHost/"
                                    log("CF-TRACE", "تحديث mainUrl ليصبح: $mainUrl")
                                } catch (e: Exception) {}
                            }
                        } else {
                            log("CF-TRACE", "5. فشل الصياد: solverResult عاد بـ null!")
                        }
                    } else {
                        log("CF-TRACE", "5. خطأ: Activity غير متاح لتشغيل الصياد!")
                    }
                }
            }

            headers = buildHeaders(referer?.let { fixUrlSlash(it) })

            // طباعة تفصيلية لجميع الهيدرات بعد إضافة الكوكيز في طلب الـ Retry
            log("CF-TRACE", "--- [الهيدرات المرسلة مع طلب الإعادة (Retry)] ---")
            headers.forEach { (key, value) ->
                log("CF-TRACE", "  -> $key: $value")
            }

            log("CF-TRACE", "6. جاري إرسال طلب الإعادة (Retry)...")
            res = app.get(currentRequestUrl, headers = headers, timeout = 30)
            log("CF-TRACE", "7. نتيجة طلب الإعادة: Code=${res.code} | حجم الرد=${res.text.length} حرف | عنوان الصفحة: '${res.document.title()}'")
            
            if (res.code in listOf(403, 503)) {
                log("CF-TRACE", "⚠️ فشل التخطي حتى بعد الكوكيز! عينة من الرد: ${res.text.take(250)}")
            }
        }

        return res.document
    }

    private suspend fun httpPost(url: String, data: Map<String, String>, referer: String? = null): Document {
        var currentRequestUrl = url
        log("POST-REQUEST", "Sending to: $currentRequestUrl")
        var headers = buildHeaders(referer?.let { fixUrlSlash(it) }).apply {
            put("X-Requested-With", "XMLHttpRequest")
            put("Content-Type", "application/x-www-form-urlencoded; charset=UTF-8")
        }

        var res = app.post(currentRequestUrl, data = data, headers = headers, timeout = 30)

        if (res.code in listOf(403, 503, 429)) {
            cfMutex.withLock {
                val activity = CommonActivity.activity ?: com.lagradost.cloudstream3.CommonActivity.activity
                if (activity != null) {
                    val solverResult = CloudflareSolver.solve(activity, currentRequestUrl, userAgent)
                    if (solverResult != null) {
                        if (!solverResult.cookies.isNullOrEmpty()) {
                            savedCookies = solverResult.cookies
                        }
                        if (solverResult.finalUrl != currentRequestUrl) {
                            try {
                                val newHost = URL(solverResult.finalUrl).host
                                mainUrl = "https://$newHost/"
                            } catch (e: Exception) {}
                        }
                    }
                }
            }
            headers = buildHeaders(referer?.let { fixUrlSlash(it) }).apply {
                put("X-Requested-With", "XMLHttpRequest")
                put("Content-Type", "application/x-www-form-urlencoded; charset=UTF-8")
            }
            res = app.post(currentRequestUrl, data = data, headers = headers, timeout = 30)
        }

        return res.document
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val requestUrl = fixUrlSlash(mainUrl)
        log("MAIN-PAGE", "=== بدء طلب الصفحة الرئيسية: $requestUrl | الصفحة: $page ===")

        val document = try {
            httpGet(requestUrl)
        } catch (e: Exception) {
            log("MAIN-PAGE", "CRITICAL ERROR: فشل الاتصال بالصفحة الرئيسية -> ${e.message}")
            return newHomePageResponse(emptyList())
        }

        val pageTitle = document.title()
        log("MAIN-PAGE", "عنوان المستند المستلم: '$pageTitle'")

        val homePageList = ArrayList<HomePageList>()

        val pinnedSection = document.selectFirst("div.pin-posts-list")
        if (pinnedSection != null) {
            val sectionTitle = pinnedSection.selectFirst("h1.TitleMaster em")?.text()?.trim() ?: "المميز"
            val items = pinnedSection.select("li.movieItem").mapNotNull {
                it.toSearchResponse("PINNED")
            }
            log("MAIN-PAGE", "قسم المميز [$sectionTitle]: استخراج ${items.size} عنصر")
            if (items.isNotEmpty()) {
                homePageList.add(HomePageList(sectionTitle, items, isHorizontalImages = true))
            }
        } else {
            log("MAIN-PAGE", "لم يتم العثور على div.pin-posts-list")
        }

        val mainSections = document.select("section.main-section")
        log("MAIN-PAGE", "عدد الأقسام المكتشفة (section.main-section): ${mainSections.size}")
        
        mainSections.forEachIndexed { index, section ->
            val sectionTitle = section.selectFirst("h1.TitleMaster em")?.text()?.trim() ?: "قسم ${index + 1}"
            val items = section.select("li.movieItem").mapNotNull {
                it.toSearchResponse("SECTION-$index")
            }
            log("MAIN-PAGE", "القسم [$sectionTitle]: استخراج ${items.size} عنصر")
            if (items.isNotEmpty()) {
                homePageList.add(HomePageList(sectionTitle, items))
            }
        }

        log("MAIN-PAGE", "إجمالي القوائم المستخرجة بنجاح: ${homePageList.size}")
        if (homePageList.isEmpty()) {
            val htmlSample = document.body()?.text()?.take(200) ?: "Body is empty"
            log("MAIN-PAGE", "تحذير: القائمة فارغة! عينة من محتوى الصفحة: $htmlSample")
        }

        return newHomePageResponse(homePageList.filter { it.list.isNotEmpty() })
    }

    private fun Element.toSearchResponse(parentTag: String): SearchResponse? {
        try {
            val linkEl = this.selectFirst("a") ?: return null
            val href = linkEl.attr("href")
            val fullUrl = fixUrlNull(href) ?: return null
            val title = this.selectFirst("h1.BottomTitle")?.text()?.trim() ?: return null
            val posterUrl = this.selectFirst("img")?.attr("src")

            return newMovieSearchResponse(title, fullUrl) {
                this.posterUrl = posterUrl
                this.posterHeaders = imageHeaders
            }
        } catch (e: Exception) {
            return null
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val url = "$mainUrl?s=$query"
        val document = httpGet(url)
        return document.select("ul.posts-list li.movieItem").mapNotNull {
            it.toSearchResponse("SEARCH")
        }
    }

    private val seasonNumRegex = Regex("""(?ix)(?:الموسم[\s:\-_.]*0*(\d+))|(?:S(?:eason)?[\s:\-_.]*0*(\d+))""")
    private val episodeNumRegex = Regex("""(?ix)(?:حلقة[\s:\-_.]*0*(\d+))|(?:Episode[\s:\-_.]*0*(\d+))|(?:EP[\s:\-_.]*0*(\d+))|(?:\d+[xX]0*(\d+))|(?:S(?:eason)?[\s:\-_.]*\d+[\s\-_.,]*E(?:p(?:isode)?)?[\s:\-_.]*0*(\d+))""")

    private fun getSeasonNum(title: String?): Int {
        if (title == null) return 9999
        val match = seasonNumRegex.find(title) ?: return 9999
        return match.groupValues.drop(1).firstOrNull { it.isNotEmpty() }?.toIntOrNull() ?: 9999
    }

    private fun getEpisodeNum(title: String?): Int {
        if (title == null) return 9999
        val match = episodeNumRegex.find(title) ?: return 9999
        return match.groupValues.drop(1).firstOrNull { it.isNotEmpty() }?.toIntOrNull() ?: 9999
    }

    private fun normalizeUrl(link: String?, base: String): String? {
        if (link.isNullOrBlank()) return null
        val t = link.trim()
        if (t.startsWith("#") || t.lowercase().startsWith("javascript:")) return null
        return try {
            val resolved = if (t.startsWith("http")) t else URL(URL(base), t).toString()
            fixUrl(resolved)
        } catch (e: Exception) { null }
    }

    private suspend fun batchFetch(urls: List<String>, concurrency: Int = 8): Map<String, Document?> {
        val sem = Semaphore(concurrency)
        val out = mutableMapOf<String, Document?>()
        coroutineScope {
            val jobs = urls.map { u ->
                async {
                    sem.withPermit {
                        try {
                            out[u] = httpGet(u)
                        } catch (e: Exception) {
                            out[u] = null
                        }
                    }
                }
            }
            jobs.awaitAll()
        }
        return out
    }

    private fun extractEpisodesFromSeasonDoc(seasonUrl: String, doc: Document): List<CS3Episode> {
        val episodes = mutableListOf<CS3Episode>()
        val epsContainer = doc.selectFirst("div.EpsList") ?: doc.selectFirst("div.episodes-list") ?: doc.selectFirst("ul") ?: return emptyList()

        val items = epsContainer.select("li, a")
        for (el in items) {
            val a: Element = if (el.tagName() == "a") el else el.selectFirst("a") ?: continue
            val rawTitle = (a.attr("title").takeIf { it.isNotBlank() } ?: a.text()).trim()
            val href = normalizeUrl(a.attr("href"), seasonUrl) ?: continue

            if (href.contains("/season/") || href.contains("/film/")) continue

            val epNum = getEpisodeNum(rawTitle)
            episodes.add(newEpisode(href) {
                this.name = rawTitle
                this.episode = if (epNum != 9999) epNum else null
                this.data = href
            })
        }
        return episodes.sortedBy { it.episode ?: 9999 }
    }

    private fun parseRecommendations(doc: Document, base: String): List<SearchResponse> {
        val out = mutableListOf<SearchResponse>()
        val nodes = doc.select(".related-posts li.movieItem, .related-posts a, .related-posts li")
        for (li in nodes) {
            val a = li.selectFirst("a") ?: continue
            val href = normalizeUrl(a.attr("href"), base) ?: continue
            val title = a.selectFirst("h1, span, .title")?.text() ?: a.attr("title").takeIf { it.isNotBlank() } ?: a.text()
            val poster = a.selectFirst("img")?.attr("src")
            val sr = when {
                href.contains("/film/") -> newMovieSearchResponse(title, href) {
                    this.posterUrl = poster
                    this.posterHeaders = imageHeaders
                }
                href.contains("/season/") || href.contains("/series/") || href.contains("/show/") || href.contains("/serie/") -> newTvSeriesSearchResponse(title, href) {
                    this.posterUrl = poster
                    this.posterHeaders = imageHeaders
                }
                else -> null
            }
            sr?.let { out.add(it) }
        }
        return out
    }

    override suspend fun load(url: String): LoadResponse? {
        val document = try {
            httpGet(url)
        } catch (e: Exception) {
            return null
        }

        if (url.contains("/film/")) {
            val title = document.selectFirst("meta[property=og:title]")?.attr("content")?.trim() ?: return null
            val poster = document.selectFirst("meta[property=og:image]")?.attr("content")
            val plot = document.selectFirst("div.singleStory")?.text()?.trim()
            val year = document.select("div.LeftBox li:has(span:contains(السنه)) a").text().toIntOrNull()
            val tags = document.select("div.LeftBox li:has(span:contains(النوع)) a").map { it.text() }

            return newMovieLoadResponse(title, url, TvType.Movie, url) {
                this.posterUrl = poster
                this.posterHeaders = imageHeaders
                this.plot = plot
                this.year = year
                this.tags = tags
                this.recommendations = parseRecommendations(document, url)
            }
        }

        val isEpisode = url.contains("/episode/")
        val isSeason = url.contains("/season/")
        var startSeasonUrl: String? = null

        if (isEpisode) {
            val bc = document.selectFirst("div.breadcrumbs-single, div.breadcrumbs")
            bc?.select("a")?.forEach { a ->
                val href = a.attr("href")
                if (href.contains("/season/") || href.contains("/serie/")) {
                    startSeasonUrl = normalizeUrl(href, url)
                }
            }
        } else if (isSeason) {
            startSeasonUrl = url
        }

        if (startSeasonUrl == null) startSeasonUrl = url
        val startDoc = try { httpGet(startSeasonUrl!!) } catch (e: Exception) { return null }

        val seasonAnchors = startDoc.select("div.seasons-list li.movieItem a, div.seasons-list a")
        val candidateSeasonUrls = seasonAnchors.mapNotNull { normalizeUrl(it.attr("href"), startSeasonUrl!!) }.distinct()

        if (candidateSeasonUrls.isNotEmpty()) {
            val fetched = batchFetch(candidateSeasonUrls.reversed(), concurrency = 8)
            val allEpisodes = mutableListOf<CS3Episode>()

            for ((idx, sUrl) in candidateSeasonUrls.reversed().withIndex()) {
                val doc = fetched[sUrl] ?: continue
                val seasonTitle = doc.selectFirst("meta[property=og:title]")?.attr("content")?.trim()
                val seasonNumber = getSeasonNum(seasonTitle).takeIf { it != 9999 } ?: (idx + 1)
                val eps = extractEpisodesFromSeasonDoc(sUrl, doc)
                eps.forEach { it.season = seasonNumber }
                allEpisodes.addAll(eps)
            }

            val seriesTitle = startDoc.selectFirst("meta[property=og:title]")?.attr("content")?.trim()?.replace(Regex("""\s*(الموسم|الحلقة)\s+.*"""), "")?.trim() ?: "TV Series"
            val poster = startDoc.selectFirst("meta[property=og:image]")?.attr("content")
            val plot = startDoc.selectFirst("div.singleStory")?.text()?.trim()

            return newTvSeriesLoadResponse(seriesTitle, startSeasonUrl!!, TvType.TvSeries, allEpisodes) {
                this.posterUrl = poster
                this.posterHeaders = imageHeaders
                this.plot = plot
            }
        }

        val fallbackEpisodes = extractEpisodesFromSeasonDoc(startSeasonUrl!!, startDoc)
        val seriesTitleFallback = startDoc.selectFirst("meta[property=og:title]")?.attr("content")?.trim() ?: "TV Series"
        val posterFallback = startDoc.selectFirst("meta[property=og:image]")?.attr("content")?.let { normalizeUrl(it, startSeasonUrl!!) }
        val plotFallback = startDoc.selectFirst("div.singleStory")?.text()?.trim()

        return newTvSeriesLoadResponse(seriesTitleFallback, startSeasonUrl!!, TvType.TvSeries, fallbackEpisodes) {
            this.posterUrl = posterFallback
            this.posterHeaders = imageHeaders
            this.plot = plotFallback
        }
    }

    private suspend fun processMegamax(
        targetUrl: String,
        referer: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        return try {
            val iframeUrl = when {
                targetUrl.contains("/mirror/") -> {
                    val converted = targetUrl.replace("/mirror/", "/iframe/")
                    if (!converted.contains("?nt=1")) "$converted?nt=1" else converted
                }
                targetUrl.contains("/iframe/") -> {
                    if (!targetUrl.contains("?nt=1")) "$targetUrl?nt=1" else targetUrl
                }
                else -> targetUrl
            }

            val reqHeaders = mapOf(
                "User-Agent" to userAgent,
                "Referer" to referer,
                "Accept-Language" to "ar-IQ,ar;q=0.9,en-US;q=0.8,en;q=0.7",
                "Upgrade-Insecure-Requests" to "1"
            )

            val initialRes = app.get(iframeUrl, headers = reqHeaders, timeout = 25)
            val doc = initialRes.document

            val rawJson = doc.selectFirst("script[data-page=app]")?.data()
                ?: doc.selectFirst("script[data-page=app]")?.html()
                ?: Regex("""<script[^>]*data-page=["']app["'][^>]*>(.*?)</script>""", RegexOption.DOT_MATCHES_ALL)
                    .find(initialRes.text)?.groupValues?.get(1)
                ?: return false

            val pageData = try {
                parseJson<MegamaxInitialPage>(rawJson)
            } catch (e: Exception) { return false }

            val version = pageData.version ?: return false
            val component = pageData.component ?: "files/mirror/video"

            val xsrfToken = initialRes.cookies["XSRF-TOKEN"]?.let {
                try { URLDecoder.decode(it, "UTF-8") } catch (e: Exception) { it }
            }

            val inertiaHeaders = mutableMapOf(
                "User-Agent" to userAgent,
                "Referer" to iframeUrl,
                "X-Inertia" to "true",
                "X-Inertia-Partial-Component" to component,
                "X-Inertia-Partial-Data" to "streams",
                "X-Inertia-Version" to version,
                "X-Requested-With" to "XMLHttpRequest",
                "Accept" to "text/html, application/xhtml+xml"
            )
            xsrfToken?.let { inertiaHeaders["X-XSRF-TOKEN"] = it }

            val streamRes = app.get(
                iframeUrl,
                headers = inertiaHeaders,
                cookies = initialRes.cookies,
                timeout = 25
            )

            val streamJson = parseJson<MegamaxInertiaResponse>(streamRes.text)
            var extractedAny = false

            streamJson.props?.streams?.data?.forEach { quality ->
                quality.mirrors?.forEach { mirror ->
                    var mirrorLink = mirror.link ?: return@forEach
                    if (mirrorLink.startsWith("//")) mirrorLink = "https:$mirrorLink"

                    try {
                        val loaded = loadExtractor(mirrorLink, iframeUrl, subtitleCallback, callback)
                        if (loaded) extractedAny = true
                    } catch (e: Exception) {}
                }
            }
            extractedAny
        } catch (e: Exception) {
            false
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        try {
            val originalUrl = data
            val watchPageUrl = if (!data.contains("?view=watch")) "$data?view=watch" else data

            try { httpGet(originalUrl, referer = originalUrl) } catch (e: Exception) {}

            val document = try {
                httpPost(watchPageUrl, data = mapOf("View" to "1"), referer = originalUrl)
            } catch (e: Exception) {
                try { httpGet(watchPageUrl, referer = originalUrl) } catch (e2: Exception) { return false }
            }

            val allSeenLinks = java.util.Collections.synchronizedSet(mutableSetOf<String>())
            val candidates = mutableListOf<Pair<String?, String?>>()

            fun normalizeCandidate(raw: String?): String? {
                if (raw.isNullOrBlank()) return null
                return try { normalizeUrl(raw, watchPageUrl) } catch (e: Exception) { null }
            }

            val downloadSelectors = listOf("ul.donwload-servers-list li", "ul.download-servers-list li")
            for (sel in downloadSelectors) {
                for (li in document.select(sel)) {
                    val serverName = li.selectFirst("span.ser-name, p")?.text()?.trim()
                    val href = li.selectFirst("a.ser-link, a")?.attr("href") ?: li.attr("data-link")
                    val normalized = normalizeCandidate(href)
                    if (normalized != null) candidates += Pair(normalized, serverName)
                }
            }

            val watchSelectors = listOf("ul.serversList li", "ul.servers-list li", "div.serversList li")
            for (sel in watchSelectors) {
                for (li in document.select(sel)) {
                    val serverName = li.selectFirst("p, .ser-name, span.ser-name")?.text()?.trim()
                    val candidate = li.attr("data-link").takeIf { it.isNotBlank() }
                        ?: li.selectFirst("[data-link]")?.attr("data-link")
                        ?: li.selectFirst("button[data-link]")?.attr("data-link")
                        ?: li.selectFirst("a")?.attr("href")
                    val normalized = normalizeCandidate(candidate)
                    if (normalized != null) candidates += Pair(normalized, serverName)
                }
            }

            for (el in document.select("[data-link]")) {
                val serverName = el.attr("data-name").takeIf { it.isNotBlank() } ?: el.attr("data-provider")
                val normalized = normalizeCandidate(el.attr("data-link"))
                if (normalized != null) candidates += Pair(normalized, serverName)
            }

            for (a in document.select("a")) {
                val href = a.attr("href")
                if (href.contains("player") || href.contains("embed") || href.contains("download") || href.contains("drive") || href.contains("mp4") || href.contains("mirror")) {
                    val serverName = a.attr("title").takeIf { it.isNotBlank() } ?: a.text().takeIf { it.isNotBlank() }
                    val normalized = normalizeCandidate(href)
                    if (normalized != null) candidates += Pair(normalized, serverName)
                }
            }

            val semaphore = Semaphore(8)

            suspend fun prepareAndSendParallel(linkRaw: String?, serverName: String?): Boolean {
                if (linkRaw.isNullOrBlank()) return false
                val normalized = normalizeUrl(linkRaw, watchPageUrl) ?: return false

                if (!allSeenLinks.add(normalized)) return false

                var isLoaded = false
                if (normalized.contains("megamax") || normalized.contains("share4max") || normalized.contains("/mirror/") || normalized.contains("/iframe/")) {
                    isLoaded = processMegamax(normalized, watchPageUrl, subtitleCallback, callback)
                }
                if (!isLoaded) {
                    try {
                        isLoaded = loadExtractor(normalized, data, subtitleCallback, callback)
                    } catch (ex: Exception) {}
                }
                if (!isLoaded) {
                    try {
                        isLoaded = processMegamax(normalized, watchPageUrl, subtitleCallback, callback)
                    } catch (ex: Exception) {}
                }

                return isLoaded
            }

            coroutineScope {
                candidates.map { pair ->
                    async {
                        semaphore.withPermit {
                            prepareAndSendParallel(pair.first, pair.second)
                        }
                    }
                }.awaitAll()
            }

            return true
        } catch (e: Exception) {
            return false
        }
    }
}
