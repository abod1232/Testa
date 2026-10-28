package com.witanime

import android.net.Uri
import android.util.Base64
import android.util.Log
import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.AppUtils
import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.getQualityFromName
import com.lagradost.cloudstream3.utils.newExtractorLink
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import org.jsoup.Jsoup
import org.jsoup.parser.Parser
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import javax.crypto.Cipher
import javax.crypto.spec.SecretKeySpec
import kotlin.random.Random

// ════════════════════════════════════════════════════════════════════════════
// 1. MEGA Extractor (فك التشفير اللحظي عبر الوكيل المحلي)
// ════════════════════════════════════════════════════════════════════════════

class MegaExtractor : ExtractorApi() {
    override val name: String = "Mega"
    override val mainUrl: String = "https://mega.nz"
    override val requiresReferer: Boolean = false

    private val TAG = "MEGA_EXTRACTOR"

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        Log.d(TAG, "▶️ getUrl start for: $url")
        try {
            val megaUrl = url.trim()
            if (megaUrl.isBlank() || (!megaUrl.contains("#") && !megaUrl.contains("!"))) {
                Log.w(TAG, "Invalid Mega URL: missing key token")
                return
            }

            Log.d(TAG, "Registering stream with local AES-128-CTR proxy...")
            val localStreamUrl = WitAnimeMegaServer.registerMegaStream(megaUrl)

            if (localStreamUrl.isNullOrBlank()) {
                Log.e(TAG, "Failed to resolve Mega node")
                return
            }

            withContext(Dispatchers.Main) {
                callback.invoke(
                    newExtractorLink(
                        source = this@MegaExtractor.name,
                        name = "${this@MegaExtractor.name} (Direct Stream)",
                        url = localStreamUrl,
                        type = ExtractorLinkType.VIDEO
                    ) {
                        this.quality = Qualities.P1080.value
                    }
                )
            }
        } catch (e: Exception) {
            Log.e(TAG, "Mega getUrl error", e)
        }
    }
}

// ════════════════════════════════════════════════════════════════════════════
// 2. GoFile Extractor (تجاوز حماية 2026 وتصفية الأرشيف)
// ════════════════════════════════════════════════════════════════════════════

class GoFileExtractor : ExtractorApi() {
    override val name: String = "GoFile"
    override val mainUrl: String = "https://gofile.io"
    override val requiresReferer: Boolean = false

    private val TAG = "GOFILE_EXTRACTOR"
    private val USER_AGENT =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36"

