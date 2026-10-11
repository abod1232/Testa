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

    // ترويسات لتجنب الحظر
    private val headers = mapOf(
        "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36",
        "Referer" to "$mainUrl/"
    )

    // 1. الصفحة الرئيسية (أقسام الموقع)
    override val mainPage = mainPageOf(
        "$mainUrl/latest-movies/" to "جديد الأفلام",
        "$mainUrl/latest-series/" to "جديد الدراما",
        "$mainUrl/category/أنمي/" to "جديد الأنمي",
        "$mainUrl/category/البرامج-الأسيوية/" to "البرامج الأسيوية"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val document = app.get(request.data, headers = headers).document
        val homeItems = mutableListOf<SearchResponse>()

        // استخراج الكروت من الصفحة
        document.select("article.ftv-home-poster-card").forEach { element ->
            val item = parseCard(element)
            if (item != null) homeItems.add(item)
        }

        return newHomePageResponse(request.name, homeItems)
    }

    // دالة مساعدة لتحويل عنصر الكارت إلى عنصر Cloudstream
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

    // 2. محرك البحث (Search)
    override suspend fun search(query: String): List<SearchResponse> {
        val searchUrl = "$mainUrl/search/?q=$query"
        val document = app.get(searchUrl, headers = headers).document

        return document.select(".ftv-search-grid article").mapNotNull { element ->
            parseCard(element)
        }
    }

    // 3. جلب تفاصيل العمل والحلقات (Load)
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
            // استخراج قائمة الحلقات في حالة المسلسلات
            val episodes = mutableListOf<Episode>()
            val episodeElements = document.select(".ftv-series-episodes-grid .ftv-episode-completion-item")

            episodeElements.forEach { el ->
                val epHref = el.selectFirst("a.ftv-series-episode-card")?.attr("href") ?: return@forEach
                val epTitle = el.selectFirst(".ftv-series-episode-card__body strong")?.text()?.trim() ?: "حلقة"
                
                // استخراج رقم الحلقة من النص أو الرابط
                val epNum = Regex("""\d+""").find(epTitle)?.value?.toIntOrNull()

                episodes.add(
                    Episode(
                        data = epHref,
                        name = epTitle,
                        episode = epNum
                    )
                )
            }

            // ترتيب الحلقات تصاعدياً
            episodes.sortBy { it.episode }

            newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
                this.posterUrl = poster
                this.plot = description
                this.year = year
                this.tags = genres
            }
        }
    }

    // 4. استخراج سيرفرات المشاهدة والتحميل (LoadLinks)
    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val document = app.get(data, headers = headers).document

        val extractedUrls = mutableSetOf<String>()

        // 1. استخراج سيرفرات المشاهدة من أزرار التاب (Tabs)
        document.select(".ftv-watch-tabs button[data-server-url]").forEach { tab ->
            val serverUrl = tab.attr("data-server-url")
            if (serverUrl.isNotEmpty()) extractedUrls.add(serverUrl)
        }

        // 2. استخراج الـ iframe المباشر إن وجد
        document.select(".ftv-player-iframe").attr("src").let { iframeSrc ->
            if (iframeSrc.isNotEmpty()) extractedUrls.add(iframeSrc)
        }

        // 3. استخراج روابط سيرفرات التحميل
        document.select(".ftv-download-list article.ftv-download-server a").forEach { link ->
            val downloadUrl = link.attr("href")
            if (downloadUrl.isNotEmpty()) extractedUrls.add(downloadUrl)
        }

        // إرسال جميع السيرفرات المستخرجة إلى المستخرجات المدمجة في Cloudstream (Extractors)
        // مثل: VidMoly, Ultra4Vid, DSVPlay, إلخ...
        extractedUrls.forEach { serverUrl ->
            loadExtractor(serverUrl, data, subtitleCallback, callback)
        }

        return true
    }
}
