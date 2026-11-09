package com.asia2tv

import android.util.Log
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.nodes.Element
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import org.json.JSONObject
import java.net.URI
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap

class Asia2tvProvider : MainAPI() {

    companion object {
        private const val TAG = "Asia2TV_Extractor"
        private const val DEFAULT_UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/128.0.0.0 Safari/537.36"
    }

    override var mainUrl = "https://ww1.asia2tv.pw"
    override var name = "Asia2TV 2"
    override val hasMainPage = true
    override var lang = "ar"
    override val supportedTypes = setOf(TvType.AsianDrama, TvType.TvSeries, TvType.Movie)

    private fun getPosterFromElement(element: Element?): String? {
        return element?.selectFirst("div.image img")?.let {
            it.attr("src").ifBlank { it.attr("data-src") }
        }
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val urls = listOf(
            Pair("$mainUrl/category/asian-drama/", "الدراما الآسيوية"),
            Pair("$mainUrl/category/asian-drama/korean/", "دراما كورية"),
            Pair("$mainUrl/category/asian-drama/japanese/", "دراما يابانية"),
            Pair("$mainUrl/category/asian-movies/", "أفلام آسيوية"),
            Pair("$mainUrl/completed-dramas/", "دراما مكتملة")
        )

        val items = urls.amap { (url, name) ->
            val pageUrl = if (page > 1) "$url/page/$page/" else url
            val soup = app.get(pageUrl).document
            val home = soup.select("div.box-item").mapNotNull {
                val a = it.selectFirst("div.postmovie-photo a") ?: return@mapNotNull null
                val title = a.attr("title")
                if (title.isBlank()) return@mapNotNull null
                val href = a.attr("href")
                val posterUrl = getPosterFromElement(it)
                newAnimeSearchResponse(title, href) {
                    this.posterUrl = posterUrl
                }
            }
            HomePageList(name, home)
        }
        return newHomePageResponse(items.filter { it.list.isNotEmpty() })
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val url = "$mainUrl/?s=$query"
        val document = app.get(url).document
        return document.select("div.box-item").mapNotNull {
            val a = it.selectFirst("div.postmovie-photo a") ?: return@mapNotNull null
            val title = a.attr("title")
            if (title.isBlank()) return@mapNotNull null
            val href = a.attr("href")
            val posterUrl = getPosterFromElement(it)
            val type = if (href.contains("/category/asian-movies/")) TvType.Movie else TvType.TvSeries
            newMovieSearchResponse(title, href, type) {
                this.posterUrl = posterUrl
            }
        }
    }

