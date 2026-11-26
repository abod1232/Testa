package com.yacin

import android.content.Context
import android.content.Intent
import com.lagradost.cloudstream3.CloudStreamApp.Companion.getKey
import com.lagradost.cloudstream3.CloudStreamApp.Companion.setKey
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.net.Uri
import android.os.Bundle
import android.util.Base64
import android.util.Log
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.annotation.JsonProperty

import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.bottomsheet.BottomSheetDialogFragment

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin
import com.lagradost.cloudstream3.utils.AppUtils.parseJson
import com.lagradost.cloudstream3.utils.AppUtils.toJson
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.newExtractorLink

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.withContext

import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.Socket
import java.net.URI
import java.security.SecureRandom
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.TimeUnit
import javax.net.ssl.HttpsURLConnection

@CloudstreamPlugin
class YacineTVPlugin : Plugin() {
    override fun load(context: Context) {
        registerMainAPI(YacineTVProvider())
        openSettings = { activityContext ->
            (activityContext as? AppCompatActivity)?.let { activity ->
                YacineSettings().show(activity.supportFragmentManager, "YacineSettings")
            }
        }
    }
}

class YacineSettings : BottomSheetDialogFragment() {
    private fun dp(dp: Int): Int = (dp * resources.displayMetrics.density + 0.5f).toInt()

    override fun onStart() {
        super.onStart()
        val dialog = dialog as? BottomSheetDialog
        dialog?.findViewById<View>(com.google.android.material.R.id.design_bottom_sheet)?.let { sheet ->
            sheet.background = null
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        val ctx = requireContext()
        val root = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(12), dp(20), dp(28))
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#181926"))
                cornerRadii = floatArrayOf(
                    dp(24).toFloat(), dp(24).toFloat(),
                    dp(24).toFloat(), dp(24).toFloat(),
                    0f, 0f, 0f, 0f
                )
            }
        }
        root.addView(View(ctx).apply {
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#45475A"))
                cornerRadius = dp(3).toFloat()
            }
            layoutParams = LinearLayout.LayoutParams(dp(44), dp(5)).apply {
                gravity = Gravity.CENTER_HORIZONTAL
                bottomMargin = dp(20)
            }
        })
        root.addView(TextView(ctx).apply {
            text = "مجتمع ودعم الإضافة"
            textSize = 19f
            setTypeface(null, Typeface.BOLD)
            setTextColor(Color.parseColor("#CAD3F5"))
            gravity = Gravity.CENTER
        })
        root.addView(TextView(ctx).apply {
            text = "تابع التحديثات وانضم لمجتمع النقاش والمطورين"
            textSize = 12.5f
            setTextColor(Color.parseColor("#8087A2"))
            gravity = Gravity.CENTER
            setPadding(0, dp(4), 0, dp(22))
        })

        fun card(icon: String, color: String, t: String, d: String, url: String) =
            LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(16), dp(14), dp(16), dp(14))
                val shape = GradientDrawable().apply {
                    setColor(Color.parseColor("#24273A"))
                    cornerRadius = dp(16).toFloat()
                    setStroke(dp(1), Color.parseColor("#363A4F"))
                }
                background = RippleDrawable(ColorStateList.valueOf(Color.parseColor("#3B4252")), shape, shape)
                isClickable = true
                setOnClickListener {
                    try { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) } catch (_: Exception) {}
                }
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { bottomMargin = dp(12) }

                addView(TextView(ctx).apply {
                    text = icon; textSize = 18f; gravity = Gravity.CENTER
                    background = GradientDrawable().apply {
                        setColor(Color.parseColor(color)); cornerRadius = dp(12).toFloat()
                    }
                    layoutParams = LinearLayout.LayoutParams(dp(44), dp(44)).apply { marginEnd = dp(14) }
                })
                addView(LinearLayout(ctx).apply {
                    orientation = LinearLayout.VERTICAL
                    layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                    addView(TextView(ctx).apply {
                        text = t; textSize = 15f; setTypeface(null, Typeface.BOLD)
                        setTextColor(Color.parseColor("#CAD3F5"))
                    })
                    addView(TextView(ctx).apply {
                        text = d; textSize = 12f; setTextColor(Color.parseColor("#A5ADCB"))
                        setPadding(0, dp(2), 0, 0)
                    })
                })
                addView(TextView(ctx).apply {
                    text = "↗"; textSize = 17f; setTextColor(Color.parseColor("#6E738D"))
                    setTypeface(null, Typeface.BOLD)
                })
            }

        root.addView(card("📧", "#229ED9", "قناة التليجرام", "أحدث الإصدارات", "https://t.me/cs3iq"))
        root.addView(card("☄️", "#5865F2", "سيرفر الديسكورد", "نقاشات المجتمع", "https://discord.gg/xY63qaQRw"))
        root.addView(card("🔗", "#313244", "مشروع GitHub", "المستودع المفتوح", "https://github.com/Abodabodd/re-3arabi"))
        return root
    }
}

