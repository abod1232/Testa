package com.arabic.extractors

import android.util.Base64
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.newSubtitleFile
import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.JsUnpacker
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.newExtractorLink
import org.json.JSONArray
import org.json.JSONObject
import org.jsoup.Jsoup
import org.jsoup.parser.Parser
import java.net.URI
import java.nio.charset.StandardCharsets
import java.util.UUID
import kotlin.random.Random

// =========================================================================
// 1. عائلة StreamWish و VidHide و EarnVids و StreamHG الموحدة
//    - فك ضغط JsUnpacker وقراءة مصفوفات links.hls4 || hls3 || hls2
// =========================================================================

open class ArabicStreamWishExtractor(
    override val name: String = "StreamWish",
    override val mainUrl: String = "https://streamwish.fun"
) : ExtractorApi() {
    override val requiresReferer = true

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val origin = ArabicExtractorUtils.originOf(url) ?: mainUrl
        val pageHtml = runCatching {
            app.get(url, referer = referer ?: "$origin/", headers = mapOf("User-Agent" to ArabicExtractorUtils.DEFAULT_UA)).text
        }.getOrNull() ?: return

        val combined = ArabicExtractorUtils.unpackScripts(pageHtml)
        val hlsCandidates = LinkedHashSet<String>()

        // 1. قراءة كائن var links = { "hls4": "...", "hls2": "..." }
        Regex("""var\s+links\s*=\s*(\{[^;]+\})""").find(combined)?.groupValues?.get(1)?.let { rawJson ->
            runCatching {
                val obj = JSONObject(rawJson)
                for (k in listOf("hls4", "hls2", "hls3", "hls", "file")) {
                    val link = obj.optString(k).trim().replace("\\/", "/")
                    if (link.isNotBlank()) resolveUrl(link, origin)?.let { hlsCandidates.add(it) }
                }
            }
        }

        // 2. البحث البديل بالـ Regex
        if (hlsCandidates.isEmpty()) {
            Regex("""["'](?:hls4|hls2|hls3|hls|file)["']\s*:\s*["']([^"']+\.m3u8[^"']*)["']""").findAll(combined).forEach { m ->
                resolveUrl(m.groupValues[1].replace("\\/", "/"), origin)?.let { hlsCandidates.add(it) }
            }
        }
        if (hlsCandidates.isEmpty()) {
            Regex("""https?://[^\s"'<>\\]+\.m3u8[^\s"'<>\\]*""").findAll(combined.replace("\\/", "/")).forEach { m ->
                hlsCandidates.add(m.value)
            }
        }

        val streamHeaders = mapOf(
            "User-Agent" to ArabicExtractorUtils.DEFAULT_UA,
            "Referer" to "$origin/",
            "Origin" to origin
        )

        for (hls in hlsCandidates) {
            ArabicExtractorUtils.emitHls(name, name, hls, "$origin/", streamHeaders, Qualities.Unknown.value, callback)
            return
        }
    }

    private fun resolveUrl(raw: String, origin: String): String? {
        val clean = raw.trim()
        if (clean.isEmpty()) return null
        return when {
            clean.startsWith("http://") || clean.startsWith("https://") -> clean
            clean.startsWith("//") -> "https:$clean"
            clean.startsWith("/") -> "$origin$clean"
            else -> "$origin/$clean"
        }
    }
}

// النطاقات التابعة لعائلة StreamWish ترث من نفس الكلاس
class VidHideExtractor : ArabicStreamWishExtractor("VidHide", "https://vidhidepro.com")
class EarnVidsExtractor : ArabicStreamWishExtractor("EarnVids", "https://morencius.com")
class StreamHgExtractor : ArabicStreamWishExtractor("StreamHG", "https://vibuxer.com")
class HanerixExtractor : ArabicStreamWishExtractor("Hanerix", "https://hanerix.com")
class FilelionsExtractor : ArabicStreamWishExtractor("Filelions", "https://filelions.to")

// =========================================================================
// 2. عائلة مشغلات الـ Packer السريعة (LuluStream / TukTukVIP / Videoland)
// =========================================================================

