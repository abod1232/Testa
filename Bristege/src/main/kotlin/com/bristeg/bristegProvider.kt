package com.bristeg

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.loadExtractor
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.Qualities
import org.jsoup.nodes.Element
import org.jsoup.nodes.Document
import com.lagradost.cloudstream3.utils.newExtractorLink
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale
import com.lagradost.cloudstream3.utils.JsUnpacker

fun getQualityFromName(name: String?): Int {
    return when (name?.lowercase(Locale.ROOT)) {
        "1080p" -> Qualities.P1080.value
        "720p" -> Qualities.P720.value
        "480p" -> Qualities.P480.value
        "360p" -> Qualities.P360.value
        else -> Qualities.Unknown.value
    }
}

class BrstejProvider : MainAPI() {

    override var mainUrl = "https://hd1.brstej.com"
    override var name = "bristege"
    override val hasMainPage = true
    override var lang = "ar"
    override val supportedTypes = setOf(
        TvType.TvSeries,
        TvType.Movie
    )
    private val standardHeaders = mapOf(
        "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36",
        "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8",
        "Accept-Language" to "ar,en-US;q=0.9,en;q=0.8",
        "Connection" to "keep-alive"
    )

    private suspend fun getDocument(url: String, referer: String? = null): Document {
        val headers = standardHeaders.toMutableMap()
        if (referer != null) headers["Referer"] = referer
        return app.get(url, headers = headers, timeout = 25).document
    }

    private fun cleanPosterUrl(url: String?): String? {
        if (url == null || url.isBlank()) return null
        var u = url.trim()
        val wpProxy = Regex("""https?://i\d+\.wp\.com/(.+)""")
        val m = wpProxy.find(u)
        if (m != null) {
            u = m.groupValues[1]
            if (!u.startsWith("http://") && !u.startsWith("https://")) {
                u = "https://$u"
            }
        }
        u = u.split("?")[0]
        if (u.startsWith("//")) u = "https:$u"
        if (!u.startsWith("http://") && !u.startsWith("https://")) {
            val base = mainUrl.trimEnd('/')
            when {
                u.startsWith("/") -> u = "$base$u"
                else -> u = "$base/$u"
            }
        }
        return u
    }

    private fun buildAbsoluteUrl(href: String?, base: String = mainUrl): String {
        if (href.isNullOrBlank()) return ""
        var h = href.trim()
        if (h.startsWith("http://") || h.startsWith("https://")) return h
        if (h.startsWith("./")) h = h.removePrefix("./")
        val baseTrim = base.trimEnd('/')
        return if (h.startsWith("/")) "$baseTrim$h" else "$baseTrim/$h"
    }

    private fun Element.toSearchResponse(): SearchResponse? {
        val seriesCard = this.selectFirst("a.pcg-series-card")
        if (seriesCard != null) {
            val href = seriesCard.attr("href")
            val title = seriesCard.attr("title").ifBlank {
                seriesCard.selectFirst(".pcg-series-name span")?.text() ?: ""
            }.trim()

            if (href.isBlank() || title.isBlank()) return null

            val img = seriesCard.selectFirst(".pcg-series-image img")
            val rawPoster = img?.attr("src") ?: img?.attr("data-original")
            val posterUrl = cleanPosterUrl(rawPoster)

            return newTvSeriesSearchResponse(title, buildAbsoluteUrl(href), TvType.TvSeries) {
                this.posterUrl = posterUrl
            }
        }

        val titleLinkElement = this.selectFirst(".prs-caption h3 a, .pcg-caption h3 a, div.caption h3 a, a.pcg-card-title")
            ?: this.selectFirst("a.prs-image, div.pm-video-thumb a:has(img), .pcg-media a:has(img)")
            ?: return null

        val href = titleLinkElement.attr("href")
        if (href.isBlank() || href.startsWith("#") || href.contains("modal-login")) {
            return null
        }

        val title = titleLinkElement.attr("title").ifBlank { titleLinkElement.text() }.trim()
        if (title.isBlank()) {
            return null
        }

        val img = this.selectFirst(".prs-image img, .pcg-media img, div.pm-video-thumb img")
        val rawPoster = img?.attr("data-echo")
            ?: img?.attr("data-original")
            ?: img?.attr("src")
        val posterUrl = cleanPosterUrl(rawPoster)

        val fullUrl = buildAbsoluteUrl(href)
        val isSeries = fullUrl.contains("series1.php") || fullUrl.contains("view-serie.php") || title.contains("مسلسل")

        return if (isSeries) {
            newTvSeriesSearchResponse(title, fullUrl, TvType.TvSeries) {
                this.posterUrl = posterUrl
            }
        } else {
            newMovieSearchResponse(title, fullUrl, TvType.Movie) {
                this.posterUrl = posterUrl
            }
        }
    }

