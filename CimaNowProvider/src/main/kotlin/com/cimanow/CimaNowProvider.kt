package com.cimanow

import android.content.Context
import android.util.Log
import android.util.Base64
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.loadExtractor
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import kotlinx.coroutines.awaitAll
import com.lagradost.cloudstream3.utils.newExtractorLink
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.async
import com.lagradost.cloudstream3.utils.getQualityFromName
import kotlinx.coroutines.delay
import java.util.regex.Pattern
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import java.io.ByteArrayOutputStream
import kotlin.coroutines.resume
import java.io.ByteArrayInputStream
import java.io.File
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.RequestBody.Companion.toRequestBody
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.coroutines.suspendCoroutine


data class ProviderCacheEntry(val bytes: ByteArray, val mimeType: String, val encoding: String)
private val inMemoryCache = java.util.concurrent.ConcurrentHashMap<String, ProviderCacheEntry>()

class CimaNowProvider(private val context: Context) : MainAPI() {
    override var name = "Cimanow0"
    override var mainUrl = "https://cimanow.cc"
    override var lang = "ar"
    override val hasMainPage = true
    override val supportedTypes = setOf(TvType.TvSeries, TvType.Movie)
    override val usesWebView = false

    private val TAG = "CimaNowDebug"

    private fun getIntFromText(text: String): Int? {
        return Regex("""\d+""").find(text)?.value?.toIntOrNull()
    }

    override val mainPage = mainPageOf(
        "$mainUrl/الاحدث/" to "الاحدث",
        "$mainUrl/category/افلام-اجنبية/page/" to "افلام اجنبية",
        "$mainUrl/category/مسلسلات-اجنبية/page/" to "مسلسلات اجنبية",
        "$mainUrl/category/افلام-نتفليكس/page/" to "افلام نتفليكس",
        "$mainUrl/category/مسلسلات-نتفليكس/page/" to "مسلسلات نتفليكس",
        "$mainUrl/category/افلام-مارفل/page/" to "افلام مارفل",
        "$mainUrl/category/مسلسلات-عربية/page/" to "مسلسلات عربية",
        "$mainUrl/category/افلام-عربية/page/" to "افلام عربية",
        "$mainUrl/category/افلام-هندية/page/" to "أفلام هندية",
        "$mainUrl/category/افلام-تركية/page/" to "أفلام تركية",
        "$mainUrl/category/مسلسلات-تركية/page/" to "مسلسلات تركية"
    )