open class ArabicPackerExtractor(
    override val name: String = "LuluStream",
    override val mainUrl: String = "https://lulustream.com"
) : ExtractorApi() {
    override val requiresReferer = true

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val origin = ArabicExtractorUtils.originOf(url) ?: mainUrl
        val html = runCatching {
            app.get(url, referer = referer ?: "$origin/", headers = mapOf("User-Agent" to ArabicExtractorUtils.DEFAULT_UA)).text
        }.getOrNull() ?: return

        val combined = ArabicExtractorUtils.unpackScripts(html)
        val m3u8 = Regex("""https?://[^"'\\\s]+/hls\d*/[^"'\\\s]+/master\.(?:txt|m3u8)[^"'\\\s]*""").find(combined)?.value
            ?: Regex("""https?://[^"'\\\s]+\.m3u8[^"'\\\s]*""").find(combined)?.value
            ?: Regex("""(?:file|src)\s*[:=]\s*["'](https?://[^"']+\.mp4[^"']*)["']""").find(combined)?.groupValues?.get(1)
            ?: return

        val headers = mapOf(
            "User-Agent" to ArabicExtractorUtils.DEFAULT_UA,
            "Referer" to "$origin/",
            "Origin" to origin
        )

        if (m3u8.contains(".m3u8") || m3u8.contains(".txt")) {
            ArabicExtractorUtils.emitHls(name, name, m3u8, "$origin/", headers, Qualities.Unknown.value, callback)
        } else {
            callback(
                newExtractorLink(name, "$name [MP4]", m3u8, ExtractorLinkType.VIDEO) {
                    this.referer = "$origin/"
                    this.quality = Qualities.P720.value
                    this.headers = headers
                }
            )
        }
    }
}

class LuluStreamExtractor : ArabicPackerExtractor("LuluStream", "https://luluvdo.com")
class TukTukVipExtractor : ArabicPackerExtractor("TukTukVIP", "https://lolololu.website")
class VideolandExtractor : ArabicPackerExtractor("Videoland", "https://videoland.sbs")
class UpDownExtractor : ArabicPackerExtractor("UpDown", "https://updown.icu")
class OkStreamExtractor : ArabicPackerExtractor("OkStream", "https://okstream.online")

// =========================================================================
// 3. عائلة DoodStream الموحدة (playmogo / dsvplay / myvidplay / d000d)
// =========================================================================

open class ArabicDoodExtractor(
    override val name: String = "DoodStream",
    override val mainUrl: String = "https://dsvplay.com"
) : ExtractorApi() {
    override val requiresReferer = false

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val id = Regex("""/(?:e|d)/([A-Za-z0-9]+)""").find(url)?.groupValues?.get(1) ?: return
        val origin = ArabicExtractorUtils.originOf(url) ?: mainUrl
        val embedUrl = "$origin/e/$id"

        val res = runCatching {
            app.get(embedUrl, referer = referer ?: "$origin/", headers = mapOf("User-Agent" to ArabicExtractorUtils.DEFAULT_UA))
        }.getOrNull() ?: return

        val passMd5 = Regex("""/pass_md5/[^'"]+""").find(res.text)?.value ?: return
        val activeOrigin = ArabicExtractorUtils.originOf(res.url) ?: origin

        val md5Base = runCatching {
            app.get(
                url = "$activeOrigin$passMd5",
                referer = res.url,
                headers = mapOf(
                    "User-Agent" to ArabicExtractorUtils.DEFAULT_UA,
                    "X-Requested-With" to "XMLHttpRequest"
                )
            ).text.trim()
        }.getOrNull() ?: return

        if (!md5Base.startsWith("http")) return
        val token = passMd5.substringAfterLast('/')
        val randomSuffix = (1..10).map { "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789".random() }.joinToString("")
        val finalUrl = "$md5Base$randomSuffix?token=$token&expiry=${System.currentTimeMillis()}"

        callback(
            newExtractorLink(name, "$name [MP4]", finalUrl, ExtractorLinkType.VIDEO) {
                this.referer = "$activeOrigin/"
                this.quality = Qualities.P720.value
                this.headers = mapOf("User-Agent" to ArabicExtractorUtils.DEFAULT_UA, "Referer" to "$activeOrigin/")
            }
        )
    }
}

