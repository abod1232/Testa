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
                            settings.userAgentString = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36"
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

    private fun fetchDocument(url: String): org.jsoup.nodes.Document {
        val req = Request.Builder()
            .url(url)
            .apply {
                baseHeaders.forEach { (k, v) -> header(k, v) }
                header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8")
                header("Accept-Language", "ar,en-US;q=0.9,en;q=0.8")
            }
            .build()
        val res = app.baseClient.newCall(req).execute()
        val body = res.body?.string().orEmpty()
        res.close()
        return Jsoup.parse(body)
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        return withContext(Dispatchers.IO) {
            val document = fetchDocument(mainUrl)
            val homePageList = ArrayList<HomePageList>()

            document.select("section.mb-14").forEach { section ->
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

            newHomePageResponse(homePageList)
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        return withContext(Dispatchers.IO) {
            val url = "$mainUrl/search?q=$query"
            val document = fetchDocument(url)

            document.select("div.grid a[href*='/anime/'], div.grid a[href*='/movie/']").mapNotNull { card ->
                val href = card.attr("href")
                val title = card.selectFirst("h3")?.text()?.trim() ?: return@mapNotNull null
                val poster = card.selectFirst("img")?.attr("src")
                val isMovie = href.contains("/movie/")

                newAnimeSearchResponse(title, fixUrl(href), if (isMovie) TvType.AnimeMovie else TvType.Anime) {
                    this.posterUrl = poster
                }
            }.distinctBy { it.url }
        }
    }

    override suspend fun load(url: String): LoadResponse {
        return withContext(Dispatchers.IO) {
            val document = fetchDocument(url)

            val title = document.selectFirst("h1 span[dir='ltr']")?.text()?.trim()
                ?: document.selectFirst("h1")?.text()?.trim()
                ?: document.selectFirst("meta[property='og:title']")?.attr("content")
                    ?.replace("مترجم", "")
                    ?.replace("اون لاين", "")
                    ?.replace("فيلم", "")
                    ?.replace("انمي", "")
                    ?.trim()
                ?: ""

            val poster = document.selectFirst("img[src*='/posters/']")?.attr("src")
                ?: document.selectFirst("div.shrink-0 img")?.attr("src")
                ?: document.selectFirst("meta[property='og:image']")?.attr("content")
                ?: document.selectFirst("meta[name='image']")?.attr("content")

            val description = document.selectFirst("p.leading-relaxed")?.text()?.trim()
                ?: document.selectFirst("meta[property='og:description']")?.attr("content")

            val isMovie = url.contains("/movie/")
            val tvType = if (isMovie) TvType.AnimeMovie else TvType.Anime

            val genres = mutableListOf<String>()
            document.select("div.flex-wrap.gap-2 span.rounded-full").forEach {
                val tag = it.text().trim()
                if (tag.isNotEmpty()) genres.add(tag)
            }

            var year: Int? = null
            var status = ShowStatus.Completed

            if (document.selectFirst("span:contains(مستمر)") != null) {
                status = ShowStatus.Ongoing
            }

            document.select("div.grid > div").forEach { box ->
                val label = box.selectFirst("span")?.text()?.trim() ?: ""
                val value = box.selectFirst("p")?.text()?.trim() ?: ""
                if (label.contains("السنة")) {
                    year = Regex("""\d{4}""").find(value)?.value?.toIntOrNull()
                }
            }

            val ratingText = document.selectFirst("span[dir='ltr']:has(svg)")?.text()?.trim()
            val rating = ratingText?.toDoubleOrNull()

            val episodes = mutableListOf<Episode>()

            if (isMovie) {
                val watchUrl = document.selectFirst("a[href*='/watch/movie/']")?.attr("href") ?: url
                episodes.add(newEpisode(fixUrl(watchUrl)) {
                    this.name = title
                    this.episode = 1
                    this.posterUrl = poster
                })
            } else {
                document.select("div[data-episode-list] a[href*='/watch/']").forEach { epTag ->
                    val epHref = epTag.attr("href")
                    val epNum = Regex("""/(\d+)$""").find(epHref)?.groupValues?.get(1)?.toIntOrNull()
                        ?: Regex("""\d+""").find(epTag.text())?.value?.toIntOrNull()

                    episodes.add(newEpisode(fixUrl(epHref)) {
                        this.name = "الحلقة ${epNum ?: ""}".trim()
                        this.episode = epNum
                        this.posterUrl = poster
                    })
                }
                episodes.sortBy { it.episode }
            }

            newAnimeLoadResponse(title, url, tvType) {
                this.posterUrl = poster
                this.plot = description
                this.tags = genres
                this.year = year
                this.showStatus = status
                this.score = rating?.let {
                    Score.from10(it)
                }

                addEpisodes(DubStatus.Subbed, episodes.distinctBy { it.data })
            }
        }
    }

    data class ServerItem(val quality: String, val label: String, val token: String, val isStream: Boolean)

    class BatchSession(val client: OkHttpClient) {
        val cookieStore = mutableMapOf<String, String>()

        fun saveCookies(res: okhttp3.Response) {
            res.headers("Set-Cookie").forEach { header ->
                val cookiePart = header.substringBefore(";")
                val eqIdx = cookiePart.indexOf("=")
                if (eqIdx > 0) {
                    val k = cookiePart.substring(0, eqIdx).trim()
                    val v = cookiePart.substring(eqIdx + 1).trim()
                    cookieStore[k] = v
                }
            }
        }

        fun makeCookieHeader(): String =
            cookieStore.entries.joinToString("; ") { "${it.key}=${it.value}" }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        return withContext(Dispatchers.IO) {
            try {
                val directClient = app.baseClient.newBuilder()
                    .followRedirects(false)
                    .followSslRedirects(false)
                    .callTimeout(20, TimeUnit.SECONDS)
                    .build()

                suspend fun fetchServersAndToken(batchSession: BatchSession): Pair, List<ServerItem>>? {
                    val cacheBusterUrl = "$data?t=${System.currentTimeMillis() / 1000}"

                    val pageReq = Request.Builder()
                        .url(cacheBusterUrl)
                        .apply { baseHeaders.forEach { (k, v) -> header(k, v) } }
                        .build()

                    val pageRes = directClient.newCall(pageReq).execute()
                    batchSession.saveCookies(pageRes)
                    val pageHtml = pageRes.body?.string().orEmpty()
                    pageRes.close()

                    if (pageRes.code != 200) return null

                    val csrfToken = Regex("""name=['"]csrf-token['"]\s+content=['"]([^'"]+)['"]""").find(pageHtml)?.groupValues?.get(1)
                        ?: Regex("""data-csrf=['"]([^'"]+)['"]""").find(pageHtml)?.groupValues?.get(1)
                        ?: return null

                    delay(1300)

                    val sourcesPattern = Regex("""sourcesUrl:\s*['"]([^'"]+)['"]""")
                    val rawSources = sourcesPattern.find(pageHtml)?.groupValues?.get(1)?.replace("\\/", "/")
                        ?: "${data.trimEnd('/')}/sources"
                    val sourcesUrl = fixUrl(rawSources)

                    val sourcesHeaders = baseHeaders.toMutableMap().apply {
                        put("X-Csrf-Token", csrfToken)
                        put("Accept", "application/json")
                        put("Origin", "https://witanime.site")
                        put("Referer", data)
                        put("Sec-Fetch-Dest", "empty")
                        put("Sec-Fetch-Mode", "cors")
                        put("Sec-Fetch-Site", "same-origin")
                        put("Cookie", batchSession.makeCookieHeader())
                    }

                    val sourcesReq = Request.Builder()
                        .url(sourcesUrl)
                        .apply { sourcesHeaders.forEach { (k, v) -> header(k, v) } }
                        .post(RequestBody.create(null, ByteArray(0)))
                        .build()

                    val sourcesRes = directClient.newCall(sourcesReq).execute()
                    batchSession.saveCookies(sourcesRes)
                    val sourcesJsonStr = sourcesRes.body?.string().orEmpty()
                    sourcesRes.close()

                    if (sourcesRes.code != 200 || sourcesJsonStr.isBlank()) return null

                    val json = JSONObject(sourcesJsonStr)
                    val players = json.optJSONObject("players")
                    val downloads = json.optJSONObject("downloads")

                    val allServers = mutableListOf<ServerItem>()
                    players?.let { p ->
                        for (q in p.keys()) {
                            val arr = p.optJSONArray(q) ?: continue
                            for (i in 0 until arr.length()) {
                                val obj = arr.optJSONObject(i) ?: continue
                                val token = obj.optString("token")
                                val label = obj.optString("label", "server")
                                if (token.isNotBlank()) allServers.add(ServerItem(q, label, token, true))
                            }
                        }
                    }
                    downloads?.let { d ->
                        for (q in d.keys()) {
                            val arr = d.optJSONArray(q) ?: continue
                            for (i in 0 until arr.length()) {
                                val obj = arr.optJSONObject(i) ?: continue
                                val token = obj.optString("token")
                                val label = obj.optString("label", "download")
                                if (token.isNotBlank()) allServers.add(ServerItem(q, label, token, false))
                            }
                        }
                    }

                    return sourcesHeaders to allServers
                }

                val batchSize = 9
                val maxProcessed = 18
                var processedCount = 0
                var totalServers: Int? = null

                while (true) {
                    val batchSession = BatchSession(directClient)

                    val fetchResult = fetchServersAndToken(batchSession) ?: break
                    val sourcesHeaders = fetchResult.first
                    var allServers = fetchResult.second
                    if (allServers.isEmpty()) break

                    if (totalServers == null) {
                        allServers = allServers.take(maxProcessed)
                        totalServers = allServers.size
                    }

                    val currentBatch = allServers.drop(processedCount).take(batchSize)
                    if (currentBatch.isEmpty()) break

                    for ((i, s) in currentBatch.withIndex()) {
                        val sourceEndpoint = if (s.isStream) "stream-source" else "download-source"
                        val gateEndpoint = if (s.isStream) "stream-gate" else "download-gate"

                        val sourceUrl = "https://witanime.site/watch/$sourceEndpoint/${s.token}"
                        val gateUrl = "https://witanime.site/watch/$gateEndpoint/${s.token}"

                        try {
                            val sReq = Request.Builder()
                                .url(sourceUrl)
                                .apply {
                                    sourcesHeaders.forEach { (k, v) -> header(k, v) }
                                    header("Cookie", batchSession.makeCookieHeader())
                                }
                                .post(RequestBody.create(null, ByteArray(0)))
                                .build()

                            val sRes = directClient.newCall(sReq).execute()
                            batchSession.saveCookies(sRes)
                            sRes.close()

                            val gReq = Request.Builder()
                                .url(gateUrl)
                                .apply {
                                    exactGateHeaders.forEach { (k, v) -> header(k, v) }
                                    header("Cookie", batchSession.makeCookieHeader())
                                }
                                .get()
                                .build()

                            val gRes = directClient.newCall(gReq).execute()
                            batchSession.saveCookies(gRes)
                            var embedUrl = gRes.header("Location") ?: gRes.header("location")

                            if (embedUrl.isNullOrBlank()) {
                                val gBody = gRes.body?.string().orEmpty()
                                val match = Regex("""url=['"]([^'"]+)['"]""").find(gBody)
                                    ?: Regex("""href=["'](https?://[^"']+)["']""").find(gBody)
                                embedUrl = match?.groupValues?.get(1)
                            }
                            gRes.close()

                            if (!embedUrl.isNullOrBlank() && embedUrl.startsWith("http")) {
                                if (embedUrl.contains("mega.nz") || embedUrl.contains("mega.co.nz")) {
                                    val typeLabel = if (s.isStream) "مشاهدة" else "تنزيل"
                                    val displayName = "${s.label.uppercase()} (${s.quality}) [$typeLabel]"

                                    var megaSuccess = false
                                    try {
                                        val localStreamUrl = WitAnimeMegaServer.registerMegaStream(embedUrl)
                                        if (!localStreamUrl.isNullOrBlank()) {
                                            val qVal = getQualityFromName(s.quality).takeIf { it > 0 } ?: Qualities.P1080.value
                                            callback(
                                                newExtractorLink(
                                                    name = "$displayName (Mega Direct)",
                                                    source = "Mega",
                                                    url = localStreamUrl
                                                ) {
                                                    this.referer = embedUrl
                                                    this.quality = qVal
                                                    this.type = ExtractorLinkType.VIDEO
                                                }
                                            )
                                            megaSuccess = true
                                        }
                                    } catch (e: Exception) {
                                        logError(e)
                                    }

                                    if (!megaSuccess) {
                                        val dummyUrl = "https://raw.githubusercontent.com/Anarios/Cloudstream/master/app/src/main/res/raw/blank.mp4#mega-webview://$embedUrl"
                                        callback(
                                            newExtractorLink(
                                                name = "$displayName [WebView]",
                                                source = "WitAnime",
                                                url = dummyUrl
                                            ) {
                                                this.referer = embedUrl
                                                this.quality = Qualities.Unknown.value
                                                this.type = ExtractorLinkType.VIDEO
                                            }
                                        )
                                    }
                                } else {
                                    loadExtractor(embedUrl, data, subtitleCallback, callback)
                                }
                            }

                            if (i < currentBatch.size - 1) {
                                delay(2000)
                            }
                        } catch (e: Exception) {
                            logError(e)
                            delay(2000)
                        }
                    }

                    processedCount += currentBatch.size

                    if (processedCount >= (totalServers ?: 0) || processedCount >= maxProcessed) {
                        break
                    } else {
                        delay(5000)
                    }
                }

                true
            } catch (e: Exception) {
                logError(e)
                false
            }
        }
    }
}
