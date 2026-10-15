package com.witanime

import android.app.Activity
import android.app.Dialog
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.util.Base64
import android.util.Log
import android.view.Gravity
import android.view.ViewGroup
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.mvvm.logError
import com.lagradost.cloudstream3.utils.*
import kotlinx.coroutines.*
import org.json.JSONArray
import org.json.JSONObject

class WitAnime : MainAPI() {
    override var mainUrl = "https://witanime.site"
    override var name = "WitAnime"
    override val hasMainPage = true
    override var lang = "ar"

    override val supportedTypes = setOf(TvType.Anime, TvType.AnimeMovie)
    private val userAgent =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"

    companion object {
        @Volatile var isWebViewOpen = false
        @Volatile var lastWebViewOpenTime = 0L
    }

    object PlayerAccess {
        private val handler = android.os.Handler(android.os.Looper.getMainLooper())
        private var isMonitoring = false
        private var lastHookedPlayer: Any? = null
        private var activeDialog: Dialog? = null
        private var isLoopStarted = false

        private val monitorRunnable = object : Runnable {
            override fun run() {
                if (!isMonitoring) return
                hookPlayerListener()
                handler.postDelayed(this, 1000L)
            }
        }

        fun startMonitoring() {
            if (isLoopStarted) return
            isLoopStarted = true
            isMonitoring = true
            handler.post(monitorRunnable)
        }

        private fun findFragmentRecursive(fragment: androidx.fragment.app.Fragment, packageName: String): androidx.fragment.app.Fragment? {
            if (fragment.javaClass.name.startsWith(packageName)) return fragment
            try {
                for (child in fragment.childFragmentManager.fragments) {
                    if (child != null) {
                        val found = findFragmentRecursive(child, packageName)
                        if (found != null) return found
                    }
                }
            } catch (e: Exception) {}
            return null
        }

        fun getActiveActivity(): Activity? {
            return try {
                val activityThreadClass = Class.forName("android.app.ActivityThread")
                val activityThread = activityThreadClass.getMethod("currentActivityThread").invoke(null)
                val activitiesField = activityThreadClass.getDeclaredField("mActivities").apply { isAccessible = true }
                val activities = activitiesField.get(activityThread) as Map<*, *>
                for (activityRecord in activities.values) {
                    if (activityRecord == null) continue
                    val act = activityRecord.javaClass.getDeclaredField("activity").apply { isAccessible = true }.get(activityRecord) as? Activity
                    if (act != null && !act.isFinishing && !act.isDestroyed) return act
                }
                null
            } catch (e: Exception) { null }
        }

        fun getPlayerFragment(): Any? {
            val activity = getActiveActivity() ?: return null
            return try {
                val fragments = (activity as? androidx.fragment.app.FragmentActivity)?.supportFragmentManager?.fragments ?: return null
                for (f in fragments) {
                    if (f != null) {
                        val found = findFragmentRecursive(f, "com.lagradost.cloudstream3.ui.player")
                        if (found != null) return found
                    }
                }
                null
            } catch (e: Exception) { null }
        }

        fun currentPlayer(): Any? {
            val fragment = getPlayerFragment() as? androidx.fragment.app.Fragment ?: return null
            return try {
                val playerField = fragment.javaClass.getDeclaredField("player").apply { isAccessible = true }
                playerField.get(fragment)
            } catch (e: Exception) {
                try {
                    fragment.javaClass.getMethod("getPlayer").invoke(fragment)
                } catch (ex: Exception) { null }
            }
        }

        fun getAppContext(): Context? {
            return try {
                val activityThreadClass = Class.forName("android.app.ActivityThread")
                activityThreadClass.getMethod("currentApplication").invoke(null) as? Context
            } catch (e: Exception) { null }
        }