class PlaymogoExtractor : ArabicDoodExtractor("Playmogo", "https://playmogo.com")
class MyVidPlayExtractor : ArabicDoodExtractor("MyVidPlay", "https://myvidplay.com")

// =========================================================================
// 4. سيرفر VOE (فك التشفير الذكي خماسي المراحل: ROT13 + Char-Shift + Reverse)
// =========================================================================

class ArabicVoeExtractor : ExtractorApi() {
    override val name = "VOE"
    override val mainUrl = "https://voe.sx"
    override val requiresReferer = false

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        var currentUrl = if (url.contains("/e/")) url else {
            Regex("""voe\.sx/([A-Za-z0-9]+)""").find(url)?.groupValues?.get(1)?.let { "https://voe.sx/e/$it" } ?: url
        }

        var html = ""
        repeat(3) {
            val res = runCatching {
                app.get(currentUrl, referer = referer ?: "https://voe.sx/", headers = mapOf("User-Agent" to ArabicExtractorUtils.DEFAULT_UA))
            }.getOrNull() ?: return

            currentUrl = res.url
            html = res.text

            val redirect = Regex("""window\.location(?:\.href)?\s*=\s*['"](https?://[^'"]+)['"]""").find(html)?.groupValues?.get(1)
            if (redirect != null && redirect != currentUrl) currentUrl = redirect else return@repeat
        }

        val origin = ArabicExtractorUtils.originOf(currentUrl) ?: mainUrl
        val headers = mapOf("User-Agent" to ArabicExtractorUtils.DEFAULT_UA, "Referer" to "$origin/", "Origin" to origin)

        // فك حزمة JSON المشفرة
        val rawPayload = Regex("""<script[^>]*type=["']application/json["'][^>]*>\s*\[\s*"([^"]+)"\s*\]\s*</script>""").find(html)?.groupValues?.get(1)
        if (!rawPayload.isNullOrBlank()) {
            val decoded = decodeVoe(rawPayload)
            if (decoded != null) {
                val hls = decoded.optString("source").takeIf { it.startsWith("http") }
                val mp4 = decoded.optString("direct_access_url").takeIf { it.startsWith("http") }

                if (hls != null) {
                    ArabicExtractorUtils.emitHls(name, name, hls, "$origin/", headers, Qualities.Unknown.value, callback)
                    return
                }
                if (mp4 != null) {
                    callback(
                        newExtractorLink(name, "$name [MP4]", mp4, ExtractorLinkType.VIDEO) {
                            this.referer = "$origin/"
                            this.quality = Qualities.P720.value
                            this.headers = headers
                        }
                    )
                    return
                }
            }
        }

        // البديل العادي
        Regex("""['"]hls['"]\s*:\s*['"]([^'"]+)['"]""").find(html)?.groupValues?.get(1)?.let { raw ->
            val hls = if (raw.startsWith("http")) raw else runCatching {
                String(Base64.decode(raw, Base64.DEFAULT), Charsets.UTF_8)
            }.getOrNull()
            if (!hls.isNullOrBlank() && hls.startsWith("http")) {
                ArabicExtractorUtils.emitHls(name, name, hls, "$origin/", headers, Qualities.Unknown.value, callback)
            }
        }
    }

    private fun decodeVoe(input: String): JSONObject? = runCatching {
        // الخطوة 1: فك ROT13
        val rot = buildString(input.length) {
            for (c in input) {
                when (c) {
                    in 'a'..'z' -> append('a' + (c - 'a' + 13) % 26)
                    in 'A'..'Z' -> append('A' + (c - 'A' + 13) % 26)
                    else -> append(c)
                }
            }
        }
        // الخطوة 2: مسح علامات التمويه
        var cleaned = rot
        for (m in listOf("@$", "^^", "~@", "%?", "*~", "!!", "#&")) cleaned = cleaned.replace(m, "")
        // الخطوة 3: Base64 decode
        val step1 = String(Base64.decode(cleaned, Base64.DEFAULT), Charsets.ISO_8859_1)
        // الخطوة 4: إزاحة الحروف بمقدار -3 وعكس النص
        val shifted = CharArray(step1.length) { i -> (step1[i].code - 3).toChar() }
        val reversed = String(shifted).reversed()
        // الخطوة 5: Base64 النهائي -> JSON
        val jsonStr = String(Base64.decode(reversed, Base64.DEFAULT), Charsets.UTF_8)
        JSONObject(jsonStr)
    }.getOrNull()
}