    private fun decodeHtml(doc: Document): Document {
        val rawHtml = doc.outerHtml()

        try {
            val secMatcher = Pattern.compile("""data-[a-zA-Z0-9]+="(\d+)"""").matcher(rawHtml)
            val secMath = if (secMatcher.find()) secMatcher.group(1).toInt() else return doc

            val keyMathMatcher = Pattern.compile("""=\s*(?:[a-zA-Z0-9_]+\s*\+\s*(\d+)\s*\+\s*(\d+)|(\d+)\s*\+\s*(\d+)\s*\+\s*[a-zA-Z0-9_]+)\s*;""").matcher(rawHtml)
            val k = if (keyMathMatcher.find()) {
                val p1 = keyMathMatcher.group(1) ?: keyMathMatcher.group(3)
                val p2 = keyMathMatcher.group(2) ?: keyMathMatcher.group(4)
                p1.toInt() + p2.toInt() + secMath
            } else return doc

            var subtraction = 0
            val subMatcher = Pattern.compile("""-\s*(\d+)\s*[;)]""").matcher(rawHtml)
            while (subMatcher.find()) {
                val v = subMatcher.group(1).toInt()
                if (v > 10) {
                    subtraction = v
                    break
                }
            }
            if (subtraction == 0) return doc

            var radix = 20
            val dynamicRadixMatcher = Pattern.compile("""var\s+[a-zA-Z0-9_]+\s*=\s*(\d+)\s*(/|\*|\+|-)\s*(\d+)\s*;""").matcher(rawHtml)
            if (dynamicRadixMatcher.find()) {
                val num1 = dynamicRadixMatcher.group(1).toInt()
                val operator = dynamicRadixMatcher.group(2)
                val num2 = dynamicRadixMatcher.group(3).toInt()
                radix = when (operator) {
                    "/" -> num1 / num2
                    "*" -> num1 * num2
                    "+" -> num1 + num2
                    "-" -> num1 + num2
                    else -> 20
                }
            } else {
                val radixMatcher = Pattern.compile("""parseInt\([^,]+,\s*(\d+)\)""").matcher(rawHtml)
                while (radixMatcher.find()) {
                    val foundRadix = radixMatcher.group(1).toInt()
                    if (foundRadix != 10) {
                        radix = foundRadix
                        break
                    }
                }
            }

            val splitMatcher = Pattern.compile("""\.split\(\s*['"]([^'"]+)['"]\s*\)""").matcher(rawHtml)
            val delimiter = if (splitMatcher.find()) splitMatcher.group(1) else return doc

            val arrayMatcher = Pattern.compile("""(?:var|let|const)\s+[a-zA-Z0-9_]+\s*=\s*(?:new Array\()?\[?(.*?)\]?\)?\s*;""", Pattern.DOTALL).matcher(rawHtml)
            var rawContent = ""
            while (arrayMatcher.find()) {
                val content = arrayMatcher.group(1) ?: ""
                if (content.length > 500) {
                    rawContent = content
                    break
                }
            }
            if (rawContent.isEmpty()) return doc

            val sbClean = StringBuilder(rawContent.length)
            for (i in 0 until rawContent.length) {
                val c = rawContent[i]
                if (c != '"' && c != '\'' && c != '\n' && c != '\r' && c != ' ' && c != ',') {
                    sbClean.append(c)
                }
            }
            val rawPayload = sbClean.toString()

            val outputStream = ByteArrayOutputStream(rawPayload.length / 4)
            var startIndex = 0
            val payloadLength = rawPayload.length
            val delimiterLength = delimiter.length

            while (startIndex < payloadLength) {
                var endIndex = rawPayload.indexOf(delimiter, startIndex)
                if (endIndex == -1) endIndex = payloadLength

                if (endIndex > startIndex) {
                    val chunk = rawPayload.substring(startIndex, endIndex)
                    decodeChunkFastAndroid(chunk, radix, subtraction, k, outputStream)
                }

                startIndex = endIndex + delimiterLength
            }

            val decodedHtmlString = outputStream.toString("UTF-8")
            if (decodedHtmlString.isBlank()) return doc

            return Jsoup.parse(decodedHtmlString)

        } catch (e: Exception) {
            return doc
        }
    }

    // =========================================================================
    // 1. دالة جلب المستند النهائي المفكوك بالتزامن مع كاش الإعدادات
    // =========================================================================
    private suspend fun getDecodedDocument(url: String, refererUrl: String? = null): Document = suspendCoroutine { continuation ->
        val logTag = "CimaNowHtmlDecoder"
        val customUserAgent = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/116.0.0.0 Safari/537.36"

        Log.i(logTag, "================ [START HTML DECODER] ================")
        Log.d(logTag, "-> Target URL: $url")

        val targetReferer = if (refererUrl.isNullOrBlank() || refererUrl.contains("cimanow.cc")) {
            "https://rm.freex2line.online/2020/02/blog-post.html/"
        } else {
            refererUrl
        }
        Log.d(logTag, "-> Forced Referer: $targetReferer")

        val mainLooper = android.os.Looper.getMainLooper()
        val handler = android.os.Handler(mainLooper)

        handler.post {
            try {
                val isFinished = java.util.concurrent.atomic.AtomicBoolean(false)
                val webView = android.webkit.WebView(this.context)

                fun safeFinish(result: Document, status: String) {
                    if (isFinished.compareAndSet(false, true)) {
                        Log.i(logTag, "Bypass Finished. Status: $status")
                        handler.post {
                            try {
                                webView.stopLoading()
                                webView.destroy()
                            } catch (e: Exception) {}
                        }
                        Log.i(logTag, "================ [END HTML DECODER] ================")
                        continuation.resume(result)
                    }
                }

                android.webkit.CookieManager.getInstance().apply {
                    setAcceptCookie(true)
                    setAcceptThirdPartyCookies(webView, true)
                }

                webView.removeJavascriptInterface("android")
                webView.settings.apply {
                    javaScriptEnabled = true
                    domStorageEnabled = true
                    mixedContentMode = android.webkit.WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
                    mediaPlaybackRequiresUserGesture = false
                    cacheMode = android.webkit.WebSettings.LOAD_DEFAULT

                    // [تعديل حاسم]: توحيد User-Agent
                    userAgentString = customUserAgent

                    loadsImagesAutomatically = false
                    blockNetworkImage = true
                }

                webView.webChromeClient = object : android.webkit.WebChromeClient() {
                    override fun onCreateWindow(view: android.webkit.WebView?, isDialog: Boolean, isUserGesture: Boolean, resultMsg: android.os.Message?): Boolean {
                        val dummyWebView = android.webkit.WebView(view!!.context)
                        dummyWebView.webViewClient = object : android.webkit.WebViewClient() {
                            override fun onPageStarted(v: android.webkit.WebView?, u: String?, f: android.graphics.Bitmap?) {
                                v?.stopLoading()
                                v?.destroy()
                            }
                        }
                        val transport = resultMsg?.obj as? android.webkit.WebView.WebViewTransport
                        if (transport != null) {
                            transport.setWebView(dummyWebView)
                        }
                        resultMsg?.sendToTarget()
                        return true
                    }
                }

                webView.webViewClient = object : android.webkit.WebViewClient() {
                    override fun onPageFinished(view: android.webkit.WebView?, pageUrl: String?) {
                        super.onPageFinished(view, pageUrl)

                        handler.postDelayed({
                            view?.evaluateJavascript(
                                "(function() { return document.documentElement.outerHTML; })();"
                            ) { htmlResult ->
                                val html = if (!htmlResult.isNullOrBlank() && htmlResult != "null") {
                                    htmlResult
                                        .removeSurrounding("\"")
                                        .replace("\\u003C", "<")
                                        .replace("\\\"", "\"")
                                        .replace("\\n", "\n")
                                } else ""

                                if (html.isNotBlank()) {
                                    Log.i(logTag, "✅ [SUCCESS] Decrypted DOM extracted! Length: ${html.length} characters.")
                                    saveDecryptedTextToFile(html, url)
                                    val doc = Jsoup.parse(html)
                                    safeFinish(doc, "SUCCESS")
                                } else {
                                    Log.e(logTag, "❌ [FAILURE] Extraction returned empty HTML from WebView.")
                                    safeFinish(Jsoup.parse("<html></html>"), "FAILURE_EMPTY_HTML")
                                }
                            }
                        }, 2500)
                    }

                    override fun shouldOverrideUrlLoading(view: android.webkit.WebView?, request: android.webkit.WebResourceRequest?): Boolean {
                        val reqUrl = request?.url?.toString() ?: return false
                        if (reqUrl.startsWith("intent", ignoreCase = true)) return true
                        return false
                    }

                    override fun shouldInterceptRequest(
                        view: android.webkit.WebView?,
                        request: android.webkit.WebResourceRequest?
                    ): android.webkit.WebResourceResponse? {
                        val reqUrl = request?.url?.toString() ?: return null
                        val uri = request.url ?: return null
                        val host = uri.host ?: ""
                        val path = uri.path ?: ""

                        if (request.method == "GET" && reqUrl.startsWith("http")) {
                            val isImage = path.endsWith(".png", true) || path.endsWith(".jpg", true) ||
                                    path.endsWith(".webp", true) || reqUrl.contains("/images/")
                            val isFont = path.endsWith(".woff", true) || path.endsWith(".woff2", true) || reqUrl.contains("/fonts/")
                            val isJs = path.endsWith(".js", true) || reqUrl.contains("/js/")

                            if (isImage || isFont || isJs) {
                                return android.webkit.WebResourceResponse("text/plain", "UTF-8", ByteArrayInputStream(ByteArray(0)))
                            }

                            if (!host.contains("freex2line.online")) return null

                            try {
                                return kotlinx.coroutines.runBlocking {
                                    val mergedHeaders = (request.requestHeaders ?: emptyMap()).toMutableMap()
                                    mergedHeaders["X-Requested-With"] = "mark.via.gp"

                                    val cookiesVal = android.webkit.CookieManager.getInstance().getCookie(reqUrl)
                                    if (!cookiesVal.isNullOrBlank()) {
                                        mergedHeaders["Cookie"] = cookiesVal
                                    }

                                    if (inMemoryCache.containsKey(reqUrl)) {
                                        val entry = inMemoryCache[reqUrl]!!
                                        return@runBlocking android.webkit.WebResourceResponse(
                                            entry.mimeType, entry.encoding, 200, "OK",
                                            mutableMapOf("Cache-Control" to "max-age=86400, public, immutable", "Access-Control-Allow-Origin" to "*"),
                                            ByteArrayInputStream(entry.bytes)
                                        )
                                    }

                                    val response = app.get(reqUrl, headers = mergedHeaders, allowRedirects = true)
                                    val rawBody = response.okhttpResponse.body ?: return@runBlocking null
                                    val contentType = response.headers["Content-Type"] ?: "text/html"
                                    val mimeType = contentType.substringBefore(";").trim()
                                    val encoding = if (contentType.contains("charset=")) contentType.substringAfter("charset=").substringBefore(";").trim() else "utf-8"

                                    val bytes = rawBody.bytes()

                                    if (response.code == 200 && (mimeType.contains("html") || mimeType.contains("css"))) {
                                        inMemoryCache[reqUrl] = ProviderCacheEntry(bytes, mimeType, encoding)
                                    }

                                    android.webkit.WebResourceResponse(
                                        mimeType, encoding, response.code, "OK",
                                        response.headers.toMultimap().mapValues { it.value.joinToString(", ") },
                                        ByteArrayInputStream(bytes)
                                    )
                                }
                            } catch (e: Exception) {
                                Log.e(logTag, "Error intercepting request: ${e.message}")
                            }
                        }
                        return super.shouldInterceptRequest(view, request)
                    }
                }

                val headers = mutableMapOf<String, String>()
                headers["X-Requested-With"] = "mark.via.gp"
                headers["Referer"] = targetReferer

                webView.loadUrl(url, headers)
                handler.postDelayed({
                    if (!isFinished.get()) {
                        safeFinish(Jsoup.parse("<html></html>"), "TIMEOUT")
                    }
                }, 20000)

            } catch (e: Exception) {
                continuation.resume(Jsoup.parse("<html></html>"))
            }
        }
    }

    // =========================================================================
    // 2. دالة حفظ الملفات المشفرة مع تنظيف الاسماء لتفادي قيود الاندرويد الحديث
    // =========================================================================
    private fun saveDecryptedTextToFile(text: String, sourceUrl: String) {
        val logTag = "CimaNowHtmlDecoder"
        try {
            // تنظيف الرابط من الرموز الخاصة كالنقطتين والمائل لتجنب أخطاء نظام الملفات
            val cleanUrlName = sourceUrl
                .substringAfter("://")
                .replace(Regex("[^a-zA-Z0-9_]"), "_")
                .take(30)

            // استخدام البادئة decrypted_ لتطابق الإعدادات تماماً
            val fileName = "decrypted_${cleanUrlName}_${System.currentTimeMillis()}.xml"
            var targetDir: File? = null

            // المحاولة الأولى: المجلد المطلوب مباشرة
            val targetDirPath = "/storage/emulated/0/Download/ADM/xml"
            val primaryDir = File(targetDirPath)
            try {
                if (primaryDir.exists() || primaryDir.mkdirs()) {
                    targetDir = primaryDir
                }
            } catch (e: Exception) {
                Log.w(logTag, "Primary directory failed, switching to fallback.")
            }

            // المحاولة الثانية: استخدام مسار التطبيق الخارجي لمنع قيود Scoped Storage
            if (targetDir == null) {
                val fallbackDir = File(context.getExternalFilesDir(null), "ADM/xml")
                if (fallbackDir.exists() || fallbackDir.mkdirs()) {
                    targetDir = fallbackDir
                }
            }

            if (targetDir == null) {
                throw Exception("Failed to access storage folders.")
            }

            val targetFile = File(targetDir, fileName)
            targetFile.writeText(text, Charsets.UTF_8)
            Log.i(logTag, "💾 [FILE SAVED] Decrypted HTML saved to: ${targetFile.absolutePath}")

        } catch (e: Exception) {
            Log.e(logTag, "❌ [SAVE ERROR] Failed to save HTML file: ${e.message}", e)
        }
    }

    // =========================================================================
    // 3. دالة التحضير وتخطي الرابط المختصر Freex2line
    // =========================================================================
    private suspend fun resolveFreex2line(url: String): String? = suspendCoroutine { continuation ->
        val mainLooper = android.os.Looper.getMainLooper()
        val handler = android.os.Handler(mainLooper)
        val customUserAgent = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/116.0.0.0 Safari/537.36"

        handler.post {
            try {
                val isFinished = java.util.concurrent.atomic.AtomicBoolean(false)
                val isResolverStarted = java.util.concurrent.atomic.AtomicBoolean(false)
                val webView = android.webkit.WebView(this.context)

                fun safeFinish(result: String?) {
                    if (isFinished.compareAndSet(false, true)) {
                        handler.post {
                            try {
                                webView.stopLoading()
                                webView.destroy()
                            } catch (_: Exception) {}
                        }
                        continuation.resume(result)
                    }
                }

                android.webkit.CookieManager.getInstance().apply {
                    setAcceptCookie(true)
                    setAcceptThirdPartyCookies(webView, true)
                }
                webView.removeJavascriptInterface("android")

                webView.settings.apply {
                    javaScriptEnabled = true
                    domStorageEnabled = true
                    mixedContentMode = android.webkit.WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
                    mediaPlaybackRequiresUserGesture = false
                    setJavaScriptCanOpenWindowsAutomatically(true)
                    setSupportMultipleWindows(true)

                    // [تعديل حاسم]: توحيد User-Agent
                    userAgentString = customUserAgent
                    cacheMode = android.webkit.WebSettings.LOAD_CACHE_ELSE_NETWORK

                    loadsImagesAutomatically = false
                    blockNetworkImage = true
                }

                webView.webChromeClient = object : android.webkit.WebChromeClient() {
                    override fun onCreateWindow(view: android.webkit.WebView?, isDialog: Boolean, isUserGesture: Boolean, resultMsg: android.os.Message?): Boolean {
                        val dummyWebView = android.webkit.WebView(view!!.context)
                        dummyWebView.webViewClient = object : android.webkit.WebViewClient() {
                            override fun onPageStarted(v: android.webkit.WebView?, u: String?, f: android.graphics.Bitmap?) {
                                v?.stopLoading()
                                v?.destroy()
                            }
                        }
                        val transport = resultMsg?.obj as? android.webkit.WebView.WebViewTransport
                        if (transport != null) {
                            transport.setWebView(dummyWebView)
                        }
                        resultMsg?.sendToTarget()
                        return true
                    }
                }

                webView.webViewClient = object : android.webkit.WebViewClient() {
                    override fun onPageFinished(view: android.webkit.WebView?, pageUrl: String?) {
                        super.onPageFinished(view, pageUrl)
                        val safeUrl = pageUrl ?: return

                        if (safeUrl.contains("freex2line.online") || safeUrl.contains("blog-post.html")) {
                            handler.postDelayed({
                                view?.evaluateJavascript(
                                    "(function() { return document.documentElement.outerHTML; })();"
                                ) { htmlResult ->
                                    if (!htmlResult.isNullOrBlank() && htmlResult != "null") {
                                        val unescapedHtml = htmlResult
                                            .removeSurrounding("\"")
                                            .replace("\\u003C", "<")
                                            .replace("\\\"", "\"")
                                            .replace("\\n", "\n")

                                        if (isResolverStarted.compareAndSet(false, true)) {
                                            CoroutineScope(Dispatchers.IO).launch {
                                                val finalUrl = resolveFreex2lineCore(url, unescapedHtml)
                                                safeFinish(finalUrl)
                                            }
                                        }
                                    }
                                }
                            }, 2500)
                        }
                    }

                    override fun shouldOverrideUrlLoading(view: android.webkit.WebView?, request: android.webkit.WebResourceRequest?): Boolean {
                        val reqUrl = request?.url?.toString() ?: return false
                        if (reqUrl.startsWith("intent", ignoreCase = true)) return true
                        return false
                    }

                    override fun shouldInterceptRequest(view: android.webkit.WebView?, request: android.webkit.WebResourceRequest?): android.webkit.WebResourceResponse? {
                        val reqUrl = request?.url?.toString() ?: return null
                        val uri = request.url ?: return null
                        val host = uri.host ?: ""
                        val path = uri.path ?: ""

                        if (request.method == "GET" && reqUrl.startsWith("http")) {
                            val isImage = path.endsWith(".png", true) || path.endsWith(".jpg", true) || reqUrl.contains("/images/")
                            val isFont = path.endsWith(".woff", true) || path.endsWith(".woff2", true) || reqUrl.contains("/fonts/")
                            val isJs = path.endsWith(".js", true) || reqUrl.contains("/js/")

                            if (isImage || isFont || isJs) {
                                return android.webkit.WebResourceResponse("text/plain", "UTF-8", ByteArrayInputStream(ByteArray(0)))
                            }

                            if (!host.contains("freex2line.online")) {
                                return android.webkit.WebResourceResponse("text/plain", "UTF-8", ByteArrayInputStream(ByteArray(0)))
                            }

                            try {
                                return kotlinx.coroutines.runBlocking {
                                    val mergedHeaders = (request.requestHeaders ?: emptyMap()).toMutableMap()
                                    mergedHeaders["X-Requested-With"] = "mark.via.gp"

                                    val cookiesVal = android.webkit.CookieManager.getInstance().getCookie(reqUrl)
                                    if (!cookiesVal.isNullOrBlank()) {
                                        mergedHeaders["Cookie"] = cookiesVal
                                    }

                                    if (reqUrl.contains("blog-post.html")) {
                                        request.requestHeaders?.forEach { (k, v) ->
                                            blogPostHeaders[k] = v
                                        }
                                    }

                                    if (inMemoryCache.containsKey(reqUrl)) {
                                        val entry = inMemoryCache[reqUrl]!!
                                        return@runBlocking android.webkit.WebResourceResponse(
                                            entry.mimeType, entry.encoding, ByteArrayInputStream(entry.bytes)
                                        )
                                    }

                                    // [تعديل]: استخدام طلب كلاودستريم بدلاً من OkHttpClient
                                    val response = app.get(reqUrl, headers = mergedHeaders, allowRedirects = true)
                                    val rawBody = response.okhttpResponse.body ?: return@runBlocking null
                                    val contentType = response.headers["Content-Type"] ?: "text/html"
                                    val mimeType = contentType.substringBefore(";").trim()
                                    val encoding = if (contentType.contains("charset=")) contentType.substringAfter("charset=").substringBefore(";").trim() else "utf-8"

                                    val bytes = rawBody.bytes()
                                    if (response.code == 200 && (mimeType.contains("html") || mimeType.contains("css"))) {
                                        inMemoryCache[reqUrl] = ProviderCacheEntry(bytes, mimeType, encoding)
                                    }

                                    android.webkit.WebResourceResponse(
                                        mimeType, encoding, response.code, "OK",
                                        response.headers.toMultimap().mapValues { it.value.joinToString(", ") },
                                        ByteArrayInputStream(bytes)
                                    )
                                }
                            } catch (_: Exception) {}
                        }
                        return super.shouldInterceptRequest(view, request)
                    }
                }

                CoroutineScope(Dispatchers.IO).launch {
                    try {
                        // [تعديل حاسم]: إرسال User-Agent الموحد في طلب الأجاكس التحضيري
                        val response = app.get(
                            url = url,
                            headers = mapOf(
                                "User-Agent" to customUserAgent,
                                "X-Requested-With" to "XMLHttpRequest"
                            ),
                            allowRedirects = true
                        )
                        val setCookieHeaders = response.headers.values("Set-Cookie")
                        val cookieManager = android.webkit.CookieManager.getInstance()
                        for (cookie in setCookieHeaders) {
                            if (cookie.contains("PHPSESSID")) {
                                cookieManager.setCookie("https://freex2line.online", cookie)
                                cookieManager.setCookie("https://rm.freex2line.online", cookie)
                            }
                        }
                        cookieManager.flush()
                    } catch (_: Exception) {}

                    withContext(Dispatchers.Main) {
                        val directUrl = "https://rm.freex2line.online/2020/02/blog-post.html/"
                        val directHeaders = mapOf(
                            "X-Requested-With" to "mark.via.gp",
                            "Referer" to "https://rm.freex2line.online/redirectingfree/"
                        )
                        webView.loadUrl(directUrl, directHeaders)
                    }
                }

                handler.postDelayed({ safeFinish(null) }, 30000)

            } catch (e: Exception) {
                continuation.resume(null)
            }
        }
    }
    private suspend fun resolveFreex2lineCore(url: String, html: String): String? {
        val resolverTag = "CimaNowResolver"
        val customUserAgent = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/116.0.0.0 Safari/537.36"

        try {
            val ctxName = Regex("""window\.ptr_[a-zA-Z0-9_]+\s*=\s*['"]([^'"]+)['"]""").find(html)?.groupValues?.get(1) ?: return null
            val mapData = Regex("""window\.map_[a-zA-Z0-9_]+\s*=\s*\{([^}]+)\}""").find(html)?.groupValues?.get(1) ?: return null
            val ctxData = Regex("""window\[['"]$ctxName['"]\]\s*=\s*\{([^}]+)\}""").find(html)?.groupValues?.get(1) ?: return null

            val chK = Regex("""ch:\s*['"]([^'"]+)['"]""").find(mapData)?.groupValues?.get(1) ?: return null
            val riK = Regex("""ri:\s*['"]([^'"]+)['"]""").find(mapData)?.groupValues?.get(1) ?: return null
            val keK = Regex("""ke:\s*['"]([^'"]+)['"]""").find(mapData)?.groupValues?.get(1) ?: return null
            val seK = Regex("""se:\s*['"]([^'"]+)['"]""").find(mapData)?.groupValues?.get(1) ?: return null

            val ch = Regex("""['"]$chK['"]:\s*['"]([^'"]+)['"]""").find(ctxData)?.groupValues?.get(1) ?: return null
            val requestId = Regex("""['"]$riK['"]:\s*['"]([^'"]+)['"]""").find(ctxData)?.groupValues?.get(1) ?: return null
            val encryptedKeyB64 = Regex("""['"]$keK['"]:\s*['"]([^'"]+)['"]""").find(ctxData)?.groupValues?.get(1) ?: return null
            val sXorKey = Regex("""['"]$seK['"]:\s*['"]([^'"]+)['"]""").find(ctxData)?.groupValues?.get(1) ?: return null

            val encryptedBytes = Base64.decode(encryptedKeyB64.replace(Regex("[\\s\\r\\n]"), ""), Base64.DEFAULT)
            val secretKeyBuilder = StringBuilder()
            for (i in encryptedBytes.indices) {
                val xorCharCode = sXorKey[i % sXorKey.length].code
                val decryptedChar = (encryptedBytes[i].toInt() xor xorCharCode).toChar()
                secretKeyBuilder.append(decryptedChar)
            }
            val secretKey = secretKeyBuilder.toString()

            val fpBase64 = "TW96aWxsYS81LjEw"
            val messageToSign = requestId + ch + fpBase64
            val keySpec = SecretKeySpec(secretKey.toByteArray(), "HmacSHA256")
            val mac = Mac.getInstance("HmacSHA256")
            mac.init(keySpec)
            val hmacToken = Base64.encodeToString(mac.doFinal(messageToSign.toByteArray()), Base64.NO_WRAP).trim()

            // الانتظار الإلزامي
            delay(11000)

            val apiUrl = "https://rm.freex2line.online/2020/02/blog-post.html/get-link.php"

            // [تعديل حاسم]: إعداد الترويسات المطابقة لبايثون
            val finalHeaders = mapOf(
                "User-Agent" to customUserAgent,
                "Content-Type" to "application/x-www-form-urlencoded",
                "Referer" to "https://rm.freex2line.online/redirectingfree/",
                "X-Requested-With" to "mark.via.gp"
            )

            // إجبار التزامن واستخراج PHPSESSID كخريطة مفاتيح لدوال app
            android.webkit.CookieManager.getInstance().flush()
            val cookiesVal = android.webkit.CookieManager.getInstance().getCookie("https://rm.freex2line.online") ?: ""
            val sessionCookies = mutableMapOf<String, String>()
            if (cookiesVal.isNotBlank()) {
                cookiesVal.split(";").forEach {
                    val parts = it.split("=")
                    if (parts.size >= 2 && parts[0].trim() == "PHPSESSID") {
                        sessionCookies["PHPSESSID"] = parts[1].trim()
                    }
                }
            }

            // [تعديل]: بناء البايلود كمصفوفة Map حيث تتكفل دالة app.post من كلاودستريم بتشفيرها (URLEncode) كـ Form
            val payloadData = mapOf(
                "request_id" to requestId,
                "hmac_token" to hmacToken,
                "ch" to ch,
                "fp" to fpBase64
            )

            // نص تجريبي فقط لطباعة المتغيرات في السجل
            val payloadString = "request_id=$requestId&hmac_token=${java.net.URLEncoder.encode(hmacToken, "UTF-8")}&ch=$ch&fp=$fpBase64"

            Log.i(resolverTag, "--- [API POST REQUEST DETAILS - CLOUDSTREAM APP.POST] ---")
            Log.i(resolverTag, "url = $apiUrl")
            Log.i(resolverTag, "headers = $finalHeaders")
            Log.i(resolverTag, "cookies = $sessionCookies")
            Log.i(resolverTag, "data = $payloadString")
            Log.i(resolverTag, "------------------------------------------------")

            // [تعديل حاسم]: إرسال الطلب بواسطة مكتبة التطبيق (Cloudstream) الموثوقة بدلاً من OkHttpClient
            val response = app.post(
                url = apiUrl,
                headers = finalHeaders,
                cookies = sessionCookies,
                data = payloadData,
                allowRedirects = true,
                timeout = 15
            )

            val finalResult = response.text.trim()

            if (finalResult.startsWith("http")) {
                Log.i(resolverTag, "[SUCCESS] Final Link: $finalResult")
                return finalResult
            } else {
                Log.e(resolverTag, "[FAILURE] Server Response: $finalResult")
            }
        } catch (e: Exception) {
            Log.e(resolverTag, "Error in resolveFreex2lineCore: ${e.message}", e)
        }
        return null
    }

    private fun decodeChunkFastAndroid(
        chunk: String,
        radix: Int,
        subtraction: Int,
        key: Int,
        out: ByteArrayOutputStream
    ) {
        try {
            val r = chunk.length % 4
            val paddedChunk = if (r > 0) chunk + "===".substring(0, 4 - r) else chunk
            val decodedBytes = Base64.decode(paddedChunk, Base64.DEFAULT)

            var num = 0L
            var found = false

            for (i in decodedBytes.indices) {
                val b = decodedBytes[i].toInt()

                val digitValue = when (b) {
                    in 48..57 -> b - 48
                    in 97..122 -> b - 87
                    in 65..90 -> b - 55
                    else -> -1
                }

                if (digitValue in 0 until radix) {
                    num = num * radix + digitValue
                    found = true
                }
            }

            if (found) {
                val fC = (num.toInt() - subtraction) xor key
                out.write(fC)
            }
        } catch (ignored: Exception) {
        }
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = "${request.data}$page/"
        val doc = app.get(url).document
        val decodedDoc = decodeHtml(doc)
        val home = decodedDoc.select("section article").mapNotNull { toSearchResponse(it) }
        return newHomePageResponse(request.name, home)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val q = query.replace(" ", "+")
        val doc = app.get("$mainUrl/?s=$q").document
        val decodedDoc = decodeHtml(doc)
        return decodedDoc.select("section article").mapNotNull { toSearchResponse(it) }
    }

    override suspend fun load(url: String): LoadResponse {
        val doc = app.get(url).document
        val decodedDoc = decodeHtml(doc)

        val isMovie = decodedDoc.title().contains("فيلم")
        val posterUrl = decodedDoc.select("figure img").attr("src")
        val year = decodedDoc.select("ul li a[href^='https://cimanow.cc/release-year/']").text().toIntOrNull()
        val title = decodedDoc.title().replace(Regex("الموسم الأول|برنامج|فيلم|مترجم|اون لاين|مسلسل|مشاهدة|انمي|أنمي|\\|$year|Cima Now|-|سيما ناو|ج[0-9]|\\|"), "")

        val tags = decodedDoc.select("article ul li")
            .filterNot { it.attr("aria-label") == "story" }
            .flatMap { it.text().split("،").map { tag -> tag.trim() } }

        val recommendations = decodedDoc.select("ul.related li").mapNotNull { toSearchResponse(it) }
        val synopsis = decodedDoc.select("li[aria-label=story] p").text()
        val actors = decodedDoc.select("ul li a[href^='https://cimanow.cc/actor/']").mapNotNull {
            val actorName = it.text()
            if (actorName.isNullOrBlank()) return@mapNotNull null
            ActorData(Actor(actorName))
        }

        if (isMovie) {
            return newMovieLoadResponse(title, url, TvType.Movie, url) {
                this.posterUrl = posterUrl
                this.year = year
                this.plot = synopsis
                this.tags = tags
                this.recommendations = recommendations
                this.actors = actors
            }
        } else {
            val episodes = mutableListOf<Episode>()
            val seasonElements = decodedDoc.select("section[aria-label=seasons] ul li a")

            if (seasonElements.isNotEmpty()) {
                coroutineScope {
                    val episodeLists = seasonElements.map { seasonElement ->
                        async {
                            try {
                                val seasonUrl = seasonElement.attr("href")
                                val seasonNum = getIntFromText(seasonElement.text())

                                val seasonDoc = decodeHtml(app.get(seasonUrl).document)

                                seasonDoc.select("ul#eps li a").mapNotNull { epElement ->
                                    newEpisode(epElement.attr("href")) {
                                        this.name = epElement.selectFirst("img")?.attr("alt")
                                        this.season = seasonNum
                                        this.episode = epElement.selectFirst("em")?.text()?.toIntOrNull()
                                        this.posterUrl = posterUrl
                                    }
                                }
                            } catch (e: Exception) {
                                emptyList<Episode>()
                            }
                        }
                    }.awaitAll()

                    episodes.addAll(episodeLists.flatten())
                }
            } else {
                val seasonNum = decodedDoc.selectFirst("span[aria-label=season-title]")?.text()?.let { getIntFromText(it) } ?: 1
                decodedDoc.select("ul#eps li a").mapNotNullTo(episodes) { epElement ->
                    newEpisode(epElement.attr("href")) {
                        this.name = epElement.selectFirst("img")?.attr("alt")
                        this.season = seasonNum
                        this.episode = epElement.selectFirst("em")?.text()?.toIntOrNull()
                        this.posterUrl = posterUrl
                    }
                }
            }

            return newTvSeriesLoadResponse(
                title,
                url,
                TvType.TvSeries,
                episodes.sortedWith(compareBy({ it.season }, { it.episode }))
            ) {
                this.posterUrl = posterUrl
                this.year = year
                this.plot = synopsis
                this.tags = tags
                this.recommendations = recommendations
                this.actors = actors
            }
        }
    }

    private val blogPostHeaders = java.util.concurrent.ConcurrentHashMap<String, String>()



    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        Log.i(TAG, "================ [START LOADLINKS] ================")

        try {
            val moviePageDoc = app.get(data).document
            var intermediateLink = moviePageDoc.selectFirst("ul.btns li a.shine[href*='freex2line']")?.attr("href")

            if (intermediateLink.isNullOrBlank()) {
                intermediateLink = moviePageDoc.select("a[href*='freex2line']").firstOrNull()?.attr("href")
            }

            if (intermediateLink.isNullOrBlank()) {
                throw ErrorLoadingException("Failed to find intermediate link.")
            }

            Log.i(TAG, "Bypassing intermediate link...")
            val finalCimaNowUrl = resolveFreex2line(intermediateLink)
            Log.i(TAG, "$finalCimaNowUrl")
            if (finalCimaNowUrl.isNullOrBlank()) {
                throw ErrorLoadingException("Failed to bypass shortlink.")
            }

            // جلب المستند النهائي المفكوك عبر التعديلات الجديدة
            val decodedDoc = getDecodedDocument(finalCimaNowUrl, refererUrl = data)
            val serverElements = decodedDoc.select("ul#watch li[data-index]")

            coroutineScope {
                serverElements.map { serverElement ->
                    async {
                        val dataIndex = serverElement.attr("data-index")
                        val dataId = serverElement.attr("data-id")
                        val name = serverElement.text().trim()

                        val serverUrl = "$mainUrl/wp-content/themes/Cima%20Now%20New/core.php?action=switch&index=$dataIndex&id=$dataId"

                        try {
                            // [سجل تتبع]: تفاصيل طلب الأجاكس (الرابط والـ Referer)
                            Log.i(TAG, "🌐 [AJAX REQUEST] Fetching server player...")
                            Log.i(TAG, "   -> URL: $serverUrl")
                            Log.i(TAG, "   -> Referer: $finalCimaNowUrl")

                            val playerResponse = app.get(serverUrl, referer = finalCimaNowUrl)
                            val playerHtml = playerResponse.text
                            val playerDoc = Jsoup.parse(playerHtml)

                            // [سجل تتبع]: استجابة الأجاكس
                            val snippet = if (playerHtml.length > 200) playerHtml.substring(0, 200) + "..." else playerHtml
                            Log.i(TAG, "✅ [AJAX RESPONSE] Status: ${playerResponse.code} | HTML Snippet: $snippet")

                            val iframeUrl = playerDoc.selectFirst("iframe")?.attr("src")?.let {
                                if (it.startsWith("//")) "https:$it" else it
                            }

                            if (iframeUrl.isNullOrBlank()) {
                                Log.w(TAG, "⚠️ [WARNING] No iframe found for server: $name")
                                return@async
                            }

                            // طباعة اسم السيرفر ورابط المشاهدة المكتشف قبل الإرسال للمستخرج
                            Log.i(TAG, "🔍 [WATCH SERVER FOUND] Name: $name | iframe: $iframeUrl")

                            when {
                                name.contains("Cima Now", true) -> handlecima(iframeUrl, name, callback)
                                name.contains("VidPro", true) -> handleVidPro(iframeUrl, name, callback)
                                name.contains("Govid", true) || name.contains("Goovid", true) -> handleGovid(iframeUrl, name, callback)
                                name.contains("Vidlook", true) -> handleVidlook(iframeUrl, name, callback)
                                name.contains("Streamwish", true) -> handleStreamwish(iframeUrl, name, callback)
                                name.contains("Streamfile", true) || name.contains("Luluvid", true) -> handleStreamfileAndLuluvid(iframeUrl, name, callback)
                                name.contains("Vadbam", true) || name.contains("Viidshare", true) -> handleVadbamAndViidshare(iframeUrl, name, callback)
                                else -> {
                                    try {
                                        loadExtractor(iframeUrl, finalCimaNowUrl, subtitleCallback, callback)
                                    } catch (e: Exception) {
                                        Log.e(TAG, "Error in loadExtractor: ${e.message}")
                                    }
                                }
                            }

                        } catch (e: Exception) {
                            Log.e(TAG, "Failed to fetch iframe for $name: ${e.message}")
                        }
                    }
                }.awaitAll()
            }

            val downloadLinks = decodedDoc.select("ul#download li a[href]")

            coroutineScope {
                downloadLinks.map { aTag ->
                    async {
                        var linkUrl = aTag.attr("href")
                        val qualityText = aTag.text().trim()
                        val qualityNum = getQualityFromName(qualityText)

                        if (linkUrl.startsWith("https://href.li/?")) {
                            linkUrl = linkUrl.substringAfter("https://href.li/?")
                        }

                        try {
                            // [إضافة سجل]: طباعة جودة ورابط التنزيل المكتشف قبل الإرسال للمستخرج
                            Log.i(TAG, "📥 [DOWNLOAD LINK FOUND] Quality: $qualityText ($qualityNum) | Link: $linkUrl")

                            when {
                                linkUrl.contains("jetload", true) -> {
                                    handleJetload(linkUrl, qualityNum, finalCimaNowUrl, callback)
                                }
                                linkUrl.contains("forafile", true) -> {
                                    handleForafile(linkUrl, qualityNum, finalCimaNowUrl, callback)
                                }

                                else -> {
                                    loadExtractor(linkUrl, finalCimaNowUrl, subtitleCallback, callback)
                                }
                            }
                        } catch (e: Exception) {
                            Log.e(TAG, "Failed to load download link: ${e.message}")
                        }
                    }
                }.awaitAll()
            }

        } catch (e: Exception) {
            Log.e(TAG, "FATAL ERROR in loadLinks: ${e.message}", e)
        }

        return true
    }