    private fun generateWebsiteToken(accountToken: String, lang: String = "ar"): String {
        val bucket = (System.currentTimeMillis() / 1000L) / 14_400L
        val payload = "$USER_AGENT::$lang::$accountToken::$bucket::12af056dacea0b"
        val digest = MessageDigest.getInstance("SHA-256").digest(payload.toByteArray(StandardCharsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it) }
    }

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        Log.d(TAG, "▶️ getUrl start for GoFile: $url")
        withContext(Dispatchers.IO) {
            try {
                val contentId = Regex("""gofile\.io/(?:d/|contents/)?([a-zA-Z0-9_-]+)""").find(url)?.groupValues?.get(1)
                    ?: url.trimEnd('/').substringAfterLast('/').substringBefore('?')

                if (contentId.isBlank()) return@withContext

                // 1. الحصول على حساب زائر
                val accReq = Request.Builder()
                    .url("https://api.gofile.io/accounts")
                    .post("{}".toRequestBody("application/json; charset=utf-8".toMediaType()))
                    .header("User-Agent", USER_AGENT)
                    .header("Origin", "https://gofile.io")
                    .header("Referer", "https://gofile.io/")
                    .build()

                val accRaw = app.baseClient.newCall(accReq).execute().use { it.body?.string().orEmpty() }
                val token = runCatching {
                    JSONObject(accRaw).optJSONObject("data")?.optString("token").orEmpty()
                }.getOrDefault("")

                if (token.isBlank()) return@withContext

                // 2. توليد X-Website-Token
                val wt = generateWebsiteToken(token, "ar")

                // 3. جلب الروابط
                val contentsUrl = "https://api.gofile.io/contents/$contentId?page=1&pageSize=100&sortField=name&sortDirection=1"
                val contentReq = Request.Builder()
                    .url(contentsUrl)
                    .header("User-Agent", USER_AGENT)
                    .header("Authorization", "Bearer $token")
                    .header("X-Website-Token", wt)
                    .header("X-BL", "ar")
                    .header("Origin", "https://gofile.io")
                    .header("Referer", "https://gofile.io/")
                    .get()
                    .build()

                val contentRaw = app.baseClient.newCall(contentReq).execute().use { it.body?.string().orEmpty() }
                val dataObj = runCatching {
                    JSONObject(contentRaw).optJSONObject("data")
                }.getOrNull() ?: return@withContext

                val fileEntries = mutableListOf<JSONObject>()
                if (dataObj.optString("type") == "file") {
                    fileEntries.add(dataObj)
                } else {
                    dataObj.optJSONObject("children")?.let { ch ->
                        val keys = ch.keys()
                        while (keys.hasNext()) {
                            ch.optJSONObject(keys.next())?.let { fileEntries.add(it) }
                        }
                    }
                    dataObj.optJSONArray("children")?.let { arr ->
                        for (i in 0 until arr.length()) {
                            arr.optJSONObject(i)?.let { fileEntries.add(it) }
                        }
                    }
                }

                val cookieHeader = "accountToken=$token"

                for (child in fileEntries) {
                    val fileName = child.optString("name").lowercase()
                    val directLink = child.optString("link").trim()
                    val mime = child.optString("mimetype").lowercase()

                    if (directLink.isEmpty()) continue
                    // استبعاد ملفات الضغط
                    if (fileName.endsWith(".zip") || fileName.endsWith(".rar") || fileName.endsWith(".7z") ||
                        mime.contains("zip") || mime.contains("rar")
                    ) {
                        continue
                    }

                    val qVal = getQualityFromName(fileName).takeIf { it > 0 } ?: Qualities.P1080.value

                    withContext(Dispatchers.Main) {
                        callback.invoke(
                            newExtractorLink(
                                source = this@GoFileExtractor.name,
                                name = "${this@GoFileExtractor.name} [DL]",
                                url = directLink,
                                type = ExtractorLinkType.VIDEO
                            ) {
                                this.referer = "https://gofile.io/"
                                this.quality = qVal
                                this.headers = mapOf("Cookie" to cookieHeader)
                            }
                        )
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "GoFile getUrl error", e)
            }
        }
    }
}

// ════════════════════════════════════════════════════════════════════════════
// 3. Videa Extractor (فك تشفير RC4 وجلب الجودات)
// ════════════════════════════════════════════════════════════════════════════

class VideaExtractor : ExtractorApi() {
    override val name: String = "Videa"
    override val mainUrl: String = "https://videa.hu"
    override val requiresReferer: Boolean = false