// =========================================================================
// 5. سيرفر StreamTape و MixDrop و Mp4Upload
// =========================================================================

class ArabicStreamTapeExtractor : ExtractorApi() {
    override val name = "StreamTape"
    override val mainUrl = "https://streamtape.com"
    override val requiresReferer = false

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val code = Regex("""/(?:e|v)/([A-Za-z0-9_-]+)""").find(url)?.groupValues?.get(1) ?: return
        val origin = ArabicExtractorUtils.originOf(url) ?: mainUrl
        val html = runCatching {
            app.get("$origin/e/$code/", headers = mapOf("User-Agent" to ArabicExtractorUtils.DEFAULT_UA)).text
        }.getOrNull() ?: return

        val concatMatch = Regex("""getElementById\(['"](?:robot|ideoo)link['"]\)\.innerHTML\s*=\s*['"]([^'"]+)['"]\s*\+\s*\(?['"]([^'"]+)['"]\)?(?:\.substring\((\d+)\))?""").find(html) ?: return
        val prefix = concatMatch.groupValues[1]
        var tail = concatMatch.groupValues[2]
        concatMatch.groupValues[3].toIntOrNull()?.let { if (it in 0..tail.length) tail = tail.substring(it) }

        var built = prefix + tail
        if (built.startsWith("//")) built = "https:$built"
        val finalUrl = if (built.contains("&stream=")) built else "$built&stream=1"

        callback(
            newExtractorLink(name, "$name [MP4]", finalUrl, ExtractorLinkType.VIDEO) {
                this.referer = "$origin/"
                this.quality = Qualities.P720.value
                this.headers = mapOf("User-Agent" to ArabicExtractorUtils.DEFAULT_UA, "Referer" to "$origin/e/$code/")
            }
        )
    }
}

class ArabicMixdropExtractor : ExtractorApi() {
    override val name = "MixDrop"
    override val mainUrl = "https://mixdrop.ag"
    override val requiresReferer = true

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val code = Regex("""/(?:e|f)/([A-Za-z0-9]+)""").find(url)?.groupValues?.get(1) ?: return
        val origin = ArabicExtractorUtils.originOf(url) ?: mainUrl
        val embedUrl = "$origin/e/$code"
        val html = runCatching {
            app.get(embedUrl, referer = referer ?: "$origin/", headers = mapOf("User-Agent" to ArabicExtractorUtils.DEFAULT_UA)).text
        }.getOrNull() ?: return

        val combined = ArabicExtractorUtils.unpackScripts(html)
        val wurl = Regex("""MDCore\.wurl\s*=\s*["']([^"']+)["']""").find(combined)?.groupValues?.get(1) ?: return
        val videoUrl = when {
            wurl.startsWith("//") -> "https:$wurl"
            wurl.startsWith("http") -> wurl
            else -> "$origin$wurl"
        }

        callback(
            newExtractorLink(name, "$name [MP4]", videoUrl, ExtractorLinkType.VIDEO) {
                this.referer = "$origin/"
                this.quality = Qualities.P720.value
                this.headers = mapOf("User-Agent" to ArabicExtractorUtils.DEFAULT_UA)
            }
        )
    }
}

class ArabicMp4UploadExtractor : ExtractorApi() {
    override val name = "Mp4Upload"
    override val mainUrl = "https://www.mp4upload.com"
    override val requiresReferer = true

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val id = Regex("""mp4upload\.com/(?:embed-)?([A-Za-z0-9]{10,})""").find(url)?.groupValues?.get(1)
        val embedUrl = if (id != null) "https://www.mp4upload.com/embed-$id.html" else url

        val html = runCatching {
            app.get(embedUrl, referer = referer ?: "https://www.mp4upload.com/", headers = mapOf("User-Agent" to ArabicExtractorUtils.DEFAULT_UA)).text
        }.getOrNull() ?: return

