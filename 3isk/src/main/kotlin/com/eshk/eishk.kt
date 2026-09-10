package com.eshk

import android.util.Base64
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import java.text.SimpleDateFormat
import java.util.*
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

class eishk : MainAPI() {
    override var mainUrl = "https://gateway.anime-rift.com"
    override var name = "أنمي ريفت"
    override val hasMainPage = true
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(TvType.Anime)

    private val JWT_SECRET = "e6c9e1af5c6e3e1f0664947361d954e7446d56dc9a7aa9ae4a62df8d7b919cd1"
    private val FIREBASE_API_KEY = "AIzaSyBiLkiGEm7ruugny3tDFHEZvqli8yv1k7I"
    private val FIREBASE_APP_ID = "1:536921039715:android:78825c96b74de921b8e956"
    private val ANDROID_PACKAGE = "com.riftapps.animerift"
    private val ANDROID_CERT = "AF40CE82A52AA4107F311D8B9727D01C8D02250B"

    private lateinit var fid: String
    private lateinit var firebaseToken: String
    private lateinit var gatewayBaseUrl: String
    private var sessionKey: String? = null
    private var deviceId: String? = null

    private val mapper = ObjectMapper()
    private fun base64UrlEncode(bytes: ByteArray): String {
        return Base64.encodeToString(
            bytes,
            Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP
        )
    }

    private fun generateIntegrityToken(scope: String): String {
        val now = System.currentTimeMillis() / 1000
        val exp = now + 60
        val header = base64UrlEncode("""{"alg":"HS256","typ":"JWT"}""".toByteArray())
        val payload = base64UrlEncode("""{"scope":"$scope","exp":$exp,"iat":$now}""".toByteArray())
        val toSign = "$header.$payload"
        val secretKey = SecretKeySpec(JWT_SECRET.toByteArray(), "HmacSHA256")
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(secretKey)
        val signature = base64UrlEncode(mac.doFinal(toSign.toByteArray()))
        return "$toSign.$signature"
    }

    private fun generateFcmToken(): String {
        val chars = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789"
        return "APA91b" + (1..80).map { chars.random() }.joinToString("")
    }

