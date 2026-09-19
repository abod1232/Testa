package com.yacin

import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.AppUtils.parseJson
import com.lagradost.cloudstream3.utils.AppUtils.toJson
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.newExtractorLink
import android.util.Base64
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

class YacineTVProvider : MainAPI() {
    companion object {
        private const val TAG = "YacineTVProvider"
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

    data class LinkData(
        val id: String,
        val name: String,
        val poster: String?
    )

    private fun decrypt(encryptedText: String, tHeader: String): String {
        return try {
            Log.d(TAG, "[Decrypt] بدء فك التشفير | النص المشفر (أول 30 حرف): ${encryptedText.take(30)}... | tHeader: $tHeader")
            val fullKey = (baseKey + tHeader).toByteArray(Charsets.UTF_8)
            val decodedBytes = Base64.decode(encryptedText.trim(), Base64.DEFAULT)
            val result = ByteArray(decodedBytes.size)
            for (i in decodedBytes.indices) {
                result[i] = (decodedBytes[i].toInt() xor fullKey[i % fullKey.size].toInt()).toByte()
            }
            val decryptedString = String(result, Charsets.UTF_8)
            Log.d(TAG, "[Decrypt] نجح فك التشفير | الناتج (أول 60 حرف): ${decryptedString.take(60)}...")
            decryptedString
        } catch (e: Exception) {
            Log.e(TAG, "[Decrypt] فشل فك التشفير!", e)
            ""
        }
    }

    private suspend fun fetchYacine(path: String): YacineResponse? = withContext(Dispatchers.IO) {
        val endpoints = listOf(mainUrl, fallbackUrl)
        for (baseUrl in endpoints) {
            val cleanBase = baseUrl.trimEnd('/')
            val cleanPath = path.trimStart('/')
            val fullUrl = "$cleanBase/$cleanPath"

            Log.i(TAG, "[Fetch] إرسال طلب إلى: $fullUrl")

            try {
                val request = Request.Builder()
                    .url(fullUrl)
                    .header("User-Agent", "okhttp/4.12.0")
                    .header("Accept", "application/json")
                    .build()

                val response = client.newCall(request).execute()
                val statusCode = response.code
                Log.d(TAG, "[Fetch] كود الاستجابة: $statusCode للرابط: $fullUrl")

                if (response.isSuccessful) {
                    val body = response.body?.string() ?: ""
                    val tHeader = response.header("t") ?: ""
                    
                    if (body.isEmpty()) {
                        Log.w(TAG, "[Fetch] محتوى الاستجابة فارغ من: $fullUrl")
                        continue
                    }

                    val decryptedJson = decrypt(body, tHeader)
                    if (decryptedJson.isNotEmpty()) {
                        val parsed = parseJson<YacineResponse>(decryptedJson)
                        Log.i(TAG, "[Fetch] تم جلب وتحليل البيانات بنجاح | عدد العناصر: ${parsed.data?.size ?: 0}")
                        return@withContext parsed
                    } else {
                        Log.e(TAG, "[Fetch] فشل تحويل النص بعد فك التشفير إلى JSON")
                    }
                } else {
                    Log.w(TAG, "[Fetch] فشل الطلب بكود: $statusCode | رسالة: ${response.message}")
                }
            } catch (e: Exception) {
                Log.e(TAG, "[Fetch] خطأ أثناء جلب الرابط ($fullUrl): ${e.message}", e)
            }
        }
        Log.e(TAG, "[Fetch] فشلت جميع المحاولات لطلب المسار: $path")
        null
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse = withContext(Dispatchers.IO) {
        Log.i(TAG, "=== [getMainPage] بدء تحميل الصفحة الرئيسية ===")
        val categories = fetchYacine("categories")?.data ?: emptyList()
        Log.d(TAG, "[getMainPage] عدد الأقسام المستلمة: ${categories.size}")

        val homePageLists = categories.map { cat ->
            async {
                Log.d(TAG, "[getMainPage] جلب قنوات القسم: ${cat.name} (ID: ${cat.id})")
                val channels = fetchYacine("categories/${cat.id}/channels")?.data ?: emptyList()
                
                if (channels.isEmpty()) {
                    Log.w(TAG, "[getMainPage] لا توجد قنوات في القسم: ${cat.name}")
                    return@async null
                }

                Log.d(TAG, "[getMainPage] تم العثور على (${channels.size}) قناة في قسم [${cat.name}]")
                val channelItems = channels.map { chan ->
                    val data = LinkData(chan.id.toString(), chan.name ?: "Unknown", chan.logo).toJson()
                    newLiveSearchResponse(chan.name ?: "Unknown", data, TvType.Live) {
                        this.posterUrl = chan.logo
                    }
                }
                HomePageList(cat.name ?: "Category", channelItems)
            }
        }.awaitAll().filterNotNull()

        Log.i(TAG, "=== [getMainPage] اكتمل تجهيز الصفحة الرئيسية بإجمالي (${homePageLists.size}) قسم ===")
        newHomePageResponse(homePageLists)
    }

    override suspend fun search(query: String): List<SearchResponse> = withContext(Dispatchers.IO) {
        Log.i(TAG, "=== [Search] بدء البحث عن: '$query' ===")
        val categories = fetchYacine("categories")?.data ?: emptyList()

        val deferredList = categories.map { cat ->
            async {
                val channels = fetchYacine("categories/${cat.id}/channels")?.data ?: emptyList()
                channels.filter { it.name?.contains(query, ignoreCase = true) == true }.map { chan ->
                    Log.d(TAG, "[Search] تم العثور على قناة مطابقة: ${chan.name}")
                    val data = LinkData(chan.id.toString(), chan.name ?: "Unknown", chan.logo).toJson()
                    newLiveSearchResponse(chan.name ?: "Unknown", data, TvType.Live) {
                        this.posterUrl = chan.logo
                    }
                }
            }
        }
        val results = deferredList.awaitAll().flatten()
        Log.i(TAG, "=== [Search] اكتمل البحث: تم العثور على (${results.size}) نتيجة ===")
        results
    }

    override suspend fun load(url: String): LoadResponse {
        Log.i(TAG, "[Load] بدء تجهيز تفاصيل القناة للبيانات: $url")
        val data = parseJson<LinkData>(url)
        Log.d(TAG, "[Load] اسم القناة: ${data.name} | ID: ${data.id}")
        return newMovieLoadResponse(
            data.name,
            url,
            TvType.Live,
            url
        ) {
            this.posterUrl = data.poster
            this.plot = "شاهد بث مباشر لقناة ${data.name}"
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean = withContext(Dispatchers.IO) {
        Log.i(TAG, "=== [loadLinks] بدء استخراج روابط البث ===")
        val linkData = try {
            parseJson<LinkData>(data)
        } catch (e: Exception) {
            Log.e(TAG, "[loadLinks] فشل تحليل LinkData من: $data", e)
            return@withContext false
        }

        Log.d(TAG, "[loadLinks] جلب سيرفرات القناة: ${linkData.name} (ID: ${linkData.id})")
        val responseData = fetchYacine("channel/${linkData.id}")
        val streams = responseData?.data

        if (streams.isNullOrEmpty()) {
            Log.w(TAG, "[loadLinks] لم يتم العثور على أي سيرفر بث لهذه القناة!")
            return@withContext false
        }

        Log.i(TAG, "[loadLinks] تم استلام (${streams.size}) سيرفر بث للقناة")

        streams.forEachIndexed { index, stream ->
            val finalUrl = stream.url?.replace("www.elahmad.coo", "www.elahmad.com") ?: ""
            if (finalUrl.isNotEmpty()) {
                val streamHeaders = mutableMapOf<String, String>()
                stream.headers?.forEach { (key, value) ->
                    if (value is String) streamHeaders[key] = value
                }
                if (!streamHeaders.containsKey("User-Agent")) {
                    streamHeaders["User-Agent"] = "okhttp/4.12.0"
                }

                Log.d(TAG, "[loadLinks] إرسال رابط السيرفر [$index]: ${stream.name ?: "Server"} | URL: $finalUrl | Headers: $streamHeaders")

                callback.invoke(
                    newExtractorLink(
                        this@YacineTVProvider.name,
                        stream.name ?: "Server ${index + 1}",
                        finalUrl
                    ) {
                        this.headers = streamHeaders
                        this.quality = Qualities.Unknown.value
                        this.referer = streamHeaders["Referer"] ?: ""
                    }
                )
            } else {
                Log.w(TAG, "[loadLinks] تم تخطي السيرفر [$index] لأن الرابط فارغ!")
            }
        }
        Log.i(TAG, "=== [loadLinks] اكتمل استخراج الروابط بنجاح ===")
        true
    }

    data class YacineResponse(
        @JsonProperty("data") val data: List<YacineData>? = null
    )

    data class YacineData(
        @JsonProperty("id") val id: Int? = null,
        @JsonProperty("name") val name: String? = null,
        @JsonProperty("logo") val logo: String? = null,
        @JsonProperty("url") val url: String? = null,
        @JsonProperty("headers") val headers: Map<String, Any>? = null
    )
}
