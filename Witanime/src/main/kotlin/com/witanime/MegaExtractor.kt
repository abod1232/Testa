package com.witanime

import android.util.Log
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.app
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
import java.nio.charset.StandardCharsets
import java.security.MessageDigest

// ════════════════════════════════════════════════════════════════════════════
// 1. مستخرج MEGA
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
            if (megaUrl.isBlank()) return

            if (!megaUrl.contains("#") && !megaUrl.contains("!")) {
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
// 2. مستخرج GoFile
// ════════════════════════════════════════════════════════════════════════════

class GoFileExtractor : ExtractorApi() {
    override val name: String = "GoFile"
    override val mainUrl: String = "https://gofile.io"
    override val requiresReferer: Boolean = false

    private val TAG = "GOFILE_EXTRACTOR"
    private val USER_AGENT =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36"

    /**
     * توليد X-Website-Token المطلوب من خوادم GoFile
     */
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
                // استخراج معرّف المحتوى (Content ID)
                val contentId = Regex("""gofile\.io/(?:d/|contents/)?([a-zA-Z0-9_-]+)""").find(url)?.groupValues?.get(1)
                    ?: url.trimEnd('/').substringAfterLast('/').substringBefore('?')

                if (contentId.isBlank()) {
                    Log.w(TAG, "Could not extract contentId from: $url")
                    return@withContext
                }

                // 1. طلب إنشاء حساب زائر للحصول على accountToken
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

                if (token.isBlank()) {
                    Log.e(TAG, "Failed to get guest account token from GoFile")
                    return@withContext
                }

                // 2. توليد رمز الحماية X-Website-Token
                val wt = generateWebsiteToken(token, "ar")

                // 3. جلب بيانات الملفات التابعة للمجلد/الرابط
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

                // جمع الملفات سواء كان الرابط لملف مفرد أو مجلد يحتوي على عدة ملفات
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

                if (fileEntries.isEmpty()) {
                    Log.w(TAG, "No files found in GoFile response")
                    return@withContext
                }

                val cookieHeader = "accountToken=$token"

                for (child in fileEntries) {
                    val fileName = child.optString("name").lowercase()
                    val directLink = child.optString("link").trim()
                    val mime = child.optString("mimetype").lowercase()

                    if (directLink.isEmpty()) continue

                    // استبعاد ملفات الضغط لأنها غير قابلة للتشغيل المباشر
                    if (fileName.endsWith(".zip") || fileName.endsWith(".rar") || fileName.endsWith(".7z") ||
                        mime.contains("zip") || mime.contains("rar")
                    ) {
                        continue
                    }

                    // التعرف على جودة الفيديو من الاسم (مثلاً 1080p, 720p)
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
                    Log.d(TAG, "Dispatched GoFile link: $fileName -> $directLink")
                }

            } catch (e: Exception) {
                Log.e(TAG, "GoFile getUrl error", e)
            }
        }
    }
}
