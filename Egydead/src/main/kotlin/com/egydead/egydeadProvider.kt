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

// ==========================================
// نماذج فك تشفير Megamax / Share4max
// ==========================================
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

    private val hgFallbackDomains = listOf(
        "streamwish.to",
        "streamhg.com",
        "wishembed.pro",
        "dwish.net",
        "embedwish.com"
    )

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
        val safeReferer = (referer ?: mainUrl).trimEnd('/') + "/"
        val headers = mutableMapOf(
            "User-Agent" to userAgent,
            "Referer" to safeReferer,
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
        var headers = buildHeaders(referer)

        var res = app.get(currentRequestUrl, headers = headers, timeout = 25)

        if (res.code in listOf(403, 503, 429)) {
            cfMutex.withLock {
                val currentCookies = try { CookieManager.getInstance().getCookie(currentRequestUrl) } catch (e: Exception) { null }
                if (currentCookies != null && currentCookies.contains("cf_clearance") && currentCookies != savedCookies) {
                    savedCookies = currentCookies
                } else {
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
            }

            headers = buildHeaders(referer)
            res = app.get(currentRequestUrl, headers = headers, timeout = 25)
        }

        return res.document
    }

    private suspend fun httpPost(url: String, data: Map<String, String>, referer: String? = null): Document {
        val currentRequestUrl = url
        var headers = buildHeaders(referer).apply {
            put("X-Requested-With", "XMLHttpRequest")
            put("Content-Type", "application/x-www-form-urlencoded; charset=UTF-8")
        }

        var res = app.post(currentRequestUrl, data = data, headers = headers, timeout = 25)

        if (res.code in listOf(403, 503, 429)) {
            cfMutex.withLock {
                val activity = CommonActivity.activity ?: com.lagradost.cloudstream3.CommonActivity.activity
                if (activity != null) {
                    val solverResult = CloudflareSolver.solve(activity, currentRequestUrl, userAgent)
                    if (solverResult != null && !solverResult.cookies.isNullOrEmpty()) {
                        savedCookies = solverResult.cookies
                    }
                }
            }
            headers = buildHeaders(referer).apply {
                put("X-Requested-With", "XMLHttpRequest")
                put("Content-Type", "application/x-www-form-urlencoded; charset=UTF-8")
            }
            res = app.post(currentRequestUrl, data = data, headers = headers, timeout = 25)
        }

        return res.document
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val requestUrl = fixUrlSlash(mainUrl)
        val document = try {
            httpGet(requestUrl)
        } catch (e: Exception) {
            return newHomePageResponse(emptyList())
        }

        val homePageList = ArrayList<HomePageList>()

        val pinnedSection = document.selectFirst("div.pin-posts-list")
        if (pinnedSection != null) {
            val sectionTitle = pinnedSection.selectFirst("h1.TitleMaster em")?.text()?.trim() ?: "المميز"
            val items = pinnedSection.select("li.movieItem").mapNotNull {
                it.toSearchResponse("PINNED")
            }
            if (items.isNotEmpty()) {
                homePageList.add(HomePageList(sectionTitle, items, isHorizontalImages = true))
            }
        }

        val mainSections = document.select("section.main-section")
        mainSections.forEachIndexed { index, section ->
            val sectionTitle = section.selectFirst("h1.TitleMaster em")?.text()?.trim() ?: "قسم ${index + 1}"
            val items = section.select("li.movieItem").mapNotNull {
                it.toSearchResponse("SECTION-$index")
            }
            if (items.isNotEmpty()) {
                homePageList.add(HomePageList(sectionTitle, items))
            }
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

        val seriesPoster = document.selectFirst("meta[property=og:image]")?.attr("content")
            ?: document.selectFirst("div.single-thumbnail img")?.attr("src")
        val plot = document.selectFirst("div.singleStory")?.text()?.trim()
            ?: document.selectFirst("div.extra-content p")?.text()?.trim()
        val year = document.select("div.LeftBox li:has(span:contains(السنه)) a").text().toIntOrNull()
        val tags = document.select("div.LeftBox li:has(span:contains(النوع)) a").map { it.text() }

        if (url.contains("/film/")) {
            val title = document.selectFirst("meta[property=og:title]")?.attr("content")?.trim() ?: "Movie"
            return newMovieLoadResponse(title, url, TvType.Movie, url) {
                this.posterUrl = seriesPoster
                this.posterHeaders = imageHeaders
                this.plot = plot
                this.year = year
                this.tags = tags
                this.recommendations = parseRecommendations(document, url)
            }
        }

        val seriesTitle = document.selectFirst("meta[property=og:title]")?.attr("content")?.trim()
            ?.replace(Regex("""\s*(الموسم|الحلقة)\s+.*"""), "")?.trim() ?: "TV Series"

        // 🌟 الحل الأساسي السريع من القالب الجديد بطلب واحد
        val episodeNav = document.selectFirst("section.dead-changes-episode-nav")
        if (episodeNav != null) {
            val groups = episodeNav.select("div.dead-changes-episode-group")
            val allEpisodes = mutableListOf<CS3Episode>()

            groups.reversed().forEachIndexed { index, group ->
                val groupTitle = group.selectFirst("h3")?.text()?.trim() ?: "الموسم ${index + 1}"
                val seasonNumber = getSeasonNum(groupTitle).takeIf { it != 9999 } ?: (index + 1)

                val elements = group.select("div.dead-changes-episode-links form, div.dead-changes-episode-links a")
                elements.forEachIndexed { epIdx, element ->
                    val epUrl = if (element.tagName() == "form") element.attr("action") else element.attr("href")
                    val normalizedEpUrl = normalizeUrl(epUrl, url) ?: return@forEachIndexed

                    val btn = element.selectFirst("button")
                    val epTitle = btn?.attr("title").takeIf { !it.isNullOrBlank() }
                        ?: btn?.text()
                        ?: element.text()
                        ?: "الحلقة ${epIdx + 1}"

                    val episodeNumber = getEpisodeNum(epTitle).takeIf { it != 9999 } ?: (epIdx + 1)

                    allEpisodes.add(newEpisode(normalizedEpUrl) {
                        this.name = epTitle.trim()
                        this.season = seasonNumber
                        this.episode = episodeNumber
                        this.data = normalizedEpUrl
                        this.posterUrl = seriesPoster
                    })
                }
            }

            if (allEpisodes.isNotEmpty()) {
                return newTvSeriesLoadResponse(seriesTitle, url, TvType.TvSeries, allEpisodes.distinctBy { it.data }) {
                    this.posterUrl = seriesPoster
                    this.posterHeaders = imageHeaders
                    this.plot = plot
                    this.year = year
                    this.tags = tags
                    this.recommendations = parseRecommendations(document, url)
                }
            }
        }

        // 🔄 الحل الثانوي القديم
        var startSeasonUrl: String? = null
        if (url.contains("/episode/")) {
            val bc = document.selectFirst("div.breadcrumbs-single, div.breadcrumbs")
            bc?.select("a")?.forEach { a ->
                val href = a.attr("href")
                if (href.contains("/season/") || href.contains("/serie/")) {
                    startSeasonUrl = normalizeUrl(href, url)
                }
            }
            if (startSeasonUrl == null) {
                val linkInPage = document.selectFirst("div.seasons-list li.movieItem a, div.seasons-list a")?.attr("href")
                startSeasonUrl = normalizeUrl(linkInPage, url)
            }
        } else if (url.contains("/season/")) {
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
                
                val rawImg = doc.selectFirst("meta[property=og:image]")?.attr("content")
                val seasonImage = rawImg?.let { normalizeUrl(it, sUrl) } ?: seriesPoster

                val eps = extractEpisodesFromSeasonDoc(sUrl, doc)
                eps.forEach { ep ->
                    ep.season = seasonNumber
                    if (ep.posterUrl.isNullOrBlank()) {
                        ep.posterUrl = seasonImage ?: seriesPoster
                    }
                }
                allEpisodes.addAll(eps)
            }

            if (allEpisodes.isNotEmpty()) {
                return newTvSeriesLoadResponse(seriesTitle, startSeasonUrl!!, TvType.TvSeries, allEpisodes.distinctBy { it.data }) {
                    this.posterUrl = seriesPoster
                    this.posterHeaders = imageHeaders
                    this.plot = plot
                    this.year = year
                    this.tags = tags
                    this.recommendations = parseRecommendations(document, url)
                }
            }
        }

        val fallbackEpisodes = extractEpisodesFromSeasonDoc(startSeasonUrl!!, startDoc)
        fallbackEpisodes.forEach { ep ->
            if (ep.posterUrl.isNullOrBlank()) {
                ep.posterUrl = seriesPoster
            }
        }

        return newTvSeriesLoadResponse(seriesTitle, startSeasonUrl!!, TvType.TvSeries, fallbackEpisodes.distinctBy { it.data }) {
            this.posterUrl = seriesPoster
            this.posterHeaders = imageHeaders
            this.plot = plot
            this.year = year
            this.tags = tags
            this.recommendations = parseRecommendations(document, url)
        }
    }

    // ==========================================
    // معالجة سيرفرات Megamax / Share4max
    // ==========================================
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

            // مهلة منخفضة (10 ثوانٍ) لمنع التجميد
            val initialRes = app.get(iframeUrl, headers = reqHeaders, timeout = 10)
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
                timeout = 10
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

    // ==========================================
    // دوال حل وفك تشفير سيرفرات HgCloud
    // ==========================================
    private fun customB64Decode(s: String, alphabet: String): String {
        var bc = 0
        var bs = 0
        val out = mutableListOf<Byte>()
        for (ch in s) {
            val idx = alphabet.indexOf(ch)
            if (idx == -1) continue
            bs = if (bc % 4 != 0) (bs * 64 + idx) else idx
            if (bc % 4 != 0) {
                out.add((0xff and (bs shr ((-2 * (bc + 1)) and 6))).toByte())
            }
            bc++
        }
        return try {
            val pct = out.joinToString("") { "%02x".format(it) }
            URLDecoder.decode("%$pct".replace("%", "%25").replace("%25", "%"), "UTF-8")
        } catch (e: Exception) {
            String(out.toByteArray(), Charsets.UTF_8)
        }
    }

    private fun rc4(s: String, key: String): String {
        val sbox = IntArray(256) { it }
        var j = 0
        for (i in 0 until 256) {
            j = (j + sbox[i] + key[i % key.length].code) % 256
            val temp = sbox[i]; sbox[i] = sbox[j]; sbox[j] = temp
        }
        var i = 0
        j = 0
        val res = StringBuilder()
        for (ch in s) {
            i = (i + 1) % 256
            j = (j + sbox[i]) % 256
            val temp = sbox[i]; sbox[i] = sbox[j]; sbox[j] = temp
            res.append((ch.code xor sbox[(sbox[i] + sbox[j]) % 256]).toChar())
        }
        return res.toString()
    }

    private fun deobfuscateJs(jsCode: String): String {
        val jsId = """[a-zA-Z_$][\w$]*"""
        val decRegex = Regex("""function\s+($jsId)\s*\(\s*($jsId)(?:\s*,\s*$jsId)?\s*\)\s*\{\s*\2\s*=\s*\2\s*([-+])\s*([^;]+);\s*(?:const|let|var)\s+$jsId\s*=\s*($jsId)\s*\(\s*\)\s*;""")
        val mDec = decRegex.find(jsCode) ?: return jsCode

        val decFn = mDec.groupValues[1]
        val op = mDec.groupValues[3]
        val offsetVal = mDec.groupValues[4].trim().toIntOrNull() ?: 0
        val offset = if (op == "+") -offsetVal else offsetVal
        val arrFn = mDec.groupValues[5]

        val arrRegex = Regex("""function\s+${Regex.escape(arrFn)}\s*\(\s*\)\s*\{\s*(?:const|let|var)\s+$jsId\s*=\s*(\[.*?\])\s*;""", RegexOption.DOT_MATCHES_ALL)
        val mArr = arrRegex.find(jsCode) ?: return jsCode
        val rawArrStr = mArr.groupValues[1]

        val arr = Regex("""['"]([^'"]+)['"]""").findAll(rawArrStr).map { it.groupValues[1] }.toMutableList()
        val alphabetMatch = Regex("""['"]([A-Za-z0-9+/=]{64,65})['"]""").find(jsCode)
        val alphabet = alphabetMatch?.groupValues?.get(1) ?: "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789+/="

        fun decodeItem(idx: Int, key: String): String {
            val realIdx = idx - offset
            if (realIdx !in arr.indices) return ""
            val raw = arr[realIdx]
            return try {
                if (key.isNotEmpty()) {
                    rc4(customB64Decode(raw, alphabet), key)
                } else {
                    customB64Decode(raw, alphabet)
                }
            } catch (e: Exception) { raw }
        }

        val callRegex = Regex("""\b${Regex.escape(decFn)}\(\s*(0x[0-9a-fA-F]+|\d+)(?:\s*,\s*['"]([^'"]*)['"])?\s*\)""")
        return callRegex.replace(jsCode) { m ->
            val numStr = m.groupValues[1]
            val idx = if (numStr.startsWith("0x")) numStr.substring(2).toInt(16) else numStr.toInt()
            val key = m.groupValues.getOrNull(2) ?: ""
            "\"${decodeItem(idx, key)}\""
        }
    }

    private fun extractDomains(decodedJs: String, currentHost: String): List<String> {
        val domains = mutableListOf<String>()
        val listRegex = Regex("""\b(?:main|dmca)\s*=\s*\[([^\]]+)\]""")
        listRegex.findAll(decodedJs).forEach { match ->
            val content = match.groupValues[1]
            Regex("""['"]([a-zA-Z0-9.-]+\.[a-z]{2,})['"]""").findAll(content).forEach { d ->
                domains.add(d.groupValues[1])
            }
        }
        if (domains.isEmpty()) {
            val domainRegex = Regex("""['"]([a-zA-Z0-9.-]+\.[a-z]{2,})['"]""")
            domainRegex.findAll(decodedJs).forEach { d ->
                val dom = d.groupValues[1]
                if (!dom.contains("google") && !dom.contains("cloudflare") && !dom.endsWith(currentHost)) {
                    domains.add(dom)
                }
            }
        }
        return domains.distinct()
    }

    private suspend fun resolveHgCloud(targetUrl: String, referer: String? = null): String? {
        return try {
            val headers = mapOf(
                "User-Agent" to userAgent,
                "Referer" to (referer ?: targetUrl)
            )

            val r1 = app.get(targetUrl, headers = headers, timeout = 8)
            if (r1.code != 200) return null

            val html = r1.text
            val jsPathMatch = Regex("""<script[^>]+src=["']([^"']*main\.js[^"']*)["']""").find(html)
                ?: if (html.lowercase().contains("loading")) Regex("""<script[^>]+src=["'](/[^"']+\.js[^"']*)["']""").find(html) else null

            val uri = URL(r1.url)
            val currentHost = uri.host
            val candidates = mutableListOf<String>()

            if (jsPathMatch != null) {
                val jsRelative = jsPathMatch.groupValues[1]
                val jsUrl = if (jsRelative.startsWith("http")) jsRelative else "${uri.protocol}://${uri.host}$jsRelative"

                val r2 = app.get(jsUrl, headers = headers, timeout = 8)
                if (r2.code == 200) {
                    try {
                        val decodedJs = deobfuscateJs(r2.text)
                        candidates.addAll(extractDomains(decodedJs, currentHost))
                    } catch (e: Exception) {}
                }
            }

            candidates.addAll(hgFallbackDomains)
            val uniqueCandidates = candidates.distinct().take(4) // فحص أسرع 4 دومينات فقط

            for (dest in uniqueCandidates) {
                if (dest.equals(currentHost, ignoreCase = true)) continue
                val query = if (!uri.query.isNullOrBlank()) "?${uri.query}" else ""
                val testUrl = "https://$dest${uri.path}$query"

                try {
                    val check = app.get(testUrl, headers = mapOf("User-Agent" to userAgent, "Referer" to r1.url), timeout = 5)
                    if (check.code == 200 && !check.text.contains("loading", ignoreCase = true)) {
                        return testUrl
                    }
                } catch (e: Exception) {}
            }
            null
        } catch (e: Exception) {
            null
        }
    }

    // ==========================================
    // دالة استخراج الروابط السريعة بالتوازي الكامل
    // ==========================================
    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        try {
            val watchPageUrl = if (!data.contains("?view=watch")) "$data?view=watch" else data

            // طلب صفحة المشاهدة مباشرة بالـ POST دون إضاعة الوقت في GET
            val document = try {
                httpPost(watchPageUrl, data = mapOf("View" to "1"), referer = data)
            } catch (e: Exception) {
                try { httpGet(watchPageUrl, referer = data) } catch (e2: Exception) { return false }
            }

            val allSeenLinks = java.util.Collections.synchronizedSet(mutableSetOf<String>())
            val rawCandidates = mutableListOf<Pair<String, String?>>()

            fun addCandidate(raw: String?, name: String?) {
                if (raw.isNullOrBlank()) return
                val normalized = normalizeUrl(raw, watchPageUrl) ?: return
                rawCandidates.add(Pair(normalized, name))
            }

            // 1. استخراج سيرفرات المشاهدة أولاً (هي الأهم والأسرع)
            val watchSelectors = listOf("ul.serversList li", "ul.servers-list li", "div.serversList li")
            for (sel in watchSelectors) {
                for (li in document.select(sel)) {
                    val serverName = li.selectFirst("p, .ser-name, span.ser-name")?.text()?.trim()
                    val candidate = li.attr("data-link").takeIf { it.isNotBlank() }
                        ?: li.selectFirst("[data-link]")?.attr("data-link")
                        ?: li.selectFirst("button[data-link]")?.attr("data-link")
                        ?: li.selectFirst("a")?.attr("href")
                    addCandidate(candidate, serverName)
                }
            }

            // 2. استخراج سيرفرات التحميل
            val downloadSelectors = listOf("ul.donwload-servers-list li", "ul.download-servers-list li")
            for (sel in downloadSelectors) {
                for (li in document.select(sel)) {
                    val serverName = li.selectFirst("span.ser-name, p")?.text()?.trim()
                    val href = li.selectFirst("a.ser-link, a")?.attr("href") ?: li.attr("data-link")
                    addCandidate(href, serverName)
                }
            }

            // 3. استخراج وسوم data-link الأخرى
            for (el in document.select("[data-link]")) {
                val serverName = el.attr("data-name").takeIf { it.isNotBlank() } ?: el.attr("data-provider")
                addCandidate(el.attr("data-link"), serverName)
            }

            // 4. استبعاد التكرار المسبق لتقليل عدد المهام
            val candidates = rawCandidates.distinctBy { it.first }

            // رفع التوازي إلى 16 رابطاً في نفس اللحظة عبر Dispatchers.IO
            val semaphore = Semaphore(16)

            suspend fun processLinkParallel(url: String, serverName: String?): Boolean {
                if (!allSeenLinks.add(url)) return false

                var targetUrl = url

                // ⚡ إذا كان الرابط hgcloud نحله فوراً
                if (targetUrl.contains("hgcloud") || (serverName != null && serverName.contains("hgcloud", ignoreCase = true))) {
                    val resolved = resolveHgCloud(targetUrl, watchPageUrl)
                    if (!resolved.isNullOrBlank()) {
                        targetUrl = resolved
                    }
                }

                // ⚡ توجيه ذكي مباشر: لا تجعل الرابط يجرب كل المستخرجات بالتتابع!
                return when {
                    // إذا كان الرابط هو سيرفر Megamax يذهب مباشرة لمستخرج Megamax فقط
                    targetUrl.contains("megamax") || targetUrl.contains("share4max") || targetUrl.contains("/mirror/") || targetUrl.contains("/iframe/") -> {
                        processMegamax(targetUrl, watchPageUrl, subtitleCallback, callback)
                    }

                    // السيرفرات الأخرى تجرب loadExtractor أولاً
                    else -> {
                        val loaded = try {
                            loadExtractor(targetUrl, watchPageUrl, subtitleCallback, callback)
                        } catch (e: Exception) { false }

                        if (!loaded) {
                            // إذا فشل المستخرج العادي، نجرب فك التشفير الشامل فوراً
                            UniversalExtractor.extract(targetUrl, watchPageUrl, subtitleCallback, callback)
                        } else true
                    }
                }
            }

            // تشغيل جميع الروابط بالتوازي التام فوراً دون انتظار أحد
            coroutineScope {
                candidates.map { pair ->
                    async(Dispatchers.IO) {
                        semaphore.withPermit {
                            processLinkParallel(pair.first, pair.second)
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