        fun pausePlayer() {
            val player = currentPlayer() ?: return
            try {
                val handleEventMethod = player.javaClass.methods.firstOrNull { it.name == "handleEvent" }
                if (handleEventMethod != null) {
                    val parameterTypes = handleEventMethod.parameterTypes
                    if (parameterTypes.isNotEmpty()) {
                        val eventEnumClass = parameterTypes[0]
                        val pauseEnumConstant = eventEnumClass.enumConstants?.firstOrNull { it.toString().contains("Pause", ignoreCase = true) }
                        if (parameterTypes.size == 2) {
                            val sourceEnumClass = parameterTypes[1]
                            val syncEnumConstant = sourceEnumClass.enumConstants?.firstOrNull {
                                it.toString().contains("Sync", ignoreCase = true) || it.toString().contains("UI", ignoreCase = true)
                            }
                            handleEventMethod.invoke(player, pauseEnumConstant, syncEnumConstant)
                        } else {
                            handleEventMethod.invoke(player, pauseEnumConstant)
                        }
                    }
                }
            } catch (e: Exception) { e.printStackTrace() }
        }

        fun getRealExoPlayer(player: Any): Any? {
            val rootClassName = player.javaClass.name
            if (rootClassName.contains("Player", ignoreCase = true) && !rootClassName.contains("Cache", ignoreCase = true)) {
                if (player.javaClass.methods.any { it.name == "addListener" }) return player
            }
            try {
                for (f in player.javaClass.declaredFields) {
                    f.isAccessible = true
                    val value = f.get(player) ?: continue
                    if (value.javaClass.name.contains("Player", ignoreCase = true) && !value.javaClass.name.contains("Cache", ignoreCase = true)) {
                        if (value.javaClass.methods.any { it.name == "addListener" }) return value
                    }
                }
            } catch (e: Exception) {}
            return null
        }

        private fun hookPlayerListener() {
            val rawPlayer = currentPlayer() ?: return
            val player = getRealExoPlayer(rawPlayer) ?: return
            if (player === lastHookedPlayer) return
            lastHookedPlayer = player

            try {
                val listenerClass = try {
                    Class.forName("androidx.media3.common.Player\$Listener")
                } catch (e: Exception) {
                    Class.forName("com.google.android.exoplayer2.Player\$Listener")
                }
                val addListenerMethod = player.javaClass.getMethod("addListener", listenerClass)
                val proxyListener = java.lang.reflect.Proxy.newProxyInstance(
                    listenerClass.classLoader,
                    arrayOf(listenerClass)
                ) { proxy, method, args ->
                    if (method.name == "equals") return@newProxyInstance proxy === (args?.get(0))
                    if (method.name == "hashCode") return@newProxyInstance System.identityHashCode(proxy)
                    if (method.name == "toString") return@newProxyInstance "ExoPlayerProxyListener"
                    handler.post { checkMegaPlayback(player) }
                    val returnType = method.returnType
                    if (returnType == Boolean::class.javaPrimitiveType || returnType == Boolean::class.java) false
                    else if (returnType?.isPrimitive == true) 0
                    else null
                }
                addListenerMethod.invoke(player, proxyListener)
            } catch (e: Exception) {}
        }

        private fun findUrlInObject(obj: Any, depth: Int = 0): String? {
            if (depth > 3) return null
            try {
                for (f in obj.javaClass.declaredFields) {
                    f.isAccessible = true
                    val value = f.get(obj) ?: continue
                    if (value is String && value.contains("mega-webview://")) return value
                    if (value is Uri && value.toString().contains("mega-webview://")) return value.toString()
                    val pkg = value.javaClass.`package`?.name ?: ""
                    if (pkg.contains("lagradost") || pkg.contains("media3") || pkg.contains("exoplayer") || pkg.contains("google")) {
                        val found = findUrlInObject(value, depth + 1)
                        if (found != null) return found
                    }
                }
            } catch (e: Exception) {}
            return null
        }

        private fun getPlayingUrl(player: Any): String? {
            return try {
                val mediaItem = player.javaClass.getMethod("getCurrentMediaItem").invoke(player) ?: return null
                val localConfig = mediaItem.javaClass.getDeclaredField("localConfiguration").apply { isAccessible = true }.get(mediaItem) ?: return null
                val uri = localConfig.javaClass.getDeclaredField("uri").apply { isAccessible = true }.get(localConfig) as? Uri
                uri?.toString() ?: findUrlInObject(player)
            } catch (e: Exception) {
                findUrlInObject(player)
            }
        }

