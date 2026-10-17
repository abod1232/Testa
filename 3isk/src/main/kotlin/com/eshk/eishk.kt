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
    // دوال فك التشفير والتنظيف المطابقة لكود بايثون
    // =========================================================================

    private fun unescapeHtml(text: String): String {
        return text
            .replace("&amp;", "&")
            .replace("&quot;", "\"")
            .replace("&#039;", "'")
            .replace("&apos;", "'")
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace("\\\"", "\"")
            .replace("\\'", "'")
    }

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

            // تصحيح مهم: بداية النص p تكون بعد قوس الإغلاق للدالة لتجنب التقاط '\\b'
            val lastBrace = sub.lastIndexOf('}', lastMatch.range.first)
            if (lastBrace == -1) return ""

            var pStart = sub.indexOf('\'', lastBrace)
            if (pStart == -1 || pStart > lastMatch.range.first) {
                pStart = sub.indexOf('"', lastBrace)
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
        clean = unescapeHtml(clean)
        clean = clean.split("\"")[0].split("'")[0]
        clean = clean.split("<")[0].split(">")[0]
        clean = clean.split(",")[0].split("\\")[0]
        return clean.trim()
    }

    // =========================================================================
    // فحص الرابط المباشر (مع وبدون Referer) لمعرفة أي وضع يعمل
    // =========================================================================

    private suspend fun verifyAndGetHeaders(videoUrl: String, domain: String): Map<String, String>? {
        val userAgent = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/122.0.0.0 Safari/537.36"

        val headersWithReferer = mapOf(
            "User-Agent" to userAgent,
            "Referer" to domain,
            "Origin" to domain.removeSuffix("/"),
            "Range" to "bytes=0-1"
        )

        val headersWithoutReferer = mapOf(
            "User-Agent" to userAgent,
            "Range" to "bytes=0-1"
        )

        // 1. فحص الرابط بدون Referer (ضروري لروابط Cloudflare R2 و S3 التخزينية)
        try {
            val resNoRef = app.get(videoUrl, headers = headersWithoutReferer, timeout = 6)
            if (resNoRef.code in 200..299) {
                println("[5TV] ✅ الرابط يعمل بدون Referer [كود: ${resNoRef.code}]")
                return emptyMap()
            }
        } catch (_: Exception) {}

        // 2. فحص الرابط مع Referer (ضروري لروابط HLS ومواقع المشاهدة)
        try {
            val resWithRef = app.get(videoUrl, headers = headersWithReferer, timeout = 6)
            if (resWithRef.code in 200..299) {
                println("[5TV] ✅ الرابط يعمل مع Referer ($domain) [كود: ${resWithRef.code}]")
                return mapOf("Referer" to domain, "Origin" to domain.removeSuffix("/"))
            }
        } catch (_: Exception) {}

        // 3. فحص خاص لملفات m3u8 التي ترفض هيدر Range
        if (videoUrl.contains(".m3u8")) {
            try {
                val resM3u8WithRef = app.get(videoUrl, headers = mapOf("Referer" to domain), timeout = 6)
                if (resM3u8WithRef.code in 200..299) return mapOf("Referer" to domain)
            } catch (_: Exception) {}

            try {
                val resM3u8NoRef = app.get(videoUrl, headers = emptyMap(), timeout = 6)
                if (resM3u8NoRef.code in 200..299) return emptyMap()
            } catch (_: Exception) {}
        }

        return null
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
        println("[5TV] جاري فحص الرابط المستهدف: $data")

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

        println("[5TV] تم اكتشاف ${targets.size} سيرفر. بدء المعالجة والاستخراج المتقدم...")

        coroutineScope {
            targets.map { target ->
                async {
                    var serverUrl = target.url

                    // استبدال dsvplay تلقائياً بـ playmogo لحل مشكلة الـ DNS
                    if (serverUrl.contains("dsvplay")) {
                        serverUrl = serverUrl.replace("dsvplay.com", "playmogo.com")
                    }

                    try {
                        // محاولة 1: تجربة مستخرج CloudStream التلقائي
                        loadExtractor(serverUrl, data, subtitleCallback, callback)

                        // محاولة 2: استخراج مخصص متطابق مع كود بايثون
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
                            var content = unescapeHtml(res.text)

                            // فك تشفير الـ Packer مثل HGCloud
                            if (content.contains("eval(function(p,a,c,k")) {
                                val unpacked = unpackDeanEdwards(content)
                                if (unpacked.isNotEmpty()) {
                                    content += "\n" + unescapeHtml(unpacked)
                                }
                            }

                            val matchedUrls = mutableSetOf<String>()

                            // أ) استخراج روابط M3U8
                            val m3u8Regex = Regex("""(https?:[\\/]+[^\s"'<>]+\.m3u8[^\s"'<>]*)""", RegexOption.IGNORE_CASE)
                            m3u8Regex.findAll(content).forEach { m ->
                                matchedUrls.add(cleanUrl(m.groupValues[1]))
                            }

                            // ب) استخراج روابط Cloudflare R2 / S3 التخزينية (مثل التي في سيرفر 71Stream)
                            val r2Regex = Regex("""(https?:[\\/]+[^\s"'<>]+cloudflarestorage[^\s"'<>]*)""", RegexOption.IGNORE_CASE)
                            r2Regex.findAll(content).forEach { m ->
                                matchedUrls.add(cleanUrl(m.groupValues[1]))
                            }

                            // ج) استخراج صيغ الفيديو MP4 / MKV
                            val mediaRegex = Regex("""(https?:[\\/]+[^\s"'<>]+\.(?:mp4|mkv)[^\s"'<>]*)""", RegexOption.IGNORE_CASE)
                            mediaRegex.findAll(content).forEach { m ->
                                matchedUrls.add(cleanUrl(m.groupValues[1]))
                            }

                            // د) خاصية file: '...'
                            val fileRegex = Regex("""["']?file["']?\s*:\s*["']([^"']+)["']""", RegexOption.IGNORE_CASE)
                            fileRegex.findAll(content).forEach { m ->
                                val f = m.groupValues[1]
                                if (f.startsWith("http")) {
                                    matchedUrls.add(cleanUrl(f))
                                }
                            }

                            // هـ) فحص روابط أزرار التحميل المباشرة
                            if (target.isDownload) {
                                val doc = Jsoup.parse(res.text)
                                doc.select("a[href]").forEach { a ->
                                    val href = a.attr("href")
                                    if (href.contains(".mp4") || href.contains(".mkv") || href.contains(".m3u8")) {
                                        matchedUrls.add(cleanUrl(href))
                                    }
                                }
                            }

                            // التحقق من كل رابط وتحديد إن كان يشتغل مع أو بدون Referer
                            matchedUrls.forEach { videoUrl ->
                                if (videoUrl.length > 20 && videoUrl != serverUrl) {
                                    val workingHeaders = verifyAndGetHeaders(videoUrl, domain)

                                    if (workingHeaders != null) {
                                        val isM3u8 = videoUrl.contains(".m3u8")
                                        println("[5TV] 🎯 تم قبول الرابط الشغال بنجاح: $videoUrl")

                                        callback(
                                            newExtractorLink(
                                                source = name,
                                                name = "[5TV] ${target.name}",
                                                url = videoUrl,
                                           ) {
                                                this.referer = workingHeaders["Referer"] ?: ""
                                                this.quality = Qualities.P1080.value
                                                this.headers = workingHeaders
                                            }
                                        )
                                    }
                                }
                            }
                        }
                    } catch (e: Exception) {
                        // تجاهل السيرفرات المتعطلة
                    }
                }
            }.awaitAll()
        }

        return true
    }
}
