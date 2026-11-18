package com.mycima

import android.util.Base64
import android.util.Log
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.net.URI
import java.net.URL
import java.net.URLEncoder

class MyCimaProvider : MainAPI() {
    override var lang = "ar"
    override var mainUrl = "https://mycima.recipes"
    override var name = "MyCima"
    override val usesWebView = false
    override val hasMainPage = true
    override val supportedTypes =
        setOf(TvType.TvSeries, TvType.Movie, TvType.Anime, TvType.AsianDrama)

    companion object {
        const val TAG = "MyCima"
        var redirectUrl: String? = null
        private const val USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"
        private const val REQUEST_DELAY = 700L  // تأخير 0.7 ثانية بين إطلاق كل طلب
        private const val MAX_AJAX_RETRIES = 15 // أقصى عدد لمحاولات الإعادة للفاشلات
    }

    private suspend fun baseUrl(): String {
        redirectUrl?.let { return it }

        return try {
            val response = app.get(mainUrl, allowRedirects = true)
            val finalUrl = response.url

            val base = try {
                val uri = URI(finalUrl)
                "${uri.scheme}://${uri.host}"
            } catch (e: Exception) {
                mainUrl
            }

            redirectUrl = base
            mainUrl = base
            Log.d(TAG, "Resolved main domain: $base")
            base
        } catch (e: Exception) {
            mainUrl
        }
    }

    private fun getHeaders(referer: String? = null): Map<String, String> {
        val activeBase = redirectUrl ?: mainUrl
        val safeReferer = referer?.takeIf { it.all { ch -> ch.code in 32..126 } } ?: activeBase

        return mapOf(
            "User-Agent" to USER_AGENT,
            "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8",
            "Accept-Language" to "ar,en-US;q=0.9,en;q=0.8",
            "Upgrade-Insecure-Requests" to "1",
            "Cache-Control" to "no-cache",
            "Pragma" to "no-cache",
            "Referer" to safeReferer
        )
    }

    private suspend fun smartGet(
        url: String,
        referer: String? = null,
        timeoutSeconds: Long = 20L
    ): Document {
        return try {
            app.get(
                url,
                headers = getHeaders(referer),
                timeout = timeoutSeconds
            ).document
        } catch (e: Exception) {
            Log.e(TAG, "smartGet failed for $url: ${e.message}")
            Jsoup.parse("<html><body>Error loading page</body></html>", url)
        }
    }

    private suspend fun smartPost(
        url: String,
        data: Map<String, String>,
        referer: String? = null,
        timeoutSeconds: Long = 20L
    ): Document {
        return try {
            val headers = getHeaders(referer).toMutableMap().apply {
                put("Content-Type", "application/x-www-form-urlencoded; charset=UTF-8")
                put("X-Requested-With", "XMLHttpRequest")
            }

            app.post(
                url,
                data = data,
                headers = headers,
                timeout = timeoutSeconds
            ).document
        } catch (e: Exception) {
            Log.e(TAG, "smartPost failed for $url: ${e.message}")
            Jsoup.parse("", url)
        }
    }

    private fun extractNumbers(text: String?): Int? {
        if (text.isNullOrBlank()) return null
        return Regex("""\d+""").find(text)?.value?.toIntOrNull()
    }

    private fun getPosterFromStyle(element: Element?): String? {
        val style = element?.attr("style")?.ifBlank { null } ?: element?.attr("data-lazy-style")
        return style?.let {
            Regex("""url\((.*?)\)""").find(it)?.groupValues?.get(1)
                ?.trim('\'', '"', ' ')
                ?.ifBlank { null }
        }
    }

    private fun extractServerName(element: Element): String {
        return (element.ownText().ifBlank { element.text() }).replace(Regex("\\s+"), " ").trim()
    }