        private fun checkMegaPlayback(player: Any) {
            val currentTime = System.currentTimeMillis()
            synchronized(WitAnime::class.java) {
                if (WitAnime.isWebViewOpen || (currentTime - WitAnime.lastWebViewOpenTime) < 10000L || activeDialog?.isShowing == true) return
                val playingUrl = getPlayingUrl(player) ?: return
                if (playingUrl.contains("mega-webview://")) {
                    WitAnime.isWebViewOpen = true
                    WitAnime.lastWebViewOpenTime = currentTime
                    pausePlayer()
                    val realMegaUrl = playingUrl.substringAfter("mega-webview://")
                    CoroutineScope(Dispatchers.Main).launch { openMegaPlayer(realMegaUrl) }
                }
            }
        }

        private suspend fun openMegaPlayer(megaUrl: String) {
            withContext(Dispatchers.Main) {
                if (activeDialog?.isShowing == true) return@withContext
                val fragment = getPlayerFragment() as? androidx.fragment.app.Fragment
                val activity = fragment?.activity ?: getActiveActivity()

                if (activity != null) {
                    try {
                        val dialog = Dialog(activity, android.R.style.Theme_Black_NoTitleBar_Fullscreen)
                        activeDialog = dialog
                        val originalOrientation = activity.requestedOrientation
                        activity.requestedOrientation = android.content.pm.ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
                        dialog.setOnDismissListener {
                            activity.requestedOrientation = originalOrientation
                            activeDialog = null
                            synchronized(WitAnime::class.java) {
                                WitAnime.isWebViewOpen = false
                                WitAnime.lastWebViewOpenTime = System.currentTimeMillis()
                            }
                            lastHookedPlayer = null
                        }
                        val frameLayout = FrameLayout(activity).apply {
                            layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
                        }
                        val webView = WebView(activity).apply {
                            layoutParams = FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
                            settings.javaScriptEnabled = true
                            settings.domStorageEnabled = true
                            settings.mediaPlaybackRequiresUserGesture = false
                            settings.userAgentString = "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36"
                            webViewClient = WebViewClient()
                            webChromeClient = WebChromeClient()
                            loadUrl(megaUrl)
                        }
                        frameLayout.addView(webView)
                        val closeButton = android.widget.Button(activity).apply {
                            text = "✕"
                            setTextColor(Color.WHITE)
                            textSize = 14f
                            setTypeface(null, android.graphics.Typeface.BOLD)
                            background = GradientDrawable().apply {
                                shape = GradientDrawable.OVAL
                                setColor(Color.parseColor("#99000000"))
                            }
                            setOnClickListener { dialog.dismiss() }
                        }
                        val btnSize = (32 * activity.resources.displayMetrics.density).toInt()
                        val btnParams = FrameLayout.LayoutParams(btnSize, btnSize, Gravity.TOP or Gravity.END).apply {
                            topMargin = (8 * activity.resources.displayMetrics.density).toInt()
                            marginEnd = (8 * activity.resources.displayMetrics.density).toInt()
                        }
                        frameLayout.addView(closeButton, btnParams)
                        dialog.setContentView(frameLayout)
                        dialog.show()
                    } catch (e: Exception) {
                        synchronized(WitAnime::class.java) { WitAnime.isWebViewOpen = false }
                        activeDialog = null
                    }
                }
            }
        }
    }

    init {
        PlayerAccess.startMonitoring()
    }
    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val document = app.get(mainUrl, headers = mapOf("User-Agent" to userAgent)).document
        val homePageList = ArrayList<HomePageList>()

