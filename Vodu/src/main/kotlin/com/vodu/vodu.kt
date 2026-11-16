package com.vodu

import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addActors
import com.lagradost.cloudstream3.LoadResponse.Companion.addTrailer
import com.lagradost.cloudstream3.utils.AppUtils.tryParseJson
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.Qualities
import org.jsoup.nodes.Element
import com.lagradost.cloudstream3.utils.newExtractorLink

class VoduProvider : MainAPI() {
    override var mainUrl = "https://movie.vodu.me"
    override var name = "Vodu"
    override val hasMainPage = true
    override var lang = "ar"
    override val hasQuickSearch = false
    override val supportedTypes = setOf(
        TvType.Movie,
        TvType.TvSeries,
        TvType.Anime
    )

    // ─────────────────────────────────────────────
    // 1. الصفحة الرئيسية (Main Page)
    // ─────────────────────────────────────────────
    override val mainPage = mainPageOf(
        "$mainUrl" to "Featured",
        "$mainUrl/movies" to "Latest Movies",
        "$mainUrl/series" to "Latest Series",
        "$mainUrl/category/15" to "Turkish Series",
        "$mainUrl/category/5" to "Asian Drama",
        "$mainUrl/category/2" to "Anime Series",
        "$mainUrl/category/9" to "Anime Movies",
        "$mainUrl/category/4" to "Arabic Series",
        "$mainUrl/category/7" to "Arabic Movies"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val document = app.get(request.data).document
        val homeItems = ArrayList<HomePageList>()

        // استخراج كافة الأقسام الأفقية (Carousels) الموجودة في الصفحة
        val sections = document.select("div:has(h2.tv-row-title)")

        if (sections.isNotEmpty()) {
            for (section in sections) {
                val title = section.selectFirst("h2.tv-row-title")?.text()?.trim() ?: continue
                val items = section.select("a[href*='/item/']").mapNotNull { it.toSearchResponse() }
                if (items.isNotEmpty()) {
                    homeItems.add(HomePageList(title, items))
                }
            }
        } else {
            // في حال فتح تصنيف يحتوي على شبكة أفلام عادية (Grid)
            val items = document.select("div.grid a[href*='/item/']").mapNotNull { it.toSearchResponse() }
            if (items.isNotEmpty()) {
                homeItems.add(HomePageList(request.name, items))
            }
        }

        return HomePageResponse(homeItems)
    }

    private fun Element.toSearchResponse(): SearchResponse? {
        val link = this.attr("href") ?: return null
        val title = this.selectFirst("p.truncate")?.text()?.trim() ?: return null
        val poster = this.selectFirst("img")?.attr("src")

        // تحديد إن كان مسلسل أو فيلم من الوسم المعروض على الصورة
        val chipText = this.selectFirst("span.ui-chip")?.text()?.lowercase() ?: ""
        val type = if (chipText.contains("series") || chipText.contains("مسلسل")) {
            TvType.TvSeries
        } else {
            TvType.Movie
        }

        return if (type == TvType.Movie) {
            newMovieSearchResponse(title, link, type) {
                this.posterUrl = poster
            }
        } else {
            newTvSeriesSearchResponse(title, link, type) {
                this.posterUrl = poster
            }
        }
    }

    // ─────────────────────────────────────────────
    // 2. البحث (Search)
    // ─────────────────────────────────────────────
    override suspend fun search(query: String): List<SearchResponse> {
        val searchUrl = "$mainUrl/search?q=$query"
        val document = app.get(searchUrl).document

        return document.select("div.grid a[href*='/item/']").mapNotNull { element ->
            val link = element.attr("href")
            val title = element.selectFirst("p.truncate")?.text()?.trim() ?: return@mapNotNull null
            val poster = element.selectFirst("img")?.attr("src")

            newMovieSearchResponse(title, link, TvType.Movie) {
                this.posterUrl = poster
            }
        }
    }

