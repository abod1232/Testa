package com.yacin

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
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
            val fullKey = (baseKey + tHeader).toByteArray(Charsets.UTF_8)
            val decodedBytes = Base64.decode(encryptedText.trim(), Base64.DEFAULT)
            val result = ByteArray(decodedBytes.size)
            for (i in decodedBytes.indices) {
                result[i] = (decodedBytes[i].toInt() xor fullKey[i % fullKey.size].toInt()).toByte()
            }
            String(result, Charsets.UTF_8)
        } catch (e: Exception) {
            Log.e(TAG, "[Decrypt] فشل فك التشفير", e)
            ""
        }
    }

    private suspend fun fetchYacine(path: String): YacineResponse? = withContext(Dispatchers.IO) {
        val endpoints = listOf(mainUrl, fallbackUrl)
        for (baseUrl in endpoints) {
            val cleanBase = baseUrl.trimEnd('/')
            val cleanPath = path.trimStart('/')
            val fullUrl = "$cleanBase/$cleanPath"

            Log.i(TAG, "[Fetch] طلب: $fullUrl")

            try {
                val request = Request.Builder()
                    .url(fullUrl)
                    .header("User-Agent", "okhttp/4.12.0")
                    .header("Accept", "application/json")
                    .build()

                client.newCall(request).execute().use { response ->
                    val statusCode = response.code
                    if (response.isSuccessful) {
                        val body = response.body?.string() ?: ""
                        val tHeader = response.header("t") ?: ""

                        if (body.isEmpty()) return@use null

                        val decryptedJson = decrypt(body, tHeader)
                        if (decryptedJson.isNotEmpty()) {
                            val parsed = parseJson<YacineResponse>(decryptedJson)
                            Log.i(TAG, "[Fetch] تم جلب (${parsed.data?.size ?: 0}) عنصر بنجاح من: $fullUrl")
                            return@withContext parsed
                        }
                    } else {
                        Log.w(TAG, "[Fetch] فشل الطلب للرابط $fullUrl بكود: $statusCode")
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "[Fetch] خطأ أثناء جلب الرابط ($fullUrl): ${e.message}", e)
            }
        }
        null
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse = withContext(Dispatchers.IO) {
        Log.i(TAG, "=== [getMainPage] بدء التحميل ===")
        val categories = fetchYacine("categories")?.data ?: emptyList()
        Log.d(TAG, "[getMainPage] تم العثور على (${categories.size}) قسم")

        val homePageLists = categories.map { cat ->
            async {
                val channels = fetchYacine("categories/${cat.id}/channels")?.data ?: emptyList()
                if (channels.isEmpty()) return@async null

                val channelItems = channels.map { chan ->
                    val data = LinkData(chan.id ?: "", chan.name ?: "Unknown", chan.logo).toJson()
                    newLiveSearchResponse(chan.name ?: "Unknown", data, TvType.Live) {
                        this.posterUrl = chan.logo
                    }
                }
                HomePageList(cat.name ?: "Category", channelItems)
            }
        }.awaitAll().filterNotNull()

        newHomePageResponse(homePageLists)
    }

    override suspend fun search(query: String): List<SearchResponse> = withContext(Dispatchers.IO) {
        val categories = fetchYacine("categories")?.data ?: emptyList()
        val deferredList = categories.map { cat ->
            async {
                val channels = fetchYacine("categories/${cat.id}/channels")?.data ?: emptyList()
                channels.filter { it.name?.contains(query, ignoreCase = true) == true }.map { chan ->
                    val data = LinkData(chan.id ?: "", chan.name ?: "Unknown", chan.logo).toJson()
                    newLiveSearchResponse(chan.name ?: "Unknown", data, TvType.Live) {
                        this.posterUrl = chan.logo
                    }
                }
            }
        }
        deferredList.awaitAll().flatten()
    }

    override suspend fun load(url: String): LoadResponse {
        val data = parseJson<LinkData>(url)
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
        val linkData = try {
            parseJson<LinkData>(data)
        } catch (e: Exception) {
            return@withContext false
        }

        val responseData = fetchYacine("channel/${linkData.id}")
        val streams = responseData?.data ?: return@withContext false

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
            }
        }
        true
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class YacineResponse(
        @JsonProperty("data") val data: List<YacineData>? = null
    )

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class YacineData(
        @JsonProperty("id") val id: String? = null, // تم تغييره إلى String لحل الـ Overflow
        @JsonProperty("name") val name: String? = null,
        @JsonProperty("logo") val logo: String? = null,
        @JsonProperty("url") val url: String? = null,
        @JsonProperty("headers") val headers: Map<String, Any>? = null
    )
}