        document.select("section").forEach { section ->
            val title = section.selectFirst("h2")?.text()?.trim() ?: return@forEach

            val items = section.select("a[href*='/watch/'], a[href*='/anime/'], a[href*='/movie/']").mapNotNull { card ->
                val href = card.attr("href")
                if (!href.contains("/watch/") && !href.contains("/anime/") && !href.contains("/movie/")) return@mapNotNull null

                val name = card.selectFirst("h3")?.text()?.trim()
                    ?: card.attr("title")
                    ?: card.selectFirst("img")?.attr("alt")
                    ?: return@mapNotNull null

                val epText = card.selectFirst("div:contains(الحلقة)")?.text()?.trim()
                val finalTitle = if (!epText.isNullOrEmpty() && !name.contains("الحلقة")) "$name - $epText" else name
                val poster = card.selectFirst("img")?.attr("src")

                newAnimeSearchResponse(finalTitle, fixUrl(href), TvType.Anime) {
                    this.posterUrl = poster
                }
            }.distinctBy { it.url }

            if (items.isNotEmpty()) {
                homePageList.add(HomePageList(title, items))
            }
        }

        val aside = document.selectFirst("aside")
        if (aside != null) {
            val asideTitle = aside.selectFirst("h2")?.text()?.trim() ?: "أشهر أنميات الموسم"
            val asideItems = aside.select("a[href*='/anime/']").mapNotNull { item ->
                val href = item.attr("href")
                val name = item.selectFirst("span[dir='ltr']")?.text()?.trim() ?: return@mapNotNull null
                val poster = item.selectFirst("img")?.attr("src")
                newAnimeSearchResponse(name, fixUrl(href), TvType.Anime) {
                    this.posterUrl = poster
                }
            }.distinctBy { it.url }

            if (asideItems.isNotEmpty()) {
                homePageList.add(HomePageList(asideTitle, asideItems))
            }
        }