        val combined = ArabicExtractorUtils.unpackScripts(html)
        val src = Regex("""player\.src\(\s*\{\s*type\s*:\s*["']video/mp4["']\s*,\s*src\s*:\s*["']([^"']+)["']""").find(combined)?.groupValues?.get(1)
            ?: Regex("""https?://[a-z0-9.-]*mp4upload\.com(?::\d+)?/[^\s"'<>\\]+\.mp4""").find(combined)?.value
            ?: return

        callback(
            newExtractorLink(name, "$name [MP4]", src, ExtractorLinkType.VIDEO) {
                this.referer = embedUrl
                this.quality = Qualities.P720.value
                this.headers = mapOf("User-Agent" to ArabicExtractorUtils.DEFAULT_UA, "Referer" to embedUrl)
            }
        )
    }
}

// =========================================================================
// 6. سيرفرات Videas و Videa.hu (من كود WitAnime)
// =========================================================================

class ArabicVideasExtractor : ExtractorApi() {
    override val name = "Videas"
    override val mainUrl = "https://app.videas.com"
    override val requiresReferer = false

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val origin = ArabicExtractorUtils.originOf(url) ?: mainUrl
        val embedId = Regex("""/embed/(?:media/)?([0-9a-fA-F-]{20,})""").find(url)?.groupValues?.get(1) ?: return

        val visitorHeaders = mapOf(
            "User-Agent" to ArabicExtractorUtils.DEFAULT_UA,
            "Accept" to "application/json",
            "Origin" to origin
        )
        val viewerId = UUID.randomUUID().toString()
        val visRaw = runCatching {
            app.get("$origin/api/visitor/embed/$embedId/?viewer_id=$viewerId", headers = visitorHeaders, referer = url).text
        }.getOrNull() ?: return

        val visObj = runCatching { JSONObject(visRaw) }.getOrNull() ?: return
        val playbackToken = visObj.optString("playback_token")
        val sessionId = visObj.optString("session_id")
        val items = visObj.optJSONArray("items") ?: return

        for (idx in 0 until items.length()) {
            val item = items.optJSONObject(idx) ?: continue
            val asset = item.optJSONObject("asset") ?: continue
            val assetUid = asset.optString("uid")

            // ترجمات
            asset.optJSONArray("subtitles")?.let { subs ->
                for (s in 0 until subs.length()) {
                    val sub = subs.optJSONObject(s) ?: continue
                    val subUrl = sub.optString("url")
                    val subLang = sub.optString("label", "Arabic")
                    if (subUrl.startsWith("http")) subtitleCallback(newSubtitleFile(subLang, subUrl))
                }
            }

            val reqJson = JSONObject().put("asset_id", assetUid).put("playback_token", playbackToken).put("session_id", sessionId).toString()
            val pbRaw = runCatching {
                app.post(
                    url = "$origin/api/visitor/playback/info/",
                    headers = mapOf("User-Agent" to ArabicExtractorUtils.DEFAULT_UA, "Origin" to origin, "Content-Type" to "application/json"),
                    data = mapOf(),
                    requestBody = reqJson.toRequestBody(ArabicExtractorUtils.JSON_MEDIA_TYPE)
                ).text
            }.getOrNull() ?: continue

            val pbObj = runCatching { JSONObject(pbRaw) }.getOrNull() ?: continue
            val hls = pbObj.optJSONObject("hls_url")?.optString("url") ?: pbObj.optString("hls_url")

            if (hls.isNotBlank()) {
                val fullHls = if (hls.startsWith("http")) hls else "$origin$hls"
                ArabicExtractorUtils.emitHls(name, name, fullHls, "$origin/", mapOf("Origin" to origin), Qualities.P1080.value, callback)
                return
            }
        }
    }
}

class ArabicVideaExtractor : ExtractorApi() {
    override val name = "Videa"
    override val mainUrl = "https://videa.hu"
    override val requiresReferer = false

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val videoId = Regex("""[?&]v=([A-Za-z0-9_-]+)""").find(url)?.groupValues?.get(1) ?: return
        val playerUrl = "https://videa.hu/player?v=$videoId"
        val playerHtml = runCatching {
            app.get(playerUrl, headers = mapOf("User-Agent" to ArabicExtractorUtils.DEFAULT_UA)).text
        }.getOrNull() ?: return