    // ─────────────────────────────────────────────
    // 3. جلب تفاصيل العمل (Load Details)
    // ─────────────────────────────────────────────
    override suspend fun load(url: String): LoadResponse {
        val document = app.get(url).document

        val title = document.selectFirst("h1")?.text()?.trim() ?: "Unknown"
        val poster = document.selectFirst("div.aspect-\\[2\\/3\\] img")?.attr("src")
        val backdrop = document.selectFirst("div.relative.h-\\[45vh\\] img")?.attr("src")
        val plot = document.selectFirst("p.max-w-2xl")?.text()?.trim()

        // استخراج التقييم والمدة والسنة
        val ratingText = document.selectFirst("span.text-white.font-bold:has(i.fa-star)")?.text()?.trim()
        val rating = ratingText?.replace(Regex("[^0-9.]"), "")?.toRatingInt()

        val yearText = document.select("div.flex.items-center span").firstOrNull {
            it.text().matches(Regex(".*\\b(19|20)\\d{2}\\b.*"))
        }?.text()
        val year = yearText?.let { Regex("\\b(19|20)\\d{2}\\b").find(it)?.value?.toIntOrNull() }

        val duration = document.select("div.flex.items-center span").firstOrNull {
            it.text().contains("min")
        }?.text()?.replace(Regex("[^0-9]"), "")?.toIntOrNull()

        // استخراج التصنيفات
        val genres = document.selectFirst("p:contains(Genres:)")?.text()
            ?.replace("Genres:", "")
            ?.split(",")
            ?.map { it.trim() }

        // الممثلين وطاقم العمل (Cast) مع الصور
        val actors = document.select("div:has(> h3:contains(Cast)) a[href*='/actor/']").mapNotNull {
            val actorName = it.selectFirst("p")?.text()?.trim() ?: return@mapNotNull null
            val actorImg = it.selectFirst("img")?.attr("src")
            Actor(actorName, actorImg)
        }

        // اقتراحات أعمال مشابهة (More Like This)
        val recommendations = document.select("div:has(> h3:contains(More Like This)) a[href*='/item/']").mapNotNull {
            it.toSearchResponse()
        }

        // استخراج رابط الإعلان الترويجي (YouTube Trailer)
        val trailerId = Regex("""const\s+trailerId\s*=\s*"([^"]+)"""").find(document.html())?.groupValues?.get(1)
        val trailerUrl = if (!trailerId.isNullOrBlank()) "https://www.youtube.com/watch?v=$trailerId" else null

        // التحقق إذا كان العمل مسلسلاً
        val isSeries = document.select("#seasonTabs, div[id^='seasonPanel_']").isNotEmpty() ||
                document.select("span.ui-chip").any { it.text().contains("Series", true) }

        if (isSeries) {
            val episodes = ArrayList<Episode>()
            val seasonPanels = document.select("div[id^='seasonPanel_']")

            seasonPanels.forEachIndexed { index, panel ->
                val seasonNum = index + 1
                val epElements = panel.select("a[href*='/play/episode/']")

                epElements.forEachIndexed { epIndex, epElement ->
                    val epUrl = epElement.attr("href")
                    val epTitle = epElement.selectFirst("p.font-semibold")?.text()?.trim() ?: "Episode ${epIndex + 1}"
                    val epDesc = epElement.selectFirst("p.text-slate-400")?.text()?.trim()

                    episodes.add(
                        newEpisode(epUrl) {
                            this.name = epTitle
                            this.season = seasonNum
                            this.episode = epIndex + 1
                            this.description = epDesc
                        }
                    )
                }
            }

            return newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
                this.posterUrl = poster
                this.backgroundPosterUrl = backdrop
                this.plot = plot
                this.year = year
                this.rating = rating
                this.tags = genres
                this.recommendations = recommendations
                addActors(actors)
                trailerUrl?.let { addTrailer(it) }
            }
        } else {
            // فيلم
            val playUrl = document.selectFirst("a[href*='/play/']")?.attr("href") ?: url.replace("/item/", "/play/")

            return newMovieLoadResponse(title, url, TvType.Movie, playUrl) {
                this.posterUrl = poster
                this.backgroundPosterUrl = backdrop
                this.plot = plot
                this.year = year
                this.duration = duration
                this.rating = rating
                this.tags = genres
                this.recommendations = recommendations
                addActors(actors)
                trailerUrl?.let { addTrailer(it) }
            }
        }
    }

    // ─────────────────────────────────────────────
    // 4. استخراج روابط المشاهدة والترجمة (Links & Subs)
    // ─────────────────────────────────────────────
    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val document = app.get(data).document
        val html = document.html()

        // 1. استخراج الجودات وروابط الفيديو من كود الجافاسكربت
        val qualitiesMatch = Regex("""const\s+qualitiesRaw\s*=\s*(\{.*?\});""").find(html)?.groupValues?.get(1)
        if (!qualitiesMatch.isNullOrBlank()) {
            val qualitiesJson = tryParseJson<Map<String, QualityItem>>(qualitiesMatch)

            qualitiesJson?.forEach { (label, info) ->
                val videoUrl = info.url?.replace("\\/", "/") ?: return@forEach
                val qualityInt = label.toIntOrNull() ?: Qualities.P720.value

                callback.invoke(
                    newExtractorLink(
                        source = name,
                        name = "$name $label" + "p",
                        url = videoUrl,
                    ) {
                        this.referer = "$mainUrl/"
                        this.quality = qualityInt
                    }
                )
            }
        } else {
            // في حال عدم وجود تفصيل جودات، استخراج الرابط البديل المباشر
            val fallbackMatch = Regex("""const\s+fallbackUrl\s*=\s*"([^"]+)"""").find(html)?.groupValues?.get(1)
            fallbackMatch?.let { rawUrl ->
                val videoUrl = rawUrl.replace("\\/", "/")
                callback.invoke(
                    newExtractorLink(
                        source = name,
                        name = "$name Standard",
                        url = videoUrl,
                    ) {
                        referer = "$mainUrl/"
                        quality = Qualities.P720.value
                    }
                )
            }
        }

        // 2. استخراج الترجمات (Subtitles)
        val subtitlesMatch = Regex("""const\s+subtitlesRaw\s*=\s*(\[\{.*?\}\]);""").find(html)?.groupValues?.get(1)
        if (!subtitlesMatch.isNullOrBlank()) {
            val subsJson = tryParseJson<List<SubtitleItem>>(subtitlesMatch)

            subsJson?.forEach { sub ->
                val subUrl = sub.path?.replace("\\/", "/") ?: return@forEach
                subtitleCallback.invoke(
                    SubtitleFile(
                        lang = sub.label ?: "Arabic",
                        url = subUrl
                    )
                )
            }
        }

        return true
    }

    // كائنات مساعدة لتحليل JSON
    data class QualityItem(
        @JsonProperty("url") val url: String?,
        @JsonProperty("type") val type: String?
    )

    data class SubtitleItem(
        @JsonProperty("lang_code") val langCode: String?,
        @JsonProperty("label") val label: String?,
        @JsonProperty("format") val format: String?,
        @JsonProperty("path") val path: String?
    )
}