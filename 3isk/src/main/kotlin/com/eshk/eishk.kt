package com.eshk

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.newExtractorLink
import com.lagradost.cloudstream3.utils.loadExtractor
import com.lagradost.cloudstream3.utils.Qualities
import org.jsoup.Jsoup
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
        "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/122.0.0.0 Safari/537.36",
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

    // =========================================================================
    // خوارزمية فك تشفير Dean Edwards Packer بدقة متناهية
    // =========================================================================

    private fun unpackDeanEdwards(scriptText: String): String {
        try {
            var splitIdx = scriptText.lastIndexOf(".split('|')")
            if (splitIdx == -1) {
                splitIdx = scriptText.lastIndexOf(".split(\"|\")")
            }
            if (splitIdx == -1) return ""

            val sub = scriptText.substring(0, splitIdx)
            val paramsRegex = Regex(""",\s*(\d+)\s*,\s*(\d+)\s*,\s*['"]""")
            val matches = paramsRegex.findAll(sub).toList()
            if (matches.isEmpty()) return ""

            val lastMatch = matches.last()
            val a = lastMatch.groupValues[1].toIntOrNull() ?: return ""
            val c = lastMatch.groupValues[2].toIntOrNull() ?: return ""

            val kStr = sub.substring(lastMatch.range.last + 1)
            val k = kStr.split("|")

            val firstQuoteIdx = sub.indexOf("function(p,a,c,k")
            var pStart = sub.indexOf("'", if (firstQuoteIdx != -1) firstQuoteIdx else 0)
            if (pStart == -1 || pStart > lastMatch.range.first) {
                pStart = sub.indexOf("\"", if (firstQuoteIdx != -1) firstQuoteIdx else 0)
            }
            if (pStart == -1) return ""

            val p = sub.substring(pStart + 1, lastMatch.range.first).trimEnd('\'', '"')

            fun getBaseNString(num: Int, base: Int): String {
                val chars = "0123456789abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ"
                if (num == 0) return "0"
                var n = num
                var res = ""
                while (n > 0) {
                    res = chars[n % base] + res
                    n /= base
                }
                return if (res.isEmpty()) "0" else res
            }

            val lookup = mutableMapOf<String, String>()
            for (i in 0 until c) {
                val key = getBaseNString(i, a)
                val value = if (i < k.size && k[i].isNotEmpty()) k[i] else key
                lookup[key] = value
            }

            val wordRegex = Regex("""\b\w+\b""")
            return wordRegex.replace(p) { matchResult ->
                val word = matchResult.value
                lookup[word] ?: word
            }
        } catch (e: Exception) {
            return ""
        }
    }

    private fun cleanUrl(rawUrl: String): String {
        var clean = rawUrl.replace("\\/", "/")
        clean = clean.split("&quot;")[0].split("\"")[0].split("'")[0]
        clean = clean.split(",")[0].split("\\")[0]
        return clean.trim()
    }

    // =========================================================================
    // استخراج السيرفرات وتشغيلها (loadLinks)
    // =========================================================================

    data class ServerTarget(val name: String, val url: String, val isDownload: Boolean)

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        println("[5TV] جاري فحص الرابط الأصلي: $data")

        val document = try {
            app.get(data, headers = browserHeaders).document
        } catch (e: Exception) {
            println("[5TV] خطأ في تحميل الصفحة: ${e.message}")
            return false
        }

        val targets = mutableListOf<ServerTarget>()
        val seenUrls = mutableSetOf<String>()

        // 1. المشغل المباشر
        val iframeSrc = document.selectFirst(".ftv-player-iframe")?.attr("src")?.trim() ?: ""
        if (iframeSrc.isNotEmpty() && !seenUrls.contains(iframeSrc)) {
            targets.add(ServerTarget("Direct Player", iframeSrc, false))
            seenUrls.add(iframeSrc)
        }

        // 2. سيرفرات المشاهدة
        document.select(".ftv-watch-tabs button[data-server-url]").forEach { tab ->
            val u = tab.attr("data-server-url").trim()
            val name = tab.selectFirst("strong")?.text()?.trim() ?: "Stream"
            if (u.isNotEmpty() && !seenUrls.contains(u)) {
                targets.add(ServerTarget(name, u, false))
                seenUrls.add(u)
            }
        }

        // 3. سيرفرات التحميل
        document.select(".ftv-download-list article.ftv-download-server a").forEach { link ->
            val u = link.attr("href").trim()
            val parentArticle = link.parents().firstOrNull { it.hasClass("ftv-download-server") }
            val name = parentArticle?.selectFirst("strong")?.text()?.trim() ?: "Download"
            if (u.isNotEmpty() && !seenUrls.contains(u)) {
                targets.add(ServerTarget(name, u, true))
                seenUrls.add(u)
            }
        }

        println("[5TV] تم اكتشاف ${targets.size} سيرفر (مشاهدة وتحميل).")

        // معالجة كافة السيرفرات بالتوازي
        coroutineScope {
            targets.map { target ->
                async {
                    var serverUrl = target.url

                    // استبدال dsvplay تلقائياً بـ playmogo
                    if (serverUrl.contains("dsvplay")) {
                        serverUrl = serverUrl.replace("dsvplay.com", "playmogo.com")
                    }

                    try {
                        // محاولة 1: تجربة مستخرج CloudStream التلقائي
                        loadExtractor(serverUrl, data, subtitleCallback, callback)

                        // محاولة 2: المعالجة المخصصة مثل كود بايثون
                        val uri = URL(serverUrl)
                        val domain = "${uri.protocol}://${uri.host}/"
                        val serverName = uri.host.replace("www.", "").lowercase()

                        val res = app.get(
                            serverUrl,
                            headers = mapOf(
                                "Referer" to data,
                                "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/122.0.0.0 Safari/537.36"
                            ),
                            timeout = 10
                        )

                        if (res.code == 200) {
                            var content = res.text

                            // فك تشفير الـ Packer إن وجد (مثل مشغل HGCloud)
                            if (content.contains("eval(function(p,a,c,k")) {
                                val unpacked = unpackDeanEdwards(content)
                                if (unpacked.isNotEmpty()) {
                                    content += "\n$unpacked"
                                }
                            }

                            val matchedLinks = mutableSetOf<Pair<String, Boolean>>() // Pair(url, isM3u8)

                            // أ) البحث عن روابط M3U8
                            val m3u8Regex = Regex("""(https?:[\\/]+[^\s"'<>]+\.m3u8[^\s"'<>]*)""", RegexOption.IGNORE_CASE)
                            m3u8Regex.findAll(content).forEach { m ->
                                matchedLinks.add(Pair(cleanUrl(m.groupValues[1]), true))
                            }

                            // ب) البحث عن روابط Cloudflare R2 التخزينية (MP4/MKV)
                            val r2Regex = Regex("""(https?:[\\/]+[^\s"'<>]+cloudflarestorage[^\s"'<>]*)""", RegexOption.IGNORE_CASE)
                            r2Regex.findAll(content).forEach { m ->
                                matchedLinks.add(Pair(cleanUrl(m.groupValues[1]), false))
                            }

                            // ج) البحث عن خاصية file: "..." أو file: '...'
                            val fileRegex = Regex("""["']?file["']?\s*:\s*["']([^"']+)["']""", RegexOption.IGNORE_CASE)
                            fileRegex.findAll(content).forEach { m ->
                                val f = m.groupValues[1]
                                if (f.startsWith("http")) {
                                    matchedLinks.add(Pair(cleanUrl(f), f.contains(".m3u8")))
                                }
                            }

                            // د) أزرار التحميل إذا كانت صفحة تحميل
                            if (target.isDownload) {
                                val doc = Jsoup.parse(res.text)
                                doc.select("a[href]").forEach { a ->
                                    val href = a.attr("href")
                                    if (href.contains(".mp4") || href.contains(".mkv") || href.contains(".m3u8")) {
                                        matchedLinks.add(Pair(cleanUrl(href), href.contains(".m3u8")))
                                    }
                                }
                            }

                            // إرسال الروابط الصافية إلى مشغل التطبيق
                            matchedLinks.forEach { (videoUrl, isM3u8) ->
                                if (videoUrl.length > 20 && videoUrl != serverUrl) {
                                    println("[5TV] 🎯 [نجاح مستخرج مخصص] تم استخراج فيديو من: $serverName -> $videoUrl")
                                    callback(
                                        newExtractorLink(
                                            source = name,
                                            name = "[5TV] ${target.name}",
                                            url = videoUrl,
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
                        }
                    } catch (e: Exception) {
                        // تجاهل السيرفرات المعطلة أو البطيئة
                    }
                }
            }.awaitAll()
        }

        return true
    }
}