    override suspend fun load(url: String): LoadResponse {
        val soup = app.get(url).document

        val title = soup.selectFirst("h1 span.title")?.text()?.trim() ?: "Unknown"
        val poster = soup.selectFirst("div.single-thumb-bg > img")?.attr("src")
            ?: soup.selectFirst("meta[property=og:image]")?.attr("content")

        val plot = soup.selectFirst("div.getcontent p")?.text()?.trim()
        val tags = soup.select("div.box-tags a, li:contains(البلد) a").map { it.text() }
        val year = soup.select("div.post-date")?.last()?.text()?.toIntOrNull()
        val episodeElements = soup.select("div.loop-episode a")
        if (episodeElements.isEmpty()) {
            return newMovieLoadResponse(title, url, TvType.Movie, url) {
                this.posterUrl = poster
                this.plot = plot
                this.tags = tags
                this.year = year
            }
        }
        val episodes = episodeElements.mapNotNull { element ->
            val href = element.attr("href")
            val episodeName = element.selectFirst("div.titlepisode")?.text()?.trim()
            if (episodeName.isNullOrBlank()) return@mapNotNull null
            val episodeNumber = episodeName.filter { it.isDigit() }.toIntOrNull()

            newEpisode(href) {
                this.name = episodeName
                this.episode = episodeNumber
                this.posterUrl = poster
            }
        }.reversed()

        return newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
            this.posterUrl = poster
            this.plot = plot
            this.tags = tags
            this.year = year
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val watchPageUrl = if (data.contains("/watching/")) {
            data
        } else {
            val doc = app.get(data).document
            doc.selectFirst("div.loop-episode a.current")?.attr("href")
                ?: doc.selectFirst("a.watch_player")?.attr("href")
                ?: return false
        }

        val resolvedWatchUrl = fixUrl(watchPageUrl)
        val serversPage = app.get(resolvedWatchUrl).document
        val serverList = serversPage.select("ul.server-list-menu li").mapNotNull { li ->
            val url = li.attr("data-server").trim()
            val name = li.text().trim()
            if (url.isNotBlank()) Pair(url, name.ifBlank { "Server" }) else null
        }.ifEmpty {
            serversPage.select("iframe").mapNotNull { iframe ->
                val src = iframe.attr("src").trim()
                if (src.isNotBlank()) Pair(src, "Server") else null
            }
        }

        Log.i(TAG, "[*] بدء استخراج ${serverList.size} سيرفر بالتوازي...")
        val emittedUrls = Collections.newSetFromMap(ConcurrentHashMap<String, Boolean>())

        val uniqueCallback: (ExtractorLink) -> Unit = { link ->
            if (emittedUrls.add(link.url.trim())) {
                Log.i(TAG, "[✔] تم جلب رابط: [${link.name}] (${link.quality}p) -> ${link.url}")
                callback(link)
            }
        }

        coroutineScope {
            serverList.map { (rawUrl, serverDisplayName) ->
                async {
                    val fullUrl = fixUrl(rawUrl)
                    runParallelExtraction(
                        url = fullUrl,
                        displayName = serverDisplayName,
                        referer = mainUrl, // استخدام mainUrl النظيف لتفادي حظر Uqload
                        subtitleCallback = subtitleCallback,
                        callback = uniqueCallback
                    )
                }
            }.awaitAll()
        }

        return true
    }

    /**
     * تشغيل loadExtractor والفك اليدوي العام في نفس الوقت
     */
    private suspend fun runParallelExtraction(
        url: String,
        displayName: String,
        referer: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
        depth: Int = 0
    ) {
        if (depth > 2 || url.isBlank()) return

        coroutineScope {
            val defaultExtractorJob = async {
                try {
                    loadExtractor(url, referer, subtitleCallback, callback)
                } catch (e: Exception) {
                    Log.w(TAG, "loadExtractor error on $url: ${e.message}")
                }
            }
            val manualExtractorJob = async {
                try {
                    extractManually(url, displayName, referer, subtitleCallback, callback, depth)
                } catch (e: Exception) {
                    Log.w(TAG, "manual extraction error on $url: ${e.message}")
                }
            }

            awaitAll(defaultExtractorJob, manualExtractorJob)
        }
    }

