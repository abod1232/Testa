package com.eshk

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.newExtractorLink
import com.lagradost.cloudstream3.utils.loadExtractor
import com.lagradost.cloudstream3.utils.Qualities
import org.jsoup.nodes.Element
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import java.net.URL

class FiveTVProvider : MainAPI() {
    override var mainUrl = "https://new61.5tv.lol"
    override var name = "5TV"
    override var lang = "ar"
    override val hasMainPage = true
    override val supportedTypes = setOf(
        TvType.Movie,
        TvType.TvSeries,
        TvType.AsianDrama,
        TvType.Anime
    )

    private val browserHeaders = mapOf(
        "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36",
        "Referer" to "$mainUrl/"
    )

    override val mainPage = mainPageOf(
        "$mainUrl/latest-movies/" to "جديد الأفلام",
        "$mainUrl/latest-series/" to "جديد الدراما",
        "$mainUrl/category/أنمي/" to "جديد الأنمي",
        "$mainUrl/category/البرامج-الأسيوية/" to "البرامج الأسيوية"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val document = app.get(request.data, headers = browserHeaders).document
        val homeItems = mutableListOf<SearchResponse>()
        document.select("article.ftv-home-poster-card").forEach { element ->
            val item = parseCard(element)
            if (item != null) homeItems.add(item)
        }

        return newHomePageResponse(request.name, homeItems)
    }

    private fun parseCard(element: Element): SearchResponse? {
        val title = element.selectFirst(".ftv-home-poster-card__title")?.text()?.trim() ?: return null
        val url = element.selectFirst(".ftv-home-poster-card__image")?.attr("href") ?: return null
        val poster = element.selectFirst("img")?.let {
            it.attr("src").ifEmpty { it.attr("data-src") }
        }

        val isMovie = element.hasClass("ftv-home-poster-card--movie") || url.contains("/movie/")

        return if (isMovie) {
            newMovieSearchResponse(title, url, TvType.Movie) {
                this.posterUrl = poster
            }
        } else {
            newTvSeriesSearchResponse(title, url, TvType.TvSeries) {
                this.posterUrl = poster
            }
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val searchUrl = "$mainUrl/search/?q=$query"
        val document = app.get(searchUrl, headers = browserHeaders).document

        return document.select(".ftv-search-grid article").mapNotNull { element ->
            parseCard(element)
        }
    }

    override suspend fun load(url: String): LoadResponse? {
        val document = app.get(url, headers = browserHeaders).document

        val title = document.selectFirst("h1.ftv-content-hero__title")?.text()?.trim() ?: return null
        val poster = document.selectFirst(".ftv-content-hero__poster img")?.attr("src")
        val description = document.selectFirst(".ftv-content-hero__description p")?.text()?.trim()
        val year = document.selectFirst(".ftv-content-hero__year a")?.text()?.trim()?.toIntOrNull()
        val genres = document.select(".ftv-content-hero__genres a").map { it.text().trim() }

        val isMovie = url.contains("/movie/")

        return if (isMovie) {
            newMovieLoadResponse(title, url, TvType.Movie, url) {
                this.posterUrl = poster
                this.plot = description
                this.year = year
                this.tags = genres
            }
        } else {
            val episodes = mutableListOf<Episode>()
            val episodeElements = document.select(".ftv-series-episodes-grid .ftv-episode-completion-item")

            episodeElements.forEach { el ->
                val epHref = el.selectFirst("a.ftv-series-episode-card")?.attr("href") ?: return@forEach
                val epTitle = el.selectFirst(".ftv-series-episode-card__body strong")?.text()?.trim() ?: "حلقة"
                val epNum = Regex("""\d+""").find(epTitle)?.value?.toIntOrNull()

                episodes.add(
                    newEpisode(epHref) {
                        this.name = epTitle
                        this.episode = epNum
                    }
                )
            }
            episodes.sortBy { it.episode }

            newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
                this.posterUrl = poster
                this.plot = description
                this.year = year
                this.tags = genres
            }
        }
    }

    // ====================== خوارزميات الاستخراج المخصصة (Packer & Regex) ======================

    private fun deobfuscatePacker(packed: String): String {
        try {
            val packerRegex = """eval\(function\(p,a,c,k,e,[rd]\)\{.*?\}\((['"])(.*?)\1\s*,\s*(\d+)\s*,\s*(\d+)\s*,\s*(['"])(.*?)\5\.split\(['"]\|['"]\)""".toRegex()
            val match = packerRegex.find(packed) ?: return ""
            val (_, p, aStr, cStr, _, kStr) = match.destructured
            val a = aStr.toIntOrNull() ?: return ""
            val c = cStr.toIntOrNull() ?: return ""
            val k = kStr.split('|')

            fun getBaseNString(num: Int, base: Int): String {
                val chars = "0123456789abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ"
                if (base <= 36) return num.toString(base)
                var n = num
                var res = ""
                while (n > 0) {
                    res = chars[n % base] + res
                    n /= base
                }
                return if (res.isEmpty()) "0" else res
            }

            val dict = mutableMapOf<String, String>()
            for (i in 0 until c) {
                val key = getBaseNString(i, a)
                val value = if (i < k.size && k[i].isNotEmpty()) k[i] else key
                dict[key] = value
            }

            val wordRegex = """\b\w+\b""".toRegex()
            return wordRegex.replace(p) { matchResult ->
                val word = matchResult.value
                dict[word] ?: word
            }
        } catch (e: Exception) {
            return ""
        }
    }