    private fun generateDeviceTimezone(): String {
        val now = Date()
        val format = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US)
        val nano = Random().nextInt(999999).toString().padStart(6, '0')
        return "${format.format(now)}.$nano"
    }

    private fun gzipCompress(data: String): ByteArray {
        val bos = java.io.ByteArrayOutputStream()
        java.util.zip.GZIPOutputStream(bos).use { it.write(data.toByteArray()) }
        return bos.toByteArray()
    }

    private suspend fun ensureInitialized() {
        if (this::fid.isInitialized && this::gatewayBaseUrl.isInitialized) return
        withContext(Dispatchers.IO) {
            registerFirebaseInstallation()
            fetchRemoteConfig()
            registerDevice()
        }
    }

    private suspend fun registerFirebaseInstallation() {
        val url = "https://firebaseinstallations.googleapis.com/v1/projects/anime-rift-4142e/installations"
        val payloadStr = """{"fid":"","appId":"$FIREBASE_APP_ID","authVersion":"FIS_v2","sdkVersion":"a:19.1.0"}"""

        val request = okhttp3.Request.Builder()
            .url(url)
            .post(gzipCompress(payloadStr).toRequestBody("application/json; charset=UTF-8".toMediaType()))
            .addHeader("Content-Type", "application/json")
            .addHeader("Accept", "application/json")
            .addHeader("Content-Encoding", "gzip")
            .addHeader("Cache-Control", "no-cache")
            .addHeader("X-Android-Package", ANDROID_PACKAGE)
            .addHeader("X-Android-Cert", ANDROID_CERT)
            .addHeader("x-goog-api-key", FIREBASE_API_KEY)
            .addHeader("x-firebase-client", "H4sIAAAAAAAA_6tWykhNLCpJSk0sKVayio7VUSpLLSrOzM9TslIyUqoFAFyivEQfAAAA")
            .addHeader("User-Agent", "Dalvik/2.1.0 (Linux; U; Android 16; RMX5061 Build/BP2A.250605.015)")
            .build()

        val response = app.baseClient.newCall(request).execute()
        val body = response.body?.string() ?: throw Exception("No response from Firebase")
        val json = mapper.readTree(body)
        fid = json.get("fid").asText()
        firebaseToken = json.get("authToken").get("token").asText()
    }

    private suspend fun fetchRemoteConfig() {
        val url = "https://firebaseremoteconfig.googleapis.com/v1/projects/536921039715/namespaces/firebase:fetch"
        val payload = mapOf(
            "appVersion" to "3.13.5",
            "appInstanceIdToken" to firebaseToken,
            "appBuild" to "68",
            "appInstanceId" to fid,
            "analyticsUserProperties" to emptyMap<String, String>(),
            "appId" to FIREBASE_APP_ID,
            "platformVersion" to "36",
            "sdkVersion" to "23.0.1",
            "packageName" to ANDROID_PACKAGE
        )

        val headers = mapOf(
            "X-Goog-Api-Key" to FIREBASE_API_KEY,
            "X-Android-Package" to ANDROID_PACKAGE,
            "X-Android-Cert" to ANDROID_CERT,
            "X-Goog-Firebase-Installations-Auth" to firebaseToken,
            "Content-Type" to "application/json"
        )

        val json = app.post(url, headers = headers, json = payload).parsed<JsonNode>()
        gatewayBaseUrl = json.get("entries")?.get("anime_rift_android_gateway_base_url_v4")?.asText()?.replace("\"", "") ?: mainUrl
    }

    private suspend fun registerDevice() {
        val url = "$gatewayBaseUrl/auth/register/device"
        val fcmToken = generateFcmToken()
        deviceId = "$fid:$fcmToken"

        val deviceInfo = mapOf(
            "manufacturer" to "realme",
            "androidVersion" to "16",
            "sdkInt" to 36,
            "isPhysicalDevice" to true,
            "supportedAbis" to listOf("arm64-v8a"),
            "tags" to "release-keys",
            "type" to "user",
            "host" to "kvm-slave-build-s-system-12107393",
            
        )

        val payload = mapOf(
            "deviceId" to deviceId,
            "current_app_version" to "3.13.5",
            "device_os" to "android",
            "device_environment" to "production",
            "device_info" to mapper.writeValueAsString(deviceInfo),
            "install_source" to "IS_INSTALLED_FROM_PLAY_PACKAGE_INSTALLER",
            "deviceOsId" to "BP2A.250605.015",
            "firebaseInstallationId" to fid,
            "apn_token" to null,
            "install_mode" to 2
        )

        val json = apiCall(url, "USER.AUTH.DEVICE.REGISTER", "POST", payload)
        sessionKey = json.get("sessionKey")?.asText()
    }

    private suspend fun apiCall(url: String, scope: String, method: String = "GET", body: Map<String, Any?>? = null): JsonNode {
        if (!this::fid.isInitialized && !url.contains("installations")) ensureInitialized()
        val integrityToken = generateIntegrityToken(scope)
        val timezone = generateDeviceTimezone()

        val headers = mapOf(
            "x-device-os-id" to "BP2A.250605.015",
            "user-agent" to "Dart/3.10 (dart:io)",
            "x-device-release-version" to "3.13.5",
            "x-firebase-app-check" to "null",
            "authorization" to "Bearer null",
            "content-type" to "application/json; charset=UTF-8",
            "x-installation-source" to "IS_INSTALLED_FROM_PLAY_PACKAGE_INSTALLER",
            "integrity" to "Bearer $integrityToken",
            "accept" to "application/json",
            "x-firebase-id" to (if (this::fid.isInitialized) fid else ""),
            "x-device-id" to (deviceId ?: ""),
            "x-device-timezone" to timezone,
            "x-device-language" to "ar",
            "x-platform" to "Mobile",
            "x-os" to "android"
        )

        val response = when (method.uppercase()) {
            "POST" -> app.post(url, headers = headers, json = body)
            "PUT" -> app.put(url, headers = headers, json = body)
            else -> app.get(url, headers = headers)
        }
        return response.parsed<JsonNode>()
    }

    // 1. تعريف الفئات الرئيسية وروابط الفرز الخاصة بها
    override val mainPage = mainPageOf(
        "sort_by=recently_updated&sort_direction=-1&filter_by=recent_releases" to "الإصدارات الحديثة",
        "sort_by=popularity&sort_direction=-1&filter_by=all" to "الأكثر شعبية",
        "sort_by=rating&sort_direction=-1&filter_by=all" to "الأعلى تقييماً",
        "sort_by=created_at&sort_direction=-1&filter_by=all" to "أحدث الأنميات المضافة"
    )

    // 2. دالة جلب محتوى الفئات مع دعم التمرير اللانهائي (Pagination)
    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        ensureInitialized()
        
        // Cloudstream يبدأ الصفحات من 1 بينما السيرفر يبدأ من 0
        val apiPage = page - 1
        val filterQuery = request.data
        val url = "$gatewayBaseUrl/library/all?page=$apiPage&$filterQuery&text_direction=jp"
        
        val json = apiCall(
            url = url,
            scope = "ANIME.LIBRARY.ALL",
            method = "POST",
            body = mapOf("country_origin" to null)
        )

        val items = json.get("items")
        val animeList = mutableListOf<SearchResponse>()

        items?.forEach { item ->
            animeList.add(
                newAnimeSearchResponse(
                    name = item.get("title")?.asText() ?: "",
                    url = "$mainUrl/api/v4/library/details/${item.get("_id")?.asText()}"
                ) {
                    this.posterUrl = item.get("medium_picture")?.asText()
                    this.year = item.get("release_year")?.asInt()
                }
            )
        }

        // قراءة hasNext لمعرفة هل يسمح بالتمرير لصفحة جديدة أم لا
        val hasNext = json.get("hasNext")?.asBoolean() ?: false
        
        return newHomePageResponse(
            list = HomePageList(
                name = request.name,
                list = animeList,
                isHorizontal = true
            ),
            hasNext = hasNext
        )
    }

    override suspend fun search(query: String): List<SearchResponse> {
        ensureInitialized()
        val allResults = mutableListOf<SearchResponse>()
        var currentPage = 0
        var hasNext = true
        val maxPages = 4 // حد أقصى 4 صفحات (حتى 120 نتيجة بحث) لضمان السرعة

        while (hasNext && currentPage < maxPages) {
            val url = "$gatewayBaseUrl/library/search?page=$currentPage&sort_by=release_year&sort_direction=1&text_direction=jp"
            try {
                val json = apiCall(url, "ANIME.LIBRARY.SEARCH", method = "POST", body = mapOf("query" to query))
                val items = json.get("items") ?: break

                items.forEach { item ->
                    allResults.add(
                        newAnimeSearchResponse(
                            name = item.get("title")?.asText() ?: "",
                            url = "$mainUrl/api/v4/library/details/${item.get("_id")?.asText()}"
                        ) {
                            this.posterUrl = item.get("medium_picture")?.asText()
                            this.year = item.get("release_year")?.asInt()
                        }
                    )
                }

                // قراءة حقل hasNext من رد السيرفر لمعرفة هل توجد صفحات أخرى
                hasNext = json.get("hasNext")?.asBoolean() ?: false
                currentPage++
            } catch (e: Exception) {
                e.printStackTrace()
                break
            }
        }
        return allResults
    }

    override suspend fun load(url: String): LoadResponse {
        ensureInitialized()
        val animeId = url.substringAfterLast("/")
        val detailUrl = "$gatewayBaseUrl/library/details/$animeId"
        val json = apiCall(detailUrl, "ANIME.LIBRARY.DETAILS")
        val item = json.get("item") ?: throw ErrorLoadingException("Failed to load anime details")

        val allRelated = mutableListOf<AnimeSearchResponse>()
        val others = json.get("others")

        others?.get("recommendations")?.forEach { rec ->
            allRelated.add(
                newAnimeSearchResponse(
                    name = rec.get("title")?.asText() ?: "",
                    url = "$mainUrl/api/v4/library/details/${rec.get("_id")?.asText()}"
                ) {
                    this.posterUrl = rec.get("main_picture")?.asText()
                }
            )
        }
        val episodesList = mutableListOf<Episode>()
        try {
            val episodeUrl = "$gatewayBaseUrl/library/episodes/$animeId?sort_by_latest=1&with_arcs=true&with_favorites=true"
            val epJson = apiCall(episodeUrl, "ANIME.LIBRARY.EPISODES.ALL")
            epJson.get("items")?.forEach { ep ->
                val epNumber = ep.get("episode_number")?.asInt() ?: 1
                val epId = ep.get("_id")?.asText() ?: ""
                episodesList.add(
            newEpisode(data = "$animeId|$epId|$epNumber") {
                this.name = "الحلقة $epNumber"
                this.episode = epNumber
                this.season = 1
                this.posterUrl = ep.get("thumbnail")?.asText()
               }
              )
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }

        return newAnimeLoadResponse(
            name = item.get("title")?.asText() ?: "",
            url = url,
            type = TvType.Anime
        ) {
            this.posterUrl = item.get("main_picture")?.asText()
            this.plot = item.get("synopsis")?.asText()
            this.year = item.get("release_year")?.asInt()
            this.tags = item.get("genreLabels")?.mapNotNull { it.get("label")?.asText() }
            this.showStatus = when (item.get("release_status")?.asText()) {
                "on_going" -> ShowStatus.Ongoing
                "finished" -> ShowStatus.Completed
                else -> null
            }
            this.recommendations = allRelated
            addEpisodes(DubStatus.Subbed, episodesList)
        }
    }
    

   override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean = withContext(Dispatchers.IO) {
        try {
            val parts = data.split('|')
            if (parts.size < 2) return@withContext false
            val animeId = parts[0]
            val episodeId = parts[1]
            val episodeNumber = parts.getOrNull(2)?.toIntOrNull() ?: 1
            
            val sourcesUrl = "$gatewayBaseUrl/library/episode/sources"
            val sourcesBody = mapOf(
                "animeId" to animeId,
                "episodeId" to episodeId,
                "episode_number" to episodeNumber
            )
            val sourcesJson = apiCall(sourcesUrl, "ANIME.LIBRARY.EPISODES.SOURCES.ALL", method = "POST", body = sourcesBody)
            val items = sourcesJson.get("items") ?: return@withContext false
            val priorityProviders = listOf("cr2", "rift-streamer", "streamtape")
            val filteredItems = items.filter { src ->
                val subTitle = src.get("sub_title")?.asText() ?: ""
                subTitle.startsWith("ar_") || subTitle.isEmpty()
            }.ifEmpty { items.toList() }

            val sortedItems = filteredItems.sortedBy { src ->
                val provider = src.get("provider")?.asText() ?: ""
                val index = priorityProviders.indexOf(provider)
                if (index != -1) index else 99
            }.take(6)

            sortedItems.forEach { src ->
                val hostId = src.get("_id")?.asText() ?: return@forEach
                val serverName = src.get("server_name")?.asText() ?: "Server"
                val provider = src.get("provider")?.asText() ?: ""
                val subTitle = src.get("sub_title")?.asText() ?: ""
                val qualitiesNode = src.get("qualities")
                val qualitiesList = if (qualitiesNode != null && qualitiesNode.isArray && qualitiesNode.size() > 0) {
                    qualitiesNode.map { it.asText() }
                } else {
                    listOf("720P")
                }
                for (quality in qualitiesList) {
                    try {
                        val canPlayUrl = "$gatewayBaseUrl/library/episode/source/can_play"
                        val canPlayBody = mapOf(
                            "episodeId" to episodeId,
                            "hostId" to hostId,
                            "is_download" to false,
                            "event_name" to "play_episode_unlocked"
                        )
                        val canPlayJson = apiCall(canPlayUrl, "ANIME.LIBRARY.EPISODES.SOURCES.CHECK_AVAILABILITY", method = "POST", body = canPlayBody)
                        val sessionId = canPlayJson.get("sessionId")?.asText() ?: ""
                        
                        val claimUrl = "$gatewayBaseUrl/ads_manager/claim"
                        val claimBody = mapOf(
                            "event_name" to "play_episode_unlocked",
                            "hostId" to hostId,
                            "episodeId" to episodeId,
                            "transactionRef" to "${System.currentTimeMillis()}_${Random().nextInt(99999999)}",
                            "reward_result" to "not_filled",
                            "streamingServerKey" to provider,
                            "is_optional" to false,
                            "is_reward" to true
                        )
                        try {
                            apiCall(claimUrl, "USER.ADS_MANAGER.CLAIMS", method = "PUT", body = claimBody)
                        } catch (_: Exception) {}
override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean = withContext(Dispatchers.IO) {
        try {
            val parts = data.split('|')
            if (parts.size < 2) return@withContext false
            val animeId = parts[0]
            val episodeId = parts[1]
            val episodeNumber = parts.getOrNull(2)?.toIntOrNull() ?: 1
            
            // 1. طلب قائمة السيرفرات
            val sourcesUrl = "$gatewayBaseUrl/library/episode/sources"
            val sourcesBody = mapOf(
                "animeId" to animeId,
                "episodeId" to episodeId,
                "episode_number" to episodeNumber
            )
            val sourcesJson = apiCall(sourcesUrl, "ANIME.LIBRARY.EPISODES.SOURCES.ALL", method = "POST", body = sourcesBody)
            val items = sourcesJson.get("items") ?: return@withContext false

            // 2. فلترة السيرفرات العربية
            val priorityProviders = listOf("cr2", "rift-streamer", "streamtape")
            val filteredItems = items.filter { src ->
                val subTitle = src.get("sub_title")?.asText() ?: ""
                subTitle.startsWith("ar_") || subTitle.isEmpty()
            }.ifEmpty { items.toList() }

            val sortedItems = filteredItems.sortedBy { src ->
                val provider = src.get("provider")?.asText() ?: ""
                val index = priorityProviders.indexOf(provider)
                if (index != -1) index else 99
            }.take(6)

            sortedItems.forEach { src ->
                val hostId = src.get("_id")?.asText() ?: return@forEach
                val serverName = src.get("server_name")?.asText() ?: "Server"
                val provider = src.get("provider")?.asText() ?: ""
                val subTitle = src.get("sub_title")?.asText() ?: ""
                
                val qualitiesNode = src.get("qualities")
                val qualitiesList = if (qualitiesNode != null && qualitiesNode.isArray && qualitiesNode.size() > 0) {
                    qualitiesNode.map { it.asText() }
                } else {
                    listOf("720P")
                }

                for (quality in qualitiesList) {
                    try {
                        // أ. فحص إمكانية التشغيل
                        val canPlayUrl = "$gatewayBaseUrl/library/episode/source/can_play"
                        val canPlayBody = mapOf(
                            "episodeId" to episodeId,
                            "hostId" to hostId,
                            "is_download" to false,
                            "event_name" to "play_episode_unlocked"
                        )
                        val canPlayJson = apiCall(canPlayUrl, "ANIME.LIBRARY.EPISODES.SOURCES.CHECK_AVAILABILITY", method = "POST", body = canPlayBody)
                        val sessionId = canPlayJson.get("sessionId")?.asText() ?: ""
                        
                        // ب. تأكيد تخطي الإعلان
                        val claimUrl = "$gatewayBaseUrl/ads_manager/claim"
                        val claimBody = mapOf(
                            "event_name" to "play_episode_unlocked",
                            "hostId" to hostId,
                            "episodeId" to episodeId,
                            "transactionRef" to "${System.currentTimeMillis()}_${Random().nextInt(99999999)}",
                            "reward_result" to "not_filled",
                            "streamingServerKey" to provider,
                            "is_optional" to false,
                            "is_reward" to true
                        )
                        try {
                            apiCall(claimUrl, "USER.ADS_MANAGER.CLAIMS", method = "PUT", body = claimBody)
                        } catch (_: Exception) {}
                        
                        // ج. طلب الرابط المباشر
                        val directLinkUrl = "$gatewayBaseUrl/library/episode/source/direct_link"
                        val directLinkBody = mapOf(
                            "id" to hostId,
                            "quality" to quality,
                            "with_internal_player" to "1",
                            "sessionId" to sessionId
                        )
                        val directLinkJson = apiCall(directLinkUrl, "ANIME.LIBRARY.EPISODES.SOURCES.DIRECT_LINK", method = "POST", body = directLinkBody)

                        // الحالة 1: روابط VRV / CR2 المباشرة (url_response)
                        if (directLinkJson.get("url_response")?.asBoolean() == true) {
                            val videoUrl = directLinkJson.get("videoUrl")?.asText()

                            if (!videoUrl.isNullOrEmpty()) {
                                val customHeaders = mutableMapOf(
                                    "User-Agent" to "libmpv",
                                    "Accept" to "*/*",
                                    "Range" to "bytes=0-",
                                    "Connection" to "close",
                                    "Icy-MetaData" to "1"
                                )
                                val hostFromUrl = try {
                                    java.net.URI(videoUrl).host
                                } catch (_: Exception) {
                                    null
                                }
                                customHeaders["Host"] = hostFromUrl ?: "media-1.rift-content.com"
                                
                                directLinkJson.get("http_headers")?.fields()?.forEach { (k, v) ->
                                    customHeaders[k] = v.asText()
                                }

                                callback.invoke(
                                    newExtractorLink(
                                        source = name,
                                        name = "$serverName [$subTitle] - $quality",
                                        url = videoUrl,
                                    ) {
                                        this.quality = getQualityFromName(quality)
                                        this.headers = customHeaders
                                    }
                                )
                            }
                        } 
                        // الحالة 2: روابط Streamtape عبر التذكرة الرسمية (ticket_response)
                        else if (directLinkJson.get("ticket_response")?.asBoolean() == true) {
                            val fileId = directLinkJson.get("fileId")?.asText() ?: ""
                            val ticket = directLinkJson.get("ticket")?.asText() ?: ""

                            if (fileId.isNotEmpty() && ticket.isNotEmpty()) {
                                val tapeApiUrl = "https://api.streamtape.com/file/dl?file=$fileId&ticket=$ticket"
                                val tapeRes = app.get(tapeApiUrl).parsed<JsonNode>()
                                val tapeDirectUrl = tapeRes.get("result")?.get("url")?.asText()

                                if (!tapeDirectUrl.isNullOrEmpty()) {
                                    callback.invoke(
                                        newExtractorLink(
                                            source = name,
                                            name = "$serverName [$subTitle] - $quality",
                                            url = tapeDirectUrl,
                                        ) {
                                            this.quality = getQualityFromName(quality)
                                            this.headers = mapOf(
                                                "User-Agent" to "libmpv",
                                                "Accept" to "*/*"
                                            )
                                        }
                                    )
                                }
                            }
                        }
                    } catch (e: Exception) {
                        e.printStackTrace()
                    }
                }
            }
            return@withContext true
        } catch (e: Exception) {
            e.printStackTrace()
            return@withContext false
        }
    }

    private fun getQualityFromName(quality: String?): Int {
        if (quality == null) return Qualities.Unknown.value
        val digits = quality.filter { it.isDigit() }
        return digits.toIntOrNull() ?: Qualities.Unknown.value
    }
}