        return newHomePageResponse(homePageList)
    }
    override suspend fun search(query: String): List<SearchResponse> {
        val url = "$mainUrl/search?q=$query"
        val document = app.get(url, headers = mapOf("User-Agent" to userAgent)).document

        return document.select("div.grid a[href*='/anime/'], div.grid a[href*='/movie/']").mapNotNull { card ->
            val href = card.attr("href")
            val title = card.selectFirst("h3")?.text()?.trim() ?: return@mapNotNull null
            val poster = card.selectFirst("img")?.attr("src")
            val isMovie = href.contains("/movie/")

            newAnimeSearchResponse(title, fixUrl(href), if (isMovie) TvType.AnimeMovie else TvType.Anime) {
                this.posterUrl = poster
            }
        }.distinctBy { it.url }
    }
    override suspend fun load(url: String): LoadResponse {
        val document = app.get(url, headers = mapOf("User-Agent" to userAgent)).document

        val jsonLd = document.selectFirst("script[type='application/ld+json']")?.data()
        val title = document.selectFirst("h1")?.text()?.trim() ?: ""
        val poster = document.selectFirst("div.shrink-0 img")?.attr("src")
            ?: document.selectFirst("meta[property='og:image']")?.attr("content")
        val description = document.selectFirst("p.text-neutral-300")?.text()?.trim()

        val isMovie = url.contains("/movie/")
        val tvType = if (isMovie) TvType.AnimeMovie else TvType.Anime

        val genres = mutableListOf<String>()
        if (!jsonLd.isNullOrBlank()) {
            try {
                val jsonObj = JSONObject(jsonLd)
                if (jsonObj.has("genre")) {
                    val genreArray = jsonObj.getJSONArray("genre")
                    for (i in 0 until genreArray.length()) {
                        genres.add(genreArray.getString(i))
                    }
                }
            } catch (e: Exception) {}
        }
        if (genres.isEmpty()) {
            document.select("div.flex.flex-wrap.gap-2 span.rounded-full").forEach {
                genres.add(it.text().trim())
            }
        }

        val status = if (document.selectFirst("span:contains(مكتمل)") != null) {
            ShowStatus.Completed
        } else {
            ShowStatus.Ongoing
        }

        val episodes = mutableListOf<Episode>()

        if (isMovie) {
            val watchUrl = document.selectFirst("a[href*='/watch/movie/']")?.attr("href") ?: url
            episodes.add(newEpisode(fixUrl(watchUrl)) {
                this.name = title
                this.episode = 1
            })
        } else {
            document.select("div[data-episode-list] a[href*='/watch/']").forEach { epTag ->
                val epHref = epTag.attr("href")
                val epText = epTag.selectFirst("span:contains(الحلقة)")?.text() ?: epTag.text()
                val epNum = Regex("""\d+""").find(epHref.substringAfterLast("/"))?.value?.toIntOrNull()

                episodes.add(newEpisode(fixUrl(epHref)) {
                    this.name = epText.ifBlank { "الحلقة $epNum" }
                    this.episode = epNum
                })
            }
            episodes.sortBy { it.episode }
        }

        return newAnimeLoadResponse(title, url, tvType) {
            this.posterUrl = poster
            this.plot = description
            this.tags = genres
            this.showStatus = status
            addEpisodes(DubStatus.Subbed, episodes.distinctBy { it.data })
        }
    }
    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        try {
            val watchHtml = app.get(data, headers = mapOf("User-Agent" to userAgent)).text

            val sourcesUrlPattern = Regex("""sourcesUrl:\s*['"]([^'"]+)['"]""")
            val rawSourcesUrl = sourcesUrlPattern.find(watchHtml)?.groupValues?.get(1) ?: return false
            val cleanSourcesUrl = rawSourcesUrl.replace("\\/", "/")
            val fullSourcesUrl = fixUrl(cleanSourcesUrl)

            val apiResponse = app.get(
                fullSourcesUrl,
                referer = data,
                headers = mapOf(
                    "User-Agent" to userAgent,
                    "Accept" to "application/json, text/plain, */*",
                    "X-Requested-With" to "XMLHttpRequest"
                )
            ).text

            val extractedUrls = mutableListOf<String>()

            fun parseJsonSources(jsonObj: JSONObject) {
                val keys = jsonObj.keys()
                while (keys.hasNext()) {
                    val key = keys.next()
                    val value = jsonObj.opt(key)
                    when (value) {
                        is String -> {
                            if (key.contains("url", ignoreCase = true) || 
                                key.contains("src", ignoreCase = true) || 
                                key.contains("link", ignoreCase = true) || 
                                key.contains("file", ignoreCase = true)) {
                                if (value.isNotBlank() && value.startsWith("http")) {
                                    extractedUrls.add(value)
                                }
                            }
                        }
                        is JSONObject -> parseJsonSources(value)
                        is JSONArray -> {
                            for (i in 0 until value.length()) {
                                val item = value.opt(i)
                                if (item is JSONObject) parseJsonSources(item)
                                else if (item is String) {
                                    if (item.isNotBlank() && item.startsWith("http")) {
                                        extractedUrls.add(item)
                                    }
                                }
                            }
                        }
                    }
                }
            }

            if (apiResponse.trim().startsWith("{")) {
                parseJsonSources(JSONObject(apiResponse))
            } else if (apiResponse.trim().startsWith("[")) {
                val jsonArray = JSONArray(apiResponse)
                for (i in 0 until jsonArray.length()) {
                    val item = jsonArray.optJSONObject(i) ?: continue
                    parseJsonSources(item)
                }
            }

            val distinctUrls = extractedUrls.distinct()
            supervisorScope {
                distinctUrls.map { url ->
                    async(Dispatchers.IO) {
                        try {
                            if (url.contains("mega.nz")) {
                                val dummyUrl = "https://raw.githubusercontent.com/Anarios/Cloudstream/master/app/src/main/res/raw/blank.mp4#mega-webview://$url"
                                callback(
                                    newExtractorLink(
                                        name = "Mega (WebView)",
                                        source = "WitAnime",
                                        url = dummyUrl
                                    ) {
                                        this.referer = url
                                        this.quality = 10
                                        this.type = ExtractorLinkType.VIDEO
                                    }
                                )
                            } else {
                                loadExtractor(url, data, subtitleCallback, callback)
                            }
                        } catch (e: Exception) {}
                    }
                }.awaitAll()
            }

            return true
        } catch (e: Exception) {
            logError(e)
            return false
        }
    }
}
