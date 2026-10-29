package com.eshk

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.newExtractorLink
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLDecoder
import java.net.URLEncoder

class OscarTvProvider : MainAPI() {

    override var name = "Oscar TV"
    override var mainUrl = "https://api.ossccaarr111.online"
    override var lang = "ar"
    override val hasMainPage = true
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries, TvType.Anime, TvType.Live)

    companion object {
        private const val STEP1 = "https://flech.tn/scrapefinal/dramabox/step1_prepare_oscar.php"
        private const val PLAY_UA = "Mozilla/5.0 (Linux; Android 16; RMX5061 Build/BP2A.250605.015; wv) AppleWebKit/537.36 (KHTML, like Gecko) Version/4.0 Chrome/156.0.8078.47 Mobile Safari/537.36"
    }
    private fun JSONObject.str(vararg keys: String): String? {
        for (k in keys) {
            if (this.has(k) && !this.isNull(k)) {
                val v = this.optString(k)
                if (v.isNotBlank() && v != "null") return v
            }
        }
        return null
    }

    private fun JSONObject.int(key: String): Int? {
        if (!this.has(key) || this.isNull(key)) return null
        return this.optInt(key, -1).takeIf { it != -1 } ?: str(key)?.toDoubleOrNull()?.toInt()
    }

    private fun JSONObject.arr(key: String): List<JSONObject> {
        val array = this.optJSONArray(key) ?: return emptyList()
        val list = mutableListOf<JSONObject>()
        for (i in 0 until array.length()) {
            array.optJSONObject(i)?.let { list.add(it) }
        }
        return list
    }

    private fun JSONObject.playCookie(): String? = try {
        str("deep_link")?.substringAfter("cookies=", "")?.takeIf { it.isNotBlank() }
            ?.substringBefore("&")?.let { URLDecoder.decode(it, "UTF-8") }
    } catch (_: Exception) { null }

    /** طلب الترويسات من flech.tn */
    private suspend fun call(path: String): JSONObject? = try {
        val url = "$mainUrl/$path"
        val prepRes = app.post(
            STEP1,
            headers = mapOf(
                "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/141.0.0.0 Safari/537.36",
                "Content-Type" to "application/x-www-form-urlencoded",
            ),
            data = mapOf("url" to url),
        ).text

        val prepJson = JSONObject(prepRes)
        val h = prepJson.optJSONObject("headers") ?: return null

        val headers = mutableMapOf<String, String>()
        val keys = h.keys()
        while (keys.hasNext()) {
            val k = keys.next()
            if (k !in setOf("Host", "Connection")) {
                headers[k] = h.optString(k)
            }
        }

        val apiRes = app.get(url, headers = headers).text
        JSONObject(apiRes)
    } catch (_: Exception) { null }

    private fun JSONObject.data(): JSONObject? = this.optJSONObject("data")
    private fun JSONObject.dataArr(): List<JSONObject> = this.arr("data")

    private fun JSONObject.toSearch(tvType: TvType): SearchResponse? {
        val id = int("id") ?: return null
        val title = str("title_ar", "title_en", "name") ?: return null
        val poster = str("poster")?.let { if (it.startsWith("http")) it else "$mainUrl$it" }
        return if (tvType == TvType.Movie) {
            newMovieSearchResponse(title, "oscar://movie/$id", TvType.Movie) { this.posterUrl = poster }
        } else {
            val kind = if (tvType == TvType.Anime) "anime" else "serie"
            newTvSeriesSearchResponse(title, "oscar://$kind/$id", tvType) { this.posterUrl = poster }
        }
    }

    private suspend fun list(path: String, tvType: TvType): List<SearchResponse> =
        call(path)?.dataArr()?.mapNotNull { it.toSearch(tvType) } ?: emptyList()

    private suspend fun searchOne(pathPrefix: String, query: String, tvType: TvType): List<SearchResponse> =
        call("$pathPrefix/?page=1&limit=20&search=${URLEncoder.encode(query, "UTF-8")}")
            ?.dataArr()?.mapNotNull { it.toSearch(tvType) } ?: emptyList()

    override val mainPage = mainPageOf(
        "movies_top" to "رائج اليوم",
        "latest_episodes" to "أحدث الحلقات",
        "armovies" to "أحدث الأفلام العربية",
        "arseries" to "أحدث الإضافات العربية",
        "oscar://movies/" to "أفلام",
        "oscar://series/" to "مسلسلات",
        "oscar://anime/" to "أنمي",
        "oscar://channels/" to "قنوات مباشرة",
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val data = request.data
        val items: List<SearchResponse> = when {
            data == "movies_top" || data == "latest_episodes" || data == "armovies" || data == "arseries" -> {
                if (page > 1) emptyList() else {
                    val home = call("api/v2/home.php?app_version=15") ?: return newHomePageResponse(request.name, emptyList())
                    val sections = home.optJSONObject("data")?.arr("sections") ?: emptyList()
                    val sec = when (data) {
                        "armovies" -> sections.firstOrNull {
                            it.str("section_type") == "movies" &&
                                (it.optJSONObject("content_filters")?.int("category_id") == 3 || it.str("title_ar") == "أحدث الأفلام العربية")
                        }
                        "arseries" -> sections.firstOrNull {
                            it.str("section_type") == "latest_additions" &&
                                (it.int("content_category_filter") == 1 || it.str("title_ar") == "أحدث الإضافات العربية")
                        }
                        else -> sections.firstOrNull { it.str("section_type") == data }
                    } ?: return newHomePageResponse(request.name, emptyList())
                    sec.arr("items").mapNotNull { o ->
                        if (o.has("home_team")) return@mapNotNull null
                        val id = o.int("item_id") ?: o.int("id") ?: return@mapNotNull null
                        val sTitle = o.str("series_title")
                        if (sTitle != null && o.has("season_id")) {
                            val sn = o.int("season_number") ?: 1
                            val ep = o.int("episode_number") ?: 0
                            val epPoster = o.str("series_poster", "series_banner")?.let { if (it.startsWith("http")) it else "$mainUrl$it" }
                            return@mapNotNull newMovieSearchResponse(
                                if (sn > 1) "$sTitle — م$sn:ح$ep" else "$sTitle — حلقة $ep",
                                "oscar://ep/$id", TvType.Movie
                            ) { this.posterUrl = epPoster }
                        }
                        val itemType = o.str("item_type") ?: if (o.has("episode_count")) "serie" else "movie"
                        val title = o.str("title_ar", "title_en") ?: return@mapNotNull null
                        val poster = o.str("poster", "image")?.let { if (it.startsWith("http")) it else "$mainUrl$it" }
                        when (itemType) {
                            "movie" -> newMovieSearchResponse(title, "oscar://movie/$id", TvType.Movie) { this.posterUrl = poster }
                            "anime" -> newTvSeriesSearchResponse(title, "oscar://anime/$id", TvType.Anime) { this.posterUrl = poster }
                            else -> newTvSeriesSearchResponse(title, "oscar://serie/$id", TvType.TvSeries) { this.posterUrl = poster }
                        }
                    }
                }
            }
            data.startsWith("oscar://movies/") -> list("api/movies/?page=$page&limit=20", TvType.Movie)
            data.startsWith("oscar://series/") -> list("api/series/?page=$page&limit=20", TvType.TvSeries)
            data.startsWith("oscar://anime/") -> list("api/anime/?page=$page&limit=20&anime_type=tv,ova,ona,special,movie", TvType.Anime)
            data.startsWith("oscar://channels/") ->
                call("api/channels/?page=$page&limit=20")?.dataArr()?.mapNotNull { o ->
                    val id = o.int("id") ?: return@mapNotNull null
                    val chName = o.str("name") ?: return@mapNotNull null
                    val logo = o.str("logo")?.let { if (it.startsWith("http")) it else "$mainUrl$it" }
                    newTvSeriesSearchResponse(chName, "oscar://channel/$id", TvType.Live) { this.posterUrl = logo }
                } ?: emptyList()
            else -> emptyList()
        }
        return newHomePageResponse(request.name, items)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val q = URLEncoder.encode(query.trim(), "UTF-8")
        if (q.isBlank()) return emptyList()
        return coroutineScope {
            listOf(
                async { searchOne("api/movies", query, TvType.Movie) },
                async { searchOne("api/series", query, TvType.TvSeries) },
                async { searchOne("api/anime", query, TvType.Anime) },
            ).awaitAll().flatten()
        }.distinctBy { it.url }
    }

    private fun JSONObject.poster2(): String? = str("poster")?.let { if (it.startsWith("http")) it else "$mainUrl$it" }

    override suspend fun load(url: String): LoadResponse? {
        if (!url.contains("oscar://")) return null
        val parts = url.substringAfter("oscar://").split("/")
        val kind = parts.firstOrNull() ?: return null
        val id = parts.getOrNull(1) ?: return null

        return when {
            kind == "channel" -> {
                val ch = call("api/channels/show.php?id=$id")?.data() ?: return null
                val name = ch.str("name") ?: "Channel $id"
                val streams = ch.arr("streams")
                if (streams.isEmpty()) return null
                val packed = streams.mapNotNull { st ->
                    val u = st.str("stream_url")?.substringBefore("#") ?: return@mapNotNull null
                    val q = st.str("quality") ?: ""
                    "$u::$q"
                }.joinToString("||")
                newMovieLoadResponse(name, url, TvType.Live, "oscar://ch/$packed") {
                    this.posterUrl = ch.str("logo")?.let { if (it.startsWith("http")) it else "$mainUrl$it" }
                }
            }
            kind == "movie" -> {
                val d = call("api/movies/show.php?id=$id")?.data() ?: return null
                val title = d.str("title_ar", "title_en") ?: return null
                val tags = d.arr("genres").mapNotNull { it.str("name") }
                newMovieLoadResponse(title, url, TvType.Movie, "oscar://player/movies/$id") {
                    this.posterUrl = d.poster2()
                    this.plot = d.str("story")
                    this.year = d.int("release_year") ?: d.str("release_date")?.take(4)?.toIntOrNull()
                    this.tags = tags
                }
            }
            kind == "ep" -> {
                var d = call("api/episodes/show.php?id=$id")?.data()
                var anime = false
                if (d == null) {
                    d = call("api/anime/episodes/show.php?id=$id")?.data()
                    anime = true
                }
                if (d == null) return null
                val sTitle = d.str("series_title") ?: d.str("title_ar", "title_en") ?: return null
                val sn = d.int("season_number") ?: 1
                val ep = d.int("episode_number") ?: 0
                newMovieLoadResponse(
                    if (sn > 1) "$sTitle — م$sn حلقة $ep" else "$sTitle — حلقة $ep",
                    url, TvType.Movie, "oscar://ep/$anime/$id"
                ) {
                    this.posterUrl = d.str("series_poster")?.let { if (it.startsWith("http")) it else "$mainUrl$it" }
                    this.plot = d.str("description")
                }
            }
            kind == "serie" || kind == "anime" || kind == "tv" || kind == "TvSeries" || kind == "Anime" -> {
                val isAnime = kind == "anime" || kind == "Anime"
                val apiBase = if (isAnime) "api/anime" else "api/series"
                var d = call("$apiBase/show.php?id=$id")?.data()
                if (d == null && !isAnime) {
                    val m = call("api/movies/show.php?id=$id")?.data()
                    if (m != null) {
                        return newMovieLoadResponse(
                            m.str("title_ar", "title_en") ?: return null, url, TvType.Movie, "oscar://player/movies/$id"
                        ) {
                            this.posterUrl = m.poster2()
                            this.plot = m.str("story")
                            this.year = m.int("release_year") ?: m.str("release_date")?.take(4)?.toIntOrNull()
                            this.tags = m.arr("genres").mapNotNull { it.str("name") }
                        }
                    }
                }
                if (d == null) return null
                val title = d.str("title_ar", "title_en", "name") ?: return null
                val seasons = d.arr("seasons")
                val episodes = coroutineScope {
                    seasons.chunked(6).flatMap { batch ->
                        batch.map { s ->
                            async {
                                val sid = s.int("season_id") ?: s.int("id") ?: return@async emptyList<Episode>()
                                val sn = s.int("season_number") ?: 1
                                val epPath = if (isAnime) "api/anime/episodes/?season_id=$sid" else "api/episodes/?season_id=$sid"
                                call(epPath)?.dataArr()?.mapIndexedNotNull { i, e ->
                                    val epId = e.int("id") ?: return@mapIndexedNotNull null
                                    val epNum = e.int("episode_number") ?: (i + 1)
                                    newEpisode(
                                        url = "oscar://ep/$isAnime/$epId",
                                        initializer = {
                                            this.name = "الحلقة $epNum"
                                            this.season = sn
                                            this.episode = epNum
                                            this.posterUrl = e.str("thumbnail")?.let { if (it.startsWith("http")) it else "$mainUrl$it" }
                                        },
                                        fix = false,
                                    )
                                } ?: emptyList()
                            }
                        }.awaitAll()
                    }.flatten()
                }
                if (episodes.isEmpty()) return null
                newTvSeriesLoadResponse(title, url, if (isAnime) TvType.Anime else TvType.TvSeries, episodes) {
                    this.posterUrl = d.poster2()
                    this.plot = d.str("story")
                    this.year = d.str("release_year")?.toIntOrNull() ?: d.int("release_year")
                    this.tags = d.arr("genres").mapNotNull { it.str("name") }
                }
            }
            else -> null
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ): Boolean {
        var got = false
        val seen = HashSet<String>()

        suspend fun emit(u0: String, label: String, quality: String?, cookie: String? = null) {
            if (!u0.startsWith("http")) return
            var u = u0
            if (u.contains("newcdn.seriesmp4.com")) {
                u = try {
                    val body = app.get(u0, headers = mapOf("User-Agent" to PLAY_UA)).text
                    Regex("src=\"(https://downloader\\.disk\\.yandex\\.ru[^\"]+)\"")
                        .find(body)?.groupValues?.get(1)?.replace("&" + "amp;", "&") ?: return
                } catch (_: Exception) { return }
            }
            if (!seen.add(u)) return

            val reqHeaders = mutableMapOf<String, String>()
            reqHeaders["user-agent"] = PLAY_UA
            reqHeaders["User-Agent"] = PLAY_UA

            if (!cookie.isNullOrBlank()) {
                reqHeaders["cookie"] = cookie
                reqHeaders["Cookie"] = cookie
            }

            callback(
                newExtractorLink(
                    name,
                    listOfNotNull(label, quality?.takeIf { it.isNotBlank() && it != "متعدد" }).joinToString(" "),
                    u,
                    if (u.contains(".mp4")) ExtractorLinkType.VIDEO else ExtractorLinkType.M3U8,
                ) {
                    this.headers = reqHeaders
                    this.quality = quality?.replace("p", "")?.toIntOrNull()
                        ?.let { q -> Qualities.entries.firstOrNull { it.value == q }?.value } ?: Qualities.Unknown.value
                }
            )
            got = true
        }

        when {
            data.startsWith("oscar://direct/") -> {
                emit(data.substringAfter("oscar://direct/"), "Oscar TV", "")
            }

            data.startsWith("oscar://ch/") -> {
                data.substringAfter("oscar://ch/").split("||").forEach { entry ->
                    val (u, q) = entry.split("::", limit = 2).let { it.getOrNull(0) to it.getOrNull(1) }
                    if (u != null) emit(u, "Oscar TV", q)
                }
            }

            data.startsWith("oscar://player/movies/") -> {
                val id = data.substringAfter("oscar://player/movies/", "")
                val d = call("api/movies/show.php?id=$id")?.data() ?: return false
                d.arr("watch_links").forEach { emit(it.str("url") ?: return@forEach, it.str("server_name") ?: "Oscar", it.str("quality"), it.playCookie()) }
                d.arr("download_links").forEach { emit(it.str("url") ?: return@forEach, "${it.str("server_name") ?: "DL"}", it.str("quality"), it.playCookie()) }
            }

            data.startsWith("oscar://ep/") -> {
                val rest = data.substringAfter("oscar://ep/", "")
                val isAnime = rest.substringBefore("/") == "true"
                val id = rest.substringAfter("/")
                val apiBase = if (isAnime) "api/anime/episodes" else "api/episodes"
                val d = call("$apiBase/show.php?id=$id")?.data() ?: return false
                d.arr("watch_links").forEach { emit(it.str("url") ?: return@forEach, it.str("server_name") ?: "Oscar", it.str("quality"), it.playCookie()) }
                d.arr("download_links").forEach { emit(it.str("url") ?: return@forEach, "${it.str("server_name") ?: "DL"}", it.str("quality"), it.playCookie()) }
            }
        }
        return got
    }
}
