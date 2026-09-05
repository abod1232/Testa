package com.faselhd
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin
import android.content.Context
import com.faselhd.FASELHD
import com.lagradost.cloudstream3.Episode
import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.fixUrlNull
import com.lagradost.cloudstream3.newEpisode
import com.lagradost.cloudstream3.newMovieLoadResponse
import com.lagradost.cloudstream3.newTvSeriesLoadResponse
import com.lagradost.cloudstream3.newTvSeriesSearchResponse
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore

@CloudstreamPlugin
class FaselhdPlugin: Plugin() {
    override fun load(context: Context) {
        registerMainAPI(FASELHD(context))
    }
}

//override suspend fun load(url: String): LoadResponse? {
//    val base = baseUrl()
//    val absoluteUrl = if (url.startsWith("/")) "$base$url" else url
//
//    val doc = smartGet(absoluteUrl)
//    val title = doc.selectFirst(".singleInfo .title.h1")?.ownText()?.trim() ?: return null
//    val poster = fixUrlNull(
//        doc.selectFirst("meta[itemprop=image]")?.attr("content")
//            ?: doc.selectFirst(".posterImg img.poster")?.attr("src")
//    )
//    val plot = doc.selectFirst(".singleDesc p, .story p")?.text()?.trim()
//    val backgroundPoster = doc.selectFirst("div.singlePage")?.attr("style")
//        ?.let { Regex("""url\(['"]?(.*?)['"]?\)""").find(it)?.groupValues?.get(1) }
//        ?.let { fixUrlNull(it) }
//
//    var year: Int? = null
//    val tagsList = mutableListOf<String>()
//    doc.select("#singleList > div").forEach {
//        val text = it.text()
//        when {
//            text.contains("سنة الإنتاج") -> year = it.selectFirst("a")?.text()?.toIntOrNull()
//            text.contains("تصنيف") -> tagsList.addAll(
//                it.select("a").map { tagEl -> tagEl.text() })
//        }
//    }
//
//    val headers = getProtectedHeaders()
//
//    val seasonCards = doc.select(".seasonDiv")
//    val seasonUrlRegex = Regex("""window\.location\.href\s*=\s*['"]([^'"]+)['"]""")
//
//    val recommendations = seasonCards.mapNotNull { seasonEl ->
//        val onclickAttr = seasonEl.attr("onclick")
//        val seasonUrlRel = seasonUrlRegex.find(onclickAttr)?.groupValues?.get(1) ?: return@mapNotNull null
//        val seasonTitle = seasonEl.selectFirst(".title")?.text() ?: "موسم"
//        val seasonPoster = seasonEl.selectFirst("img")?.attr("data-src") ?: seasonEl.selectFirst("img")?.attr("src")
//        // 🔴 التصحيح هنا: نستخدم الطريقة اليدوية لدمج الرابط
//        val fullSeasonUrl = if (seasonUrlRel.startsWith("http")) seasonUrlRel else "$base$seasonUrlRel"
//        newTvSeriesSearchResponse(seasonTitle, fullSeasonUrl, TvType.TvSeries) {
//            this.posterUrl = seasonPoster
//            this.posterHeaders = headers
//        }
//    }
//
//    data class SeasonTask(val name: String, val url: String, val poster: String?)
//
//    val seasonTasks = mutableListOf<SeasonTask>()
//    seasonCards.forEachIndexed { idx, seasonEl ->
//        val onclickAttr = seasonEl.attr("onclick")
//        val seasonUrlRel = seasonUrlRegex.find(onclickAttr)?.groupValues?.get(1)
//        if (!seasonUrlRel.isNullOrBlank()) {
//            val seasonUrl = if (seasonUrlRel.startsWith("http")) seasonUrlRel else "$base$seasonUrlRel"
//            val seasonName = seasonEl.selectFirst(".title")?.text()?.trim() ?: "الموسم ${idx + 1}"
//            val seasonPoster = seasonEl.selectFirst("img")?.attr("data-src") ?: seasonEl.selectFirst("img")?.attr("src")
//            seasonTasks.add(SeasonTask(seasonName, seasonUrl, seasonPoster))
//        }
//    }
//
//    val allEpisodes = mutableListOf<Episode>()
//
//    if (seasonTasks.isNotEmpty()) {
//        val semaphore = Semaphore(5)
//        try {
//            val results: List<Pair<Int, List<Episode>>> = coroutineScope {
//                seasonTasks.mapIndexed { idx, task ->
//                    async(Dispatchers.IO) {
//                        semaphore.acquire()
//                        try {
//                            val seasonDoc = smartGet(task.url)
//                            val episodeElements = seasonDoc.select("div#epAll a")
//                            val eps = mutableListOf<Episode>()
//                            val seasonPosterUrl = task.poster?.let { fixUrlNull(it) } ?: poster
//
//                            episodeElements.forEach { el ->
//                                val epUrlRaw = el.attr("href").trim()
//                                if (epUrlRaw.isNotBlank()) {
//                                    val epTitle = el.ownText().ifBlank { el.text() }.trim()
//                                    if (!epTitle.contains("باقي الحلقات") && !epTitle.contains("المزيد")) {
//                                        val epNum = Regex("""\d+""").find(epTitle)?.value?.toIntOrNull()
//                                        val fullEpUrl = if (epUrlRaw.startsWith("http")) epUrlRaw else "$base$epUrlRaw"
//
//                                        // 🔴 التصحيح هنا: طريقة تعريف الحلقة
//                                        val episode = newEpisode(fullEpUrl) {
//                                            this.name = epTitle
//                                            this.episode = epNum
//                                            this.season = idx + 1
//                                            this.posterUrl = seasonPosterUrl
//                                        }
//                                        eps.add(episode)
//                                    }
//                                }
//                            }
//                            Pair(idx, eps.toList())
//                        } catch (e: Exception) {
//                            Pair(idx, emptyList())
//                        } finally {
//                            semaphore.release()
//                        }
//                    }
//                }.awaitAll()
//            }
//            results.sortedBy { it.first }.forEach { (_, eps) -> allEpisodes.addAll(eps) }
//        } catch (e: Exception) {}
//    } else {
//        // ... (باقي الكود كما هو) ...
//    }
//
//    return if (allEpisodes.isNotEmpty()) {
//        newTvSeriesLoadResponse(title, absoluteUrl, TvType.TvSeries, allEpisodes) {
//            this.posterUrl = poster
//            this.posterHeaders = headers
//            this.backgroundPosterUrl = backgroundPoster
//            this.year = year
//            this.plot = plot
//            this.tags = tagsList
//            this.recommendations = recommendations
//        }
//    } else {
//        newMovieLoadResponse(title, absoluteUrl, TvType.Movie, absoluteUrl) {
//            this.posterUrl = poster
//            this.posterHeaders = headers
//            this.backgroundPosterUrl = backgroundPoster
//            this.year = year
//            this.plot = plot
//            this.tags = tagsList
//            this.recommendations = recommendations
//        }
//    }
//}