class YacineTVProvider : MainAPI() {
    companion object {
        private const val FB_PROJECT_ID = "ycntv-7a08e"
        private const val FB_PROJECT_NUMBER = "692330584196"
        private const val FB_APP_ID = "1:692330584196:android:68ea9f0c920aa17904cad1"
        private const val FB_API_KEY = "AIzaSyDRKL14PPiXzk7qNUNLgV2IsjasxNpWLeU"
        private const val FB_PKG = "ver3.ycntivi.off"
        private const val FB_CERT = "E404353443FB03A54702D53E2C7563D791D92559"

        private const val PREF_SAVED_DOMAIN_KEY = "YACINE_SAVED_DOMAIN_URL"
        @Volatile private var cachedUrl: String = "https://def11.ycnapi.com/api"
        @Volatile private var cachedEtag: String? = null
        @Volatile private var cachedFid: String? = null
        @Volatile private var cachedToken: String? = null

        private const val LOG_IC = "YACINE_IC"
        private const val LOG_MAIN = "YACINE"
    }

    override var mainUrl = "https://def11.ycnapi.com/api"
    private val fallbackUrl = "https://deft.yacinelive.com/api"

    override var name = "Yacine TV"
    override val hasMainPage = true
    override var lang = "ar"
    override val supportedTypes = setOf(TvType.Live)

    private val baseKey = "c!xZj+N9&G@Ev@vw"
    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()
    
    private fun getSavedApiUrl(): String = try {
        getKey<String>(PREF_SAVED_DOMAIN_KEY) ?: cachedUrl
    } catch (e: Exception) { cachedUrl }

    private fun saveApiUrl(url: String) {
        cachedUrl = url
        try { setKey(PREF_SAVED_DOMAIN_KEY, url) } catch (_: Exception) {}
    }

    data class LinkData(val id: String, val name: String, val poster: String?)

    private fun generateFid(): String {
        val randomBytes = ByteArray(17)
        SecureRandom().nextBytes(randomBytes)
        randomBytes[0] = ((randomBytes[0].toInt() and 0x0F) or 0x70).toByte()
        return Base64.encodeToString(randomBytes, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING).take(22)
    }

    private fun getFirebaseToken(fid: String): String? {
        val url = "https://firebaseinstallations.googleapis.com/v1/projects/$FB_PROJECT_ID/installations"
        val jsonBody = """{"fid":"$fid","appId":"$FB_APP_ID","authVersion":"FIS_v2","sdkVersion":"a:18.0.0"}"""
        val req = Request.Builder().url(url)
            .header("Content-Type", "application/json")
            .header("X-Android-Package", FB_PKG)
            .header("X-Android-Cert", FB_CERT)
            .header("x-goog-api-key", FB_API_KEY)
            .header("x-firebase-client", "H4sIAAAAAAAA_6tWykhNLCpJSk0sKVayio7VUSpLLSrOzM9TslIyUqoFAFyivEQfAAAA")
            .header("User-Agent", "Dalvik/2.1.0 (Linux; U; Android 16; RMX5061 Build/BP2A.250605.015)")
            .post(jsonBody.toRequestBody("application/json".toMediaType()))
            .build()
        return try {
            client.newCall(req).execute().use { res ->
                if (res.isSuccessful) parseJson<FirebaseInstallationResponse>(res.body?.string() ?: "").authToken?.token
                else null
            }
        } catch (_: Exception) { null }
    }

    private suspend fun syncDynamicApiUrl(): String = withContext(Dispatchers.IO) {
        var fid = cachedFid
        var token = cachedToken
        if (fid.isNullOrEmpty() || token.isNullOrEmpty()) {
            fid = generateFid(); token = getFirebaseToken(fid)
            if (token != null) { cachedFid = fid; cachedToken = token }
        }
        if (token.isNullOrEmpty() || fid.isNullOrEmpty()) return@withContext getSavedApiUrl()

        val iso = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US).apply {
            timeZone = TimeZone.getTimeZone("UTC")
        }.format(Date())