    private val TAG = "VIDEA_EXTRACTOR"
    private val STUPID_KEY = "xHb0ZvME5q8CBcoQi6AngerDu3FGO9fkUlwPmLVY_RTzj2hJIS4NasXWKy1td7p"

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        Log.d(TAG, "▶️ getUrl start for: $url")
        try {
            val iframeSrc = url.trim()
            if (iframeSrc.isBlank()) return

            val pageResp = app.get(iframeSrc).text
            val nonce = Regex("_xt\\s*=\\s*\"([^\"]+)\"").find(pageResp)?.groupValues?.get(1) ?: return

            val paramL = if (nonce.length >= 32) nonce.substring(0, 32) else nonce.padEnd(32, 'a')
            val paramSPart = if (nonce.length > 32) nonce.substring(32) else ""

            val resultBuilder = StringBuilder()
            for (i in 0 until 32) {
                val ch = paramL.getOrNull(i) ?: 'a'
                val idxInStupid = STUPID_KEY.indexOf(ch).takeIf { it >= 0 } ?: 0
                val index = i - (idxInStupid - 31)
                val safeIndex = when {
                    paramSPart.isEmpty() -> 0
                    index < 0 -> 0
                    index >= paramSPart.length -> paramSPart.length - 1
                    else -> index
                }
                resultBuilder.append(paramSPart.getOrNull(safeIndex) ?: 'a')
            }
            val result = resultBuilder.toString()

            val seed = (1..8).map { "abcdefghijklmnopqrstuvwxyz0123456789"[Random.nextInt(36)] }.joinToString("")
            val paramT = if (result.length >= 16) result.substring(0, 16) else result.padEnd(16, '0')
            val rc4KeyPart = if (result.length > 16) result.substring(16) else ""

            val videoId = Uri.parse(iframeSrc).getQueryParameter("v")
                ?: Regex("[?&]v=([^&]+)").find(iframeSrc)?.groupValues?.get(1)
                ?: return

            val xmlUrl = "https://videa.hu/player/xml?platform=desktop"
            val params = mapOf("_s" to seed, "_t" to paramT, "v" to videoId)
            val headers = mapOf("Referer" to iframeSrc, "Origin" to "https://videa.hu")

            val xmlResponse = app.get(xmlUrl, params = params, headers = headers)
            val body = xmlResponse.text
            val xVideaXsHeader = xmlResponse.headers["x-videa-xs"] ?: ""

            val finalDoc = if (body.trimStart().startsWith("<?xml")) {
                val parsed = Jsoup.parse(body, "", Parser.xmlParser())
                if (parsed.selectFirst("error") != null) return
                parsed
            } else {
                try {
                    val decoded = Base64.decode(body.trim(), Base64.DEFAULT)
                    val decrypted = rc4Decrypt(decoded, rc4KeyPart + seed + xVideaXsHeader)
                    Jsoup.parse(decrypted, "", Parser.xmlParser())
                } catch (e: Exception) {
                    return
                }
            }

            val videoSources = finalDoc.select("video_source")
            val collected = mutableListOf<Triple<Int, String, String>>()

            for (src in videoSources) {
                val name = src.attr("name")
                val videoUrlPart = src.text().trim()
                val exp = src.attr("exp")
                val hashElem = finalDoc.getElementsByTag("hash_value_$name").first()
                val md5 = hashElem?.text()?.trim() ?: continue

                val finalUrl = if (videoUrlPart.startsWith("http")) {
                    "$videoUrlPart?md5=$md5&expires=$exp"
                } else {
                    "https:$videoUrlPart?md5=$md5&expires=$exp"
                }
                val q = getQualityFromName(name).takeIf { it > 0 } ?: Qualities.Unknown.value
                collected.add(Triple(q, name, finalUrl))
            }

            val sorted = collected.sortedWith(compareByDescending<Triple<Int, String, String>> { it.first }
                .thenByDescending {
                    Regex("(\\d{3,4})").find(it.second)?.groupValues?.get(1)?.toIntOrNull() ?: 0
                })

            withContext(Dispatchers.Main) {
                for ((qualityVal, name, finalUrl) in sorted) {
                    callback.invoke(
                        newExtractorLink(
                            source = this@VideaExtractor.name,
                            name = "$name (Videa)",
                            url = finalUrl,
                            type = if (finalUrl.contains(".m3u8", ignoreCase = true)) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                        ) {
                            this.referer = referer ?: iframeSrc
                            this.quality = qualityVal
                        }
                    )
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Videa getUrl error", e)
        }
    }

    private fun rc4Decrypt(data: ByteArray, key: String): String {
        return try {
            val spec = SecretKeySpec(key.toByteArray(StandardCharsets.UTF_8), "RC4")
            val cipher = Cipher.getInstance("RC4")
            cipher.init(Cipher.DECRYPT_MODE, spec)
            val out = cipher.update(data) ?: cipher.doFinal(data)
            String(out, StandardCharsets.UTF_8)
        } catch (e: Exception) {
            ""
        }
    }
}

// ════════════════════════════════════════════════════════════════════════════
// 4. MailRu Extractor
// ════════════════════════════════════════════════════════════════════════════

class MailruExtractor : ExtractorApi() {
    override val name = "MailRu"
    override val mainUrl = "https://my.mail.ru"
    override val requiresReferer = false

