package com.cimanow

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Typeface
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.google.android.material.button.MaterialButton
import java.io.ByteArrayInputStream
import java.io.File
import java.net.URLEncoder
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.delay

// بنية بيانات لحفظ كاش الملفات مع الاحتفاظ بنوعها الأصلي لمنع حظر الجافاسكربت
data class CacheEntry(val bytes: ByteArray, val mimeType: String, val encoding: String)

class cimanowsetting : BottomSheetDialogFragment() {

    private var webView: WebView? = null
    private var logScrollView: ScrollView? = null
    private var logTextView: TextView? = null
    private var backFloatingBtn: MaterialButton? = null
    private var logToggleBtn: MaterialButton? = null

    private val isFinished = AtomicBoolean(false)
    private val isResolverStarted = AtomicBoolean(false) // مفتاح لمنع تكرار تشغيل الـ Resolver

    // الرابط الرئيسي للمؤقت والتوجيه
    private val targetUrl = "https://rm.freex2line.online/loadon/?link=aHR0cHM6Ly9jaW1hbm93LmNjLyVkOSU4NSVkOCViMyVkOSU4NCVkOCViMyVkOSU4NC1hLXNob3AtZm9yLWtpbGxlcnMtJWQ4JWFjMi0lZDglYWQxLSVkOSU4NSVkOCVhYSVkOCViMSVkOCVhYyVkOSU4NSVkOCVhOS93YXRjaGluZy8="
    private val extraHeaders = mapOf("X-Requested-With" to "mark.via.gp")

    // الذاكرة المؤقتة لحفظ الصفحات والملفات البرمجية مع الحفاظ على أنواعها الأصلية
    private val inMemoryCache = ConcurrentHashMap<String, CacheEntry>()

    // مستودع لحفظ هيدرات WebView الأصلية الخاصة بصفحة blog-post لنسخها لاحقاً في طلب الـ POST
    private val blogPostHeaders = ConcurrentHashMap<String, String>()

    private val logBuilder = StringBuilder("--- بدء تشغيل سجل اتصالات الإضافة (حقن mark.via.gp فقط) ---\n\n")

    // دالة مساعدة لطباعة السجل في الوقت الفعلي داخل الواجهة
    private fun writeToLog(message: String) {
        Handler(Looper.getMainLooper()).post {
            synchronized(logBuilder) {
                logBuilder.append(message).append("\n")
                logTextView?.text = logBuilder.toString()
            }
            logScrollView?.post {
                logScrollView?.fullScroll(View.FOCUS_DOWN)
            }
        }
    }

    // دالة للتبديل البرمجي التلقائي بين واجهة المتصفح وواجهة السجل
    private fun showLogView(show: Boolean) {
        Handler(Looper.getMainLooper()).post {
            if (show) {
                logScrollView?.visibility = View.VISIBLE
                webView?.visibility = View.GONE
                backFloatingBtn?.visibility = View.GONE
                logToggleBtn?.text = "عرض المتصفح (WebView)"
            } else {
                logScrollView?.visibility = View.GONE
                webView?.visibility = View.VISIBLE
                backFloatingBtn?.visibility = View.VISIBLE
                logToggleBtn?.text = "عرض السجل (Log)"
            }
        }
    }

    // دالة مساعدة لعرض التنبيهات المنبثقة من أي Thread
    private fun showToast(message: String) {
        Handler(Looper.getMainLooper()).post {
            context?.let {
                Toast.makeText(it, message, Toast.LENGTH_SHORT).show()
            }
        }
    }

    // ربط سجلات الـ Resolver لتعرض في لوحة التحكم وتدفقها بالوقت الفعلي
    private fun logInfo(tag: String, msg: String) {
        writeToLog("ℹ️ [$tag] $msg")
        android.util.Log.i(tag, msg)
    }

    private fun logDebug(tag: String, msg: String) {
        writeToLog("🔍 [$tag] $msg")
        android.util.Log.d(tag, msg)
    }

    private fun logError(tag: String, msg: String, throwable: Throwable? = null) {
        writeToLog("❌ [$tag] $msg ${throwable?.message ?: ""}")
        android.util.Log.e(tag, msg, throwable)
    }

    // دالة مساعدة لتنظيف كود الـ HTML المستخرج من الـ WebView
    private fun cleanHtml(raw: String): String {
        return raw.removeSurrounding("\"")
            .replace("\\u003C", "<")
            .replace("\\\"", "\"")
            .replace("\\n", "\n")
    }

