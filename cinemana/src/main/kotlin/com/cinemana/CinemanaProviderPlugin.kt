//package com.cinemana
//
//import com.lagradost.cloudstream3.plugins.BasePlugin
//import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
//@CloudstreamPlugin
//class CinemanaProviderPlugin : BasePlugin() {
//    override fun load() {
//        registerMainAPI(Cinemana())
//    }
//}
//
//






//package com.cinemana
//
//import com.lagradost.cloudstream3.plugins.BasePlugin
//import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
//import android.content.Context
//import com.lagradost.cloudstream3.plugins.Plugin
//@CloudstreamPlugin
//class CinemanaPlugin : Plugin() {
//    override fun load(context: Context) {
//
//        registerMainAPI(Cinemana(context))
//    }
//}




package com.cinemana

import android.content.Context
import androidx.appcompat.app.AppCompatActivity
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin
import androidx.fragment.app.FragmentActivity

@CloudstreamPlugin
class CinemanaPlugin : Plugin() {
    override fun load(context: Context) {

        registerMainAPI(Cinemana(context))

        openSettings = { activityContext ->
            (activityContext as? FragmentActivity)?.let { activity ->
                val settingsFragment = CinemanaSettings()
                settingsFragment.show(activity.supportFragmentManager, "CinemanaSettings")
            }

        }
    }
}







