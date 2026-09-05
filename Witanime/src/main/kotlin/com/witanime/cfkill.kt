//package com.witanime
//
//import com.lagradost.cloudstream3.*
//import com.lagradost.cloudstream3.utils.*
//import android.util.Base64
//import android.util.Log
//import com.lagradost.cloudstream3.mvvm.logError
//import kotlinx.coroutines.Dispatchers
//import kotlinx.coroutines.launch
//import kotlinx.coroutines.async
//import kotlinx.coroutines.awaitAll
//import kotlinx.coroutines.supervisorScope
//import kotlinx.coroutines.sync.Semaphore
//import kotlinx.coroutines.sync.withPermit
//import kotlinx.coroutines.sync.Mutex
//import kotlinx.coroutines.sync.withLock
//import org.json.JSONObject
//import org.json.JSONArray
//import java.nio.charset.Charset
//import android.webkit.CookieManager
//
//class WitAnime : MainAPI() {
//    override var mainUrl = "https://witanime.red"
//    override var name = "WitAnime2"
//    override val hasMainPage = true
//    override var lang = "ar"
//    override val supportedTypes = setOf(TvType.Anime, TvType.AnimeMovie)
//
//    private val userAgent = "Mozilla/5.0 (Linux; Android 10; SM-G975F) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/83.0.4103.106 Mobile Safari/537.36"
//
//    // 🚨 متغيرات الكوكيز والـ Mutex لمنع فتح أكثر من نافذة في نفس الوقت
//    private var savedCookies: String? = null
//    private val cfMutex = Mutex()
//
//    private fun log(tag: String, msg: String) {
//        Log.d("WitAnimeDebug | [$tag]", msg)
//    }
//
//    private fun buildHeaders(referer: String? = null): Map<String, String> {
//        val headers = mutableMapOf(
//            "User-Agent" to userAgent,
//            "Accept-Language" to "ar,en-US;q=0.9",
//            "Upgrade-Insecure-Requests" to "1"
//        )
//        referer?.let { headers["Referer"] = it }
//        savedCookies?.let { headers["Cookie"] = it }
//        return headers
//    }
//
//    // 🚨 الدالة المركزية لطلب الصفحات وصيد الكوكيز إذا لزم الأمر
//    private suspend fun safeGet(url: String, referer: String? = null): com.lagradost.nicehttp.NiceResponse {
//        var currentRequestUrl = url
//        var headers = buildHeaders(referer)
//        var res = app.get(currentRequestUrl, headers = headers, timeout = 30)
//
//        if (res.code in listOf(403, 503, 429)) {
//            cfMutex.withLock {
//                val currentCookies = CookieManager.getInstance().getCookie(currentRequestUrl)
//                if (currentCookies != null && currentCookies != savedCookies && currentCookies.contains("cf_clearance")) {
//                    log("SAFE-GET", "Cloudflare already solved by another thread.")
//                    savedCookies = currentCookies
//                } else {
//                    log("SAFE-GET", "Cloudflare detected (Code: ${res.code}). Running Cookie Hunter...")
//                    val activity = CommonActivity.activity ?: com.lagradost.cloudstream3.CommonActivity.activity
//
//                    if (activity != null) {
//                        val solverResult = CloudflareSolver.solve(activity, currentRequestUrl, userAgent)
//                        if (solverResult != null) {
//                            if (!solverResult.cookies.isNullOrEmpty()) {
//                                savedCookies = solverResult.cookies
//                            }
//                            if (solverResult.finalUrl != currentRequestUrl) {
//                                log("DOMAIN-UPDATE", "Redirected to: ${solverResult.finalUrl}")
//                                try {
//                                    val newHost = java.net.URL(solverResult.finalUrl).host
//                                    mainUrl = "https://$newHost"
//                                } catch (e: Exception) {}
//                                currentRequestUrl = solverResult.finalUrl
//                            }
//                        }
//                    }
//                }
//            }
//
//            headers = buildHeaders(referer)
//            res = app.get(currentRequestUrl, headers = headers, timeout = 30)
//        }
//        return res
//    }
//
//    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
//        val document = safeGet(mainUrl).document
//        val homePageList = ArrayList<HomePageList>()
//
//        document.select("div.main-widget").forEach { widget ->
//            val title = widget.selectFirst("div.main-didget-head h3")?.text()?.trim() ?: return@forEach
//            val isEpisodeList = title.contains("حلقات")
//
//            val items = widget.select(if (isEpisodeList) "div.episodes-card-container" else "div.anime-card-container")
//                .mapNotNull {
//                    val a = if (isEpisodeList) it.selectFirst(".ep-card-anime-title a") else it.selectFirst("a.overlay")
//                    val itemUrl = a?.attr("href") ?: return@mapNotNull null
//                    val itemName = (if (isEpisodeList) a?.text() else it.selectFirst(".anime-card-title a")?.text()) ?: ""
//                    val itemPoster = it.selectFirst("img")?.attr("src")
//
//                    val finalTitle = if (isEpisodeList) {
//                        val epTitle = it.selectFirst(".episodes-card-title a")?.text() ?: ""
//                        "$itemName - $epTitle"
//                    } else {
//                        itemName
//                    }
//
//                    newAnimeSearchResponse(finalTitle, itemUrl, TvType.Anime) {
//                        posterUrl = itemPoster
//                    }
//                }
//            if (items.isNotEmpty()) homePageList.add(HomePageList(title, items))
//        }
//
//        return newHomePageResponse(homePageList)
//    }
//
//    override suspend fun search(query: String): List<SearchResponse> {
//        val url = "$mainUrl/?search_param=animes&s=$query"
//        val document = safeGet(url).document
//
//        return document.select("div.anime-list-content div.anime-card-container").mapNotNull {
//            val a = it.selectFirst("div.anime-card-poster a")
//            val href = a?.attr("href") ?: return@mapNotNull null
//
//            val title = it.selectFirst("div.anime-card-title h3 a")?.text() ?: return@mapNotNull null
//            val poster = it.selectFirst("img.img-responsive")?.attr("src")
//
//            newAnimeSearchResponse(title, href, TvType.Anime) {
//                this.posterUrl = poster
//            }
//        }
//    }
//
//    override suspend fun load(url: String): LoadResponse {
//        // 🚨 هنا تم التخلص من الـ WebViewResolver لأن safeGet سيتولى الأمر بكفاءة
//        val document = safeGet(url).document
//
//        val title = document.selectFirst("h1.anime-details-title")?.text()?.trim() ?: ""
//        val poster = document.selectFirst("div.anime-thumbnail img")?.attr("src")
//        val description = document.selectFirst("p.anime-story")?.text()?.trim()
//        val genres = document.select("ul.anime-genres li a").map { it.text() }
//
//        var status = ShowStatus.Ongoing
//        var tvType = TvType.Anime
//
//        document.select(".anime-info").forEach {
//            val infoText = it.text()
//            if (infoText.startsWith("حالة الأنمي:")) {
//                status = if (infoText.contains("مكتمل")) ShowStatus.Completed else ShowStatus.Ongoing
//            }
//            if (infoText.startsWith("النوع:")) {
//                tvType = if (infoText.contains("Movie")) TvType.AnimeMovie else TvType.Anime
//            }
//        }
//
//        var episodes = listOf<Episode>()
//
//        val regex = Regex("""var\s+processedEpisodeData\s*=\s*'([^']+)'""")
//        val match = regex.find(document.html())
//        val encodedData = match?.groupValues?.get(1)
//
//        if (!encodedData.isNullOrBlank()) {
//            try {
//                val parts = encodedData.split(".")
//                if (parts.size == 2) {
//                    val part1 = String(android.util.Base64.decode(parts[0], android.util.Base64.DEFAULT))
//                    val part2 = String(android.util.Base64.decode(parts[1], android.util.Base64.DEFAULT))
//
//                    val decodedJson = StringBuilder()
//                    for (i in part1.indices) {
//                        decodedJson.append((part1[i].code xor part2[i % part2.length].code).toChar())
//                    }
//
//                    val episodesList = AppUtils.parseJson(decodedJson.toString()) as? List<Map<String, Any>>
//                    if (episodesList != null) {
//                        episodes = episodesList.mapNotNull { ep ->
//                            val epUrl = ep["url"]?.toString() ?: return@mapNotNull null
//                            val epName = ep["number"]?.toString() ?: ep["title"]?.toString() ?: "حلقة"
//                            newEpisode(epUrl) { this.name = epName }
//                        }
//                    }
//                }
//            } catch (e: Exception) {
//                logError(e)
//            }
//        }
//
//        return newAnimeLoadResponse(title, url, tvType) {
//            this.posterUrl = poster
//            this.plot = description
//            this.tags = genres
//            this.showStatus = status
//            addEpisodes(DubStatus.Subbed, episodes)
//        }
//    }
//
//    override suspend fun loadLinks(
//        data: String,
//        isCasting: Boolean,
//        subtitleCallback: (SubtitleFile) -> Unit,
//        callback: (ExtractorLink) -> Unit
//    ): Boolean {
//        val TAG = "WitAnimeLinks"
//
//        fun decodeWitAnimeLink(raw: String?, offset: Int): String {
//            if (raw.isNullOrBlank()) return ""
//            return try {
//                val reversed = raw.reversed()
//                val cleaned = reversed.replace(Regex("[^A-Za-z0-9+/=]"), "")
//                val decodedBytes = android.util.Base64.decode(cleaned, android.util.Base64.DEFAULT)
//                val finalBytes = if (offset > 0 && offset <= decodedBytes.size) {
//                    decodedBytes.copyOfRange(0, decodedBytes.size - offset)
//                } else {
//                    decodedBytes
//                }
//                String(finalBytes, Charsets.UTF_8).trim()
//            } catch (e: Exception) { "" }
//        }
//
//        val FRAMEWORK_HASH = "1c0f3441-e3c2-4023-9e8b-bee77ff59adf"
//
//        fun hexToByteArray(hex: String?): ByteArray {
//            if (hex.isNullOrBlank()) return ByteArray(0)
//            val cleaned = hex.replace(Regex("[^0-9a-fA-F]"), "")
//            if (cleaned.length % 2 != 0) return ByteArray(0)
//            return cleaned.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
//        }
//
//        fun xorWithKey(data: ByteArray, key: ByteArray): ByteArray {
//            if (key.isEmpty()) return data
//            val out = ByteArray(data.size)
//            for (i in data.indices) out[i] = (data[i].toInt() xor key[i % key.size].toInt()).toByte()
//            return out
//        }
//
//        fun bytesToStringSafe(bytes: ByteArray): String {
//            if (bytes.isEmpty()) return ""
//            return try {
//                String(bytes, Charsets.UTF_8)
//            } catch (e: Exception) {
//                try {
//                    String(bytes, Charset.forName("ISO-8859-1"))
//                } catch (e2: Exception) {
//                    bytes.joinToString("") { (it.toInt() and 0xFF).toChar().toString() }
//                }
//            }
//        }
//
//        fun safeTrim(s: String?): String {
//            if (s == null) return ""
//            return s.replace(Regex("[\\x00\\u0000]"), "").trim()
//        }
//
//        fun processPxChunk(hex: String?, secret: ByteArray): String {
//            val data = hexToByteArray(hex)
//            if (data.isEmpty()) return ""
//            val xored = xorWithKey(data, secret)
//            val s = bytesToStringSafe(xored)
//            return safeTrim(s)
//        }
//
//        // 🚨 تعديل استخراج الملفات باستخدام دالتنا القوية
//        suspend fun fetchUrl(url: String): String {
//            return try {
//                safeGet(url).text // آمنة وتتخطى الحماية إذا لزم الأمر
//            } catch (e: Exception) {
//                Log.w(TAG, "fetchUrl failed for $url: ${e.message}")
//                ""
//            }
//        }
//
//        fun parsePx9FromScript(js: String): Triple<String?, List<String>, Map<String, List<String>>> {
//            val mMatch = Regex("""var\s+_m\s*=\s*\{\s*\"r\"\s*:\s*\"([^\"]+)\"""").find(js)
//            val mVal = mMatch?.groupValues?.get(1)
//            val sMatch = Regex("""var\s+_s\s*=\s*\[(.*?)\]\s*;""", RegexOption.DOT_MATCHES_ALL).find(js)
//            val sList = mutableListOf<String>()
//            if (sMatch != null) {
//                val body = sMatch.groupValues[1]
//                val items = Regex("\"([^\"]*)\"").findAll(body).map { it.groupValues[1] }.toList()
//                sList.addAll(items)
//            }
//            val pMatches = Regex("""var\s+(_p\d+)\s*=\s*\[\s*(.*?)\s*\]\s*;""", RegexOption.DOT_MATCHES_ALL).findAll(js)
//            val pMap = mutableMapOf<String, List<String>>()
//            for (m in pMatches) {
//                val key = m.groupValues[1]
//                val body = m.groupValues[2]
//                val items = Regex("\"([^\"]*)\"").findAll(body).map { it.groupValues[1] }.toList()
//                pMap[key] = items
//            }
//            return Triple(mVal, sList, pMap)
//        }
//
//        fun decryptPx9All(mrBase64: String?, sList: List<String>, pDict: Map<String, List<String>>): List<String> {
//            if (mrBase64.isNullOrBlank()) return emptyList()
//            val secret = try {
//                android.util.Base64.decode(mrBase64, android.util.Base64.DEFAULT)
//            } catch (e: Exception) { ByteArray(0) }
//            val results = mutableListOf<String>()
//            val count = maxOf(sList.size, pDict.size)
//            for (i in 0 until count) {
//                val key = "_p$i"
//                val chunks = pDict[key] ?: continue
//                val seq: IntArray? = if (i < sList.size) {
//                    try {
//                        val seqHex = sList[i]
//                        val seqDecoded = processPxChunk(seqHex, secret)
//                        val arr = JSONArray(seqDecoded)
//                        IntArray(arr.length()) { idx -> arr.getInt(idx) }
//                    } catch (e: Exception) { null }
//                } else null
//
//                val decrypted = chunks.map { ch -> processPxChunk(ch, secret) }
//                val final = if (seq != null && seq.size == decrypted.size) {
//                    val arr = Array(decrypted.size) { "" }
//                    for (j in decrypted.indices) {
//                        val pos = seq[j]
//                        if (pos in arr.indices) arr[pos] = decrypted[j] else {
//                            val idxFallback = arr.indexOfFirst { it.isEmpty() }
//                            if (idxFallback >= 0) arr[idxFallback] = decrypted[j]
//                        }
//                    }
//                    arr.joinToString("")
//                } else {
//                    decrypted.joinToString("")
//                }
//                results.add(safeTrim(final))
//            }
//            return results
//        }
//
//        fun findServerElements(html: String): List<Pair<String, String>> {
//            val items = mutableListOf<Pair<String, String>>()
//            val anchorRegex = Regex("""(<a[^>]+class=[\"'][^\"']*server-link[^\"']*[\"'][^>]*>.*?</a>)""", RegexOption.DOT_MATCHES_ALL)
//            for (m in anchorRegex.findAll(html)) {
//                val tag = m.groupValues[1]
//                val sid = Regex("""data-server-id\s*=\s*[\"']([^\"']+)[\"']""").find(tag)?.groupValues?.get(1)
//                val label = Regex("""<span[^>]+class=[\"'][^\"']*ser[^\"']*[\"'][^>]*>(.*?)</span>""", RegexOption.DOT_MATCHES_ALL)
//                    .find(tag)?.groupValues?.get(1)?.replace(Regex("\\s+"), " ")?.trim()
//                if (sid != null) items.add(sid to (label ?: "server-$sid"))
//            }
//            return items
//        }
//
//        return try {
//            // استخدام safeGet بدلاً من fetchUrl المباشر لضمان تخطي Cloudflare
//            var html = safeGet(data).text
//
//            var zG: String? = Regex("""var\s+_zG\s*=\s*\"([^\"]+)\"""").find(html)?.groupValues?.get(1)
//            var zH: String? = Regex("""var\s+_zH\s*=\s*\"([^\"]+)\"""").find(html)?.groupValues?.get(1)
//
//            if (zG.isNullOrBlank() || zH.isNullOrBlank()) {
//                val inlineScripts = Regex("""<script[^>]*>(.*?)</script>""", RegexOption.DOT_MATCHES_ALL)
//                    .findAll(html).map { it.groupValues[1] }.toList()
//                for (s in inlineScripts) {
//                    if (zG.isNullOrBlank()) zG = Regex("""var\s+_zG\s*=\s*\"([^\"]+)\"""").find(s)?.groupValues?.get(1)
//                    if (zH.isNullOrBlank()) zH = Regex("""var\s+_zH\s*=\s*\"([^\"]+)\"""").find(s)?.groupValues?.get(1)
//                    if (!zG.isNullOrBlank() && !zH.isNullOrBlank()) break
//                }
//            }
//            val resArray = try { JSONArray(String(android.util.Base64.decode(zG, android.util.Base64.DEFAULT))) } catch (e: Exception) { null }
//            val cfgArray = try { JSONArray(String(android.util.Base64.decode(zH, android.util.Base64.DEFAULT))) } catch (e: Exception) { null }
//
//            val servers = findServerElements(html)
//            val PARALLELISM = 6
//            val semaphore = Semaphore(PARALLELISM)
//
//            supervisorScope {
//                servers.forEach { (sidStr, label) ->
//                    launch(Dispatchers.IO) {
//                        semaphore.withPermit {
//                            try {
//                                val sid = sidStr.toIntOrNull() ?: return@launch
//                                if (resArray == null || cfgArray == null || sid >= resArray.length()) return@launch
//
//                                val res = resArray.optString(sid, "")
//                                val cfg = cfgArray.optJSONObject(sid) ?: return@launch
//                                val kBase64 = cfg.optString("k", "")
//                                val kIndex = String(android.util.Base64.decode(kBase64, android.util.Base64.DEFAULT)).toIntOrNull() ?: 0
//                                val offsetArray = cfg.optJSONArray("d")
//                                val offset = offsetArray?.optInt(kIndex) ?: 0
//                                val link = decodeWitAnimeLink(res, offset)
//
//                                val finalLink = if (link.contains("yonaplay.net/embed.php")) {
//                                    "$link&apiKey=$FRAMEWORK_HASH"
//                                } else link
//
//                                if (finalLink.isNotBlank() && finalLink.startsWith("http")) {
//                                    if (finalLink.contains("yonaplay.net", ignoreCase = true)) {
//                                        // تأكد من وجود دالة decodeYonaplayAndLoad لديك في الإضافة
//                                        // decodeYonaplayAndLoad(finalLink, subtitleCallback, callback)
//                                    } else {
//                                        when {
//                                            finalLink.contains("streamwish", ignoreCase = true) ||
//                                                    finalLink.contains("hgcloud", ignoreCase = true) ||
//                                                    finalLink.contains("mwish", ignoreCase = true) -> {
//                                                launch(Dispatchers.IO) {
//                                                    try {
//                                                        // StreamWishExtractor().getUrl(...)
//                                                    } catch (e: Exception) {
//                                                        loadExtractor(finalLink, data, subtitleCallback, callback)
//                                                    }
//                                                }
//                                            }
//                                            else -> {
//                                                // 🚨 تحميل السيرفر الخارجي، هنا نستخدم app.get وليس safeGet
//                                                // لأن السيرفرات الخارجية لا علاقة لها بكلاودفلير الخاص بـ WitAnime
//                                                loadExtractor(finalLink, data, subtitleCallback, callback)
//                                            }
//                                        }
//                                    }
//                                }
//                            } catch (e: Exception) {}
//                        }
//                    }
//                }
//            }
//
//            var px_mr: String? = null
//            var px_s: List<String> = emptyList()
//            val px_p = mutableMapOf<String, List<String>>()
//
//            val inlineScripts = Regex("""<script[^>]*>(.*?)</script>""", RegexOption.DOT_MATCHES_ALL).findAll(html).map { it.groupValues[1] }
//            for (s in inlineScripts) {
//                if ("_m" in s && "_p0" in s) {
//                    val (mVal, sList, pMap) = parsePx9FromScript(s)
//                    px_mr = mVal ?: px_mr
//                    if (sList.isNotEmpty()) px_s = sList
//                    px_p.putAll(pMap)
//                    if (!px_mr.isNullOrBlank() && px_p.isNotEmpty()) break
//                }
//            }
//
//            if (px_p.isEmpty() || px_mr.isNullOrBlank()) {
//                val scriptSrcs = Regex("""<script[^>]+src=[\"']([^\"']+)[\"'][^>]*>""", RegexOption.IGNORE_CASE).findAll(html).map { it.groupValues[1] }.toList()
//                for (src in scriptSrcs) {
//                    val srcUrl = if (src.startsWith("http")) src else try { java.net.URL(java.net.URL(data), src).toString() } catch (e: Exception) { src }
//
//                    val js = fetchUrl(srcUrl) // تستخدم safeGet داخلياً الآن!
//                    if (js.isBlank()) continue
//
//                    if (px_p.isEmpty() || px_mr.isNullOrBlank()) {
//                        val (mVal2, sList2, pMap2) = parsePx9FromScript(js)
//                        if (mVal2 != null && px_mr.isNullOrBlank()) px_mr = mVal2
//                        if (sList2.isNotEmpty() && px_s.isEmpty()) px_s = sList2
//                        if (pMap2.isNotEmpty()) px_p.putAll(pMap2)
//                        if (!px_mr.isNullOrBlank() && px_p.isNotEmpty()) break
//                    }
//                }
//            }
//
//            if (px_p.isEmpty()) {
//                val (mVal3, sList3, pMap3) = parsePx9FromScript(html)
//                if (mVal3 != null) px_mr = mVal3
//                if (sList3.isNotEmpty()) px_s = sList3
//                if (pMap3.isNotEmpty()) px_p.putAll(pMap3)
//            }
//
//            val downloadLinks = decryptPx9All(px_mr, px_s, px_p)
//
//            supervisorScope {
//                val dlTasks = downloadLinks.mapIndexed { idx, dl ->
//                    async(Dispatchers.IO) {
//                        semaphore.withPermit {
//                            try {
//                                if (dl.isNotBlank()) {
//                                    val httpIndex = dl.indexOf("http")
//                                    val cleaned = if (httpIndex >= 0) dl.substring(httpIndex) else dl
//                                    val final = safeTrim(cleaned)
//                                    if (final.startsWith("http")) {
//                                        try {
//                                            loadExtractor(final, data, subtitleCallback, callback)
//                                        } catch (e: Exception) {}
//                                    }
//                                }
//                            } catch (e: Exception) {}
//                        }
//                    }
//                }
//                dlTasks.awaitAll()
//            }
//            true
//        } catch (e: Exception) {
//            false
//        }
//    }
//}
//
//private suspend fun decodeYonaplayAndLoad(
//    yonaplayUrl: String,
//    subtitleCallback: (SubtitleFile) -> Unit,
//    callback: (ExtractorLink) -> Unit
//) {
//    val TAG = "YonaplayExtractor"
//    Log.d(TAG, "🟣 Start decoding: $yonaplayUrl")
//
//    try {
//        val html = app.get(
//            yonaplayUrl,
//            referer = "https://witanime.red/",
//            headers = mapOf(
//                "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) " +
//                        "AppleWebKit/537.36 (KHTML, like Gecko) Chrome/118.0.5993.90 Safari/537.36"
//            )
//        ).text
//
//        Log.d(TAG, "📄 Page length = ${html.length}")
//
//        val regex = Regex("""go_to_player\('([A-Za-z0-9+/=]+)'\)""")
//        val matches = regex.findAll(html).map { it.groupValues[1] }.toList()
//
//        Log.d(TAG, "🧩 Found ${matches.size} encoded servers")
//
//        if (matches.isEmpty()) {
//            Log.w(TAG, "⚠️ No encoded servers found in Yonaplay page")
//            return
//        }
//
//        for (encoded in matches) {
//            var fixed = encoded
//            val padding = encoded.length % 4
//            if (padding != 0) fixed += "=".repeat(4 - padding)
//
//            try {
//                val decoded =
//                    String(android.util.Base64.decode(fixed, android.util.Base64.DEFAULT))
//                Log.d(TAG, "🔗 Decoded: $decoded")
//
//                // 🟢 إذا الرابط Google Drive نحوله إلى رابط مباشر مع referer للرابط الأصلي
//                if (decoded.contains("drive.google.com/file/d/")) {
//                    val match = Regex("""/file/d/([0-9A-Za-z_-]{10,})""").find(decoded)
//                    val fileId = match?.groupValues?.get(1)
//                    if (fileId != null) {
//                        val directUrl =
//                            "https://drive.usercontent.google.com/download?id=$fileId&export=download&confirm=t"
//                        Log.d(TAG, "🎯 Direct Google Drive link: $directUrl")
//
//                        callback(
//                            newExtractorLink(
//                                name = "Google Drive",
//                                source = "Yonaplay",
//                                url = directUrl,
//                            ) {
//                                referer = "https://drive.google.com/"  // هذا هو رابط preview الأصلي
//                                this.quality = Qualities.Unknown.value
//                                this.type = ExtractorLinkType.VIDEO
//                            }
//                        )
//                        continue
//                    }
//                }
//
//                // 🟣 السيرفرات الأخرى يرسلها إلى loadExtractor
//                loadExtractor(decoded, subtitleCallback, callback)
//
//            } catch (e: Exception) {
//                Log.e(TAG, "❌ Failed to decode $encoded -> ${e.message}")
//            }
//        }
//
//    } catch (e: Exception) {
//        Log.e(TAG, "❌ Error while decoding Yonaplay: ${e.message}", e)
//    }
//}