        val url = "https://firebaseremoteconfig.googleapis.com/v1/projects/$FB_PROJECT_NUMBER/namespaces/firebase:fetch"
        val payload = """{"appVersion":"3.1","firstOpenTime":"$iso","timeZone":"Asia/Baghdad","appInstanceIdToken":"$token","languageCode":"ar-IQ","appBuild":"4","appInstanceId":"$fid","countryCode":"IQ","analyticsUserProperties":{},"appId":"$FB_APP_ID","platformVersion":"36","sdkVersion":"22.0.0","packageName":"$FB_PKG"}"""

        val rb = Request.Builder().url(url)
            .header("Content-Type", "application/json")
            .header("X-Goog-Api-Key", FB_API_KEY)
            .header("X-Android-Package", FB_PKG)
            .header("X-Android-Cert", FB_CERT)
            .header("X-Goog-Firebase-Installations-Auth", token)
            .header("X-Firebase-RC-Fetch-Type", "BASE/1")
            .header("User-Agent", "Dalvik/2.1.0 (Linux; U; Android 16; RMX5061 Build/BP2A.250605.015)")
            .post(payload.toRequestBody("application/json".toMediaType()))
        if (!cachedEtag.isNullOrEmpty()) rb.header("If-None-Match", cachedEtag!!)

