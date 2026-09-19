package com.yacin

import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.AppUtils.parseJson
import com.lagradost.cloudstream3.utils.AppUtils.toJson
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import android.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

class YacineTVProvider : MainAPI() {
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
        } catch (e: Exception) { "" }
    }

    private suspend fun fetchYacine(path: String): YacineResponse? = withContext(Dispatchers.IO) {
        val endpoints = listOf(mainUrl, fallbackUrl)
        for (baseUrl in endpoints) {
            try {
                val cleanBase = baseUrl.trimEnd('/')
                val cleanPath = path.trimStart('/')
                val fullUrl = "$cleanBase/$cleanPath"

                val request = Request.Builder()
                    .url(fullUrl)
                    .header("User-Agent", "okhttp/4.12.0")
                    .header("Accept", "application/json")
                    .build()

                val response = client.newCall(request).execute()
                if (response.isSuccessful) {
                    val body = response.body?.string() ?: ""
                    val tHeader = response.header("t") ?: ""
                    val decryptedJson = decrypt(body, tHeader)
                    return@withContext parseJson<YacineResponse>(decryptedJson)
                }
            } catch (e: Exception) { 
                continue 
            }
        }
        null
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse = withContext(Dispatchers.IO) {
        val categories = fetchYacine("categories")?.data ?: emptyList()

        // جلب قنوات الأقسام بالتوازي لتفادي الـ Timeout
        val homePageLists = categories.map { cat ->
            async {
                val channels = fetchYacine("categories/${cat.id}/channels")?.data ?: emptyList()
                if (channels.isEmpty()) return@async null

                val channelItems = channels.map { chan ->
                    val data = LinkData(chan.id.toString(), chan.name ?: "Unknown", chan.logo).toJson()
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
                    val data = LinkData(chan.id.toString(), chan.name ?: "Unknown", chan.logo).toJson()
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
        // استخدام دالة البث المباشر بدلاً من Movie
        return newLiveStreamLoadResponse(
            data.name,
            url,
            TvType.Live,
            url
        ) {
            this.posterUrl = data.poster
            this.plot = "بث مباشر لقناة ${data.name}"
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean = withContext(Dispatchers.IO) {
        val linkData = parseJson<LinkData>(data)
        val responseData = fetchYacine("channel/${linkData.id}")
        val streams = responseData?.data ?: return@withContext false

        streams.forEach { stream ->
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
                    ExtractorLink(
                        source = this@YacineTVProvider.name,
                        name = stream.name ?: "بث مباشر",
                        url = finalUrl,
                        referer = streamHeaders["Referer"] ?: "",
                        quality = Qualities.Unknown.value,
                        type = ExtractorLinkType.M3U8,
                        headers = streamHeaders
                    )
                )
            }
        }
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