//package com.cinemana
//
//import android.content.Context
//import android.util.Log
//import androidx.preference.PreferenceManager
//import com.lagradost.cloudstream3.*
//import com.lagradost.cloudstream3.utils.ExtractorLink
//import com.lagradost.cloudstream3.utils.getQualityFromName
//import com.lagradost.cloudstream3.utils.newExtractorLink
//import kotlinx.coroutines.Dispatchers
//import kotlinx.coroutines.async
//import kotlinx.coroutines.awaitAll
//import kotlinx.coroutines.coroutineScope
//import kotlinx.serialization.Serializable
//import java.net.URLEncoder
//import com.lagradost.cloudstream3.Actor
//import com.lagradost.cloudstream3.ActorData
//import kotlinx.serialization.SerialName
//
//class Cinemana(val context: Context) : MainAPI() {
//    override var name = "Shabakaty Cinemana (\uD83C\uDDEE\uD83C\uDDF6)"
//    override var mainUrl = "https://cinemana.shabakaty.com"
//    override var lang = "ar"
//    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries)
//    override val hasMainPage = true
//    private val apiV2 = "$mainUrl/api/android"
//
//    // هيكل القسم (كما في كودك الأصلي للحفاظ على الإعدادات)
//    data class Section(
//        val title: String,
//        val url: String,
//        val prefKey: String,
//        val defaultEnabled: Boolean = true
//    )
//
//    // --- القائمة الكاملة (تم الحفاظ عليها كما هي لعدم تخريب الإعدادات) ---
//    private val categories = listOf(
//        // أحدث الإضافات
//        Section("أحدث الإضافات", "$apiV2/newlyVideosItems/level/0/offset/12/page/", "cine_newly_added", true),
//
//        // --- أفلام ---
//        Section("أفلام - تاريخ الرفع - الأحدث", "$mainUrl/api/android/video/V/2?videoKind=1&langNb=&itemsPerPage=30&pageNumber=&level=0&sortParam=desc", "cine_mov_upload_desc", true),
//        Section("أفلام - تاريخ الرفع - الأقدم", "$mainUrl/api/android/video/V/2?videoKind=1&langNb=&itemsPerPage=30&pageNumber=&level=0&sortParam=asc", "cine_mov_upload_asc", false),
//        Section("أفلام - تاريخ الإصدار - الأحدث", "$mainUrl/api/android/video/V/2?videoKind=1&langNb=&itemsPerPage=30&pageNumber=&level=0&sortParam=r_desc", "cine_mov_release_desc", false),
//        Section("أفلام - تاريخ الإصدار - الأقدم", "$mainUrl/api/android/video/V/2?videoKind=1&langNb=&itemsPerPage=30&pageNumber=&level=0&sortParam=r_asc", "cine_mov_release_asc", false),
//        Section("أفلام - أبجديًا (أ-ي)", "$mainUrl/api/android/video/V/2?videoKind=1&langNb=&itemsPerPage=30&pageNumber=&level=0&sortParam=ar_title_asc", "cine_mov_ar_asc", false),
//        Section("أفلام - أبجديًا (ب-أ)", "$mainUrl/api/android/video/V/2?videoKind=1&langNb=&itemsPerPage=30&pageNumber=&level=0&sortParam=ar_title_desc", "cine_mov_ar_desc", false),
//        Section("أفلام - أبجديًا (Z-A)", "$mainUrl/api/android/video/V/2?videoKind=1&langNb=&itemsPerPage=30&pageNumber=&level=0&sortParam=en_title_desc", "cine_mov_en_desc", false),
//        Section("أفلام - أبجديًا (A-Z)", "$mainUrl/api/android/video/V/2?videoKind=1&langNb=&itemsPerPage=30&pageNumber=&level=0&sortParam=en_title_asc", "cine_mov_en_asc", false),
//        Section("أفلام - الأكثر مشاهدة", "$mainUrl/api/android/video/V/2?videoKind=1&langNb=&itemsPerPage=30&pageNumber=&level=0&sortParam=views_desc", "cine_mov_views_desc", true),
//        Section("أفلام - الأقل مشاهدة", "$mainUrl/api/android/video/V/2?videoKind=1&langNb=&itemsPerPage=30&pageNumber=&level=0&sortParam=views_asc", "cine_mov_views_asc", false),
//        Section("أفلام - أعلى تقييم IMDb", "$mainUrl/api/android/video/V/2?videoKind=1&langNb=&itemsPerPage=30&pageNumber=&level=0&sortParam=stars_desc", "cine_mov_stars_desc", true),
//        Section("أفلام - أقل تقييم IMDb", "$mainUrl/api/android/video/V/2?videoKind=1&langNb=&itemsPerPage=30&pageNumber=&level=0&sortParam=stars_asc", "cine_mov_stars_asc", false),
//
//        // --- مسلسلات ---
//        Section("مسلسلات - تاريخ الرفع - الأحدث", "$mainUrl/api/android/video/V/2?videoKind=2&langNb=&itemsPerPage=30&pageNumber=&level=0&sortParam=desc", "cine_ser_upload_desc", true),
//        Section("مسلسلات - تاريخ الرفع - الأقدم", "$mainUrl/api/android/video/V/2?videoKind=2&langNb=&itemsPerPage=30&pageNumber=&level=0&sortParam=asc", "cine_ser_upload_asc", false),
//        Section("مسلسلات - تاريخ الإصدار - الأحدث", "$mainUrl/api/android/video/V/2?videoKind=2&langNb=&itemsPerPage=30&pageNumber=&level=0&sortParam=r_desc", "cine_ser_release_desc", false),
//        Section("مسلسلات - تاريخ الإصدار - الأقدم", "$mainUrl/api/android/video/V/2?videoKind=2&langNb=&itemsPerPage=30&pageNumber=&level=0&sortParam=r_asc", "cine_ser_release_asc", false),
//        Section("مسلسلات - أبجديًا (أ-ي)", "$mainUrl/api/android/video/V/2?videoKind=2&langNb=&itemsPerPage=30&pageNumber=&level=0&sortParam=en_title_desc", "cine_ser_ar_asc", false),
//        Section("مسلسلات - أبجديًا (ي-أ)", "$mainUrl/api/android/video/V/2?videoKind=2&langNb=&itemsPerPage=30&pageNumber=&level=0&sortParam=en_title_asc", "cine_ser_ar_desc", false),
//        Section("مسلسلات - أبجديًا (Z-A)", "$mainUrl/api/android/video/V/2?videoKind=2&langNb=&itemsPerPage=30&pageNumber=&level=0&sortParam=en_title_desc", "cine_ser_en_desc", false),
//        Section("مسلسلات - أبجديًا (A-Z)", "$mainUrl/api/android/video/V/2?videoKind=2&langNb=&itemsPerPage=30&pageNumber=&level=0&sortParam=en_title_asc", "cine_ser_en_asc", false),
//        Section("مسلسلات - الأكثر مشاهدة", "$mainUrl/api/android/video/V/2?videoKind=2&langNb=&itemsPerPage=30&pageNumber=&level=0&sortParam=views_desc", "cine_ser_views_desc", true),
//        Section("مسلسلات - الأقل مشاهدة", "$mainUrl/api/android/video/V/2?videoKind=2&langNb=&itemsPerPage=30&pageNumber=&level=0&sortParam=views_asc", "cine_ser_views_asc", false),
//        Section("مسلسلات - أعلى تقييم IMDb", "$mainUrl/api/android/video/V/2?videoKind=2&langNb=&itemsPerPage=30&pageNumber=&level=0&sortParam=stars_desc", "cine_ser_stars_desc", true),
//        Section("مسلسلات - أقل تقييم IMDb", "$mainUrl/api/android/video/V/2?videoKind=2&langNb=&itemsPerPage=30&pageNumber=&level=0&sortParam=stars_asc", "cine_ser_stars_asc", false)
//    )
//
//    // Main Page Dummy (ضروري لكي يعمل التطبيق، لكننا نتجاهله في getMainPage ونستخدم categories)
//    override val mainPage = mainPageOf("cine_main_page_init" to "أحدث الإضافات")
//
//    private fun isSectionEnabled(prefKey: String, default: Boolean): Boolean {
//        val prefs = PreferenceManager.getDefaultSharedPreferences(context)
//        return prefs.getBoolean(prefKey, default)
//    }
//
//    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
//        val items = mutableListOf<HomePageList>()
//        var hasMore = false
//
//        Log.d(name, "getMainPage called -> page=$page, request.name='${request.name}', request.data='${request.data}'")
//
//        val requestData = request.data ?: ""
//
//        // =========================================================================
//        // 1) منطق Pagination (تم استبداله بمنطق Code 2 بالكامل)
//        // =========================================================================
//        // نتحقق من أن requestData ليس فارغاً وأنه ليس مجرد تهيئة الصفحة الرئيسية
//        if (requestData.isNotBlank() && requestData != "cine_main_page_init") {
//            Log.d(name, "Detected section pagination request. Fetching only for request.data: $requestData , page=$page")
//
//            // --- هذا المنطق منسوخ حرفياً من Code 2 ---
//            val fetchUrl = when {
//                requestData.contains("/page/") -> {
//                    if (requestData.endsWith("/page/"))
//                        "$requestData$page/"
//                    else
//                        requestData.replace(Regex("/page/\\d+/?$"), "/page/$page/")
//                }
//                requestData.contains("pageNumber=") -> {
//                    val zeroBasedPage = page - 1
//                    val replaced = requestData.replace(
//                        Regex("pageNumber=\\d*"),
//                        "pageNumber=$zeroBasedPage"
//                    )
//                    if (replaced == requestData) {
//                        if (requestData.contains("?"))
//                            "$requestData&pageNumber=$zeroBasedPage"
//                        else
//                            "$requestData?pageNumber=$zeroBasedPage"
//                    } else replaced
//                }
//                else -> {
//                    if (requestData.endsWith("/"))
//                        "$requestData$page/"
//                    else
//                        "$requestData/$page/"
//                }
//            }
//            // ----------------------------------------
//
//            Log.d(name, "Section fetch URL resolved to: $fetchUrl")
//            val resp = runCatching { app.get(fetchUrl).parsedSafe<List<Map<String, Any>>>() }.getOrNull()
//            val parsed = resp?.mapNotNull { it.toCinemanaItem().toSearchResponse() } ?: emptyList()
//
//            Log.d(name, "Section parsed items count: ${parsed.size} (raw=${resp?.size ?: 0})")
//            val listTitle = request.name ?: "القسم"
//            items.add(HomePageList(listTitle, parsed))
//
//            val rawSize = resp?.size ?: parsed.size
//            // --- منطق hasMore من Code 2 ---
//            hasMore = rawSize >= 24 || rawSize >= 30 || rawSize >= 12
//
//            Log.d(name, "Returning only section results for '${listTitle}' with hasNext=$hasMore")
//            return newHomePageResponse(items, hasNext = hasMore)
//        }
//
//        // =========================================================================
//        // 2) منطق الصفحة الرئيسية (بناء الأقسام مع الحفاظ على الإعدادات)
//        // =========================================================================
//        if (page == 1) {
//            Log.d(name, "Building main page sections based on preferences.")
//
//            categories.forEach { section ->
//                // 1. التحقق من الإعدادات (خاص بـ Code 1)
//                if (!isSectionEnabled(section.prefKey, section.defaultEnabled)) {
//                    return@forEach
//                }
//
//                try {
//                    val baseTemplate = section.url
//                    val title = section.title
//
//                    // 2. تنظيف وبناء الرابط (منطق Code 2)
//                    val firstUrl = when {
//                        baseTemplate.contains("/page/") && baseTemplate.endsWith("/page/") -> "$baseTemplate/0".replace("//0", "/0").replace("/page//0", "/page/0")
//                        baseTemplate.contains("/page/") -> baseTemplate.replace(Regex("/page/\\d+/?$"), "/page/0/")
//                        baseTemplate.contains("page=") && baseTemplate.endsWith("page=") -> "${baseTemplate}0"
//                        baseTemplate.contains("page=") -> baseTemplate.replace(Regex("page=\\d*"), "page=0")
//                        else -> baseTemplate
//                    }.replace(":/", "://").replace(Regex("([^:])/+"), "$1/") // normalize logic from Code 2
//
//                    Log.d(name, "Fetching section: $title -> $firstUrl")
//
//                    val response = app.get(firstUrl).parsedSafe<List<Map<String, Any>>>()
//                    val parsedList = response?.mapNotNull { it.toCinemanaItem().toSearchResponse() } ?: emptyList()
//
//                    if (parsedList.isNotEmpty()) {
//                        val hp = HomePageList(title, parsedList)
//
//                        // 3. حقن الرابط الأصلي ليعمل السحب (منطق Reflection من Code 2)
//                        val candidateFieldNames = listOf("data", "requestData", "request", "pageUrl", "url", "extra", "nextPage", "params", "metadata")
//                        var attached: String? = null
//                        for (fName in candidateFieldNames) {
//                            try {
//                                val f = hp.javaClass.getDeclaredField(fName)
//                                f.isAccessible = true
//                                f.set(hp, baseTemplate) // نحقن baseTemplate لكي يستخدم في Pagination لاحقاً
//                                attached = fName
//                                Log.d(name, "Attached baseTemplate to HomePageList via field '$fName' for '$title'")
//                                break
//                            } catch (_: NoSuchFieldException) {
//                            } catch (e: Exception) {
//                                Log.w(name, "Reflection attach error on '$fName' for '$title': ${e.message}")
//                            }
//                        }
//
//                        if (attached == null) {
//                            // Fallback في حال فشل الحقن
//                            val titleWithMeta = "$title ||PAGE_BASE::$baseTemplate"
//                            items.add(HomePageList(titleWithMeta, parsedList))
//                            Log.w(name, "Could not attach baseTemplate to HomePageList for '$title' — added fallback titleWithMeta")
//                        } else {
//                            items.add(hp)
//                        }
//                    }
//
//                } catch (e: Exception) {
//                    Log.e(name, "Error loading section ${section.title}: ${e.message}")
//                }
//            }
//        }
//
//        // للصفحة الرئيسية نرجع hasNext = false لأن القوائم الداخلية هي التي تحتوي على التصفح
//        return newHomePageResponse(items, hasNext = false)
//    }
//
//    // دالة لحقن الرابط (مهمة لكي يعمل Pagination)
//    // استبدل الدالة القديمة بهذه الدالة الأكثر شمولاً
//    private fun injectUrl(hp: HomePageList, url: String) {
//        val candidateFieldNames = listOf(
//            "data", "requestData", "request", "pageUrl", "url",
//            "extra", "nextPage", "params", "metadata", "page", "page_base"
//        )
//
//        for (fName in candidateFieldNames) {
//            try {
//                val f = hp.javaClass.getDeclaredField(fName)
//                f.isAccessible = true
//                f.set(hp, url)
//                Log.d(name, "injectUrl: attached base url via field '$fName'")
//                return
//            } catch (e: NoSuchFieldException) {
//                // الحقل غير موجود — جرب الحقل التالي
//            } catch (e: Exception) {
//                Log.w(name, "injectUrl: failed to set field '$fName' -> ${e.message}")
//            }
//        }
//
//        // إذا فشلنا بالحقن، نضيف على الأقل العنوان مع ميتا حتى يسهل تتبعه في الواجهة
//        try {
//            val titleField = hp.javaClass.getDeclaredField("title")
//            titleField.isAccessible = true
//            val oldTitle = titleField.get(hp) as? String ?: ""
//            titleField.set(hp, "$oldTitle ||PAGE_BASE::$url")
//            Log.w(name, "injectUrl: reflection failed, added PAGE_BASE to title as fallback")
//        } catch (_: Exception) { }
//    }
//
//
//
//
//    override suspend fun search(query: String): List<SearchResponse>? {
//        Log.d(name, "🔍 search() first page query='$query' (fallback to paged)")
//        return search(query, 1)?.items
//    }
//
//    override suspend fun search(query: String, page: Int): SearchResponseList? = coroutineScope {
//        val encoded = URLEncoder.encode(query, "utf-8")
//        val itemsPerPageSearch = 30 // هذا لا يستخدم مباشرة لتحديد hasMore الآن
//        val currentYear = java.util.Calendar.getInstance().get(java.util.Calendar.YEAR)
//        val yearRange = "1900,$currentYear"
//
//        val pageParam_0_indexed = (page - 1).coerceAtLeast(0) // API uses 0-indexed pages
//
//        Log.d(
//            name,
//            "🔎 [SEARCH_PAGINATION] Initiating search for query: '$query', requested page: $page"
//        )
//        Log.d(name, "  - Calculated API page parameter (0-indexed): $pageParam_0_indexed")
//        Log.d(name, "  - Items requested per API call: $itemsPerPageSearch")
//
//        val moviesUrl =
//            "$apiV2/AdvancedSearch?level=0&videoTitle=$encoded&staffTitle=$encoded&year=$yearRange&page=$pageParam_0_indexed&type=movies&itemsPerPage=$itemsPerPageSearch"
//        val seriesUrl =
//            "$apiV2/AdvancedSearch?level=0&videoTitle=$encoded&staffTitle=$encoded&year=$yearRange&page=$pageParam_0_indexed&type=series&itemsPerPage=$itemsPerPageSearch"
//
//        Log.d(name, "  - Movies API URL: $moviesUrl")
//        Log.d(name, "  - Series API URL: $seriesUrl")
//
//        val (moviesRawAndParsed, seriesRawAndParsed) = listOf(moviesUrl, seriesUrl).map { url ->
//            async(Dispatchers.IO) {
//                runCatching {
//                    Log.d(name, "📡 [SEARCH_PAGINATION] Requesting data from: $url")
//                    val rawResp = app.get(url).parsedSafe<List<Map<String, Any>>>()
//                    val rawSize = rawResp?.size ?: 0
//                    Log.d(name, "📥 [SEARCH_PAGINATION] Received RAW ${rawSize} items from $url")
//
//                    val parsedItems = rawResp?.mapNotNull { itemMap ->
//                        val cinemanaItem = itemMap.toCinemanaItem()
//                        if (cinemanaItem.nb == null) {
//                            Log.w(
//                                name,
//                                "⚠️ [SEARCH_PAGINATION_PARSE_WARN] CinemanaItem.nb is NULL for item from $url. Raw Map: $itemMap"
//                            )
//                        }
//                        val searchResponse = cinemanaItem.toSearchResponse()
//                        if (searchResponse == null) {
//                            Log.w(
//                                name,
//                                "⚠️ [SEARCH_PAGINATION_PARSE_WARN] toSearchResponse returned NULL for item from $url. CinemanaItem: $cinemanaItem"
//                            )
//                        }
//                        searchResponse
//                    } ?: emptyList()
//
//                    Log.d(
//                        name,
//                        "✨ [SEARCH_PAGINATION] PARSED ${parsedItems.size} valid items from $url (after filtering null IDs/responses)."
//                    )
//                    Pair(rawSize, parsedItems)
//                }.getOrDefault(Pair(0, emptyList()))
//            }
//        }.awaitAll()
//
//        val moviesRawCount = moviesRawAndParsed.first
//        val movies = moviesRawAndParsed.second
//
//        val seriesRawCount = seriesRawAndParsed.first
//        val series = seriesRawAndParsed.second
//
//        Log.d(
//            name,
//            "🎬 [SEARCH_PAGINATION] Movies: RAW=${moviesRawCount}, PARSED=${movies.size} for page $page."
//        )
//        Log.d(
//            name,
//            "📺 [SEARCH_PAGINATION] Series: RAW=${seriesRawCount}, PARSED=${series.size} for page $page."
//        )
//
//        // دمج النتائج بالتناوب: movie0, series0, movie1, series1, ...
//        val maxSize = maxOf(movies.size, series.size)
//        val interleaved = ArrayList<SearchResponse>(movies.size + series.size)
//        for (i in 0 until maxSize) {
//            if (i < movies.size) interleaved.add(movies[i])
//            if (i < series.size) interleaved.add(series[i])
//        }
//
//        Log.d(
//            name,
//            "🔄 [SEARCH_PAGINATION] Interleaved ${interleaved.size} items in total for page $page."
//        )
//
//        fun scoreMatch(title: String?, q: String): Int {
//            if (title.isNullOrBlank()) return 0
//            val t = title.lowercase()
//            val ql = q.lowercase().trim()
//            if (t == ql) return 100
//            if (t.startsWith(ql)) return 80
//            if (t.contains(ql)) return 60
//            val tokens = ql.split(Regex("\\s+")).filter { it.isNotBlank() }
//            val tokenMatches = tokens.count { t.contains(it) }
//            return 40 + tokenMatches
//        }
//
//        val sorted = interleaved
//            .mapIndexed { idx, item ->
//                val titleCandidate = item.name ?: item.url ?: ""
//                val score = scoreMatch(titleCandidate, query)
//                Triple(item, score, idx)
//            }
//            .sortedWith(
//                compareByDescending<Triple<SearchResponse, Int, Int>> { it.second }
//                    .thenBy { it.third }
//            )
//            .map { it.first }
//
//        // إزالة التكرار إن وجد
//        val finalResults = sorted.distinctBy { "${it.url ?: ""}-${it.name ?: ""}" }
//
//        Log.d(name, "✅ Sorted & deduped results = ${finalResults.size}")
//
//        val hasMore = interleaved.isNotEmpty()
//
//        Log.d(
//            name,
//            "🤔 [SEARCH_PAGINATION] Determining 'hasMore' using interleaved.isNotEmpty() logic."
//        )
//        Log.d(name, "  - Total interleaved items for page $page: ${interleaved.size}")
//        Log.d(name, "  - Result: hasMore = $hasMore. (If true, UI will request page ${page + 1})")
//
//        Log.d(
//            name,
//            "✅ [SEARCH_PAGINATION] Search for query: '$query', page: $page completed. Returning ${finalResults.size} items with hasMore: $hasMore."
//        )
//        newSearchResponseList(finalResults, hasMore)
//    }
//
//
//    override suspend fun load(url: String): LoadResponse? {
//        val extractedId = url.substringAfterLast("/")
//
//        val detailsUrl = "$mainUrl/api/android/allVideoInfo/id/$extractedId"
//        Log.d(
//            name,
//            "Loading details for URL: $detailsUrl (Using extracted ID: $extractedId from input URL: $url)"
//        )
//        val detailsMap = app.get(detailsUrl).parsedSafe<Map<String, Any>>()
//        if (detailsMap == null) {
//            Log.e(
//                name,
//                "Failed to parse details from: $detailsUrl. Response might be empty or malformed."
//            )
//            return null
//        }
//        val details = detailsMap.toCinemanaItem()
//
//        val title = details.enTitle ?: run {
//            Log.e(name, "Title is null for item from URL: $detailsUrl")
//            return null
//        }
//        val posterUrl = details.imgObjUrl
//        val plot = details.enContent
//        val year = details.year?.toIntOrNull()
//
//        // ------------------ حساب التقييم مع البوينت العشرية ------------------
//        val ratingFloatPrimary = details.stars?.toFloatOrNull()
//
//        val finalRatingScore: Score? = ratingFloatPrimary?.let { Score.from10(it) } ?: run {
//            val altCandidates = listOf("rate", "filmRating", "seriesRating")
//            altCandidates.mapNotNull { k ->
//                val raw = detailsMap[k]
//                val asFloat = when (raw) {
//                    is Number -> raw.toDouble().toFloat()
//                    is String -> raw.toFloatOrNull()
//                    else -> null
//                }
//                asFloat?.let { Score.from10(it) }
//            }.firstOrNull()
//        }
//
//        Log.d(name, "Parsed rating: raw='${details.stars}', finalScore=$finalRatingScore")
//
//        // ------------------ التصنيفات والممثلون ------------------
//        val genresList = details.categories
//            ?.mapNotNull { cat ->
//                cat.en_title?.takeIf { it.isNotBlank() } ?: cat.ar_title?.takeIf { it.isNotBlank() }
//            }
//            ?.distinct()
//            ?: emptyList()
//
//        val actorsList: List<ActorData> = details.actorsInfo?.mapNotNull { actorInfoItem ->
//            val actorName =
//                actorInfoItem.name?.trim()?.takeIf { it.isNotEmpty() } ?: return@mapNotNull null
//            val actorImageUrl = actorInfoItem.staff_img_thumb ?: actorInfoItem.staff_img
//            ?: "defaultImages/not_available.jpg"
//
//            ActorData(
//                actor = Actor(
//                    name = actorName, // فقط الاسم
//                    image = actorImageUrl
//                ),
//                roleString = null // تجاهل رقم الدور
//            )
//        } ?: emptyList()
//
//
//        // ------------------ بناء الاستجابة ------------------
//        return if (details.kind == 2) {
//            // TV Series
//            val seasonsAndEpisodesUrl = "$mainUrl/api/android/videoSeason/id/$extractedId"
//            Log.d(name, "Fetching seasons and episodes from: $seasonsAndEpisodesUrl")
//
//            val episodesResponse =
//                app.get(seasonsAndEpisodesUrl).parsedSafe<List<Map<String, Any>>>()
//            val episodes = mutableListOf<Episode>()
//            val seasonsMap = mutableMapOf<Int, MutableList<Episode>>()
//
//            episodesResponse?.forEach { episodeMap ->
//                val episodeDetails = episodeMap.toCinemanaItem()
//                if (episodeDetails.nb != null && episodeDetails.enTitle != null) {
//                    val episodeNum = (episodeDetails.episodeNummer as? String)?.toIntOrNull() ?: 1
//                    val seasonNum = (episodeDetails.season as? String)?.toIntOrNull() ?: 1
//                    val episodeTitle = "الموسم $seasonNum - الحلقة $episodeNum"
//
//                    val newEpisode = newEpisode(episodeDetails.nb) {
//                        this.name = episodeTitle
//                        this.season = seasonNum
//                        this.episode = episodeNum
//                        this.posterUrl = episodeDetails.imgObjUrl ?: posterUrl
//                        this.description = episodeDetails.enContent
//                    }
//                    seasonsMap.getOrPut(seasonNum) { mutableListOf() }.add(newEpisode)
//                } else {
//                    Log.w(
//                        name,
//                        "Skipping malformed episode item: $episodeMap for series ID: $extractedId"
//                    )
//                }
//            }
//
//            // ترتيب الحلقات حسب الموسم والرقم
//            val sortedSeasonNumbers = seasonsMap.keys.sorted()
//            sortedSeasonNumbers.forEach { sNum ->
//                val seasonEpisodes = seasonsMap[sNum]
//                seasonEpisodes?.sortBy { it.episode }
//                if (seasonEpisodes != null) episodes.addAll(seasonEpisodes)
//            }
//
//            newTvSeriesLoadResponse(title, extractedId, TvType.TvSeries, episodes) {
//                this.posterUrl = posterUrl
//                this.plot = plot
//                this.year = year
//                this.score = finalRatingScore
//                if (genresList.isNotEmpty()) this.tags = genresList
//                if (actorsList.isNotEmpty()) this.actors = actorsList
//            }
//        } else {
//            // Movie
//            newMovieLoadResponse(title, extractedId, TvType.Movie, extractedId) {
//                this.posterUrl = posterUrl
//                this.plot = plot
//                this.year = year
//                this.score = finalRatingScore
//                if (genresList.isNotEmpty()) this.tags = genresList
//                if (actorsList.isNotEmpty()) this.actors = actorsList
//            }
//        }
//    }
//
//
//    override suspend fun loadLinks(
//        data: String,
//        isCasting: Boolean,
//        subtitleCallback: (SubtitleFile) -> Unit,
//        callback: (ExtractorLink) -> Unit
//    ): Boolean {
//        val extractedId = data.substringAfterLast("/")
//
//        val videosUrl = "$apiV2/transcoddedFiles/id/$extractedId"
//        Log.d(name, "Fetching video links from: $videosUrl")
//        val videoResponse = app.get(videosUrl).parsedSafe<List<Map<String, Any>>>()
//
//        if (videoResponse.isNullOrEmpty()) {
//            Log.e(
//                name,
//                "Failed to get video links from $videosUrl or response was empty for ID: $extractedId"
//            )
//            return false
//        }
//
//        Log.d(
//            name,
//            "Received ${videoResponse.size} links. Reversing order to show highest quality first."
//        )
//
//        videoResponse.reversed().forEach { videoMap ->
//            val videoUrl = videoMap["videoUrl"] as? String
//            val resolution = videoMap["resolution"] as? String
//            val linkName = resolution ?: "Default"
//
//            if (videoUrl != null) {
//                val headers = mapOf("Referer" to mainUrl)
//                callback(
//                    newExtractorLink(
//                        source = name,
//                        name = linkName,
//                        url = videoUrl
//                    ) {
//                        getQualityFromName(resolution)
//                    }
//                )
//            } else {
//                Log.w(name, "videoUrl is null for a video map in ID: $extractedId, Map: $videoMap")
//            }
//        }
//
//        val detailsUrl = "$apiV2/allVideoInfo/id/$extractedId"
//        app.get(detailsUrl).parsedSafe<Map<String, Any>>()?.let { detailsMap ->
//            (detailsMap["translations"] as? List<Map<String, Any>>)?.forEach { sub ->
//                val file = sub["file"] as? String
//                val lang = sub["name"] as? String
//                if (file != null && lang != null) {
//                    subtitleCallback(SubtitleFile(lang, file))
//                }
//            }
//        }
//
//        return true
//    }
//
//    @Serializable
//    data class Category(
//        val en_title: String? = null,
//        val ar_title: String? = null
//    )
//
//    @Serializable
//    data class ActorInfo(
//        val nb: String? = null,
//        val name: String? = null,
//        val role: String? = null,
//        val staff_img: String? = null,
//        val staff_img_thumb: String? = null,
//        val staff_img_medium_thumb: String? = null
//    )
//
//    @Serializable
//    data class CinemanaItem(
//        val nb: String? = null,
//        @SerialName("en_title") val enTitle: String? = null,
//        val imgObjUrl: String? = null,
//        val year: String? = null,
//        @SerialName("en_content") val enContent: String? = null,
//        val stars: String? = null,
//        val kind: Int? = null,
//        val fileFile: String? = null,
//        @SerialName("episodeNummer") val episodeNummer: String? = null,
//        val season: String? = null,
//        val categories: List<Category>? = null,
//        @SerialName("actorsInfo") val actorsInfo: List<ActorInfo>? = null
//    )
//
//    @Serializable
//    data class SeasonNumberItem(
//        val season: String? = null
//    )
//
//    @Serializable
//    data class VideoGroup(
//        val id: String? = null,
//        val title: String? = null,
//    )
//
//    private fun Map<String, Any>.toCinemanaItem(): CinemanaItem {
//        val parsedNb = when (val nbValue = this["nb"]) {
//            is String -> nbValue
//            is Int -> nbValue.toString()
//            is Double -> nbValue.toLong().toString()
//            else -> null
//        }
//
//        val categoriesParsed = (this["categories"] as? List<*>)?.mapNotNull { c ->
//            (c as? Map<*, *>)?.let { m ->
//                Category(
//                    en_title = m["en_title"] as? String,
//                    ar_title = m["ar_title"] as? String
//                )
//            }
//        }
//
//        val actorsParsed = (this["actorsInfo"] as? List<*>)?.mapNotNull { a ->
//            (a as? Map<*, *>)?.let { m ->
//                ActorInfo(
//                    nb = (m["nb"] as? String) ?: (m["nb"] as? Int)?.toString(),
//                    name = m["name"] as? String,
//                    role = m["role"] as? String,
//                    staff_img = m["staff_img"] as? String,
//                    staff_img_thumb = m["staff_img_thumb"] as? String,
//                    staff_img_medium_thumb = m["staff_img_medium_thumb"] as? String
//                )
//            }
//        }
//
//        return CinemanaItem(
//            nb = parsedNb,
//            enTitle = this["en_title"] as? String,
//            imgObjUrl = this["imgObjUrl"] as? String ?: this["img"] as? String,
//            year = this["year"] as? String,
//            enContent = this["en_content"] as? String,
//            stars = this["stars"] as? String,
//            kind = (this["kind"] as? String)?.toIntOrNull() ?: (this["kind"] as? Int),
//            fileFile = this["fileFile"] as? String,
//            episodeNummer = this["episodeNummer"] as? String,
//            season = this["season"] as? String,
//            categories = categoriesParsed,
//            actorsInfo = actorsParsed
//        )
//    }
//
//    // ================== التصحيح النهائي والصحيح هنا ==================
//    private fun CinemanaItem.toSearchResponse(): SearchResponse? {
//        val validNb = nb ?: run {
//            Log.e(name, "🚫 CinemanaItem.nb is null for title: $enTitle")
//            return null
//        }
//
//        val rating = this.stars?.toFloatOrNull()
//        val scoreObject = rating?.let { Score.from10(it) }
//
//        return if (kind == 2) {
//            newTvSeriesSearchResponse(
//                name = enTitle ?: "No Title",
//                url = validNb,
//                type = TvType.TvSeries
//            ) {
//                this.posterUrl = imgObjUrl
//                this.score = scoreObject
//            }
//        } else {
//            newMovieSearchResponse(
//                name = enTitle ?: "No Title",
//                url = validNb,
//                type = TvType.Movie
//            ) {
//                this.posterUrl = imgObjUrl
//                this.score = scoreObject
//            }
//        }
//    }
//
//}
