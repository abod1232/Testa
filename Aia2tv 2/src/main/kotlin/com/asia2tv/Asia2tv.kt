package com.asia2tv

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.nodes.Element
import android.util.Log
import com.lagradost.cloudstream3.utils.AppUtils.parseJson
import com.lagradost.cloudstream3.utils.AppUtils.toJson

class Asia2tvProvider : MainAPI() {
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
                this.posterUrl = poster // إضافة بوستر المسلسل لكل حلقة
            }
        }.reversed() // عكس الترتيب لأن المواقع غالبًا ما تعرض الحلقات من الأحدث للأقدم

        return newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
            this.posterUrl = poster
            this.plot = plot
            this.tags = tags
            this.year = year
        }
    }

    

package com.asia2tv

import android.util.Log
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

// أضف هذه الدوال داخل كلاس Asia2tvProvider:

    // 1. فك تشفير سكربتات Packer (eval(function(p,a,c,k...)))
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
            Log.e(name, "Error in extractLogic: ${e.message}")
        }
        return htmlText
    }

    // 2. استخراج رابط m3u8 (يدعم ' و " تلقائياً)
    private fun findM3u8(text: String): String? {
        val fileRegex = """file\s*:\s*["']([^"']+)["']""".toRegex()
        val fileMatch = fileRegex.find(text)
        if (fileMatch != null) return fileMatch.groupValues[1]

        val m3u8Regex = """(https?://[^\s"\'<>]+?\.m3u8[^\s"\'<>?]*(?:\?[^\s"\'<>]*)?)""".toRegex()
        val m3u8Match = m3u8Regex.find(text)
        return m3u8Match?.groupValues?.get(1)
    }

    // 3. دالة loadLinks المعدلة بالكامل مثل Elif
    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        // جلب رابط صفحة المشاهدة
        val serverPageUrl = if (data.contains("/watching/")) {
            data
        } else {
            val doc = app.get(data).document
            doc.selectFirst("div.loop-episode a.current")?.attr("href") // للمسلسلات
                ?: doc.selectFirst("a.watch_player")?.attr("href") // للأفلام
                ?: return false
        }

        val resolvedServerPageUrl = fixUrl(serverPageUrl)
        val serversPage = app.get(resolvedServerPageUrl).document
        val servers = serversPage.select("ul.server-list-menu li")

        // تنفيذ متوازي لجميع السيرفرات في الخلفية
        coroutineScope {
            servers.map { server ->
                async {
                    val embedSource = server.attr("data-server") ?: return@async
                    if (embedSource.isBlank()) return@async

                    val serverUrl = fixUrl(embedSource)

                    // 1. المحاولة الأولى: تمرير الرابط لمستخرجات التطبيق الافتراضية
                    try {
                        loadExtractor(serverUrl, resolvedServerPageUrl, subtitleCallback, callback)
                    } catch (e: Exception) {
                        Log.w(name, "loadExtractor failed for $serverUrl: ${e.message}")
                    }

                    // 2. المحاولة الثانية: طلب السيرفر بنفسنا وفك تشفيره وفحص الرابط المباشر (مثل Elif)
                    try {
                        val uri = URL(serverUrl)
                        val domain = "${uri.protocol}://${uri.host}/"
                        val serverName = uri.host.replace("www.", "").lowercase()

                        val serverResponse = app.get(
                            serverUrl,
                            headers = mapOf(
                                "Referer" to resolvedServerPageUrl,
                                "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"
                            )
                        )

                        if (serverResponse.code == 200) {
                            val content = extractLogic(serverResponse.text)
                            val videoLink = findM3u8(content)

                            if (!videoLink.isNullOrEmpty()) {
                                val finalVideoUrl = fixUrl(videoLink)

                                val verifyHeaders = mapOf(
                                    "Referer" to domain,
                                    "Origin" to domain.removeSuffix("/"),
                                    "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"
                                )

                                // فحص الرابط المباشر للتأكد من أنه يعمل (200 OK)
                                val check = app.get(
                                    url = finalVideoUrl,
                                    headers = verifyHeaders,
                                    timeout = 10
                                )

                                if (check.code == 200) {
                                    Log.i(name, "Direct stream link verified (200 OK) from $serverName -> $finalVideoUrl")
                                    callback(
                                        newExtractorLink(
                                            source = name,
                                            name = serverName,
                                            url = finalVideoUrl,
                                        ) {
                                            this.referer = domain
                                            this.quality = Qualities.Unknown.value
                                            this.headers = verifyHeaders
                                        }
                                    )
                                }
                            }
                        }
                    } catch (e: Exception) {
                        Log.e(name, "Manual fetch failed for $serverUrl: ${e.message}")
                    }
                }
            }.awaitAll()
        }

        return true
    }
}