    override val mainPage = mainPageOf(
        "home03" to "الرئيسية",
        "cat03.php?cat=prss7-2025" to "مسلسلات برستيج",
        "cat03.php?cat=movies2-2224" to "افلام",
        "cat03.php?cat=ramdan2026" to "مسلسلات رمضان 2026",
        "cat03.php?cat=arab8-2025" to "مسلسلات عربية",
        "cat03.php?cat=ty9-2025" to "مسلسلات تركية 2026",
        "cat03.php?cat=eg8-2025" to "مسلسلات مصرية",
        "cat03.php?cat=syy5-2025" to "مسلسلات شامية",
        "cat03.php?cat=5a7-2024" to "مسلسلات خليجية",
        "new-videos.php" to "أحدث الإضافات"
    )

    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse {
        val separator = if (request.data.contains("?")) "&" else "?"
        val url = if (page > 1) {
            "$mainUrl/${request.data}${separator}page=$page"
        } else {
            "$mainUrl/${request.data}"
        }

        try {
            val document = getDocument(url)

            val selector = """
                ul.pcg-series-grid > li, 
                ul.pcg-episodes-grid > li, 
                ul.pcg-featured-grid > li, 
                ul[class*='pm-ul-browse-videos'] > li, 
                ul[class*='pm-ul-carousel-videos'] > li
            """.trimIndent()

            val items = document.select(selector)

            val home = items.mapNotNull {
                it.toSearchResponse()
            }.distinctBy { it.url }

            return newHomePageResponse(request.name, home)
        } catch (e: Exception) {
            throw e
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val url = "$mainUrl/search.php?keywords=$query"

        try {
            val document = getDocument(url)

            val selector = """
                ul.prs-grid > li,
                article.prs-card,
                ul.pm-ul-browse-videos > li,
                ul.pcg-episodes-grid > li
            """.trimIndent()

            val items = document.select(selector)

            val results = items.mapNotNull {
                it.toSearchResponse()
            }.distinctBy { it.url }

            return results
        } catch (e: Exception) {
            throw e
        }
    }

    override suspend fun load(url: String): LoadResponse? {
        try {
            val document = getDocument(url)

            val title = document.selectFirst("#pds-title, .pwr-heading h1, div.pm-video-heading h1, h1")?.text()?.trim()
                ?: return null

            val poster = cleanPosterUrl(
                document.selectFirst("meta[property=og:image]")?.attr("content")
                    ?: document.selectFirst(".pds-art img, .pwu-poster-image, #BiBplayer img")?.attr("src")
            )

            val description = document.selectFirst(".pds-description, .pwr-description p, div.pm-video-description > div.txtv, meta[property=og:description]")?.text()?.trim()

            val categoryTags = document.select(".pds-crumb li a, .pwr-breadcrumb a, dl.dl-horizontal p strong:contains(اقسام) ~ span a span")
                .map { it.text().trim() }
                .filter { it.isNotBlank() && !it.contains("الرئيسية") && !it.contains("الأقسام") }

            val keywordTags = document.select(".pwr-meta dd a, dl.dl-horizontal strong:contains(الكلمات الدلالية) ~ a, meta[name=keywords]")
                .map { it.text().trim() }
                .filter { it.isNotBlank() }

            val allTags = (categoryTags + keywordTags).distinct()
            val isMovie = allTags.any { it.contains("افلام") || it.contains("فلم") } || title.contains("فيلم")

            val episodes = mutableListOf<Episode>()
            val seasonSections = document.select("section.pds-season")
            if (seasonSections.isNotEmpty()) {
                for (seasonSec in seasonSections) {
                    val seasonName = seasonSec.selectFirst(".pds-season-heading h3")?.text() ?: ""
                    val seasonNum = Regex("""\d+""").find(seasonName)?.value?.toIntOrNull() 
                        ?: Regex("""pds-season-(\d+)""").find(seasonSec.id())?.groupValues?.get(1)?.toIntOrNull() 
                        ?: 1

                    seasonSec.select(".pds-episodes article.pds-episode").forEach { epArticle ->
                        val epLink = epArticle.selectFirst("h4 a, a.pds-thumb") ?: return@forEach
                        val epUrl = buildAbsoluteUrl(epLink.attr("href"))
                        val epName = epArticle.selectFirst("h4 a")?.attr("title")?.ifBlank { epArticle.selectFirst("h4 a")?.text() } 
                            ?: epLink.text().trim()
                        val epNum = Regex("""\d+""").find(epName)?.value?.toIntOrNull() 
                            ?: epArticle.attr("data-order").toIntOrNull()?.plus(1)
                        val epPoster = cleanPosterUrl(epArticle.selectFirst("img")?.attr("src")) ?: poster

                        episodes.add(
                            newEpisode(epUrl) {
                                this.name = epName
                                this.season = seasonNum
                                this.episode = epNum
                                this.posterUrl = epPoster
                            }
                        )
                    }
                }
            }
            val watchSeasonPanels = document.select("div.pwr-season-panel")
            if (watchSeasonPanels.isNotEmpty()) {
                for (panel in watchSeasonPanels) {
                    val fallbackHeading = panel.selectFirst("h3.pwr-season-fallback")?.text() ?: ""
                    val seasonNum = Regex("""\d+""").find(fallbackHeading)?.value?.toIntOrNull() ?: 1

                    panel.select("ul.pwr-episode-grid li a").forEach { epA ->
                        val epUrl = buildAbsoluteUrl(epA.attr("href"))
                        val epName = epA.attr("title").ifBlank { "الحلقة " + epA.selectFirst("strong")?.text() }
                        val epNum = epA.selectFirst("strong")?.text()?.toIntOrNull() 
                            ?: Regex("""\d+""").find(epName)?.value?.toIntOrNull()

                        episodes.add(
                            newEpisode(epUrl) {
                                this.name = epName
                                this.season = seasonNum
                                this.episode = epNum
                                this.posterUrl = poster
                            }
                        )
                    }
                }
            }

            if (episodes.isNotEmpty() || url.contains("series1.php")) {
                val sorted = episodes.distinctBy { it.data }.sortedWith(
                    compareBy<Episode> { it.season ?: 1 }
                        .thenBy { it.episode ?: Int.MAX_VALUE }
                )

                return newTvSeriesLoadResponse(title, url, TvType.TvSeries, sorted) {
                    this.posterUrl = poster
                    this.plot = description
                    this.tags = allTags
                }
            }

            return if (isMovie) {
                newMovieLoadResponse(title, url, TvType.Movie, url) {
                    this.posterUrl = poster
                    this.plot = description
                    this.tags = allTags
                }
            } else {
                newTvSeriesLoadResponse(title, url, TvType.TvSeries, listOf(
                    newEpisode(url) {
                        this.name = title
                        this.season = 1
                        this.episode = Regex("""الحلقة\s*(\d+)""").find(title)?.groupValues?.get(1)?.toIntOrNull() ?: 1
                        this.posterUrl = poster
                    }
                )) {
                    this.posterUrl = poster
                    this.plot = description
                    this.tags = allTags
                }
            }

        } catch (e: Exception) {
            e.printStackTrace()
            return null
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {

        fun intToBaseStr(n: Int, baseNum: Int): String {
            val digits = "0123456789abcdefghijklmnopqrstuvwxyz"
            return if (n < baseNum) digits.getOrNull(n)?.toString() ?: ""
            else intToBaseStr(n / baseNum, baseNum) + (digits.getOrNull(n % baseNum) ?: "")
        }

        fun jsUnescape(s: String): String {
            var r = s
            r = Regex("""\\x([0-9a-fA-F]{2})""").replace(r) { mr ->
                try {
                    mr.groupValues[1].toInt(16).toChar().toString()
                } catch (_: Exception) {
                    mr.value
                }
            }
            r = Regex("""\\u([0-9a-fA-F]{4})""").replace(r) { mr ->
                try {
                    mr.groupValues[1].toInt(16).toChar().toString()
                } catch (_: Exception) {
                    mr.value
                }
            }
            return r.replace("""\"""", "\"").replace("""\'""", "'").replace("""\\""", "\\")
                .replace("""\n""", "\n").replace("""\r""", "\r").replace("""\t""", "\t")
        }

        fun unpackJs(packedJs: String): String? {
            try {
                val regex = Regex(
                    """eval\(function\s*\(p,a,c,k,e,d\)\s*\{[\s\S]*?\}\s*\(\s*(['"])(.*?)\1\s*,\s*(\d+)\s*,\s*(\d+)\s*,\s*(['"])(.*?)\5\.split\(['"]\|['"]\)\s*\)\s*\)""",
                    RegexOption.DOT_MATCHES_ALL
                )
                val m = regex.find(packedJs) ?: return null

                val payloadRaw = m.groupValues[2]
                val base = m.groupValues[3].toIntOrNull() ?: return null
                val count = m.groupValues[4].toIntOrNull() ?: return null
                val dictRaw = m.groupValues[6]
                val dictionary = dictRaw.split("|")

                val payload = jsUnescape(payloadRaw)
                val lookup = mutableMapOf<String, String>()
                for (i in (count - 1) downTo 0) {
                    val key = intToBaseStr(i, base).ifBlank { i.toString() }
                    val value = dictionary.getOrNull(i)?.ifBlank { key } ?: key
                    lookup[key] = value
                }

                val unpacked = Regex("""\b\w+\b""").replace(payload) { mr ->
                    lookup[mr.value] ?: mr.value
                }

                return if (unpacked.isBlank()) null else unpacked
            } catch (_: Exception) {
                return null
            }
        }

        fun findVideoInText(text: String): String? {
            val patterns = listOf(
                Regex("""(?:file|src)\s*:\s*['"](https?://[^'"]+)['"]"""),
                Regex("""['"](https?://[^\s'"]+\.(?:m3u8|mp4)[^\s'"]*)['"]"""),
                Regex("""(https?://[^\s'"]+\.(?:m3u8|mp4)[^\s'"]*)""")
            )
            for (p in patterns) {
                p.find(text)?.groupValues?.get(1)?.let {
                    return it
                }
            }
            return null
        }

        suspend fun extractFromEmbed(
            embedUrl: String,
            referer: String,
            serverName: String,
            quality: Int,
            callback: (ExtractorLink) -> Unit,
            subtitleCallback: (SubtitleFile) -> Unit
        ) {
            val isKnownExtractor = loadExtractor(
                embedUrl,
                referer,
                subtitleCallback,
                callback
            )
            if (isKnownExtractor) return
            try {
                val embedHeaders = standardHeaders.toMutableMap().apply {
                    put("Referer", referer)
                }
                val embedResponse = app.get(embedUrl, headers = embedHeaders, timeout = 15)
                val embedText = embedResponse.text
                findVideoInText(embedText)?.let {
                    callback(
                        newExtractorLink(
                            this@BrstejProvider.name,
                            "$serverName (Direct)",
                            it
                        ) {
                            this.referer = embedUrl
                            this.quality = quality
                        })
                    return
                }
                val packedJsMatch = Regex("""eval\((function\s*\(.*)\)\)""", RegexOption.DOT_MATCHES_ALL).find(embedText)
                if (packedJsMatch != null) {
                    val fullEval = "eval(${packedJsMatch.groupValues[1]})"
                    var unpacked: String? = null
                    try {
                        unpacked = JsUnpacker(fullEval).unpack()
                    } catch (_: Exception) {}
                    if (unpacked.isNullOrBlank()) {
                        unpacked = unpackJs(fullEval)
                    }

                    if (!unpacked.isNullOrBlank()) {
                        findVideoInText(unpacked)?.let {
                            callback(
                                newExtractorLink(
                                    this@BrstejProvider.name,
                                    "$serverName",
                                    it
                                ) {
                                    this.referer = embedUrl
                                    this.quality = quality
                                })
                            return
                        }
                    }
                }
                val innerIframe = embedResponse.document.selectFirst("iframe")?.attr("src")
                if (!innerIframe.isNullOrBlank()) {
                    val fullInnerIframe = buildAbsoluteUrl(innerIframe, embedUrl)
                    loadExtractor(
                        fullInnerIframe,
                        embedUrl,
                        subtitleCallback,
                        callback
                    )
                }

            } catch (_: Exception) {
                try {
                    loadExtractor(embedUrl, referer, subtitleCallback, callback)
                } catch (_: Exception) {}
            }
        }

        try {
            val watchDoc = getDocument(data, mainUrl)
            val playHrefRaw = watchDoc.selectFirst("a.xtgo, a.pwu-watch-link")?.attr("href")

            val embedLinksToProcess = mutableListOf<Triple<String, String, String?>>()

            val targetDoc = if (!playHrefRaw.isNullOrBlank()) {
                val playUrl = buildAbsoluteUrl(playHrefRaw, data)
                getDocument(playUrl, data)
            } else {
                watchDoc
            }

            val currentUrl = if (!playHrefRaw.isNullOrBlank()) buildAbsoluteUrl(playHrefRaw, data) else data
            targetDoc.select("div#WatchServers button.watchButton, div#WatchServers button.watchbutton")
                .forEach { btn ->
                    val rawEmbedUrl = btn.attr("data-embed-url").ifBlank { btn.attr("data-embed") }
                    val serverName = btn.text().trim()
                    if (rawEmbedUrl.isNotBlank()) {
                        embedLinksToProcess.add(Triple(buildAbsoluteUrl(rawEmbedUrl, currentUrl), serverName, "HD"))
                    }
                }
            val iframeSrc = targetDoc.selectFirst("div#Playerholder iframe")?.attr("src")
            if (!iframeSrc.isNullOrBlank()) {
                embedLinksToProcess.add(Triple(buildAbsoluteUrl(iframeSrc, currentUrl), "سيرفر رئيسي", "HD"))
            }
            val uniqueEmbeds = embedLinksToProcess.distinctBy { it.first }

            kotlinx.coroutines.coroutineScope {
                for ((currentEmbedUrl, serverName, qualityStr) in uniqueEmbeds) {
                    val quality = getQualityFromName(qualityStr)
                    launch {
                        extractFromEmbed(currentEmbedUrl, currentUrl, serverName, quality, callback, subtitleCallback)
                    }
                }
            }

            return uniqueEmbeds.isNotEmpty()
        } catch (_: Exception) {
            return false
        }
    }
}