        val nonce = Regex("""_xt\s*=\s*["']([^"']+)["']""").find(playerHtml)?.groupValues?.get(1) ?: return
        val paramL = if (nonce.length >= 32) nonce.substring(0, 32) else nonce.padEnd(32, 'a')
        val paramS = if (nonce.length > 32) nonce.substring(32) else ""

        val unscrambled = buildString(32) {
            for (i in 0 until 32) {
                val ch = paramL[i]
                val idxInSecret = VIDEA_SECRET.indexOf(ch).takeIf { it >= 0 } ?: 0
                val index = i - (idxInSecret - 31)
                val safeIndex = index.coerceIn(0, (paramS.length - 1).coerceAtLeast(0))
                append(if (paramS.isNotEmpty()) paramS[safeIndex] else 'a')
            }
        }

        val alphabet = "abcdefghijklmnopqrstuvwxyz0123456789"
        val seed = (1..8).map { alphabet[Random.nextInt(alphabet.length)] }.joinToString("")
        val paramT = unscrambled.take(16).padEnd(16, '0')
        val rc4KeyPart = if (unscrambled.length > 16) unscrambled.substring(16) else ""

        val xmlEndpoint = "https://videa.hu/player/xml?platform=desktop&v=$videoId&_s=$seed&_t=$paramT"
        val xmlRes = runCatching {
            app.get(xmlEndpoint, referer = playerUrl, headers = mapOf("User-Agent" to ArabicExtractorUtils.DEFAULT_UA, "Origin" to "https://videa.hu"))
        }.getOrNull() ?: return

        val xVideaXs = xmlRes.headers["x-videa-xs"].orEmpty()
        val xmlBody = xmlRes.text
        val xmlString = if (xmlBody.trimStart().startsWith("<")) xmlBody else {
            val decoded = runCatching { Base64.decode(xmlBody.trim(), Base64.DEFAULT) }.getOrNull() ?: return
            rc4Decrypt(decoded, rc4KeyPart + seed + xVideaXs)
        }

        val xmlDoc = Jsoup.parse(xmlString, "", Parser.xmlParser())
        for (src in xmlDoc.select("video_source")) {
            val qName = src.attr("name").trim()
            val rawVideoUrl = src.text().trim()
            if (rawVideoUrl.isEmpty()) continue
            val exp = src.attr("exp").trim()
            val md5 = xmlDoc.getElementsByTag("hash_value_$qName").firstOrNull()?.text()?.trim()
            val base = if (rawVideoUrl.startsWith("//")) "https:$rawVideoUrl" else rawVideoUrl
            val signed = if (!md5.isNullOrEmpty() && exp.isNotEmpty()) "$base?md5=$md5&expires=$exp" else base
            val qVal = ArabicExtractorUtils.parseQuality(qName)

            callback(
                newExtractorLink(name, "$name [$qName]", signed, ExtractorLinkType.VIDEO) {
                    this.referer = playerUrl
                    this.quality = qVal
                }
            )
        }
    }

    private fun rc4Decrypt(data: ByteArray, key: String): String {
        if (key.isEmpty()) return ""
        val s = IntArray(256) { it }
        val keyBytes = key.toByteArray(StandardCharsets.UTF_8)
        var j = 0
        for (i in 0 until 256) {
            j = (j + s[i] + (keyBytes[i % keyBytes.size].toInt() and 0xFF)) and 0xFF
            val tmp = s[i]; s[i] = s[j]; s[j] = tmp
        }
        val out = ByteArray(data.size)
        var i = 0; j = 0
        for (idx in data.indices) {
            i = (i + 1) and 0xFF
            j = (j + s[i]) and 0xFF
            val tmp = s[i]; s[i] = s[j]; s[j] = tmp
            val k = s[(s[i] + s[j]) and 0xFF]
            out[idx] = ((data[idx].toInt() and 0xFF) xor k).toByte()
        }
        return String(out, StandardCharsets.UTF_8)
    }

    companion object {
        private const val VIDEA_SECRET = "xHb0ZvME5q8CBcoQi6AngerDu3FGO9fkUlwPmLVY_RTzj2hJIS4NasXWKy1td7p"
    }
}