    // دالة لحفظ النص المفكوك مباشرة إلى مسار التنزيلات المطلوب أو المسار البديل الآمن
    private fun saveDecryptedTextToFile(context: Context?, text: String, sourceUrl: String) {
        try {
            // تنظيف الرابط من الرموز التي تسبب مشاكل لأنظمة الملفات مثل : و / و % واقتصاره على الحروف والأرقام
            val cleanUrlName = sourceUrl
                .substringAfter("://")
                .replace(Regex("[^a-zA-Z0-9_]"), "_")
                .take(30)

            val fileName = "decrypted_${cleanUrlName}_${System.currentTimeMillis()}.xml"
            var targetDir: File? = null

            // المحاولة الأولى: الحفظ في المجلد المحدد في الطلب
            val targetDirPath = "/storage/emulated/0/Download/ADM/xml"
            val primaryDir = File(targetDirPath)
            try {
                if (primaryDir.exists() || primaryDir.mkdirs()) {
                    targetDir = primaryDir
                }
            } catch (e: Exception) {
                // تجاهل الخطأ للانتقال للمحاولة البديلة
            }

            // المحاولة الثانية: استخدام المسار المخصص للتطبيق لتجنب قيود الحماية في إصدارات الأندرويد الحديثة
            if (targetDir == null && context != null) {
                val fallbackDir = File(context.getExternalFilesDir(null), "ADM/xml")
                if (fallbackDir.exists() || fallbackDir.mkdirs()) {
                    targetDir = fallbackDir
                }
            }

            if (targetDir == null) {
                throw Exception("فشل العثور أو إنشاء مجلد تخزين صالح.")
            }

            val targetFile = File(targetDir, fileName)
            targetFile.writeText(text, Charsets.UTF_8)

            writeToLog("💾 [FILE SAVED] تم حفظ النص المفكوك بنجاح في:")
            writeToLog("   -> ${targetFile.absolutePath}")

        } catch (e: Exception) {
            writeToLog("❌ [SAVE ERROR] فشل حفظ الملف في الذاكرة: ${e.message}")
            android.util.Log.e("CIMANOW_SAVE", "Error saving XML to storage", e)
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        val context = requireContext()

        val mainContainer = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        }

        val actionBar = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                setMargins(24, 16, 24, 16)
            }
        }

        logToggleBtn = MaterialButton(context).apply {
            text = "عرض السجل (Log)"
            layoutParams = LinearLayout.LayoutParams(
                0,
                LinearLayout.LayoutParams.WRAP_CONTENT,
                1f
            ).apply {
                setMargins(0, 0, 12, 0)
            }
            setOnClickListener {
                if (logScrollView?.visibility == View.VISIBLE) {
                    showLogView(false)
                } else {
                    showLogView(true)
                }
            }
        }

        val copyLogBtn = MaterialButton(context).apply {
            text = "نسخ السجل"
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                setMargins(0, 0, 12, 0)
            }
            setOnClickListener {
                val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                val clip = ClipData.newPlainText("Network Log", logBuilder.toString())
                clipboard.setPrimaryClip(clip)
                Toast.makeText(context, "📋 تم نسخ تقرير الشبكة الكامل!", Toast.LENGTH_SHORT).show()
            }
        }

        val closeBtn = MaterialButton(context).apply {
            text = "إغلاق"
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            setOnClickListener {
                dismissAllowingStateLoss()
            }
        }

        actionBar.addView(logToggleBtn)
        actionBar.addView(copyLogBtn)
        actionBar.addView(closeBtn)
        mainContainer.addView(actionBar)

        val contentFrame = FrameLayout(context).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                0,
                1f
            )
        }

        webView = WebView(context).apply {
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        }

        logScrollView = ScrollView(context).apply {
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
            visibility = View.GONE
            setBackgroundColor(Color.parseColor("#121212"))
        }

        logTextView = TextView(context).apply {
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                setMargins(24, 24, 24, 24)
            }
            setTextColor(Color.parseColor("#00FF00"))
            textSize = 11f
            typeface = Typeface.MONOSPACE
            text = logBuilder.toString()
        }

        backFloatingBtn = MaterialButton(context).apply {
            text = "رجوع"
            setTextColor(Color.WHITE)
            setBackgroundColor(Color.parseColor("#CC000000"))
            cornerRadius = 24
            iconSize = 32

            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                gravity = Gravity.CENTER_VERTICAL or Gravity.END
                marginEnd = 16
            }

            setOnClickListener {
                val wv = webView ?: return@setOnClickListener
                if (wv.canGoBack()) {
                    val history = wv.copyBackForwardList()
                    var steps = -1
                    var foundTarget = false
                    val currentIndex = history.currentIndex

                    while (currentIndex + steps >= 0) {
                        val prevUrl = history.getItemAtIndex(currentIndex + steps).url ?: ""
                        if (prevUrl.contains("freex2line.online") || prevUrl.contains("cimanow")) {
                            wv.goBackOrForward(steps)
                            foundTarget = true
                            break
                        }
                        steps--
                    }

                    if (!foundTarget) {
                        wv.goBack()
                    }
                } else {
                    Toast.makeText(context, "لا توجد صفحات سابقة للرجوع إليها", Toast.LENGTH_SHORT).show()
                }
            }
        }

        logScrollView?.addView(logTextView)
        contentFrame.addView(webView)
        contentFrame.addView(logScrollView)
        contentFrame.addView(backFloatingBtn)
        mainContainer.addView(contentFrame)

        setupWebView()
        return mainContainer
    }

    override fun onStart() {
        super.onStart()
        dialog?.let { d ->
            val bottomSheet = d.findViewById<View>(com.google.android.material.R.id.design_bottom_sheet)
            bottomSheet?.layoutParams?.height = ViewGroup.LayoutParams.MATCH_PARENT
        }
    }

    private fun setupWebView() {
        val wv = webView ?: return
        val currentContext = wv.context

        CookieManager.getInstance().apply {
            setAcceptCookie(true)
            setAcceptThirdPartyCookies(wv, true)
        }
        wv.removeJavascriptInterface("android")

        wv.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            databaseEnabled = true
            allowFileAccess = true
            allowContentAccess = true
            loadWithOverviewMode = true
            useWideViewPort = true
            mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
            mediaPlaybackRequiresUserGesture = false
            javaScriptCanOpenWindowsAutomatically = true
            setSupportMultipleWindows(true)
            cacheMode = WebSettings.LOAD_CACHE_ELSE_NETWORK

            // إلغاء تحميل الصور لتسريع المعالجة وحفظ البيانات
            loadsImagesAutomatically = false
            blockNetworkImage = true
        }

        wv.webChromeClient = object : WebChromeClient() {
            override fun onCreateWindow(
                view: WebView?,
                isDialog: Boolean,
                isUserGesture: Boolean,
                resultMsg: android.os.Message?
            ): Boolean {
                val dummyWebView = WebView(view!!.context)
                dummyWebView.webViewClient = object : WebViewClient() {
                    override fun onPageStarted(v: WebView?, u: String?, f: Bitmap?) {
                        writeToLog("🛡️ [POPUP BLOCKED] تم حظر وتفتيت نافذة منبثقة: $u")
                        v?.stopLoading()
                        v?.destroy()
                    }
                }
                val transport = resultMsg?.obj as? WebView.WebViewTransport
                if (transport != null) {
                    transport.webView = dummyWebView
                }
                resultMsg?.sendToTarget()
                return true
            }
        }

        wv.webViewClient = object : WebViewClient() {

            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                val safeUrl = url ?: return
                val title = view?.title ?: ""
                val activeContext = view?.context ?: currentContext

                writeToLog("📄 [INFO] اكتمل تحميل الصفحة: $safeUrl | العنوان: $title")

                // فحص إذا كنا في صفحة حماية المتصفح، نتوقف ونترك المتصفح يحلها تلقائياً بالكامل دون تداخل من كود الحقن
                if (title.contains("Checking your browser", ignoreCase = true) || safeUrl.contains("__challenge")) {
                    writeToLog("🔒 [CHALLENGE DETECTED] المتصفح يحل تحدي فحص الأمان الآن بالخلفية... جاري الانتظار.")
                    return
                }

                // الحالة (أ): صفحة العداد لتخطي الرابط المختصر
                if (safeUrl == "https://rm.freex2line.online/2020/02/blog-post.html/" || safeUrl == "https://rm.freex2line.online/2020/02/blog-post.html") {
                    writeToLog("⏳ [WAIT] جاري إعطاء الجافاسكربت فرصة لفك تشفير الصفحة...")

                    Handler(Looper.getMainLooper()).postDelayed({

                        // إعادة فحص العنوان مجدداً قبل الحقن للاطمئنان أننا تخطينا شاشة الحماية
                        val currentTitle = view?.title ?: ""
                        if (currentTitle.contains("Checking your browser", ignoreCase = true)) {
                            writeToLog("🔒 [CHALLENGE TRIGGERED] ظهر فحص الأمان مجدداً، تم إرجاء فك تشفير الكود.")
                            return@postDelayed
                        }

                        view?.evaluateJavascript(
                            "(function() { return document.documentElement.outerHTML; })();"
                        ) { htmlResult ->
                            if (!htmlResult.isNullOrBlank() && htmlResult != "null") {
                                val unescapedHtml = cleanHtml(htmlResult)

                                // التحقق الإضافي لعدم سحب كود صفحة التحدي بالخطأ
                                if (unescapedHtml.contains("Checking your browser") || unescapedHtml.contains("__challenge")) {
                                    writeToLog("🔒 [CHALLENGE CAPTURED] تم التقاط كود فحص الأمان بدلاً من العداد، جاري الانتظار للتحويل...")
                                    return@evaluateJavascript
                                }

                                writeToLog("🔓 [DECRYPTED HTML] تم سحب كود العداد بعد الفك بنجاح!")
                                saveDecryptedTextToFile(activeContext, unescapedHtml, safeUrl)

                                // تشغيل الـ Resolver بأمان تام بعد التأكد من الحصول على كود العداد الحقيقي
                                if (isResolverStarted.compareAndSet(false, true)) {
                                    CoroutineScope(Dispatchers.IO).launch {
                                        val finalWatchUrl = resolveFreex2line(targetUrl, unescapedHtml, activeContext)
                                        if (finalWatchUrl != null) {
                                            withContext(Dispatchers.Main) {
                                                writeToLog("\n🎉 [RESOLVE SUCCESS] تم الحصول على رابط المشاهدة بنجاح: $finalWatchUrl")
                                                writeToLog("🚀 جاري توجيه المتصفح لفتح صفحة المشاهدة وحفظها وفك تشفير سيرفراتها تلقائياً مع تزويد الـ Referer والـ mark.via.gp...")

                                                // تحميل صفحة المشاهدة النهائية مع إرسال Referer الـ blog-post وحقن ترويسة Via
                                                val watchHeaders = mapOf(
                                                    "X-Requested-With" to "mark.via.gp",
                                                    "Referer" to "https://rm.freex2line.online/2020/02/blog-post.html/"
                                                )
                                                webView?.loadUrl(finalWatchUrl, watchHeaders)
                                            }
                                        } else {
                                            writeToLog("❌ [RESOLVE FAILED] لم نتمكن من الحصول على الرابط من خادم get-link.php")
                                            isResolverStarted.set(false)
                                        }
                                    }
                                }
                            }
                        }
                    }, 2500)
                }

                // الحالة (ب): استهداف صفحة السيرفرات النهائية لسيما ناو وفك تشفيرها مرئياً
                if (safeUrl.contains("cimanow") || safeUrl.contains("watching") || safeUrl.contains("watching/")) {
                    writeToLog("⏳ [WAIT] جاري الانتظار لفك تشفير صفحة السيرفرات لسيما ناو أوتوماتيكياً...")

                    Handler(Looper.getMainLooper()).postDelayed({
                        view?.evaluateJavascript(
                            "(function() { return document.documentElement.outerHTML; })();"
                        ) { htmlResult ->
                            if (!htmlResult.isNullOrBlank() && htmlResult != "null") {
                                val unescapedHtml = cleanHtml(htmlResult)

                                writeToLog("🔓 [DECRYPTED CIMANOW] تم استخراج وحفظ كود السيرفرات والروابط المفكوكة بالكامل!")
                                saveDecryptedTextToFile(activeContext, unescapedHtml, safeUrl)

                                val snippet = if (unescapedHtml.length > 800) unescapedHtml.substring(0, 800) + "\n... [تم اقتطاع الباقي]" else unescapedHtml
                                writeToLog("\n--- بداية كود السيرفرات المفكوك ---\n$snippet\n--- نهاية الكود ---")

                                Toast.makeText(activeContext, "🎉 تم فك تشفير صفحة السيرفرات النهائية وحفظها بنجاح!", Toast.LENGTH_LONG).show()
                            }
                        }
                    }, 2500)
                }
            }

            override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                val reqUrl = request?.url?.toString() ?: return false

                // التقاط رابط الفيديو وحفظه كإجراء احتياطي للمتصفح التقليدي
                if ((reqUrl.contains("watching") || reqUrl.contains("watching/")) && !reqUrl.contains("pig")) {
                    if (isFinished.compareAndSet(false, true)) {
                        writeToLog("\n🎉 [SUCCESS] تم التقاط الرابط بنجاح: $reqUrl\n")
                        val clipboard = view?.context?.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                        clipboard.setPrimaryClip(ClipData.newPlainText("CimaNow Watch Link", reqUrl))
                        CookieManager.getInstance().flush()
                        Toast.makeText(view?.context, "🎉 تم التقاط رابط المشاهدة ونسخه!", Toast.LENGTH_LONG).show()
                    }
                    return true
                }

                // حظر روابط الـ Intent (التطبيقات الخارجية) فقط بناءً على طلبك
                if (reqUrl.startsWith("intent", ignoreCase = true)) {
                    writeToLog("🚫 [BLOCKED INTENT] تم حظر رابط Intent الخارجي: $reqUrl")
                    return true
                }

                return false
            }

            override fun shouldInterceptRequest(
                view: WebView?,
                request: WebResourceRequest?
            ): WebResourceResponse? {
                val reqUrl = request?.url?.toString() ?: return null
                val uri = request.url ?: return null
                val host = uri.host ?: ""
                val path = uri.path ?: ""
                val method = request.method ?: "GET"

                if (request.method == "GET" && reqUrl.startsWith("http")) {

                    // حظر الصور تماماً لتقليل استهلاك الشبكة
                    val isImage = path.endsWith(".png", true) || path.endsWith(".jpg", true) ||
                            path.endsWith(".jpeg", true) || path.endsWith(".gif", true) ||
                            path.endsWith(".webp", true) || path.endsWith(".svg", true) ||
                            path.endsWith(".ico", true) || reqUrl.contains("/images/")

                    // حظر ملفات الخطوط
                    val isFont = path.endsWith(".woff", true) || path.endsWith(".woff2", true) ||
                            path.endsWith(".ttf", true) || path.endsWith(".otf", true) ||
                            path.endsWith(".eot", true) || reqUrl.contains("/fonts/")

                    // [تعديل حاسم]: حظر تحميل أي ملفات جافاسكربت خارجية (.js) لتقليل حجم الطلبات والوقت
                    val isJs = path.endsWith(".js", true) || reqUrl.contains("/js/")

                    if (isImage || isFont || isJs) {
                        return WebResourceResponse("text/plain", "UTF-8", ByteArrayInputStream(ByteArray(0)))
                    }

                    // حظر أي اتصالات خارجية خارج النطاقات الحيوية لعمل الإضافة
                    if (!host.contains("freex2line.online") && !host.contains("cimanow.cc")) {
                        return WebResourceResponse("text/plain", "UTF-8", ByteArrayInputStream(ByteArray(0)))
                    }

                    try {
                        return kotlinx.coroutines.runBlocking {
                            val originalHeaders = request.requestHeaders ?: emptyMap()
                            val mergedHeaders = originalHeaders.toMutableMap()

                            mergedHeaders["X-Requested-With"] = "mark.via.gp"

                            val cookiesVal = CookieManager.getInstance().getCookie(reqUrl)
                            if (!cookiesVal.isNullOrBlank()) {
                                mergedHeaders["Cookie"] = cookiesVal
                            }

                            // التقاط الهيدرات الأصلية لطلب صفحة blog-post لاستخدامها في البايلود لاحقاً
                            if (reqUrl.contains("blog-post.html")) {
                                originalHeaders.forEach { (k, v) ->
                                    blogPostHeaders[k] = v
                                }
                            }

                            writeToLog("🌐 [$method] -> $reqUrl")
                            writeToLog("   📋 Headers: $mergedHeaders")

                            // قراءة كاش الملفات وإرجاع الـ MIME-Type الحقيقي والترميز الحقيقي للمتصفح دون إجبار text/html
                            if (inMemoryCache.containsKey(reqUrl)) {
                                val entry = inMemoryCache[reqUrl]!!
                                val cacheHeaders = mutableMapOf(
                                    "Cache-Control" to "max-age=86400, public, immutable",
                                    "Access-Control-Allow-Origin" to "*"
                                )
                                return@runBlocking WebResourceResponse(
                                    entry.mimeType, entry.encoding, 200, "OK", cacheHeaders, ByteArrayInputStream(entry.bytes)
                                )
                            }

                            val response = com.lagradost.cloudstream3.app.get(
                                url = reqUrl,
                                headers = mergedHeaders,
                                allowRedirects = true
                            )

                            val rawBody = response.okhttpResponse.body ?: return@runBlocking null
                            val contentType = response.headers["Content-Type"] ?: response.headers["content-type"] ?: "text/html"
                            val mimeType = contentType.substringBefore(";").trim()
                            val encoding = if (contentType.contains("charset=")) contentType.substringAfter("charset=").substringBefore(";").trim() else "utf-8"

                            val bytes = rawBody.bytes()

                            // حفظ الملف بنوعه الأصلي (سواء html أو css) لمنع حظر الموارد الخاصة بفك التحدي
                            if (response.code == 200 && (mimeType.contains("html") || mimeType.contains("css"))) {
                                inMemoryCache[reqUrl] = CacheEntry(bytes, mimeType, encoding)
                            }

                            val responseHeaders = response.headers.toMultimap().mapValues { it.value.joinToString(", ") }.toMutableMap()
                            responseHeaders["Cache-Control"] = "max-age=86400, public, immutable"
                            responseHeaders["Pragma"] = "cache"

                            WebResourceResponse(
                                mimeType,
                                encoding,
                                response.code,
                                "OK",
                                responseHeaders,
                                ByteArrayInputStream(bytes)
                            )
                        }
                    } catch (e: Exception) {
                        writeToLog("❌ [FAILED] GET -> $reqUrl | Error: ${e.message}")
                        return null
                    }
                }

                return super.shouldInterceptRequest(view, request)
            }
        }

        CoroutineScope(Dispatchers.IO).launch {
            try {
                writeToLog("🔄 [PRE-FLIGHT] جاري محاكاة الطلب الخلفي لجلب الكوكيز والجلسة...")

                val response = com.lagradost.cloudstream3.app.get(
                    url = targetUrl,
                    headers = mapOf(
                        "User-Agent" to "Mozilla/5.0",
                        "X-Requested-With" to "XMLHttpRequest",
                        "Accept" to "*/*"
                    ),
                    allowRedirects = true
                )

                val setCookieHeaders = response.headers.values("Set-Cookie")
                var phpsessidFound = false
                val cookieManager = CookieManager.getInstance()

                for (cookie in setCookieHeaders) {
                    if (cookie.contains("PHPSESSID")) {
                        cookieManager.setCookie("https://freex2line.online", cookie)
                        cookieManager.setCookie("https://rm.freex2line.online", cookie)
                        phpsessidFound = true
                        writeToLog("🍪 [SESSION OK] تم دمج الكوكيز وحقن الجلسة: ${cookie.substringBefore(";")}")
                    }
                }
                cookieManager.flush()

                if (!phpsessidFound) {
                    writeToLog("⚠️ [WARNING] لم يتم العثور على جلسة PHPSESSID في الطلب الأولي.")
                }

                // توجيه المتصفح لفتح صفحة العداد مباشرة مع تزويد الـ Referer والـ mark.via.gp
                withContext(Dispatchers.Main) {
                    writeToLog("🚀 [START] فتح صفحة العداد مباشرة وتمرير referer الـ redirectingfree...")

                    val directUrl = "https://rm.freex2line.online/2020/02/blog-post.html/"
                    val directHeaders = mapOf(
                        "X-Requested-With" to "mark.via.gp",
                        "Referer" to "https://rm.freex2line.online/redirectingfree/"
                    )
                    wv.loadUrl(directUrl, directHeaders)
                }

            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    writeToLog("❌ [PRE-FLIGHT ERROR] فشل التوجيه التمهيدي: ${e.message}")
                    // خطة بديلة للفتح العادي للرابط الرئيسي إذا فشل جلب الجلسة مسبقاً
                    wv.loadUrl(targetUrl, extraHeaders)
                }
            }
        }
    }

    // ==========================================
    // دالة تخطي حماية واختصار موقع Freex2line
    // ==========================================
    private suspend fun resolveFreex2line(url: String, webViewHtml: String?, context: Context?): String? {
        val resolverTag = "Freex2lineResolver"

        showLogView(true)
        showToast("جاري فك التشفير برمجياً...")
        logInfo(resolverTag, "======= [STARTING RESOLVER - BYPASS] =======")

        try {
            val userAgent = "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36"
            val mainReferer = "https://rm.freex2line.online/"
            val sessionCookies = mutableMapOf<String, String>()

            // [1/6] مزامنة الكوكيز النشطة من WebView مباشرة لتجنب كشف البوتات
            logInfo(resolverTag, "[1/6] Extracting authenticated cookies from WebView...")
            val cookiesString = CookieManager.getInstance().getCookie("https://rm.freex2line.online")
            if (!cookiesString.isNullOrBlank()) {
                cookiesString.split(";").forEach { cookie ->
                    val parts = cookie.split("=")
                    if (parts.size >= 2) {
                        sessionCookies[parts[0].trim()] = parts[1].trim()
                    }
                }
                logInfo(resolverTag, "   -> Active Session Cookies Loaded: $sessionCookies")
            } else {
                logInfo(resolverTag, "   -> Fallback: Fetching session cookie via back request...")
                val headResponse = com.lagradost.cloudstream3.app.get(url, headers = mapOf("User-Agent" to userAgent, "Referer" to mainReferer))
                sessionCookies.putAll(headResponse.cookies)
            }

            // [2/6] استخدام كود الصفحة المفكوك من WebView مباشرة لمنع حظر الطلبات الإضافية
            logInfo(resolverTag, "[2/6] Accessing HTML page data...")
            val html = if (!webViewHtml.isNullOrBlank()) {
                logInfo(resolverTag, "   -> Successfully loaded decrypted WebView HTML source.")
                webViewHtml
            } else {
                logInfo(resolverTag, "   -> Fallback: Fetching fresh HTML via OkHttp...")
                val pageUrl = "https://rm.freex2line.online/2020/02/blog-post.html/"
                val res = com.lagradost.cloudstream3.app.get(pageUrl, headers = mapOf("User-Agent" to userAgent, "Referer" to mainReferer), cookies = sessionCookies)
                sessionCookies.putAll(res.cookies)
                res.text
            }

            logInfo(resolverTag, "[3/6] Analyzing dynamic mapping...")
            // استخدام تعبيرات نمطية مرنة تدعم علامات الاقتباس الفردية والمزدوجة والمسافات
            val ctxName = extractGroup("""window\.ptr_[a-zA-Z0-9_]+\s*=\s*['"]([^'"]+)['"]""", html, "Pointer (ptr_) not found")
            val mapData = extractGroup("""window\.map_[a-zA-Z0-9_]+\s*=\s*\{([^}]+)\}""", html, "Map (map_) not found")
            val ctxData = extractGroup("""window\[['"]$ctxName['"]\]\s*=\s*\{([^}]+)\}""", html, "Context data not found")

            val chK = extractGroup("""ch:\s*['"]([^'"]+)['"]""", mapData, "Key 'ch' not found in map")
            val riK = extractGroup("""ri:\s*['"]([^'"]+)['"]""", mapData, "Key 'ri' not found in map")
            val keK = extractGroup("""ke:\s*['"]([^'"]+)['"]""", mapData, "Key 'ke' not found in map")
            val seK = extractGroup("""se:\s*['"]([^'"]+)['"]""", mapData, "Key 'se' not found in map")

            logInfo(resolverTag, "[4/6] Extracting real dynamic values...")
            val ch = extractGroup("""['"]$chK['"]:\s*['"]([^'"]+)['"]""", ctxData, "Value for 'ch' not found")
            val requestId = extractGroup("""['"]$riK['"]:\s*['"]([^'"]+)['"]""", ctxData, "Value for request_id not found")
            val encryptedKeyB64 = extractGroup("""['"]$keK['"]:\s*['"]([^'"]+)['"]""", ctxData, "Value for encrypted key not found")
            val sXorKey = extractGroup("""['"]$seK['"]:\s*['"]([^'"]+)['"]""", ctxData, "Value for XOR key not found")

            logInfo(resolverTag, "[5/6] Decrypting secret key...")
            val encryptedBytes = Base64.getDecoder().decode(encryptedKeyB64.replace(Regex("[\\s\\r\\n]"), ""))
            val secretKeyBuilder = StringBuilder()

            for (i in encryptedBytes.indices) {
                val xorCharCode = sXorKey[i % sXorKey.length].code
                val decryptedChar = (encryptedBytes[i].toInt() xor xorCharCode).toChar()
                secretKeyBuilder.append(decryptedChar)
            }
            val secretKey = secretKeyBuilder.toString()
            logDebug(resolverTag, "   Dynamic Secret Key: $secretKey")

            logInfo(resolverTag, "[6/6] Generating HMAC signature...")
            val fpBase64 = "TW96aWxsYS81LjEw" // تم التحديث بناءً على بصمة الـ fp المكتشفة لديك (Mozilla/5.10)
            val messageToSign = requestId + ch + fpBase64
            val hmacToken = calculateHmacSha256(messageToSign, secretKey)

            logInfo(resolverTag, "Waiting for 10 seconds (Server-side timer)...")
            delay(10000)

            logInfo(resolverTag, "Sending final API POST request...")
            val apiUrl = "https://rm.freex2line.online/2020/02/blog-post.html/get-link.php"

            // استنساخ كامل هيدرات المتصفح الحقيقية الملتقطة وتعديلها لتناسب طلب الـ POST
            val finalHeaders = mutableMapOf<String, String>()
            blogPostHeaders.forEach { (k, v) ->
                finalHeaders[k] = v
            }

            // ترويسات طلب POST الإلزامية والمنسقة
            finalHeaders["Content-Type"] = "application/x-www-form-urlencoded"
            finalHeaders["Cache-Control"] = "max-age=0"
            finalHeaders["Referer"] = "https://rm.freex2line.online/2020/02/blog-post.html/"

            // بناء بايلود الـ POST البرمجي بشكل آمن وذاتي التشفير (UrlEncoded) عبر OkHttp
            val payloadData = mapOf(
                "request_id" to requestId,
                "hmac_token" to hmacToken,
                "ch" to ch,
                "fp" to fpBase64
            )

            // طباعة تفاصيل الاتصال المطلوب عرضها في الـ log
            logInfo(resolverTag, "--- [API POST REQUEST DETAILS] ---")
            logInfo(resolverTag, "url = $apiUrl")
            logInfo(resolverTag, "headers = $finalHeaders")
            logInfo(resolverTag, "cookies = $sessionCookies")
            logInfo(resolverTag, "data = $payloadData")
            logInfo(resolverTag, "---------------------------------")

            // إرسال الطلب النهائي بصيغة POST المشفرة
            val finalRes = com.lagradost.cloudstream3.app.post(
                url = apiUrl,
                headers = finalHeaders,
                cookies = sessionCookies,
                data = payloadData
            )

            val finalResult = finalRes.text.trim()

            if (finalResult.startsWith("http")) {
                logInfo(resolverTag, "[SUCCESS] Watch page URL obtained: $finalResult")
                return finalResult
            } else {
                logError(resolverTag, "[FAILURE] Server did not return a valid URL. Response: $finalResult")
            }

        } catch (e: Exception) {
            logError(resolverTag, "[FATAL ERROR] Exception occurred during resolution", e)
        }

        logInfo(resolverTag, "======= [RESOLVER FINISHED - FAILED] =======")
        return null
    }

    private fun extractGroup(regex: String, text: String, errorMsg: String): String {
        return Regex(regex).find(text)?.groupValues?.get(1)
            ?: throw Exception(errorMsg)
    }

    private fun calculateHmacSha256(message: String, secret: String): String {
        val hashingAlg = "HmacSHA256"
        val keySpec = SecretKeySpec(secret.toByteArray(), hashingAlg)
        val mac = Mac.getInstance(hashingAlg)
        mac.init(keySpec)
        val bytes = mac.doFinal(message.toByteArray())
        return Base64.getEncoder().encodeToString(bytes).replace("\n", "").replace("\r", "")
    }

    override fun onDestroyView() {
        super.onDestroyView()
        try {
            inMemoryCache.clear()
            blogPostHeaders.clear()
            CookieManager.getInstance().flush()
            webView?.destroy()
            webView = null
            backFloatingBtn = null
            logToggleBtn = null
        } catch (_: Exception) {}
    }
}