        try {
            client.newCall(rb.build()).execute().use { res ->
                val et = res.header("ETag"); if (et != null) cachedEtag = et
                if (res.isSuccessful) {
                    val cfg = parseJson<RemoteConfigResponse>(res.body?.string() ?: "")
                    if (cfg.state == "UPDATE") {
                        cfg.entries?.get("defaults")?.takeIf { it.isNotEmpty() }?.let {
                            val u = "https://$it/api"
                            saveApiUrl(u); return@withContext u
                        }
                    }
                }
            }
        } catch (_: Exception) {}
        getSavedApiUrl()
    }

    private fun decrypt(encryptedText: String, tHeader: String): String {
        return try {
            val fullKey = (baseKey + tHeader).toByteArray(Charsets.UTF_8)
            val decodedBytes = Base64.decode(encryptedText.trim(), Base64.DEFAULT)
            val result = ByteArray(decodedBytes.size)
            for (i in decodedBytes.indices) {
                result[i] = (decodedBytes[i].toInt() xor fullKey[i % fullKey.size].toInt()).toByte()
            }
            String(result, Charsets.UTF_8)
        } catch (_: Exception) { "" }
    }

    private fun executeYacineRequest(baseUrl: String, path: String): YacineResponse? {
        val fullUrl = "${baseUrl.trimEnd('/')}/${path.trimStart('/')}"
        return try {
            val req = Request.Builder().url(fullUrl)
                .header("User-Agent", "okhttp/4.12.0")
                .header("Accept", "application/json")
                .build()
            client.newCall(req).execute().use { r ->
                if (r.code == 200) {
                    val body = r.body?.string() ?: ""
                    val t = r.header("t") ?: ""
                    if (body.isNotEmpty()) {
                        val js = decrypt(body, t)
                        if (js.isNotEmpty()) return parseJson<YacineResponse>(js)
                    }
                }
                null
            }
        } catch (_: Exception) { null }
    }

    private suspend fun fetchYacine(path: String): YacineResponse? = withContext(Dispatchers.IO) {
        val eps = listOf(getSavedApiUrl(), fallbackUrl).distinct()
        for (b in eps) {
            executeYacineRequest(b, path)?.let { saveApiUrl(b); return@withContext it }
        }
        val dyn = syncDynamicApiUrl()
        if (!eps.contains(dyn)) {
            executeYacineRequest(dyn, path)?.let { saveApiUrl(dyn); return@withContext it }
        }
        null
    }
    
    override fun getVideoInterceptor(extractorLink: ExtractorLink): Interceptor? {
    Log.d(LOG_IC, "========== getVideoInterceptor CALLED ==========")
    Log.d(LOG_IC, "  url = ${extractorLink.url}")
    Log.d(LOG_IC, "  headers = ${extractorLink.headers}")

    return Interceptor { chain ->
        Log.d(LOG_IC, ">>>>>>>>>> INTERCEPTOR START <<<<<<<<<<")
        val req = chain.request()
        Log.d(LOG_IC, "  req.url = ${req.url}")
        for ((k, v) in req.headers) {
            Log.d(LOG_IC, "  req.header[$k] = $v")
        }

        // احذف Referer المشوّه
        val newReq = req.newBuilder()
            .removeHeader("Referer")
            .removeHeader("referer")
            .build()

        Log.d(LOG_IC, "  after removeHeader, headers:")
        for ((k, v) in newReq.headers) {
            Log.d(LOG_IC, "    $k = $v")
        }
        Log.d(LOG_IC, ">>>>>>>>>> INTERCEPTOR PROCEEDING <<<<<<<<<<")

        chain.proceed(newReq)
    }
}
    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse = withContext(Dispatchers.IO) {
        val cats = fetchYacine("categories")?.data ?: emptyList()
        val lists = cats.map { cat ->
            async {
                val chans = fetchYacine("categories/${cat.id}/channels")?.data ?: emptyList()
                if (chans.isEmpty()) return@async null
                HomePageList(cat.name ?: "Category", chans.map { c ->
                    val d = LinkData(c.id ?: "", c.name ?: "Unknown", c.logo).toJson()
                    newLiveSearchResponse(c.name ?: "Unknown", d, TvType.Live) { this.posterUrl = c.logo }
                })
            }
        }.awaitAll().filterNotNull()
        newHomePageResponse(lists)
    }

    override suspend fun search(query: String): List<SearchResponse> = withContext(Dispatchers.IO) {
        val cats = fetchYacine("categories")?.data ?: emptyList()
        cats.map { cat ->
            async {
                val chans = fetchYacine("categories/${cat.id}/channels")?.data ?: emptyList()
                chans.filter { it.name?.contains(query, true) == true }.map { c ->
                    val d = LinkData(c.id ?: "", c.name ?: "Unknown", c.logo).toJson()
                    newLiveSearchResponse(c.name ?: "Unknown", d, TvType.Live) { this.posterUrl = c.logo }
                }
            }
        }.awaitAll().flatten()
    }

    override suspend fun load(url: String): LoadResponse {
        val d = parseJson<LinkData>(url)
        return newMovieLoadResponse(d.name, url, TvType.Live, url) {
            this.posterUrl = d.poster
            this.plot = "شاهد بث مباشر لقناة ${d.name}"
        }
    }
    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean = withContext(Dispatchers.IO) {
        Log.d(LOG_MAIN, "========== loadLinks START ==========")
        val linkData = try { parseJson<LinkData>(data) } catch (_: Exception) { return@withContext false }

        val streams = fetchYacine("channel/${linkData.id}")?.data ?: return@withContext false
        Log.d(LOG_MAIN, "streams.size=${streams.size}")

        streams.forEachIndexed { index, stream ->
            val rawUrl = stream.url?.replace("www.elahmad.coo", "www.elahmad.com") ?: ""
            if (rawUrl.isEmpty()) return@forEachIndexed

            val rawReferer = (stream.headers?.get("Referer") as? String)
                ?: "http://yacinneeee.live⁠‍⁠ㅤ‌⁠‍⁠"
            val ua = (stream.headers?.get("User-Agent") as? String)
                ?: "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/150.0.0.0 Safari/537.36"

            Log.d(LOG_MAIN, "  [$index] ${stream.name}")
            Log.d(LOG_MAIN, "    url=$rawUrl")
            Log.d(LOG_MAIN, "    referer.len=${rawReferer.toByteArray().size}")
            Log.d(LOG_MAIN, "    referer.hex=" +
                rawReferer.toByteArray().joinToString(" ") { String.format("%02X", it) })

            callback.invoke(
                newExtractorLink(
                    this@YacineTVProvider.name,
                    stream.name ?: "Server ${index + 1}",
                    rawUrl
                ) {
                    this.headers = mapOf(
                        "Referer" to rawReferer,
                        "User-Agent" to ua
                    )
                    this.quality = Qualities.Unknown.value
                }
            )
        }
        true
    }
    @JsonIgnoreProperties(ignoreUnknown = true)
    data class FirebaseInstallationResponse(
        @JsonProperty("authToken") val authToken: AuthToken? = null
    ) {
        @JsonIgnoreProperties(ignoreUnknown = true)
        data class AuthToken(@JsonProperty("token") val token: String? = null)
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class RemoteConfigResponse(
        @JsonProperty("state") val state: String? = null,
        @JsonProperty("entries") val entries: Map<String, String>? = null,
        @JsonProperty("templateVersion") val templateVersion: String? = null
    )

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class YacineResponse(
        @JsonProperty("data") val data: List<YacineData>? = null
    )

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class YacineData(
        @JsonProperty("id") val id: String? = null,
        @JsonProperty("name") val name: String? = null,
        @JsonProperty("logo") val logo: String? = null,
        @JsonProperty("url") val url: String? = null,
        @JsonProperty("headers") val headers: Map<String, Any>? = null
    )
}