    private fun String.encodeURL(): String {
        return try {
            URLEncoder.encode(this, "UTF-8")
        } catch (e: Exception) {
            this
        }
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val linkElement = this.selectFirst("div.Thumb--GridItem a") ?: return null
        val url = linkElement.attr("href")
        if (url.isBlank()) return null

        val posterUrl = getPosterFromStyle(linkElement.selectFirst("span.BG--GridItem"))
        val titleTag = linkElement.selectFirst("strong") ?: return null
        val title = titleTag.ownText().trim().ifBlank { linkElement.attr("title").trim() }
        val year = titleTag.selectFirst("span.year")?.text()?.let { extractNumbers(it) }

        val isMovie = this.selectFirst("div.Episode--number") == null && !url.contains("/series/")

        return if (isMovie) {
            newMovieSearchResponse(title, url, TvType.Movie) {
                this.posterUrl = posterUrl
                this.year = year
            }
        } else {
            newTvSeriesSearchResponse(title, url, TvType.TvSeries) {
                this.posterUrl = posterUrl
                this.year = year
            }
        }
    }

    override val mainPage = mainPageOf(
        "$mainUrl/" to "احدث الاضافات",
        "$mainUrl/movies/" to "افلام جديدة",
        "$mainUrl/series/" to "مسلسلات جديدة",
        "$mainUrl/category/افلام-اجنبي/" to "افلام اجنبي",
        "$mainUrl/category/مسلسلات-عربي/" to "مسلسلات عربي",
        "$mainUrl/category/افلام-انمي/" to "أفلام أنمي",
        "$mainUrl/category/مسلسلات-انمي/" to "مسلسلات أنمي",
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        return try {
            val activeBase = baseUrl().trimEnd('/')
            val baseRequestUrl = request.data.replace(Regex("^https?://[^/]+"), activeBase)
            val url = if (page > 1) {
                "${baseRequestUrl.removeSuffix("/")}/page/$page/"
            } else {
                baseRequestUrl
            }

            val document = smartGet(url)

            val isBannerRequest = request.name == "احدث الاضافات" && page == 1
            val selector =
                "div.Grid--WecimaPosts div.GridItem, div#MainFiltar div.GridItem, div.Slider--Grid div.GridItem"
            val list = document.select(selector).mapNotNull { it.toSearchResult() }

            val homePageList = HomePageList(
                name = request.name,
                list = list,
                isHorizontalImages = isBannerRequest
            )

            newHomePageResponse(homePageList)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load page $page for ${request.name}", e)
            val homePageList = HomePageList(
                name = request.name,
                list = emptyList(),
                isHorizontalImages = false
            )
            newHomePageResponse(homePageList)
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val url = "${baseUrl()}/filtering/?keywords=${query.encodeURL()}"
        val document = smartGet(url)
        return document.select("div#MainFiltar div.GridItem").mapNotNull { it.toSearchResult() }
    }

    private fun parseSeasonNumber(text: String): Int? {
        Regex("(\\d+)").find(text)?.groupValues?.get(1)?.toIntOrNull()?.let { return it }

        val normalized = text
            .replace(Regex("[\u064B-\u065F\u0670\u0640]"), "")
            .replace('أ', 'ا')
            .replace('إ', 'ا')
            .replace('آ', 'ا')
            .replace('ٱ', 'ا')
            .replace('ى', 'ي')
            .replace('ؤ', 'و')
            .replace('ئ', 'ي')
            .replace(Regex("\\s+"), " ")
            .trim()

        val ordinals = listOf(
            "الحادي والثلاثون" to 31, "الحادي والثلاثين" to 31, "الواحد والثلاثون" to 31, "الواحد والثلاثين" to 31,
            "الثاني والثلاثون" to 32, "الثاني والثلاثين" to 32,
            "الثالث والثلاثون" to 33, "الثالث والثلاثين" to 33,
            "الرابع والثلاثون" to 34, "الرابع والثلاثين" to 34,
            "الخامس والثلاثون" to 35, "الخامس والثلاثين" to 35,
            "الثلاثون" to 30, "الثلاثين" to 30,
            "الحادي والعشرون" to 21, "الحادي والعشرين" to 21, "الواحد والعشرون" to 21, "الواحد والعشرين" to 21,
            "الثاني والعشرون" to 22, "الثاني والعشرين" to 22,
            "الثالث والعشرون" to 23, "الثالث والعشرين" to 23,
            "الرابع والعشرون" to 24, "الرابع والعشرين" to 24,
            "الخامس والعشرون" to 25, "الخامس والعشرين" to 25,
            "السادس والعشرون" to 26, "السادس والعشرين" to 26,
            "السابع والعشرون" to 27, "السابع والعشرين" to 27,
            "الثامن والعشرون" to 28, "الثامن والعشرين" to 28,
            "التاسع والعشرون" to 29, "التاسع والعشرين" to 29,
            "العشرون" to 20, "العشرين" to 20,
            "الحادي عشر" to 11, "الواحد عشر" to 11,
            "الثاني عشر" to 12,
            "الثالث عشر" to 13,
            "الرابع عشر" to 14,
            "الخامس عشر" to 15,
            "السادس عشر" to 16,
            "السابع عشر" to 17,
            "الثامن عشر" to 18,
            "التاسع عشر" to 19,
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

    

    override suspend fun load(url: String): LoadResponse? {
        return try {
            val document = smartGet(url)
            val currentBase = try {
                val u = URL(url)
                "${u.protocol}://${u.host}"
            } catch (_: Exception) {
                baseUrl()
            }

            val isSeriesPage = document.selectFirst(
                "div.SeasonsList ul li a, div.Seasons--Episodes, div.EpisodesList a, singlesection.Series--Section a"
            ) != null

            val seriesTitleFromSection = document.selectFirst("singlesection.Series--Section h2")?.text()
                ?.replace("حلقات ", "")
                ?.trim()
                ?.ifBlank { null }

            var rawTitle = if (isSeriesPage && !seriesTitleFromSection.isNullOrBlank()) {
                seriesTitleFromSection
            } else {
                document.selectFirst("div.Title--Content--Single-begin > h1")?.ownText()
                    ?.trimEnd('(', ' ', ')')?.trim()
            }

            if (rawTitle.isNullOrBlank()) {
                rawTitle = document.selectFirst("meta[property='og:title']")?.attr("content")?.trim()
                    ?.replace(" - وي سيما WECIMA ماي سيما MYCIMA", "")
                    ?.replace(" - ماي سيما", "")
            }
            if (rawTitle.isNullOrBlank()) {
                rawTitle = document.title()
            }
            val finalTitle = rawTitle?.trim()?.takeIf { it.isNotBlank() } ?: return null

            val poster = getPosterFromStyle(document.selectFirst("wecima.separated--top"))
                ?: document.selectFirst("meta[property='og:image']")?.attr("content")?.ifBlank { null }

            val year = document.selectFirst("div.Title--Content--Single-begin h1 a")?.text()
                ?.trim()?.toIntOrNull()
                ?: extractNumbers(document.selectFirst("div.Title--Content--Single-begin h1")?.text())

            val plot = document.selectFirst("div.StoryMovieContent")?.text()?.trim()?.ifBlank { null }
                ?: document.selectFirst("div.AsideContext p, div.AsideContext div")?.text()?.trim()?.ifBlank { null }
                ?: document.selectFirst("div.PostItemContent")?.text()?.trim()?.ifBlank { null }
                ?: document.selectFirst("meta[property='og:description']")?.attr("content")?.trim()?.ifBlank { null }
                ?: document.selectFirst("meta[name='description']")?.attr("content")?.trim()?.ifBlank { null }

            val tags = document.select("ul.Terms--Content--Single-begin li:has(span:contains(النوع)) p a")
                .map { it.text().trim() }

            val recommendations = document.select("div.Grid--WecimaPosts div.GridItem")
                .mapNotNull { it.toSearchResult() }

            val seriesUrlFromEpisode = document
                .selectFirst("singlesection.Series--Section > a, ul.Terms--Content--Single-begin li:contains(المسلسل) a")
                ?.attr("href")

            fun extractPostId(doc: Document): String? {
                val html = doc.html()
                Regex("""Single/Episodes\.php.*?post_id:\s*['"](\d+)['"]""", RegexOption.DOT_MATCHES_ALL)
                    .find(html)?.groupValues?.get(1)?.let { return it }
                doc.selectFirst("input[name=post_id]")?.attr("value")?.takeIf { it.isNotBlank() }?.let { return it }
                doc.selectFirst("[data-post_id]")?.attr("data-post_id")?.takeIf { it.isNotBlank() }?.let { return it }
                doc.selectFirst("[data-postid]")?.attr("data-postid")?.takeIf { it.isNotBlank() }?.let { return it }
                doc.selectFirst("link[rel=shortlink]")?.attr("href")?.let {
                    Regex("p=(\\d+)").find(it)?.groupValues?.get(1)
                }?.let { return it }
                val scriptsText = doc.select("script").joinToString(" ") { it.data() }
                Regex("""post_id['"]?\s*[:=]\s*['"]?(\d{3,})['"]?""").find(scriptsText)?.groupValues?.get(1)?.let { return it }
                return null
            }

            fun extractEpisodeNumberFromText(text: String?): Int? {
                if (text.isNullOrBlank()) return null
                Regex("""الحلقة\s*(\d+)""").find(text)?.groupValues?.get(1)?.toIntOrNull()?.let { return it }
                Regex("""\b(\d{1,4})\b""").find(text)?.groupValues?.get(1)?.toIntOrNull()?.let { return it }
                return null
            }

            fun resolveUrl(base: String, relative: String): String {
                return try {
                    URL(URL(base), relative).toString()
                } catch (e: Exception) {
                    if (relative.startsWith("http")) relative else currentBase.trimEnd('/') + "/" + relative.trimStart('/')
                }
            }

            fun parseEpisodesFromAnchors(
                anchors: List<Element>,
                seasonLabel: String?,
                seasonNumber: Int?
            ): List<Episode> {
                var counter = 0
                return anchors.mapNotNull { a ->
                    val rawHref = a.attr("href").ifBlank { a.attr("data-href") }
                    if (rawHref.isBlank() || rawHref.startsWith("#") || rawHref.contains("season", ignoreCase = true)) {
                        return@mapNotNull null
                    }
                    val epHref = resolveUrl(url, rawHref)

                    val epTitleRaw = a.selectFirst("episodetitle, .episodetitle")?.text()?.trim()
                        ?: a.attr("title").ifBlank { a.text() }.trim()
                    
                    val epNum = extractEpisodeNumberFromText(epTitleRaw) ?: run {
                        counter++
                        counter
                    }

                    val epName = if (!seasonLabel.isNullOrBlank()) {
                        "$seasonLabel الحلقة $epNum"
                    } else {
                        "الحلقة $epNum"
                    }

                    newEpisode(epHref) {
                        this.name = epName
                        this.season = seasonNumber
                        this.episode = epNum
                        this.posterUrl = poster
                    }
                }
            }

            if (isSeriesPage) {
                val htmlText = document.html()
                val ajaxBase = Regex("""var\s+AjaxtURL\s*=\s*"([^"]+)"""")
                    .find(htmlText)?.groupValues?.get(1)
                    ?: "$currentBase/wp-content/themes/mycima/Ajaxt/"
                val ajaxUrl = "${ajaxBase.trimEnd('/')}/Single/Episodes.php"
                val postId = extractPostId(document)
                val allAnchors = document.select("div.SeasonsList ul li a, .Seasons--Episodes ul li a, div.SeasonsList a")
                val seasonAnchors = allAnchors.filter { a ->
                    val text = a.text().trim()
                    !text.contains("حلقة") && (text.contains("الموسم") || text.contains("موسم") || a.hasAttr("data-season"))
                }

                val allEpisodes = mutableListOf<Episode>()

                if (seasonAnchors.isEmpty()) {
                    val anchors = document.select("div.EpisodesList a[href], div.Episodes--Seasons--Episodes a[href]")
                    allEpisodes.addAll(parseEpisodesFromAnchors(anchors, null, 1))
                } else {
                    data class SeasonTask(
                        val id: String,
                        val label: String,
                        val number: Int
                    )

                    val parsedNums = seasonAnchors.map { parseSeasonNumber(it.text()) }
                    val usedNumbers = parsedNums.filterNotNull().toMutableSet()
                    var nextMissing = 1
                    val resolvedSeasonNums = parsedNums.map { known ->
                        if (known != null) {
                            known
                        } else {
                            while (usedNumbers.contains(nextMissing)) nextMissing++
                            val assigned = nextMissing
                            usedNumbers.add(assigned)
                            nextMissing++
                            assigned
                        }
                    }

                    val pendingTasks = mutableListOf<SeasonTask>()
                    val activeBox = document.select("div.EpisodesList a[href], div.Episodes--Seasons--Episodes a[href]")

                    seasonAnchors.forEachIndexed { idx, seasonEl ->
                        val seasonNumber = resolvedSeasonNums[idx]
                        val rawSeasonText = seasonEl.text().trim()
                        val seasonLabel = rawSeasonText
                            .replace(Regex("\\s*(مترجم|مدبلج).*"), "")
                            .trim()
                            .ifBlank { "الموسم $seasonNumber" }

                        val seasonId = seasonEl.attr("data-season").ifBlank { seasonEl.attr("data-season-id") }
                        val isActiveSeason = seasonEl.parent()?.hasClass("active") == true || seasonEl.hasClass("active")
                        if (isActiveSeason && activeBox.isNotEmpty()) {
                            val inPageEpisodes = parseEpisodesFromAnchors(activeBox, seasonLabel, seasonNumber)
                            if (inPageEpisodes.isNotEmpty()) {
                                allEpisodes.addAll(inPageEpisodes)
                                return@forEachIndexed
                            }
                        }

                        if (seasonId.isNotBlank()) {
                            pendingTasks.add(SeasonTask(seasonId, seasonLabel, seasonNumber))
                        }
                    }
                    suspend fun fetchStaggered(tasks: List<SeasonTask>): Pair<List<Episode>, List<SeasonTask>> {
                        val failed = mutableListOf<SeasonTask>()
                        val fetched = coroutineScope {
                            tasks.mapIndexed { index, task ->
                                if (index > 0) {
                                    kotlinx.coroutines.delay(REQUEST_DELAY)
                                }
                                async(Dispatchers.IO) {
                                    try {
                                        if (!postId.isNullOrBlank()) {
                                            val seasonDoc = smartPost(
                                                ajaxUrl,
                                                data = mapOf("season" to task.id, "post_id" to postId),
                                                referer = currentBase,
                                                timeoutSeconds = 12L
                                            )
                                            val anchors = seasonDoc.select("a[href]")
                                            val eps = parseEpisodesFromAnchors(anchors, task.label, task.number)
                                            if (eps.isNotEmpty()) {
                                                return@async Pair(eps, null)
                                            }
                                        }
                                    } catch (_: Exception) {
                                    }
                                    Pair(emptyList<Episode>(), task)
                                }
                            }.awaitAll()
                        }

                        val successEps = mutableListOf<Episode>()
                        fetched.forEach { (eps, failTask) ->
                            if (eps.isNotEmpty()) {
                                successEps.addAll(eps)
                            } else if (failTask != null) {
                                failed.add(failTask)
                            }
                        }
                        return Pair(successEps, failed)
                    }
                    var (stageEps, failedTasks) = fetchStaggered(pendingTasks)
                    allEpisodes.addAll(stageEps)
                    var retryCount = 1
                    while (failedTasks.isNotEmpty() && retryCount <= MAX_AJAX_RETRIES) {
                        kotlinx.coroutines.delay(800L) // انتظار بسيط قبل جولة الإعادة
                        val (retryEps, stillFailed) = fetchStaggered(failedTasks)
                        allEpisodes.addAll(retryEps)
                        failedTasks = stillFailed
                        retryCount++
                    }
                }

                val distinctEpisodes = allEpisodes.distinctBy { it.data }
                    .sortedWith(compareBy({ it.season }, { it.episode }))

                val seriesPageUrl = seriesUrlFromEpisode?.let { resolveUrl(url, it) } ?: url
                return newTvSeriesLoadResponse(finalTitle, seriesPageUrl, TvType.TvSeries, distinctEpisodes) {
                    this.posterUrl = poster
                    this.year = year
                    this.plot = plot
                    this.tags = tags
                    this.recommendations = recommendations
                }
            } else if (!seriesUrlFromEpisode.isNullOrBlank()) {
                return load(resolveUrl(url, seriesUrlFromEpisode))
            } else {
                return newMovieLoadResponse(finalTitle, url, TvType.Movie, url) {
                    this.posterUrl = poster
                    this.year = year
                    this.plot = plot
                    this.tags = tags
                    this.recommendations = recommendations
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error loading page: $url", e)
            e.printStackTrace()
            return null
        }
    }

    private fun fixBase64Padding(text: String): String {
        val cleanText = text.trim().replace(" ", "").replace("\n", "")
        val missingPadding = cleanText.length % 4
        return if (missingPadding > 0) {
            cleanText + "=".repeat(4 - missingPadding)
        } else {
            cleanText
        }
    }

    private fun smartDecode(payload: String): String? {
        try {
            val decoded =
                String(Base64.decode(fixBase64Padding(payload), Base64.DEFAULT), Charsets.UTF_8)
            if (decoded.contains("http")) return decoded
            val reversed = payload.reversed()
            val decodedRev =
                String(Base64.decode(fixBase64Padding(reversed), Base64.DEFAULT), Charsets.UTF_8)
            if (decodedRev.contains("http")) return decodedRev
        } catch (_: Exception) {
        }
        return null
    }

    private suspend fun deepExtractGovid(url: String, referer: String): String {
        try {
            val reqHeaders = mapOf(
                "User-Agent" to USER_AGENT,
                "Referer" to referer
            )
            val res = app.get(url, headers = reqHeaders, timeout = 10L)

            if (!res.isSuccessful) return url

            val html = res.text
            val atobRegex = Regex("""atob\s*\(\s*["']([^"']+)["']\s*\)""")
            val atobMatches = atobRegex.findAll(html)
            for (match in atobMatches) {
                val decoded = smartDecode(match.groupValues[1])
                if (decoded != null) return decoded
            }
            val iframeSrc = res.document.selectFirst("iframe")?.attr("src")
            if (!iframeSrc.isNullOrBlank()) {
                return if (iframeSrc.startsWith("//")) "https:$iframeSrc" else iframeSrc
            }
            val directRegex = Regex("""["'](https?://[^"']+\.(?:m3u8|mp4|php)[^"']*)["']""")
            val directMatch = directRegex.find(html)
            if (directMatch != null) {
                return directMatch.groupValues[1].replace("\\/", "/")
            }
        } catch (_: Exception) {
        }
        return url
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val activeBase = baseUrl()
        val document = smartGet(data, referer = activeBase)

        val linksToProcess = mutableListOf<Pair<String, String>>()
        document.select("ul#watch li[data-watch]").forEach {
            val url = it.attr("data-watch")
            val name = extractServerName(it)
            if (url.isNotBlank()) linksToProcess.add(url to name)
        }
        document.select("ul.List--Download--Wecima--Single li a[href]").forEach {
            val url = it.attr("href")
            val name = it.selectFirst("quality")?.text()?.trim() ?: "تحميل"
            if (url.isNotBlank()) linksToProcess.add(url to name)
        }

        coroutineScope {
            linksToProcess.distinctBy { it.first }.map { (link, serverName) ->
                async {
                    var finalUrl = link.trim()
                    if (finalUrl.contains("govid")) {
                        if (finalUrl.contains("=") && !finalUrl.contains("pic=")) {
                            val encodedPart = finalUrl.substringAfterLast("=")
                            val decoded = smartDecode(encodedPart)
                            finalUrl = decoded ?: deepExtractGovid(finalUrl, activeBase)
                        } else {
                            finalUrl = deepExtractGovid(finalUrl, activeBase)
                        }
                    } else if (finalUrl.contains("/go/")) {
                        val encodedPart = finalUrl.substringAfterLast("/go/").trimEnd('/')
                        val decoded = smartDecode(encodedPart)
                        if (decoded != null) {
                            finalUrl = decoded
                        }
                    }
                    if (finalUrl.isNotBlank() && finalUrl.startsWith("http")) {
                        loadExtractor(finalUrl, activeBase, subtitleCallback, callback)
                    }
                }
            }.awaitAll()
        }

        return true
    }
}