// =========================================================================
// 7. السيرفرات الاجتماعية الروسية (OkRu و VK Video)
// =========================================================================

class ArabicOkRuExtractor : ExtractorApi() {
    override val name = "OkRu"
    override val mainUrl = "https://ok.ru"
    override val requiresReferer = false

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val embedUrl = url.replace("/video/", "/videoembed/")
        val html = runCatching {
            app.get(embedUrl, headers = mapOf("User-Agent" to ArabicExtractorUtils.DEFAULT_UA)).text
        }.getOrNull() ?: return

        val optionsRaw = Jsoup.parse(html).selectFirst("[data-options]")?.attr("data-options").orEmpty()
        val optsJson = runCatching { JSONObject(optionsRaw) }.getOrNull() ?: return
        val flashvars = optsJson.optJSONObject("flashvars") ?: return
        val meta = flashvars.optJSONObject("metadata") ?: runCatching { JSONObject(flashvars.optString("metadata")) }.getOrNull() ?: return

        val videos = meta.optJSONArray("videos")
        if (videos != null) {
            for (i in 0 until videos.length()) {
                val v = videos.optJSONObject(i) ?: continue
                val vUrl = v.optString("url").takeIf { it.startsWith("http") } ?: continue
                val vName = v.optString("name").trim()
                val q = ArabicExtractorUtils.parseQuality(vName)

                callback(
                    newExtractorLink(name, "$name [$vName]", vUrl, ExtractorLinkType.VIDEO) {
                        this.referer = "https://ok.ru/"
                        this.quality = q
                    }
                )
            }
        }
    }
}

class ArabicVkExtractor : ExtractorApi() {
    override val name = "VK Video"
    override val mainUrl = "https://vkvideo.ru"
    override val requiresReferer = false

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val html = runCatching {
            app.get(url, headers = mapOf("User-Agent" to ArabicExtractorUtils.DEFAULT_UA)).text
        }.getOrNull() ?: return

        val unescaped = html.replace("\\/", "/")
        for ((field, qVal, label) in listOf(
            Triple("url1080", Qualities.P1080.value, "1080p"),
            Triple("url720", Qualities.P720.value, "720p"),
            Triple("url480", Qualities.P480.value, "480p"),
            Triple("url360", Qualities.P360.value, "360p")
        )) {
            val mp4 = Regex(""""$field"\s*:\s*"([^"]+)"""").find(unescaped)?.groupValues?.get(1)?.replace("\\u0026", "&")
            if (!mp4.isNullOrBlank() && mp4.startsWith("http")) {
                callback(
                    newExtractorLink(name, "$name ($label)", mp4, ExtractorLinkType.VIDEO) {
                        this.referer = "https://vk.ru/"
                        this.quality = qVal
                    }
                )
            }
        }
    }
}

// =========================================================================
// 8. سيرفرات التخزين السحابي والتنزيل المباشر (مصفاة من الـ ZIP)
// =========================================================================

class ArabicMediaFireExtractor : ExtractorApi() {
    override val name = "MediaFire"
    override val mainUrl = "https://www.mediafire.com"
    override val requiresReferer = false

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val cleanUrl = url.substringBefore('?').lowercase()
        if (cleanUrl.endsWith(".zip") || cleanUrl.endsWith(".rar") || cleanUrl.endsWith(".7z")) return

        val html = runCatching {
            app.get(url, referer = referer, headers = mapOf("User-Agent" to ArabicExtractorUtils.DEFAULT_UA)).text
        }.getOrNull() ?: return

        val scrambled = Regex("""data-scrambled-url=["']([^"']+)["']""").find(html)?.groupValues?.get(1)
        val directUrl = if (!scrambled.isNullOrBlank()) {
            runCatching { String(Base64.decode(scrambled, Base64.DEFAULT), Charsets.UTF_8).trim() }.getOrNull()?.takeIf { it.startsWith("http") }
        } else {
            Regex("""href=["'](https?://download\d*\.mediafire\.com/[^"']+)["']""").find(html)?.groupValues?.get(1)
                ?: Regex("""https?://download\d*\.mediafire\.com/[^\s"'<>]+""").find(html)?.value
        } ?: return