    private val TAG = "MAILRU_EXTRACTOR"

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val extRef = referer ?: url
        val vidId = url.substringAfter("video/embed/").substringBefore("?").trim()
        if (vidId.isBlank()) return

        val videoReq = try {
            app.get("${mainUrl}/+/video/meta/${vidId}", referer = extRef)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to fetch meta: ${e.message}")
            return
        }

        val videoKey = videoReq.cookies["video_key"]?.toString().orEmpty()
        val videoData = AppUtils.tryParseJson<MailRuData>(videoReq.text) ?: return

        for (video in videoData.videos ?: emptyList()) {
            var videoUrl = if (video.url.startsWith("//")) "https:${video.url}" else video.url
            if (videoKey.isNotBlank()) {
                videoUrl = if (videoUrl.contains("?")) "$videoUrl&video_key=$videoKey" else "$videoUrl?video_key=$videoKey"
            }

            try {
                callback.invoke(
                    newExtractorLink(
                        source = this.name,
                        name = this.name,
                        url = videoUrl,
                        type = if (videoUrl.contains(".m3u8", ignoreCase = true)) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                    ) {
                        this.referer = extRef
                        this.quality = getQualityFromName(video.key)
                    }
                )
            } catch (e: Exception) {
                Log.e(TAG, "Failed to create extractor link for $videoUrl", e)
            }
        }
    }

    data class MailRuData(
        @JsonProperty("provider") val provider: String? = null,
        @JsonProperty("videos") val videos: List<MailRuVideoData>? = null
    )

    data class MailRuVideoData(
        @JsonProperty("url") val url: String,
        @JsonProperty("key") val key: String
    )
}

// ════════════════════════════════════════════════════════════════════════════
// 5. FourShared (4Shared) Extractor
// ════════════════════════════════════════════════════════════════════════════

class FourSharedExtractor : ExtractorApi() {
    override val name = "4Shared"
    override val mainUrl = "https://www.4shared.com"
    override val requiresReferer = false

    private val TAG = "4SHARED_EXTRACTOR"

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        try {
            val fileId = Regex("""4shared\.com/(?:web/embed/file|embed|video|file|s)/([A-Za-z0-9_-]+)""", RegexOption.IGNORE_CASE)
                .find(url)?.groupValues?.get(1) ?: return

            val normalizedEmbed = "https://www.4shared.com/web/embed/file/$fileId"
            val pageHtml = app.get(normalizedEmbed, referer = "https://www.4shared.com/").text

            val doc = Jsoup.parse(pageHtml, normalizedEmbed)
            val mp4Url = doc.selectFirst("video source[src], video[src]")?.attr("src")?.trim()
                ?: Regex("""https?://dc\d+\.4shared\.com/[^\s"'<>]+\.mp4[^\s"'<>]*""", RegexOption.IGNORE_CASE)
                    .find(pageHtml)?.value
                ?: return

            val finalDirectUrl = if (mp4Url.startsWith("//")) "https:$mp4Url" else mp4Url

            withContext(Dispatchers.Main) {
                callback.invoke(
                    newExtractorLink(
                        source = this@FourSharedExtractor.name,
                        name = "${this@FourSharedExtractor.name} (Direct)",
                        url = finalDirectUrl,
                        type = ExtractorLinkType.VIDEO
                    ) {
                        this.referer = "https://www.4shared.com/"
                        this.quality = Qualities.P1080.value
                    }
                )
            }
        } catch (e: Exception) {
            Log.e(TAG, "4Shared getUrl error", e)
        }
    }
}