    private suspend fun handlecima(iframeUrl: String, name: String, callback: (ExtractorLink) -> Unit) {
        try {
            val finalUrl = if (iframeUrl.startsWith("//")) "https:$iframeUrl" else iframeUrl
            val iframeResponse = app.get(finalUrl, referer = finalUrl).text

            val regex = Regex("""\[(\d+p)]\s+(/uploads/[^\"]+\.mp4)""")
            val baseUrl = Regex("""(https?://[^/]+)""").find(finalUrl)?.groupValues?.get(1) ?: ""
            val links = mutableListOf<ExtractorLink>()
            regex.findAll(iframeResponse).forEach { match ->
                val qualityStr = match.groupValues[1]
                val filePath = match.groupValues[2]
                val videoUrl = baseUrl + filePath

                links.add(
                    newExtractorLink(
                        source = "CimaNow",
                        name = "CimaNow",
                        url = videoUrl
                    ).apply {
                        this.quality = getQualityFromName(qualityStr)
                        this.referer = finalUrl
                    }
                )
            }
            links.sortByDescending { it.quality }
            links.forEach { link -> callback.invoke(link) }
        } catch (e: Exception) {}
    }

    private suspend fun handleVidPro(iframeUrl: String, name: String, callback: (ExtractorLink) -> Unit) {
        try { val finalUrl = if (iframeUrl.startsWith("//")) "https:$iframeUrl" else iframeUrl
            loadExtractor(finalUrl, mainUrl, {}, callback) } catch (e: Exception) {}
    }
    private suspend fun handleGovid(iframeUrl: String, name: String, callback: (ExtractorLink) -> Unit) {
        try { val finalUrl = if (iframeUrl.startsWith("//")) "https:$iframeUrl" else iframeUrl
            loadExtractor(finalUrl, mainUrl, {}, callback) } catch (e: Exception) {}
    }
    private suspend fun handleVidlook(iframeUrl: String, name: String, callback: (ExtractorLink) -> Unit) {
        try { val finalUrl = if (iframeUrl.startsWith("//")) "https:$iframeUrl" else iframeUrl
            loadExtractor(finalUrl, mainUrl, {}, callback) } catch (e: Exception) {}
    }
    private suspend fun handleStreamwish(iframeUrl: String, name: String, callback: (ExtractorLink) -> Unit) {
        try { val finalUrl = if (iframeUrl.startsWith("//")) "https:$iframeUrl" else iframeUrl
            loadExtractor(finalUrl, mainUrl, {}, callback) } catch (e: Exception) {}
    }
    private suspend fun handleStreamfileAndLuluvid(iframeUrl: String, name: String, callback: (ExtractorLink) -> Unit) {
        try { val finalUrl = if (iframeUrl.startsWith("//")) "https:$iframeUrl" else iframeUrl
            loadExtractor(finalUrl, mainUrl, {}, callback) } catch (e: Exception) {}
    }
    private suspend fun handleVadbamAndViidshare(iframeUrl: String, name: String, callback: (ExtractorLink) -> Unit) {
        try { val finalUrl = if (iframeUrl.startsWith("//")) "https:$iframeUrl" else iframeUrl
            loadExtractor(finalUrl, mainUrl, {}, callback) } catch (e: Exception) {}
    }