    private fun extractLogic(htmlText: String): String {
        try {
            val startMarker = "eval(function(p,a,c,k,e,d)"
            val startIdx = htmlText.indexOf(startMarker)
            if (startIdx != -1) {
                val endMarker = ".split('|')))"
                val endIdx = htmlText.indexOf(endMarker, startIdx)
                if (endIdx != -1) {
                    val packedJs = htmlText.substring(startIdx, endIdx + endMarker.length)
                    val unpacked = deobfuscatePacker(packedJs)
                    if (unpacked.isNotEmpty()) {
                        return unpacked
                    }
                }
            }
        } catch (e: Exception) {
        }
        return htmlText
    }

    private fun findVideoSource(text: String): String? {
        // 1. البحث عن ملفات m3u8
        val m3u8Regex = """(https?://[^\s"\'<>]+?\.m3u8[^\s"\'<>?]*(?:\?[^\s"\'<>]*)?)""".toRegex()
        val m3u8Match = m3u8Regex.find(text)
        if (m3u8Match != null) return m3u8Match.groupValues[1]

        // 2. البحث داخل خاصية file: '...' أو file: "..."
        val fileRegex = """file\s*:\s*["']([^"']+)["']""".toRegex()
        val fileMatch = fileRegex.find(text)
        if (fileMatch != null) return fileMatch.groupValues[1]

        // 3. البحث عن ملفات mp4
        val mp4Regex = """(https?://[^\s"\'<>]+?\.mp4[^\s"\'<>?]*(?:\?[^\s"\'<>]*)?)""".toRegex()
        val mp4Match = mp4Regex.find(text)
        return mp4Match?.groupValues?.get(1)
    }

    // =========================================================================================

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val document = try {
            app.get(data, headers = browserHeaders).document
        } catch (e: Exception) {
            return false
        }

        val serverUrls = mutableSetOf<String>()

        // 1. استخراج سيرفرات المشاهدة من الـ Tabs
        document.select(".ftv-watch-tabs button[data-server-url]").forEach { tab ->
            val serverUrl = tab.attr("data-server-url").trim()
            if (serverUrl.isNotEmpty()) serverUrls.add(serverUrl)
        }

        // 2. استخراج المشغل المباشر
        val iframeSrc = document.selectFirst(".ftv-player-iframe")?.attr("src")?.trim() ?: ""
        if (iframeSrc.isNotEmpty()) serverUrls.add(iframeSrc)

        // 3. استخراج سيرفرات التحميل
        document.select(".ftv-download-list article.ftv-download-server a").forEach { link ->
            val downloadUrl = link.attr("href").trim()
            if (downloadUrl.isNotEmpty()) serverUrls.add(downloadUrl)
        }

        // معالجة جميع السيرفرات بالتوازي وبشكل غير متزامن (نفس طريقة Elif)
        coroutineScope {
            serverUrls.map { serverUrl ->
                async {
                    try {
                        val fixedUrl = fixUrl(serverUrl)

                        // محاولة 1: تجربة مستخرج CloudStream التلقائي أولاً
                        loadExtractor(fixedUrl, data, subtitleCallback, callback)

                        // محاولة 2: المعالجة المخصصة (Custom Scraper) في حال لم يتعرف عليه كلاودستريم
                        val uri = URL(fixedUrl)
                        val domain = "${uri.protocol}://${uri.host}/"
                        val serverName = uri.host.replace("www.", "").lowercase()

                        val serverResponse = app.get(
                            fixedUrl,
                            headers = mapOf(
                                "Referer" to data,
                                "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"
                            )
                        )

                        if (serverResponse.code == 200) {
                            val content = extractLogic(serverResponse.text)
                            val videoLink = findVideoSource(content)

                            if (!videoLink.isNullOrEmpty()) {
                                val finalVideoUrl = fixUrl(videoLink).replace("\\/", "/")

                                val isM3u8 = finalVideoUrl.contains(".m3u8")

                                callback(
                                    newExtractorLink(
                                        source = name,
                                        name = "[5TV] $serverName",
                                        url = finalVideoUrl,
                                        type = if (isM3u8) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                                    ) {
                                        this.referer = domain
                                        this.quality = Qualities.P1080.value
                                        this.headers = mapOf(
                                            "Referer" to domain,
                                            "Origin" to domain.removeSuffix("/")
                                        )
                                    }
                                )
                            }
                        }
                    } catch (e: Exception) {
                        // تجاهل أي سيرفر يتعطل والانتقال للسيرفر التالي
                    }
                }
            }.awaitAll()
        }

        return true
    }
}