        callback(
            newExtractorLink(name, "$name [MP4]", directUrl, ExtractorLinkType.VIDEO) {
                this.referer = "https://www.mediafire.com/"
                this.quality = Qualities.P1080.value
            }
        )
    }
}

class ArabicPixelDrainExtractor : ExtractorApi() {
    override val name = "PixelDrain"
    override val mainUrl = "https://pixeldrain.com"
    override val requiresReferer = false

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val id = Regex("""pixeldrain\.[a-z]+/(?:u|api/file)/([A-Za-z0-9_-]+)""").find(url)?.groupValues?.get(1) ?: return
        val directUrl = "https://pixeldrain.com/api/file/$id"

        callback(
            newExtractorLink(name, "$name [MP4]", directUrl, ExtractorLinkType.VIDEO) {
                this.referer = "https://pixeldrain.com/"
                this.quality = Qualities.P1080.value
                this.headers = mapOf("User-Agent" to ArabicExtractorUtils.DEFAULT_UA)
            }
        )
    }
}

class ArabicFourSharedExtractor : ExtractorApi() {
    override val name = "4Shared"
    override val mainUrl = "https://www.4shared.com"
    override val requiresReferer = false

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val fileId = Regex("""4shared\.com/(?:web/embed/file|embed|video|file|s)/([A-Za-z0-9_-]+)""").find(url)?.groupValues?.get(1) ?: return
        val embedUrl = "https://www.4shared.com/web/embed/file/$fileId"

        val html = runCatching {
            app.get(embedUrl, headers = mapOf("User-Agent" to ArabicExtractorUtils.DEFAULT_UA)).text
        }.getOrNull() ?: return

        val doc = Jsoup.parse(html)
        val mp4 = doc.selectFirst("video source[src], video[src]")?.attr("src")?.trim()
            ?: Regex("""https?://dc\d+\.4shared\.com/[^\s"'<>]+\.mp4[^\s"'<>]*""").find(html)?.value
            ?: return

        callback(
            newExtractorLink(name, "$name [MP4]", mp4, ExtractorLinkType.VIDEO) {
                this.referer = "https://www.4shared.com/"
                this.quality = Qualities.P720.value
            }
        )
    }
}

class ArabicGoFileExtractor : ExtractorApi() {
    override val name = "GoFile"
    override val mainUrl = "https://gofile.io"
    override val requiresReferer = false

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val contentId = url.trimEnd('/').substringAfterLast('/').substringBefore('?')
        if (contentId.isBlank()) return

        val accRes = runCatching {
            app.post("https://api.gofile.io/accounts", headers = mapOf("User-Agent" to ArabicExtractorUtils.DEFAULT_UA)).text
        }.getOrNull() ?: return

        val token = runCatching { JSONObject(accRes).optJSONObject("data")?.optString("token") }.getOrNull()
        if (token.isNullOrBlank()) return

        val contentRaw = runCatching {
            app.get(
                url = "https://api.gofile.io/contents/$contentId",
                headers = mapOf(
                    "Authorization" to "Bearer $token",
                    "User-Agent" to ArabicExtractorUtils.DEFAULT_UA
                )
            ).text
        }.getOrNull() ?: return

        val data = runCatching { JSONObject(contentRaw).optJSONObject("data") }.getOrNull() ?: return
        val children = data.optJSONObject("children") ?: return
        val keys = children.keys()

        while (keys.hasNext()) {
            val item = children.optJSONObject(keys.next()) ?: continue
            val link = item.optString("link")
            val nameStr = item.optString("name").lowercase()

            if (link.startsWith("http") && !nameStr.endsWith(".zip") && !nameStr.endsWith(".rar") && !nameStr.endsWith(".7z")) {
                callback(
                    newExtractorLink(name, "$name [MP4]", link, ExtractorLinkType.VIDEO) {
                        this.referer = "https://gofile.io/"
                        this.quality = Qualities.P1080.value
                        this.headers = mapOf("Cookie" to "accountToken=$token")
                    }
                )
            }
        }
    }
}