    private suspend fun handleJetload(url: String, quality: Int, referer: String, callback: (ExtractorLink) -> Unit) {
        try {
            val headers = mapOf(
                "User-Agent" to "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/147.0.0.0 Mobile Safari/537.36",
                "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8",
                "Accept-Language" to "ar-EG,ar;q=0.9"
            )

            val res1 = app.get(url, headers = headers)
            val sessionCookies = mutableMapOf<String, String>()
            sessionCookies.putAll(res1.cookies)

            val targetUrl = "https://jetload.pp.ua/Jetload4/"
            val headers2 = headers + mapOf("Referer" to url)

            val res2 = app.get(targetUrl, headers = headers2, cookies = sessionCookies)
            val html = res2.text
            sessionCookies.putAll(res2.cookies)

            val extraToken = Regex("""window\.extraToken\s*=\s*'([^']+)'""").find(html)?.groupValues?.get(1)
            val dataToken = Regex("""data-token="([^"]+)"""").find(html)?.groupValues?.get(1)

            if (extraToken == null || dataToken == null) return

            delay(10000)

            val ajaxUrl = "https://jetload.pp.ua/Jetload4/get-link.php?token=$dataToken"
            val ajaxHeaders = headers + mapOf(
                "X-Requested-With" to "XMLHttpRequest",
                "Referer" to targetUrl
            )

            val finalResp = app.get(ajaxUrl, headers = ajaxHeaders, cookies = sessionCookies)
            val rawLink = finalResp.text.trim()

            if (rawLink.startsWith("http")) {
                val intermediateLink = "$rawLink?t=$extraToken"
                callback.invoke(
                    newExtractorLink(
                        source = "Jetload",
                        name = "Jetload",
                        url = intermediateLink,
                    ) {
                        this.referer = targetUrl
                        this.quality = quality
                    }
                )
            }
        } catch (e: Exception) {}
    }

    private suspend fun handleForafile(url: String, quality: Int, referer: String, callback: (ExtractorLink) -> Unit) {
        try {
            val match = Regex("""(https://forafile\.com/([^/]+)/)""").find(url) ?: return
            val baseUrl = match.groupValues[1]
            val fileId = match.groupValues[2]

            val headers = mapOf("user-agent" to "Mozilla/5.0 (Linux; Android 13)", "referer" to url)
            val data = mapOf(
                "op" to "download2", "id" to fileId, "rand" to "",
                "referer" to "", "method_free" to "", "method_premium" to "", "adblock_detected" to "0"
            )

            val response = app.post(baseUrl, headers = headers, data = data, allowRedirects = false)
            val location = response.headers["location"] ?: response.headers["Location"]

            if (!location.isNullOrBlank()) {
                callback.invoke(
                    newExtractorLink(
                        source = "Forafile",
                        name = "Forafile",
                        url = location,
                    ) {
                        this.referer = baseUrl
                        this.quality = quality
                    }
                )
            }
        } catch (e: Exception) {}
    }

    private fun toSearchResponse(element: Element): SearchResponse? {
        if (element.select("a").text().contains("الكل")) return null

        val urlElement = element.selectFirst("a")
        val posterUrl = element.select("img.lazy").attr("data-src").ifBlank {
            element.select("img.lazy").attr("src")
        }
        val category = element.select("ul.info li[aria-label=tab]").text()
        val extype = element.select("ul.info li[aria-label=tab]")
        val title = element.selectFirst("li[aria-label=title]")?.let {
            it.select("em").remove()
            it.text()
        } ?: ""

        val year = element.select("li[aria-label=year]").text().toIntOrNull()
        val qualitiesSelector = element.select("li[aria-label=ribbon]").mapNotNull {
            it.text().takeIf { text -> text.contains(Regex("""\d+""")) }
        }.joinToString(" ")
        val quality = getQualityFromString(qualitiesSelector)

        val type = if (extype.text().contains("مسلسلات", true) || extype.text().contains("موسم", true)) {
            TvType.TvSeries
        } else {
            TvType.Movie
        }

        return urlElement?.attr("href")?.let { href ->
            newMovieSearchResponse(
                name = title.replace(Regex("$category|موسم 1|برنامج|فيلم|مترجم|اون لاين|مسلسل|مشاهدة|انمي|أنمي|\\||"), ""),
                url = href,
                type = type,
            ) {
                this.posterUrl = posterUrl
                this.year = year
                this.quality = quality
            }
        }
    }
}