    /**
     * محرك الفحص اليدوي العام (يدعم Uqload / Clappr / Vidmoly / StreamWish / StreamTape)
     */
    private suspend fun extractManually(
        url: String,
        displayName: String,
        referer: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
        depth: Int
    ) {
        val hostUri = runCatching { URI(url) }.getOrNull() ?: return
        val origin = "${hostUri.scheme}://${hostUri.host}"
        val fetchReferer = if (url.contains("uqload")) url else referer

        val rawHtml = runCatching {
            app.get(
                url,
                referer = fetchReferer,
                headers = mapOf("User-Agent" to DEFAULT_UA, "Accept" to "*/*")
            ).text
        }.getOrNull() ?: return
        val unpackedHtml = buildString {
            append(rawHtml)
            val evalRegex = Regex("""eval\(function\(p,a,c,k,e,[dr]\).*?\)\)\s*;?""", RegexOption.DOT_MATCHES_ALL)
            evalRegex.findAll(rawHtml).forEach { match ->
                runCatching { JsUnpacker(match.value).unpack() }.getOrNull()?.let {
                    append("\n").append(it)
                }
            }
        }.replace("\\/", "/")

        val commonHeaders = mapOf("User-Agent" to DEFAULT_UA, "Referer" to "$origin/", "Origin" to origin)
        val clapprRegex = Regex("""(?:sources|file)\s*:\s*\[?["']([^"']+\.mp4[^"']*)["']\]?""")
        clapprRegex.find(unpackedHtml)?.groupValues?.get(1)?.let { mp4Url ->
            val fullMp4 = if (mp4Url.startsWith("//")) "https:$mp4Url" else mp4Url
            emitMedia(displayName, fullMp4, "$origin/", commonHeaders, callback)
            return
        }
        val jsonPattern = Regex("""var\s+links\s*=\s*(\{[^;]+\})""").find(unpackedHtml)?.groupValues?.get(1)
        if (jsonPattern != null) {
            runCatching {
                val obj = JSONObject(jsonPattern)
                for (key in listOf("hls4", "hls2", "hls3", "hls", "file")) {
                    val link = obj.optString(key).trim()
                    if (link.startsWith("http")) {
                        emitMedia(displayName, link, "$origin/", commonHeaders, callback)
                    }
                }
            }
        }
        val concatRegex = Regex("""getElementById\(['"][^'"]+['"]\)\.innerHTML\s*=\s*['"]([^'"]+)['"]\s*\+\s*\(?['"]([^'"]+)['"]\)?""")
        concatRegex.find(unpackedHtml)?.let { m ->
            var direct = m.groupValues[1] + m.groupValues[2]
            if (direct.startsWith("//")) direct = "https:$direct"
            if (!direct.contains("&stream=")) direct += "&stream=1"
            emitMedia(displayName, direct, "$origin/", commonHeaders, callback)
        }
        val hlsRegex = Regex("""(?i)["']((?:https?:)?//[^\s"'<>\\]+?\.(?:m3u8|txt)(?:\?[^\s"'<>\\]*)?)["']""")
        hlsRegex.findAll(unpackedHtml).forEach { m ->
            val hlsLink = m.groupValues[1]
            val fullHls = if (hlsLink.startsWith("//")) "https:$hlsLink" else hlsLink
            emitMedia(displayName, fullHls, "$origin/", commonHeaders, callback)
        }
        val mp4Regex = Regex("""(?i)["']((?:https?:)?//[^\s"'<>\\]+?\.mp4(?:\?[^\s"'<>\\]*)?)["']""")
        mp4Regex.findAll(unpackedHtml).forEach { m ->
            val mp4Link = m.groupValues[1]
            val fullMp4 = if (mp4Link.startsWith("//")) "https:$mp4Link" else mp4Link
            emitMedia(displayName, fullMp4, "$origin/", commonHeaders, callback)
        }
        val innerIframe = Regex("""<iframe[^>]+src=["']([^"']+)["']""").find(unpackedHtml)?.groupValues?.get(1)
        if (!innerIframe.isNullOrBlank() && !innerIframe.startsWith("javascript")) {
            val nextUrl = if (innerIframe.startsWith("//")) "https:$innerIframe" else if (innerIframe.startsWith("/")) "$origin$innerIframe" else innerIframe
            if (nextUrl != url) {
                runParallelExtraction(nextUrl, displayName, url, subtitleCallback, callback, depth + 1)
            }
        }
    }

    /**
     * إرسال الرابط وتوليد الجودات
     */
    private suspend fun emitMedia(
        name: String,
        mediaUrl: String,
        referer: String,
        headers: Map<String, String>,
        callback: (ExtractorLink) -> Unit
    ) {
        val isHls = mediaUrl.contains(".m3u8") || mediaUrl.contains(".txt")
        if (isHls) {
            val generated = runCatching {
                M3u8Helper.generateM3u8(
                    source = name,
                    streamUrl = mediaUrl,
                    referer = referer,
                    headers = headers
                )
            }.getOrDefault(emptyList())

            if (generated.isNotEmpty()) {
                generated.forEach(callback)
            } else {
                callback(
                    newExtractorLink(name, name, mediaUrl, ExtractorLinkType.M3U8) {
                        this.referer = referer
                        this.headers = headers
                        this.quality = Qualities.Unknown.value
                    }
                )
            }
        } else {
            callback(
                newExtractorLink(name, "$name [MP4]", mediaUrl, ExtractorLinkType.VIDEO) {
                    this.referer = referer
                    this.headers = headers
                    this.quality = Qualities.P720.value
                }
            )
        }
    }
}
