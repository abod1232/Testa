package com.eshk

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.loadExtractor
import org.jsoup.nodes.Element

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

    private val headers = mapOf(
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
        val document = app.get(request.data, headers = headers).document
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
        val document = app.get(searchUrl, headers = headers).document

        return document.select(".ftv-search-grid article").mapNotNull { element ->
            parseCard(element)
        }
    }

    override suspend fun load(url: String): LoadResponse? {
        val document = app.get(url, headers = headers).document

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

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        println("[5TV] ================== بدء جلب الروابط ==================")
        println("[5TV] جاري فحص الرابط المستهدف: $data")

        val document = try {
            app.get(data, headers = headers).document
        } catch (e: Exception) {
            println("[5TV] [خطأ فادح] فشل تحميل الصفحة المستهدفة: ${e.message}")
            return false
        }

        val extractedUrls = mutableSetOf<String>()
        val watchTabs = document.select(".ftv-watch-tabs button[data-server-url]")
        println("[5TV] عدد السيرفرات في الـ Tabs المكتشفة: ${watchTabs.size}")
        watchTabs.forEach { tab ->
            val serverName = tab.selectFirst("strong")?.text()?.trim() ?: "Unknown"
            val serverUrl = tab.attr("data-server-url").trim()
            if (serverUrl.isNotEmpty()) {
                println("[5TV] 🟢 تم التقاط سيرفر المشاهدة: [$serverName] -> $serverUrl")
                extractedUrls.add(serverUrl)
            }
        }
        val iframeSrc = document.selectFirst(".ftv-player-iframe")?.attr("src")?.trim() ?: ""
        if (iframeSrc.isNotEmpty()) {
            println("[5TV] 🟢 تم التقاط المشغل المباشر (Iframe): $iframeSrc")
            extractedUrls.add(iframeSrc)
        } else {
            println("[5TV] ⚠️ لم يتم العثور على مشغل iframe مباشر في الصفحة.")
        }
        val downloadLinks = document.select(".ftv-download-list article.ftv-download-server a")
        println("[5TV] عدد سيرفرات التحميل المكتشفة: ${downloadLinks.size}")
        downloadLinks.forEach { link ->
            val downloadUrl = link.attr("href").trim()
            if (downloadUrl.isNotEmpty()) {
                println("[5TV] 🔵 تم التقاط سيرفر تحميل: $downloadUrl")
                extractedUrls.add(downloadUrl)
            }
        }

        println("[5TV] ----------------------------------------------------")
        println("[5TV] إجمالي السيرفرات الفريدة المكتشفة: ${extractedUrls.size}")
        println("[5TV] بدء محاولة استخراج روابط الفيديو المباشرة عبر الـ Extractors...")

        var successCount = 0
        var failCount = 0
        extractedUrls.forEach { serverUrl ->
            println("[5TV] ⏳ تجربة المستخرج مع الرابط: $serverUrl")
            var serverExtractedSomething = false

            try {
                loadExtractor(
                    url = serverUrl,
                    referer = data,
                    subtitleCallback = { sub ->
                        println("[5TV]    📝 تم العثور على ترجمة: [${sub.lang}] ${sub.url}")
                        subtitleCallback(sub)
                    },
                    callback = { link ->
                        serverExtractedSomething = true
                        successCount++
                        println("[5TV]    ✅ [نجاح] تم استخراج فيديو: [${link.name}] (${link.quality}p) -> ${link.url}")
                        callback(link)
                    }
                )

                if (!serverExtractedSomething) {
                    failCount++
                    println("[5TV]    ⚠️ [تحذير] لم يجد المستخرج رابط فيديو صالح لهذا السيرفر (قد يحتاج Extractor مخصص).")
                }
            } catch (e: Exception) {
                failCount++
                println("[5TV]    ❌ [خطأ أثناء الاستخراج]: ${e.javaClass.simpleName} - ${e.message}")
            }
        }

        println("[5TV] ================= ملخص الاستخراج =================")
        println("[5TV] إجمالي الروابط التي نجح تشغيلها: $successCount")
        println("[5TV] إجمالي السيرفرات التي فشلت: $failCount")
        println("[5TV] ==================================================")

        return successCount > 0
    }
}
