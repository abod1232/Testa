package com.faselhd

import android.annotation.SuppressLint
import android.app.Activity
import android.app.Dialog
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.util.TypedValue
import android.view.Gravity
import android.view.ViewGroup
import android.view.Window
import android.view.WindowManager
import android.webkit.CookieManager
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import okhttp3.CookieJar
import okhttp3.FormBody
import okhttp3.Request as OkRequest
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.net.URLEncoder
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine
import android.widget.FrameLayout
import android.widget.TextView
import android.graphics.Color
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.net.Uri
import android.widget.LinearLayout
import kotlinx.coroutines.withContext
import kotlinx.coroutines.suspendCancellableCoroutine
import java.net.HttpURLConnection
import java.net.URL
import android.webkit.JavascriptInterface
import android.widget.ScrollView
import android.webkit.WebChromeClient
import android.view.View
import android.webkit.ConsoleMessage
import android.graphics.Bitmap
import java.io.InputStream
import android.webkit.SslErrorHandler
import android.widget.Toast
import com.lagradost.cloudstream3.newMovieSearchResponse

class FASELHD(private val context: Context) : MainAPI() {
    override var name = "FASELHD"
    override val hasQuickSearch = true
    override var mainUrl = "https://web31312x.faselhdx.bid"
    override var lang = "ar"
    override val hasMainPage = true
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries, TvType.Anime)
    companion object {

        var redirectUrl: String? = null

    }
    private suspend fun baseUrl(): String {
        redirectUrl?.let { return it }

        return try {
            val response = app.get(mainUrl, allowRedirects = true)
            val finalUrl = response.url

            val base = try {
                val uri = java.net.URI(finalUrl)
                "${uri.scheme}://${uri.host}"
            } catch (e: Exception) {
                mainUrl
            }

            redirectUrl = base

            base
        } catch (e: Exception) {
            mainUrl
        }
    }

    private val cfLock = Mutex()
    private var lastValidUserAgent =
        "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36"


    private fun getModernHeaders(url: String): MutableMap<String, String> {
        val cm = CookieManager.getInstance()
        val cookies = cm.getCookie(url) ?: ""
        return mutableMapOf(
            "Cookie" to cookies,
            "User-Agent" to lastValidUserAgent,
            "sec-ch-ua" to "\"Not:A-Brand\";v=\"99\", \"Google Chrome\";v=\"145\", \"Chromium\";v=\"145\"",
            "sec-ch-ua-mobile" to "?1",
            "sec-ch-ua-platform" to "\"Android\"",
            "upgrade-insecure-requests" to "1",
            "accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,image/apng,*/*;q=0.8,application/signed-exchange;v=b3;q=0.7",
            "sec-fetch-site" to "none",
            "sec-fetch-mode" to "navigate",
            "sec-fetch-dest" to "document",
            "accept-language" to "ar-EG,ar;q=0.9",
            "priority" to "u=0, i"
        )
    }
    private fun getProtectedHeaders(): Map<String, String> {
        return getModernHeaders(mainUrl)
    }
    private suspend fun smartGet(
        url: String,
        referer: String? = null,
        timeoutSeconds: Long? = null
    ): Document {
        val cleanUrl = if (!url.endsWith("/") && !url.substringAfterLast("/").contains(".")) "$url/" else url
        for (attempt in 1..3) {
            try {
                val headers = getModernHeaders(cleanUrl)
                if (referer != null) headers["Referer"] = referer

                val response = app.get(
                    cleanUrl,
                    headers = headers,
                    timeout = timeoutSeconds ?: 30L,
                    allowRedirects = true
                )

                if (response.code == 200 || response.code in 300..308) {
                    return response.document
                }

                if (response.code == 429) {
                    Log.w("FASELHD", "Got 429 on attempt $attempt for $cleanUrl. Retrying in $attempt second(s)...")
                    kotlinx.coroutines.delay(1000L * attempt)
                    continue
                }

                Log.d("FASELHD", "smartGet returned code ${response.code}. Checking for CF...")
                break

            } catch (e: Exception) {
                if (e.message?.contains("429") == true) {
                    Log.w("FASELHD", "Got 429 Exception on attempt $attempt for $cleanUrl. Retrying in $attempt second(s)...")
                    kotlinx.coroutines.delay(1000L * attempt)
                    continue
                }
                Log.d("FASELHD", "smartGet Normal request failed: ${e.message}")
                break
            }
        }

        return cfLock.withLock {
            val activity = context as? Activity
            Log.d("FASELHD", "Invoking CloudflareSolver for: $cleanUrl")
            CloudflareSolver.solve(activity, cleanUrl, lastValidUserAgent) ?: Jsoup.parse("", cleanUrl)
        }
    }






    private suspend fun executeRequestWithCloudflareRetry(requestBlock: suspend (String) -> String): String? {
        val base = baseUrl()
        var currentCookies = CookieManager.getInstance().getCookie(base) ?: ""
        try {
            val result = requestBlock(currentCookies)
            if (result.isNotBlank()) return result
        } catch (e: Exception) {
            if (e.message != "403_FORBIDDEN") return null
        }
        Log.d("FASELHD_RETRY", "403 Detected. Refreshing CF clearance...")
        try {
            cfLock.withLock {
                val activity = context as? Activity
                CloudflareSolver.solve(activity, base, lastValidUserAgent)
            }
            currentCookies = CookieManager.getInstance().getCookie(base) ?: ""
            if (!currentCookies.contains("cf_clearance")) return null
        } catch (e: Exception) { return null }
        Log.d("FASELHD_RETRY", "Retrying with fresh cookies...")
        return try {
            requestBlock(currentCookies)
        } catch (e: Exception) { null }
    }






    private fun Element.toSearchResult(): SearchResponse? {
        val href = this.selectFirst("a")?.attr("href")?.trim() ?: return null
        val title = this.selectFirst(".h1, .h4, .h5")?.text()?.trim() ?: return null
        val posterUrl = this.selectFirst("img")
            ?.let { it.attr("data-src").ifBlank { it.attr("src") } }
            ?.trim()
        if (href.isBlank() || title.isBlank()) return null

        val headers = getProtectedHeaders()

        return newTvSeriesSearchResponse(title, href, TvType.TvSeries) {
            this.posterUrl = posterUrl
            this.posterHeaders = headers
        }
    }
    override val mainPage = mainPageOf(
        "$mainUrl/main" to "الرئيسية" // تحديث الرابط ليكون /main
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = if (page > 1 && !request.data.endsWith("/main")) {
            if (request.data.contains("all_movies"))
                "${request.data.removeSuffix("/")}/page/$page"
            else
                "${request.data}/page/$page"
        } else {
            request.data
        }

        val document = smartGet(url)
        val headers = getProtectedHeaders()
        if (request.data.endsWith("/main") || request.data == mainUrl) {
            val lists = mutableListOf<HomePageList>()
            val sliderItems = document.select("#homeSlide .swiper-slide").mapNotNull {
                val slideHref = it.selectFirst("a")?.attr("href") ?: return@mapNotNull null
                val slideTitle = it.selectFirst(".h1 a")?.text()?.trim() ?: return@mapNotNull null
                val slidePoster = it.selectFirst(".poster img")?.attr("src")
                newMovieSearchResponse(slideTitle, slideHref, TvType.Movie) {
                    this.posterUrl = slidePoster
                    this.posterHeaders = headers
                }
            }
            if (sliderItems.isNotEmpty()) {
                lists.add(HomePageList("أحدث الإضافات", sliderItems, isHorizontalImages = true))
            }
            document.select("section#blockList").forEach { block ->
                val title = block.selectFirst(".blockHead .h3")?.text()?.trim() ?: return@forEach
                val items = block.select(".blockMovie, .postDiv, .epDivHome").mapNotNull { it.toSearchResult() }

                if (items.isNotEmpty()) {
                    lists.add(HomePageList(title, items))
                }
            }
            document.select("div.slider")
                .firstOrNull { it.selectFirst(".h4")?.text()?.contains("مشاهدة") == true }
                ?.let { mostWatchedBlock ->
                    val title = mostWatchedBlock.selectFirst(".h4")?.text()?.trim() ?: "الأكثر مشاهدة"
                    val items = mostWatchedBlock.select(".itemviews .postDiv").mapNotNull { it.toSearchResult() }
                    if (items.isNotEmpty()) {
                        lists.add(HomePageList(title, items, isHorizontalImages = true))
                    }
                }
            return newHomePageResponse(lists.filter { it.list.isNotEmpty() }, hasNext = false)
        } else {
            val items = document.select(".postDiv, .blockMovie").mapNotNull { it.toSearchResult() }
            val hasNext = document.select("ul.pagination a[href*='/page/${page + 1}']").isNotEmpty()
            return newHomePageResponse(request.name, items, hasNext)
        }
    }

    override suspend fun quickSearch(query: String): List<SearchResponse>? = search(query,1)?.items
    override suspend fun search(query: String, page: Int): SearchResponseList {
        val base = baseUrl()
        val encoded = URLEncoder.encode(query, "UTF-8")
        val originalSearch = if (page == 1) {
            "$base/?s=$encoded"
        } else {
            "$base/page/$page/?s=$encoded"
        }

        var finalSearchUrl = originalSearch
        try {
            val resp = app.get(originalSearch, allowRedirects = true)
            val final = resp.url
            finalSearchUrl =
                if (final.contains("?s=", ignoreCase = true)) final else "$final?s=$encoded"
        } catch (_: Exception) {
            finalSearchUrl = originalSearch
        }
        val document = try {
            smartGet(finalSearchUrl, referer = base)
        } catch (e: Exception) {
            Log.d("FASELHD", "search: smartGet failed: ${e.message}")
            Jsoup.parse("", finalSearchUrl)
        }

        var items = document.select("div#postList div.postDiv, div.postDiv, article")
            .mapNotNull { it.toSearchResult() }
        var hasNext = document.select("ul.pagination a[href*='/page/${page + 1}']").isNotEmpty()

            if (items.isEmpty() && page == 1) {
                try {
                    val ajaxUrl = "${baseUrl()}/wp-admin/admin-ajax.php"
                    val formBody = FormBody.Builder()
                        .add("action", "dtc_live")
                        .add("trsearch", query)
                        .build()
                    val bodyStr = executeRequestWithCloudflareRetry { cookies ->
                        makeAjaxRequest(ajaxUrl, finalSearchUrl, formBody, cookies)
                    }
                    if (!bodyStr.isNullOrBlank()) {
                        val ajaxDoc = Jsoup.parse(bodyStr, baseUrl()) // يعمل الآن بأمان!

                        items = ajaxDoc.select("div.postDiv, article, .result, .search-item").mapNotNull { it.toSearchResult() }
                        hasNext = ajaxDoc.select("a.dtc_more").isNotEmpty() ||
                                ajaxDoc.select("a").any { it.text().contains("المزيد", ignoreCase = true) }
                        Log.d("FASELHD", "Search fallback: found ${items.size} items from AJAX")
                    }
                } catch (e: Exception) {
                    Log.d("FASELHD", "Search fallback error: ${e.message}")
                }
            }

            return newSearchResponseList(items, hasNext)
        }

        /**
         * دالة مساعدة لتنفيذ أي طلب مع إعادة محاولة تلقائية عند فشل Cloudflare.
         * @param requestBlock دالة تستقبل الكوكيز وتُرجع استجابة الطلب كـ String.
         * @return String نتيجة الاستجابة الناجحة، أو null إذا فشلت المحاولتان.
         */

        private suspend fun makeAjaxRequest(
            ajaxUrl: String,
            referer: String,
            formBody: FormBody,
            cookies: String
        ): String {
            val client = app.baseClient.newBuilder()
                .cookieJar(CookieJar.NO_COOKIES)
                .followRedirects(false) // معالجة التحويلات يدوياً للحفاظ على POST
                .build()

            var currentUrl = ajaxUrl
            var redirectCount = 0

            while (redirectCount < 5) {
                val requestBuilder = OkRequest.Builder()
                    .url(currentUrl)
                    .post(formBody)
                    .header("User-Agent", lastValidUserAgent)
                    .header("Referer", referer)
                if (cookies.isNotBlank()) requestBuilder.header("Cookie", cookies)

                val response = withContext(Dispatchers.IO) { client.newCall(requestBuilder.build()).execute() }

                response.use { res ->
                    when (res.code) {
                        403 -> throw IllegalStateException("403_FORBIDDEN")
                        301, 302, 307, 308 -> {
                            val location = res.header("Location")
                            if (location != null) {
                                currentUrl = if (location.startsWith("http")) location else "${baseUrl()}$location"
                                redirectCount++
                            } else {
                                return ""
                            }
                        }
                        200 -> return res.body?.string() ?: ""
                        else -> return ""
                    }
                }
            }
            return "" // تم الوصول للحد الأقصى للتحويلات
        }
    override suspend fun load(url: String): LoadResponse? {
        val base = baseUrl()
        val absoluteUrl = if (url.startsWith("/")) "$base$url" else url
        val doc = smartGet(absoluteUrl)
        val rawTitle = doc.selectFirst(".singleInfo .title.h1")?.ownText() ?: ""
        val title = rawTitle.replace("\\n", "").replace("\n", "").trim()
        if (title.isBlank()) return null

        val plot = doc.selectFirst(".singleDesc p, .story p")?.text()?.replace("\\n", " ")?.replace("\n", " ")?.trim()

        val poster = fixUrlNull(
            doc.selectFirst("meta[itemprop=image]")?.attr("content")
                ?: doc.selectFirst(".posterImg img.poster")?.attr("src")
        )

        val backgroundPoster = doc.selectFirst("div.singlePage")?.attr("style")
            ?.let { Regex("""url\(['"]?(.*?)['"]?\)""").find(it)?.groupValues?.get(1) }
            ?.let { fixUrlNull(it) }

        val headers = getProtectedHeaders()
        val seasonCards = doc.select(".seasonDiv")
        val seasonUrlRegex = Regex("""window\.location\.href\s*=\s*['"]([^'"]+)['"]""")

        val recommendations = seasonCards.mapNotNull { seasonEl ->
            val onclickAttr = seasonEl.attr("onclick")
            val seasonPoster = seasonEl.selectFirst("img")?.attr("data-src") ?: seasonEl.selectFirst("img")?.attr("src")
            val seasonUrlRel = seasonUrlRegex.find(onclickAttr)?.groupValues?.get(1) ?: return@mapNotNull null
            val seasonTitle = seasonEl.selectFirst(".title")?.text()?.replace("\\n", "")?.trim() ?: "موسم"
            val fullSeasonUrl = if (seasonUrlRel.startsWith("http")) seasonUrlRel else "$base$seasonUrlRel"
            newTvSeriesSearchResponse(seasonTitle, fullSeasonUrl, TvType.TvSeries) {
                this.posterUrl = seasonPoster
                this.posterHeaders = headers
            }
        }

        val currentSeasonEpisodes = mutableListOf<Episode>()
        val otherSeasonsFakeEpisodes = mutableListOf<Episode>()
        if (seasonCards.isNotEmpty()) {
            val h1TitleText = doc.selectFirst(".singleInfo .title.h1")?.text() ?: ""
            var currentSeasonIndex = -1
            seasonCards.forEachIndexed { idx, seasonEl ->
                val onclickAttr = seasonEl.attr("onclick")
                val seasonUrlRel = seasonUrlRegex.find(onclickAttr)?.groupValues?.get(1) ?: ""
                val fullSeasonUrl = if (seasonUrlRel.startsWith("http")) seasonUrlRel else "$base$seasonUrlRel"
                if (fullSeasonUrl.removeSuffix("/") == absoluteUrl.removeSuffix("/")) {
                    currentSeasonIndex = idx
                }
            }
            if (currentSeasonIndex == -1) {
                seasonCards.forEachIndexed { idx, seasonEl ->
                    val seasonCardTitle = seasonEl.selectFirst(".title")?.text()?.replace("\\n", "")?.trim() ?: ""
                    if (seasonCardTitle.isNotBlank() && h1TitleText.contains(seasonCardTitle)) {
                        currentSeasonIndex = idx
                    }
                }
            }
            if (currentSeasonIndex == -1) currentSeasonIndex = 0


            var fakeSeasonCounter = 2
            for (i in 0 until seasonCards.size) {
                val actualSeasonNum = i + 1
                val seasonName = seasonCards[i].selectFirst(".title")?.text()?.replace("\\n", "")?.trim() ?: "الموسم $actualSeasonNum"

                if (i == currentSeasonIndex) {
                    doc.select("div#epAll a").forEach { el ->
                        val epUrlRaw = el.attr("href").trim()
                        if (epUrlRaw.isNotBlank()) {
                            val epTitle = el.ownText().ifBlank { el.text() }.replace("\\n", "").replace("\n", "").trim()
                            if (!epTitle.contains("باقي الحلقات") && !epTitle.contains("المزيد")) {
                                val epNum = Regex("""\d+""").find(epTitle)?.value?.toIntOrNull()
                                currentSeasonEpisodes.add(newEpisode(if (epUrlRaw.startsWith("http")) epUrlRaw else "$base$epUrlRaw") {
                                    this.name = "$seasonName - $epTitle"
                                    this.episode = epNum
                                    this.season = 1
                                    this.posterUrl = poster
                                })
                            }
                        }
                    }
                } else {
                    otherSeasonsFakeEpisodes.add(newEpisode("$absoluteUrl?s=$actualSeasonNum#fake") {
                        this.name = "($seasonName في الاقتراحات فوق في الزاوية اليسار)"
                        this.episode = 1
                        this.season = fakeSeasonCounter
                        this.posterUrl = "https://raw.githubusercontent.com/Abodabodd/Oldarabrepo/refs/heads/main/img/1.jpg"
                    })
                    fakeSeasonCounter++
                }
            }
        } else {
            doc.select("div#epAll a").forEach { el ->
                val epUrlRaw = el.attr("href").trim()
                if (epUrlRaw.isNotBlank()) {
                    val epTitle = el.ownText().ifBlank { el.text() }.replace("\\n", "").replace("\n", "").trim()
                    val epNum = Regex("""\d+""").find(epTitle)?.value?.toIntOrNull()
                    currentSeasonEpisodes.add(newEpisode(if (epUrlRaw.startsWith("http")) epUrlRaw else "$base$epUrlRaw") {
                        this.name = epTitle
                        this.episode = epNum
                        this.season = 1
                        this.posterUrl = poster
                    })
                }
            }
        }

        val allEpisodes = currentSeasonEpisodes + otherSeasonsFakeEpisodes

        return if (allEpisodes.isEmpty()) {
            newMovieLoadResponse(title, absoluteUrl, TvType.Movie, absoluteUrl) {
                this.posterUrl = poster
                this.posterHeaders = headers
                this.backgroundPosterUrl = backgroundPoster
                this.plot = plot
                this.recommendations = recommendations
            }
        } else {
            newTvSeriesLoadResponse(title, absoluteUrl, TvType.Anime, allEpisodes) {
                this.posterUrl = poster
                this.posterHeaders = headers
                this.backgroundPosterUrl = backgroundPoster
                this.plot = plot
                this.recommendations = recommendations
            }
        }
    }
    private fun extractIframeSources(doc: Document): List<String> {
        val results = mutableSetOf<String>()
        val blockedKeywords = listOf(
            "google.com/recaptcha",
            "google.com/ads",
            "googlesyndication.com",
            "googletagmanager.com"
        )
        fun addResult(url: String, sourceTag: String) {
            val fixedUrl = fixUrl(url)
            if (blockedKeywords.none { fixedUrl.contains(it) }) {
                Log.d("FASELHD_IFRAME", " [مقبول] تم العثور على رابط من ($sourceTag): $fixedUrl")
                results.add(fixedUrl)
            } else {
                Log.d("FASELHD_IFRAME", " [مستبعد] رابط إعلاني أو تحقق من ($sourceTag): $fixedUrl")
            }
        }

        Log.d("FASELHD_IFRAME", "========== بدء استخراج الإطارات (iframes) ==========")
        Log.d("FASELHD_IFRAME", "رابط الصفحة الأساسي: ${doc.baseUri()}")
        doc.select("iframe[src]").forEach { el ->
            val src = el.attr("src")
            if (src.isNotBlank()) addResult(src, "iframe[src]")
        }
        val onClickRegex = Regex("""player_iframe\.location\.href\s*=\s*['"]([^'"]+)['"]""")
        doc.select("[onclick]").forEach { el ->
            val onclick = el.attr("onclick")
            onClickRegex.find(onclick)?.let { match ->
                addResult(match.groupValues[1], "onclick (player_iframe)")
            }
        }
        val scriptRegex = Regex("""https?://[^\s"'<>]+""")
        doc.select("script").forEach { s ->
            val data = s.data()
            if (data.isNotBlank()) {
                scriptRegex.findAll(data).forEach { m ->
                    val url = m.value
                    if (url.contains("player") || url.contains("embed")) {
                        addResult(url, "inline script")
                    }
                }
            }
        }
        doc.select("div.shortLink, span#liskSh, a[data-src]").forEach { el ->
            val text = el.text().trim()
            if (text.startsWith("http")) addResult(text, "server link element")
        }

        Log.d("FASELHD_IFRAME", "========== إجمالي الروابط الصالحة المكتشفة: ${results.size} ==========")
        return results.toList()
    }


    
    @SuppressLint("SetJavaScriptEnabled")
    private suspend fun resolveWithWebView(
        iframeUrl: String,
        referer: String
    ): List<String> = suspendCancellableCoroutine { cont ->

        val activity = context as? Activity
        if (activity == null || activity.isFinishing) {
            cont.resume(emptyList())
            return@suspendCancellableCoroutine
        }

        val finalUrl = iframeUrl.replace("&amp;", "&").trim()
        val iframeUri = try { Uri.parse(finalUrl) } catch (e: Exception) { null }
        val iframeOrigin = if (iframeUri != null && iframeUri.host != null) "${iframeUri.scheme}://${iframeUri.host}" else referer

        activity.runOnUiThread {
            val dialog = Dialog(activity, android.R.style.Theme_Black_NoTitleBar_Fullscreen)
            dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
            dialog.setCancelable(true) // يمكنك إغلاقها بالرجوع
            val rootLayout = FrameLayout(activity).apply {
                layoutParams = ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT
                )
            }
            val webView = WebView(activity).apply {
                layoutParams = FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.MATCH_PARENT
                )
                visibility = View.VISIBLE
            }
            rootLayout.addView(webView)
            val sidePanelScroll = ScrollView(activity).apply {
                layoutParams = FrameLayout.LayoutParams(
                    TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 150f, resources.displayMetrics).toInt(),
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    Gravity.END or Gravity.TOP
                ).apply {
                    setMargins(0, 40, 10, 40)
                }
                setBackgroundColor(Color.parseColor("#CC111111"))
                elevation = 20f
            }

            val buttonContainer = LinearLayout(activity).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(10, 10, 10, 10)
            }

            val panelTitle = TextView(activity).apply {
                text = "أزرار الجودة المكتشفة"
                setTextColor(Color.YELLOW)
                textSize = 12f
                gravity = Gravity.CENTER
                setPadding(0, 10, 0, 15)
            }
            buttonContainer.addView(panelTitle)
            sidePanelScroll.addView(buttonContainer)
            rootLayout.addView(sidePanelScroll)

            dialog.setContentView(rootLayout)
            dialog.show()
            webView.settings.apply {
                javaScriptEnabled = true
                domStorageEnabled = true
                databaseEnabled = true
                mediaPlaybackRequiresUserGesture = false
                mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
                cacheMode = WebSettings.LOAD_DEFAULT
                userAgentString = lastValidUserAgent
                useWideViewPort = true
                loadWithOverviewMode = true
            }

            val cookieManager = CookieManager.getInstance()
            try {
                cookieManager.setAcceptCookie(true)
                cookieManager.setAcceptThirdPartyCookies(webView, true)
            } catch (_: Exception) {}

            val foundM3u8 = linkedSetOf<String>()
            var finished = false
            val finishLock = Any()
            val handler = Handler(Looper.getMainLooper())

            fun cleanup() {
                try { (webView.parent as? ViewGroup)?.removeView(webView) } catch (_: Exception) {}
                try { webView.stopLoading() } catch (_: Exception) {}
                try { webView.destroy() } catch (_: Exception) {}
                try { if (dialog.isShowing) dialog.dismiss() } catch (_: Exception) {}
            }

            fun safeFinish() {
                synchronized(finishLock) {
                    if (finished) return
                    finished = true
                }
                val list = synchronized(foundM3u8) { foundM3u8.toList() }
                try { if (cont.isActive) cont.resume(list) } catch (_: Exception) {}
                cleanup()
            }

            fun handleFoundLink(url: String) {
                val clean = url.substringBefore("?")
                if (!clean.endsWith(".m3u8")) return
                synchronized(foundM3u8) {
                    if (!foundM3u8.contains(url)) {
                        foundM3u8.add(url)
                        Log.d("FASEL_INSPECT", "🎯 تم التقاط رابط (.m3u8): $url")
                        Toast.makeText(activity, "تم التقاط m3u8!", Toast.LENGTH_SHORT).show()
                    }
                }
            }
            class InspectBridge {
                @JavascriptInterface
                fun onButtonsFound(buttonsJson: String) {
                    activity.runOnUiThread {
                        try {
                            val array = org.json.JSONArray(buttonsJson)
                            buttonContainer.removeAllViews()
                            buttonContainer.addView(panelTitle)
                            val refreshBtn = android.widget.Button(activity).apply {
                                text = "🔄 إعادة مسح"
                                textSize = 10f
                                setBackgroundColor(Color.DKGRAY)
                                setTextColor(Color.WHITE)
                                setOnClickListener {
                                    webView.evaluateJavascript("window.__scanQualityButtons && window.__scanQualityButtons();", null)
                                }
                            }
                            buttonContainer.addView(refreshBtn)

                            for (i in 0 until array.length()) {
                                val item = array.getJSONObject(i)
                                val label = item.getString("text")
                                val selector = item.getString("selector")
                                val index = item.getInt("index")

                                val btn = android.widget.Button(activity).apply {
                                    text = label
                                    textSize = 11f
                                    setTextColor(Color.WHITE)
                                    setBackgroundColor(Color.parseColor("#2196F3"))
                                    val params = LinearLayout.LayoutParams(
                                        LinearLayout.LayoutParams.MATCH_PARENT,
                                        LinearLayout.LayoutParams.WRAP_CONTENT
                                    )
                                    params.setMargins(0, 8, 0, 8)
                                    layoutParams = params

                                    setOnClickListener {
                                        Log.d("FASEL_INSPECT", " تم الضغط على زر التجربة: '$label' | Selector: $selector | Index: $index")
                                        val clickJs = """
                                            (function() {
                                                const els = document.querySelectorAll('$selector');
                                                if (els && els[$index]) {
                                                    els[$index].dispatchEvent(new MouseEvent('click', { bubbles: true, cancelable: true }));
                                                    els[$index].click();
                                                    console.log('CLICKED_BTN::' + '$label');
                                                }
                                            })();
                                        """.trimIndent()
                                        webView.evaluateJavascript(clickJs, null)
                                    }
                                }
                                buttonContainer.addView(btn)
                            }
                        } catch (e: Exception) {
                            Log.e("FASEL_INSPECT", "خطأ في تحليل الأزرار: ${e.message}")
                        }
                    }
                }
            }

            webView.addJavascriptInterface(InspectBridge(), "InspectBridge")

            val scannerJs = """
            (function() {
                window.__scanQualityButtons = function() {
                    let results = [];
                    let targets = document.querySelectorAll('.quality_ch, ul.quality_ch li, [class*="quality"], [id*="quality"], button, .jw-settings-content-item, .jw-reset-text');
                    targets.forEach((el, idx) => {
                        let txt = (el.innerText || el.textContent || '').trim();
                        if (txt.length > 0 && txt.length < 35) {
                            results.push({
                                text: txt,
                                selector: el.className ? '.' + el.className.trim().split(/\s+/).join('.') : el.tagName.toLowerCase(),
                                index: idx
                            });
                        }
                    });
                    if (window.InspectBridge) {
                        window.InspectBridge.onButtonsFound(JSON.stringify(results));
                    }
                };
                setTimeout(window.__scanQualityButtons, 2000);
                setTimeout(window.__scanQualityButtons, 4000);
            })();
            """.trimIndent()

            val fastSnifferJs = """
            (function() {
                try {
                    if (!window.__NET_HOOKED__) {
                        window.__NET_HOOKED__ = true;
                        const _fetch = window.fetch;
                        if (_fetch) {
                            window.fetch = function() {
                                return _fetch.apply(this, arguments).then(function(resp) {
                                    try {
                                        const u = resp && resp.url ? resp.url : '';
                                        if (u && u.indexOf('.m3u8') !== -1) { console.log('NET_M3U8::' + u); }
                                    } catch(e){}
                                    return resp;
                                });
                            };
                        }
                        const _open = XMLHttpRequest.prototype.open;
                        XMLHttpRequest.prototype.open = function(method, u) {
                            this.addEventListener('load', function() {
                                try {
                                    if (typeof u === 'string' && u.indexOf('.m3u8') !== -1) { console.log('NET_M3U8::' + u); }
                                } catch(e){}
                            });
                            return _open.apply(this, arguments);
                        };
                    }
                } catch(err){}
            })();
            """.trimIndent()

            webView.webViewClient = object : WebViewClient() {
                override fun onPageFinished(view: WebView?, url: String?) {
                    super.onPageFinished(view, url)
                    view?.evaluateJavascript(fastSnifferJs, null)
                    view?.evaluateJavascript(scannerJs, null)
                }

                override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
                    val url = request.url.toString()
                    val lower = url.lowercase()

                    if (lower.contains(".m3u8")) {
                        handleFoundLink(url)
                    }
                    return super.shouldInterceptRequest(view, request)
                }

                @SuppressLint("WebViewClientOnReceivedSslError")
                override fun onReceivedSslError(view: WebView?, handler: SslErrorHandler?, error: android.net.http.SslError?) {
                    handler?.proceed()
                }
            }

            webView.webChromeClient = object : WebChromeClient() {
                override fun onConsoleMessage(cm: ConsoleMessage?): Boolean {
                    val msg = cm?.message() ?: ""
                    if (msg.startsWith("NET_M3U8::")) {
                        handleFoundLink(msg.substringAfter("::").trim())
                    } else if (msg.startsWith("CLICKED_BTN::")) {
                        Log.d("FASEL_INSPECT", "✅ تم تنفيذ النقر بنجاح على: ${msg.substringAfter("::")}")
                    }
                    return true
                }
            }
            val finishManualBtn = android.widget.Button(activity).apply {
                text = "حفظ وإنهاء ✔"
                setTextColor(Color.WHITE)
                setBackgroundColor(Color.parseColor("#4CAF50"))
                layoutParams = FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.WRAP_CONTENT,
                    FrameLayout.LayoutParams.WRAP_CONTENT,
                    Gravity.START or Gravity.BOTTOM
                ).apply {
                    setMargins(20, 0, 0, 20)
                }
                setOnClickListener {
                    safeFinish()
                }
            }
            rootLayout.addView(finishManualBtn)

            webView.loadUrl(finalUrl, mapOf("Referer" to referer))

            dialog.setOnDismissListener {
                safeFinish()
            }

            cont.invokeOnCancellation {
                handler.post { safeFinish() }
            }
        }
    }
    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        Log.d("FASELHD_LOAD", "========== بدء معالجة الرابط: $data ==========")
        val doc = smartGet(data)
        val iframeUrls = extractIframeSources(doc)

        if (iframeUrls.isEmpty()) {
            Log.e("FASELHD_LOAD", "❌ لم يتم العثور على أي Iframe")
            return false
        }

        var totalExtractedLinks = 0

        for ((index, iframeUrl) in iframeUrls.distinct().withIndex()) {
            Log.d("FASELHD_LOAD", "--------------------------------------------------")
            Log.d("FASELHD_LOAD", "▶ تجربة الـ Iframe رقم (${index + 1}/${iframeUrls.size}): $iframeUrl")

            // تُرجع الآن List<String>
            val caughtM3u8List = resolveWithWebView(iframeUrl, data)

            if (caughtM3u8List.isEmpty()) {
                Log.w("FASELHD_LOAD", "⚠️ لم يتم التقاط أي روابط m3u8 من هذا الـ Iframe.")
                continue
            }

            val iframeUri = try { Uri.parse(iframeUrl) } catch (e: Exception) { null }
            val iframeOrigin = if (iframeUri != null && iframeUri.host != null) "${iframeUri.scheme}://${iframeUri.host}" else iframeUrl
            val cm = CookieManager.getInstance()

            caughtM3u8List.forEach { m3u8Url ->
                val streamCookies = cm.getCookie(m3u8Url) ?: cm.getCookie(iframeUrl) ?: ""
                val streamHeaders = mutableMapOf(
                    "User-Agent" to lastValidUserAgent,
                    "Referer" to iframeUrl,
                    "Origin" to iframeOrigin
                )
                if (streamCookies.isNotBlank()) {
                    streamHeaders["Cookie"] = streamCookies
                }

                var successWithHelper = false
                try {
                    val generated = M3u8Helper.generateM3u8(
                        source = name,
                        streamUrl = m3u8Url,
                        referer = iframeUrl,
                        headers = streamHeaders
                    )
                    if (generated.isNotEmpty()) {
                        generated.forEach { link ->
                            callback(link)
                            totalExtractedLinks++
                            successWithHelper = true
                        }
                    }
                } catch (e: Exception) {
                    Log.w("FASELHD_LOAD", "M3u8Helper failed: ${e.message}")
                }

                // إذا فشل M3u8Helper نضيف الرابط مباشرة للمشغل
                if (!successWithHelper) {
                    val label = if (m3u8Url.contains("master")) "Auto / Multi" else "Direct Stream"
                    callback(
                        ExtractorLink(
                            source = name,
                            name = "$name - $label",
                            url = m3u8Url,
                            referer = iframeUrl,
                            quality = Qualities.Unknown.value,
                            type = ExtractorLinkType.M3U8,
                            headers = streamHeaders
                        )
                    )
                    totalExtractedLinks++
                }
            }

            if (totalExtractedLinks > 0) {
                return true
            }
        }

        return totalExtractedLinks > 0
    }
}
