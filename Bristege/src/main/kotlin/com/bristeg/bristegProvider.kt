package com.bristeg

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.loadExtractor
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.Qualities
import org.jsoup.nodes.Element
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
        val titleLinkElement = this.selectFirst(".pcg-caption h3 a, div.caption h3 a, a.pcg-card-title")
            ?: this.selectFirst("div.pm-video-thumb a:has(img), .pcg-media a:has(img)")
            ?: return null

        val href = titleLinkElement.attr("href")
        if (href.isBlank() || href.startsWith("#") || href.contains("modal-login")) {
            return null
        }

        val title = titleLinkElement.attr("title").ifBlank { titleLinkElement.text() }.trim()
        if (title.isBlank()) {
            return null
        }

        val img = this.selectFirst(".pcg-media img, div.pm-video-thumb img")
        val rawPoster = img?.attr("data-echo")
            ?: img?.attr("data-original")
            ?: img?.attr("src")
        val posterUrl = cleanPosterUrl(rawPoster)

        val fullUrl = buildAbsoluteUrl(href)
        val type = if (fullUrl.contains("series1.php") || fullUrl.contains("view-serie.php")) {
            TvType.TvSeries
        } else {
            TvType.Movie
        }

        return if (type == TvType.TvSeries) {
            newTvSeriesSearchResponse(title, fullUrl, type) {
                this.posterUrl = posterUrl
            }
        } else {
            newMovieSearchResponse(title, fullUrl, type) {
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
            val document = app.get(url).document
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
            val document = app.get(url).document

            val selector = "ul.pm-ul-browse-videos > li, ul.pcg-episodes-grid > li, div.thumbnail"

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
            val document = app.get(url, timeout = 15).document

            val title = document.selectFirst("div.pm-video-heading h1, h1#pcg-title, h1")?.text()?.trim()
            if (title.isNullOrBlank()) {
                return null
            }

            val poster = cleanPosterUrl(document.selectFirst("meta[property=og:image]")?.attr("content"))

            val description = document.selectFirst("div.pm-video-description > div.txtv, div.pm-category-description, p.pcg-intro-summary")?.text()?.trim()

            val categoryTags = document.select("dl.dl-horizontal p strong:contains(اقسام) ~ span a span, .pcg-breadcrumb li a")
                .map { it.text().trim() }
                .filter { it.isNotBlank() }

            val keywordTags = document.select("dl.dl-horizontal strong:contains(الكلمات الدلالية) ~ a")
                .map { it.text().trim() }
                .filter { it.isNotBlank() }

            val allTags = (categoryTags + keywordTags).distinct()

            val isMovieByTag = allTags.any { it.contains("افلام", ignoreCase = true) || it.contains("فلم", ignoreCase = true) }
            val hasSeasonBox = document.selectFirst("div.SeasonsBox") != null
            val seasonListItems = document.select("div.SeasonsBoxUL ul li")
            val hasActualEpisodes = seasonListItems.any {
                val seasonId = it.attr("data-serie")
                document.select("div.SeasonsEpisodes[data-serie='${seasonId}'] a").isNotEmpty()
            }

            val isSeries = (hasSeasonBox && hasActualEpisodes && !isMovieByTag) || url.contains("series1.php")

            if (isSeries) {
                val episodes = mutableListOf<Episode>()

                for (seasonLi in seasonListItems) {
                    val seasonName = seasonLi.text().trim()
                    val seasonId = seasonLi.attr("data-serie")
                    val seasonNum = seasonId.toIntOrNull()

                    if (seasonId.isNullOrBlank()) {
                        continue
                    }

                    val episodesSelector = "div.SeasonsEpisodes[data-serie='${seasonId}'] a"
                    val episodeElements = document.select(episodesSelector)

                    episodeElements.forEach { epElement ->
                        val epUrlRaw = epElement.attr("href")
                        if (epUrlRaw.isBlank()) {
                            return@forEach
                        }

                        val epUrl = buildAbsoluteUrl(epUrlRaw)
                        val epName = epElement.attr("title").ifBlank { epElement.text() }
                        val epNum = epElement.selectFirst("em")?.text()?.toIntOrNull()

                        episodes.add(
                            newEpisode(epUrl) {
                                data = epUrl
                                name = epName
                                season = seasonNum
                                episode = epNum
                                posterUrl = poster
                            }
                        )
                    }
                }

                val sorted = episodes.sortedWith(compareBy<Episode> { it.season ?: Int.MAX_VALUE }
                    .thenBy { it.episode ?: Int.MAX_VALUE })

                return newTvSeriesLoadResponse(title, url, TvType.TvSeries, sorted) {
                    this.posterUrl = poster
                    this.plot = description
                    this.tags = allTags
                }
            } else {
                return newMovieLoadResponse(title, url, TvType.Movie, url) {
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
            suspend fun unpackAndExtractInternal(
                packedJsCode: String,
                currentEmbedUrl: String
            ): String? {
                var unpacked: String? = null
                try {
                    unpacked = JsUnpacker(packedJsCode).unpack()
                } catch (_: Exception) {}

                if (unpacked.isNullOrBlank()) {
                    unpacked = unpackJs(packedJsCode)
                }

                if (unpacked.isNullOrBlank()) {
                    return null
                }
                return findVideoInText(unpacked)
            }

            try {
                val embedText = app.get(embedUrl, referer = referer, timeout = 15).text

                findVideoInText(embedText)?.let {
                    callback(
                        newExtractorLink(
                            this@BrstejProvider.name,
                            "$serverName (direct)",
                            it
                        ) {
                            this.referer = embedUrl
                            this.quality = quality
                        })
                    return
                }

                val jwPlayerScriptMatch =
                    Regex("""jwplayer\.key\s*=\s*['"]([^'"]+)['"];[\s\S]*?(jwplayer\.setup\s*\(\{([\s\S]*?)\}\);)""").find(
                        embedText
                    )
                if (jwPlayerScriptMatch != null) {
                    val jwSetupStatement = jwPlayerScriptMatch.groupValues[2]
                    val videoUrl = unpackAndExtractInternal(jwSetupStatement, embedUrl)

                    if (videoUrl != null) {
                        callback(
                            newExtractorLink(
                                this@BrstejProvider.name,
                                "$serverName (JW Player)",
                                videoUrl
                            ) {
                                this.referer = embedUrl
                                this.quality = quality
                            })
                        return
                    }
                }

                val packedJsMatch =
                    Regex("""eval\((function\s*\(.*)\)\)""", RegexOption.DOT_MATCHES_ALL).find(
                        embedText
                    )
                if (packedJsMatch != null) {
                    val fullEval = "eval(${packedJsMatch.groupValues[1]})"
                    val videoUrl = unpackAndExtractInternal(fullEval, embedUrl)
                    if (videoUrl != null) {
                        callback(
                            newExtractorLink(
                                this@BrstejProvider.name,
                                "$serverName ",
                                videoUrl
                            ) {
                                this.referer = embedUrl
                                this.quality = quality
                            })
                        return
                    }
                }

                loadExtractor(
                    embedUrl,
                    referer,
                    subtitleCallback,
                    callback
                )

            } catch (_: Exception) {}
        }

        try {
            val watchDoc = app.get(data, referer = mainUrl, timeout = 15).document
            val playHrefRaw = watchDoc.selectFirst("a.xtgo")?.attr("href")

            val embedLinksToProcess = mutableListOf<Triple<String, String, String?>>()

            if (!playHrefRaw.isNullOrBlank()) {
                val playUrl = buildAbsoluteUrl(playHrefRaw)
                val playDoc = app.get(playUrl, referer = data, timeout = 15).document

                playDoc.select("div#WatchServers button.watchButton, div#WatchServers button.watchbutton")
                    .forEach { btn ->
                        val rawEmbedUrl = btn.attr("data-embed-url").ifBlank { btn.attr("data-embed") }
                        val serverName = btn.text().trim()
                        val qualityStr = "HD"
                        if (rawEmbedUrl.isNotBlank()) {
                            embedLinksToProcess.add(Triple(buildAbsoluteUrl(rawEmbedUrl, playUrl), serverName, qualityStr))
                        }
                    }

                val iframeSrcFromPlayPage = playDoc.selectFirst("div#Playerholder iframe")?.attr("src")
                if (!iframeSrcFromPlayPage.isNullOrBlank()) {
                    embedLinksToProcess.add(Triple(buildAbsoluteUrl(iframeSrcFromPlayPage, playUrl), "Player Iframe", "HD"))
                }
            } else {
                watchDoc.select("div#WatchServers button.watchButton, div#WatchServers button.watchbutton")
                    .forEach { btn ->
                        val rawEmbedUrl = btn.attr("data-embed-url").ifBlank { btn.attr("data-embed") }
                        val serverName = btn.text().trim()
                        val qualityStr = "HD"
                        if (rawEmbedUrl.isNotBlank()) {
                            embedLinksToProcess.add(Triple(buildAbsoluteUrl(rawEmbedUrl, data), serverName, qualityStr))
                        }
                    }

                val iframeSrcFromWatchPage = watchDoc.selectFirst("div#Playerholder iframe")?.attr("src")
                if (!iframeSrcFromWatchPage.isNullOrBlank()) {
                    embedLinksToProcess.add(Triple(buildAbsoluteUrl(iframeSrcFromWatchPage, data), "Player Iframe", "HD"))
                }
            }

            val processedUrls = mutableSetOf<String>()
            kotlinx.coroutines.coroutineScope {
                for ((currentEmbedUrl, serverName, qualityStr) in embedLinksToProcess) {
                    if (processedUrls.add(currentEmbedUrl)) {
                        val quality = getQualityFromName(qualityStr)
                        launch {
                            val actualReferer = if (!playHrefRaw.isNullOrBlank()) buildAbsoluteUrl(playHrefRaw, data) else data
                            extractFromEmbed(currentEmbedUrl, actualReferer, serverName, quality, callback, subtitleCallback)
                        }
                    }
                }
            }

            return processedUrls.isNotEmpty()
        } catch (_: Exception) {
            return false
        }
    }
}
