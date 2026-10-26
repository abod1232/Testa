package com.eshk

import android.util.Base64
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.newSubtitleFile
import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.JsUnpacker
import com.lagradost.cloudstream3.utils.M3u8Helper
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.getQualityFromName
import com.lagradost.cloudstream3.utils.newExtractorLink
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import org.jsoup.parser.Parser
import java.net.URI
import java.net.URLDecoder
import java.security.MessageDigest
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

object ArabicExtractorUtils {
    const val DEFAULT_UA =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/128.0.0.0 Safari/537.36"

    val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()

    fun parseQuality(hint: String?, fallback: String = ""): Int {
        val combined = "${hint.orEmpty()} $fallback".uppercase()
        return when {
            combined.contains("2160") || combined.contains("4K") || combined.contains("UHD") -> Qualities.P2160.value
            combined.contains("1440") || combined.contains("2K") -> Qualities.P1440.value
            combined.contains("1080") || combined.contains("FHD") -> Qualities.P1080.value
            combined.contains("720") || combined.contains("HD") -> Qualities.P720.value
            combined.contains("480") || combined.contains("SD") -> Qualities.P480.value
            combined.contains("360") -> Qualities.P360.value
            combined.contains("240") -> Qualities.P240.value
            else -> getQualityFromName(hint.orEmpty()).takeIf { it > 0 } ?: Qualities.Unknown.value
        }
    }

    fun originOf(url: String): String? = runCatching {
        val u = URI(url)
        if (u.host.isNullOrBlank()) null else "${u.scheme}://${u.host}"
    }.getOrNull()

    fun unpackScripts(html: String): String {
        val sb = StringBuilder(html)
        val evalRegex = Regex("""eval\(function\(p,a,c,k,e,[dr]\).*?\)\)\s*;?""", RegexOption.DOT_MATCHES_ALL)
        for (m in evalRegex.findAll(html)) {
            runCatching { JsUnpacker(m.value).unpack() }.getOrNull()?.let { unpacked ->
                sb.append('\n').append(unpacked)
            }
        }
        return sb.toString()
    }

    suspend fun emitHls(
        source: String,
        name: String,
        url: String,
        referer: String,
        headers: Map<String, String> = mapOf("User-Agent" to DEFAULT_UA, "Referer" to referer),
        quality: Int = Qualities.Unknown.value,
        callback: (ExtractorLink) -> Unit
    ) {
        val generated = runCatching {
            M3u8Helper.generateM3u8(
                source = source,
                streamUrl = url,
                referer = referer,
                quality = quality.takeIf { it > 0 },
                headers = headers
            )
        }.getOrDefault(emptyList())

        if (generated.isNotEmpty()) {
            generated.forEach(callback)
        } else {
            callback(
                newExtractorLink(source, name, url, ExtractorLinkType.M3U8) {
                    this.referer = referer
                    this.quality = quality
                    this.headers = headers
                }
            )
        }
    }

    fun aesCbcDecrypt(key: ByteArray, iv: ByteArray, cipherBytes: ByteArray): String? = runCatching {
        val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(iv))
        String(cipher.doFinal(cipherBytes), Charsets.UTF_8).trim { it <= ' ' || it.code < 0x20 }
    }.getOrNull()

    fun aesGcmDecrypt(key: ByteArray, iv: ByteArray, cipherBytes: ByteArray): String? = runCatching {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, iv))
        String(cipher.doFinal(cipherBytes), Charsets.UTF_8).trim()
    }.getOrNull()

    fun b64UrlDecode(s: String): ByteArray {
        var norm = s.trim().replace('-', '+').replace('_', '/')
        val rem = norm.length % 4
        if (rem != 0) norm += "=".repeat(4 - rem)
        return Base64.decode(norm, Base64.DEFAULT)
    }
}

// =========================================================================
// 2. عائلة Vidstack الموحدة (UPNShare / StreamP2P / RPMShare) - بدون requiresUrl
// =========================================================================

open class ArabicVidstackExtractor(
    override val name: String = "UPNShare",
    override val mainUrl: String = "https://upns.one"
) : ExtractorApi() {
    override val requiresReferer = true

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val videoId = url.substringAfterLast('#', "")
            .substringAfterLast('/')
            .substringBefore('&')
            .substringBefore('?')
            .trim()
            .ifEmpty {
                url.substringBefore('?').substringBefore('#').trimEnd('/')
                    .substringAfterLast('/')
                    .takeIf { it.isNotBlank() && !it.contains('.') }
                    .orEmpty()
            }
        if (videoId.isBlank()) return

        val host = runCatching { URI(url).host?.lowercase().orEmpty() }.getOrDefault("")
        val originFromUrl = ArabicExtractorUtils.originOf(url) ?: "https://$host"

        val candidateOrigins = buildList {
            add(originFromUrl)
            if (host.contains("rpmplay")) add("https://megamax.rpmplay.me")
            if (host.contains("uns.bio")) add("https://megamax.uns.bio")
            if (host.contains("strp2p")) add("https://megamax.strp2p.site")
            if (host.contains("upns.one")) add("https://tuktuk.upns.one")
            add(mainUrl)
        }.distinct()

        for (origin in candidateOrigins) {
            val reqHeaders = mapOf(
                "User-Agent" to ArabicExtractorUtils.DEFAULT_UA,
                "Referer" to "$origin/",
                "Origin" to origin,
                "Accept" to "*/*"
            )

            val rawText = runCatching {
                app.get("$origin/api/v1/video?id=$videoId", headers = reqHeaders).text
            }.getOrNull() ?: continue

            val cleanHex = rawText.trim().trim('"').replace(Regex("""\s+"""), "")
            if (cleanHex.length < 32 || cleanHex.length % 2 != 0 || cleanHex.startsWith("{")) continue

            val cipherBytes = ByteArray(cleanHex.length / 2) { idx ->
                cleanHex.substring(idx * 2, idx * 2 + 2).toInt(16).toByte()
            }

            val decrypted = ArabicExtractorUtils.aesCbcDecrypt(KEY, IV, cipherBytes) ?: continue
            val json = runCatching { JSONObject(decrypted) }.getOrNull() ?: continue

            val streamUrls = LinkedHashSet<String>()

            fun collectFromNode(key: String) {
                val node = json.opt(key) ?: return
                when (node) {
                    is String -> {
                        val v = node.trim()
                        if (v.startsWith("http")) streamUrls.add(v)
                        else if (v.startsWith("//")) streamUrls.add("https:$v")
                        else if (v.startsWith("/")) streamUrls.add("$origin$v")
                    }
                    is JSONArray -> {
                        for (i in 0 until node.length()) {
                            val item = node.opt(i)
                            if (item is String && item.startsWith("http")) streamUrls.add(item)
                            else if (item is JSONObject) {
                                val f = item.optString("file").ifBlank { item.optString("url") }
                                if (f.startsWith("http")) streamUrls.add(f)
                            }
                        }
                    }
                    is JSONObject -> {
                        val keys = node.keys()
                        while (keys.hasNext()) {
                            val v = node.optString(keys.next())
                            if (v.startsWith("http")) streamUrls.add(v)
                        }
                    }
                }
            }

            collectFromNode("cfNative")
            collectFromNode("source")
            collectFromNode("cf")
            collectFromNode("streams")

            if (streamUrls.isNotEmpty()) {
                val streamHeaders = mapOf(
                    "User-Agent" to ArabicExtractorUtils.DEFAULT_UA,
                    "Referer" to "$origin/",
                    "Origin" to origin
                )
                for (hls in streamUrls) {
                    ArabicExtractorUtils.emitHls(
                        source = name,
                        name = name,
                        url = hls,
                        referer = "$origin/",
                        headers = streamHeaders,
                        callback = callback
                    )
                }
                return
            }
        }
    }

    companion object {
        private val KEY = "kiemtienmua911ca".toByteArray(Charsets.UTF_8)
        private val IV = "1234567890oiuytr".toByteArray(Charsets.UTF_8)
    }
}

// كلاسات تغطي جميع دومينات ومرايا UPN و Vidstack المتداولة في المواقع:
class UpnShareExtractor : ArabicVidstackExtractor("UPNShare", "https://upns.one")
class UpnShareTukTukExtractor : ArabicVidstackExtractor("UPNShare", "https://tuktuk.upns.one")
class UpnShareBioExtractor : ArabicVidstackExtractor("UPNShare", "https://uns.bio")
class UpnShareMegaMaxExtractor : ArabicVidstackExtractor("UPNShare", "https://megamax.uns.bio")

class StreamP2PExtractor : ArabicVidstackExtractor("StreamP2P", "https://strp2p.site")
class StreamP2PMegaMaxExtractor : ArabicVidstackExtractor("StreamP2P", "https://megamax.strp2p.site")
class P2PStreamVipExtractor : ArabicVidstackExtractor("StreamP2P", "https://p2pstream.vip")

class RpmShareExtractor : ArabicVidstackExtractor("RPMShare", "https://rpmplay.me")
class RpmShareMegaMaxExtractor : ArabicVidstackExtractor("RPMShare", "https://megamax.rpmplay.me")
open class ArabicMegaMaxExtractor(
    override val name: String = "MegaMax",
    override val mainUrl: String = "https://megamax.me"
) : ExtractorApi() {
    override val requiresReferer = false

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val hashId = Regex("""/(?:iframe|d|download|e|f|mirror)/([A-Za-z0-9_-]+)""").find(url)?.groupValues?.get(1) ?: return
        val origin = ArabicExtractorUtils.originOf(url) ?: mainUrl
        val refUrl = referer ?: "$origin/"
        extractLeechHls(hashId, origin, refUrl, subtitleCallback, callback)
        extractLeechDirectMp4(hashId, origin, refUrl, callback)
    }

    private suspend fun extractLeechHls(
        hashId: String,
        origin: String,
        referer: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val pageRes = runCatching {
            app.get("$origin/e/$hashId", referer = referer, headers = mapOf("User-Agent" to ArabicExtractorUtils.DEFAULT_UA))
        }.getOrNull() ?: return

        val doc = pageRes.document
        val rawPage = doc.selectFirst("script[data-page=app]")?.html()?.trim()
            ?: doc.selectFirst("#app[data-page]")?.attr("data-page")?.let { Parser.unescapeEntities(it, false) }
            ?: return
        val pageJson = runCatching { JSONObject(rawPage) }.getOrNull() ?: return
        val version = pageJson.optString("version")

        val partialRes = runCatching {
            app.get(
                url = "$origin/e/$hashId",
                headers = mapOf(
                    "User-Agent" to ArabicExtractorUtils.DEFAULT_UA,
                    "Accept" to "text/html, application/xhtml+xml",
                    "X-Requested-With" to "XMLHttpRequest",
                    "X-Inertia" to "true",
                    "X-Inertia-Version" to version,
                    "X-Inertia-Partial-Component" to "files/leech/video",
                    "X-Inertia-Partial-Data" to "streams,subtitles",
                    "Referer" to "$origin/e/$hashId"
                )
            ).text
        }.getOrNull() ?: return

        val props = runCatching { JSONObject(partialRes).optJSONObject("props") }.getOrNull() ?: return
        props.optJSONArray("subtitles")?.let { subs ->
            for (i in 0 until subs.length()) {
                val s = subs.optJSONObject(i) ?: continue
                val file = s.optString("file").takeIf { it.startsWith("http") } ?: continue
                val lang = s.optString("label").ifBlank { "Arabic" }
                subtitleCallback(newSubtitleFile(lang, file))
            }
        }

        val dataRaw = props.optJSONObject("streams")?.optString("data").orEmpty()
        if (dataRaw.isBlank()) return
        val plain = (if (dataRaw.contains("\"ct\"")) {
            decryptCryptoJs(dataRaw, LEECH_KEY)
        } else {
            dataRaw
        })?.trim() ?: return

        val streamHeaders = mapOf(
            "User-Agent" to ArabicExtractorUtils.DEFAULT_UA,
            "Referer" to "$origin/",
            "Origin" to origin
        )

        val sourcesArr = runCatching {
            if (plain.startsWith("[")) JSONArray(plain) else JSONArray().put(JSONObject(plain))
        }.getOrNull() ?: return

        for (i in 0 until sourcesArr.length()) {
            val item = sourcesArr.optJSONObject(i) ?: continue
            val fileUrl = item.optString("file").takeIf { it.startsWith("http") } ?: continue
            val label = item.optString("label")
            val q = ArabicExtractorUtils.parseQuality(label, fileUrl)

            if (fileUrl.contains(".m3u8") || item.optString("type").contains("mpegURL", ignoreCase = true)) {
                ArabicExtractorUtils.emitHls(name, "$name ($label)", fileUrl, "$origin/", streamHeaders, q, callback)
            } else {
                callback(
                    newExtractorLink(name, "$name ($label) [MP4]", fileUrl, ExtractorLinkType.VIDEO) {
                        this.referer = "$origin/"
                        this.quality = q
                        this.headers = streamHeaders
                    }
                )
            }
        }
    }

    private suspend fun extractLeechDirectMp4(
        hashId: String,
        origin: String,
        referer: String,
        callback: (ExtractorLink) -> Unit
    ) {
        val res = runCatching {
            app.get("$origin/d/$hashId", referer = referer, headers = mapOf("User-Agent" to ArabicExtractorUtils.DEFAULT_UA))
        }.getOrNull() ?: return

        val doc = res.document
        val rawPage = doc.selectFirst("#app[data-page]")?.attr("data-page")?.let { Parser.unescapeEntities(it, false) } ?: return
        val pageJson = runCatching { JSONObject(rawPage) }.getOrNull() ?: return
        val attachments = pageJson.optJSONObject("props")?.optJSONArray("attachments") ?: return
        val version = pageJson.optString("version")

        for (i in 0 until attachments.length()) {
            val att = attachments.optJSONObject(i) ?: continue
            val attachId = att.optString("hashId").takeIf { it.isNotBlank() } ?: continue
            val label = att.optString("label").ifBlank { att.optString("resolution") }
            val q = ArabicExtractorUtils.parseQuality(label)

            val body = JSONObject().put("attach", attachId).toString().toRequestBody(ArabicExtractorUtils.JSON_MEDIA_TYPE)
            val postRes = runCatching {
                app.post(
                    url = "$origin/d/$hashId",
                    headers = mapOf(
                        "User-Agent" to ArabicExtractorUtils.DEFAULT_UA,
                        "Accept" to "text/html, application/xhtml+xml",
                        "Content-Type" to "application/json",
                        "X-Requested-With" to "XMLHttpRequest",
                        "X-Inertia" to "true",
                        "X-Inertia-Version" to version,
                        "X-Inertia-Partial-Component" to "files/leech/download",
                        "X-Inertia-Partial-Data" to "download",
                        "Origin" to origin,
                        "Referer" to "$origin/d/$hashId"
                    ),
                    requestBody = body
                ).text
            }.getOrNull() ?: continue

            val directUrl = runCatching {
                JSONObject(postRes).optJSONObject("props")?.optJSONObject("download")?.optJSONObject("data")?.optString("url")
            }.getOrNull()?.takeIf { it.startsWith("http") } ?: continue

            callback(
                newExtractorLink(name, "$name Direct ($label) [MP4]", directUrl, ExtractorLinkType.VIDEO) {
                    this.referer = "$origin/"
                    this.quality = q
                    this.headers = mapOf("User-Agent" to ArabicExtractorUtils.DEFAULT_UA)
                }
            )
        }
    }

    private fun decryptCryptoJs(jsonStr: String, passphrase: String): String? = runCatching {
        val obj = JSONObject(jsonStr)
        val ct = Base64.decode(obj.getString("ct"), Base64.DEFAULT)
        val iv = hexToBytes(obj.getString("iv"))
        val salt = hexToBytes(obj.getString("s"))
        val passBytes = passphrase.toByteArray(Charsets.UTF_8)
        val md5 = MessageDigest.getInstance("MD5")
        var derived = ByteArray(0)
        var block = ByteArray(0)
        while (derived.size < 48) {
            md5.reset()
            md5.update(block)
            md5.update(passBytes)
            md5.update(salt)
            block = md5.digest()
            derived += block
        }
        val key = derived.copyOfRange(0, 32)
        ArabicExtractorUtils.aesCbcDecrypt(key, iv, ct)
    }.getOrNull()

    private fun hexToBytes(hex: String): ByteArray {
        val clean = hex.trim()
        return ByteArray(clean.length / 2) { idx -> clean.substring(idx * 2, idx * 2 + 2).toInt(16).toByte() }
    }

    companion object {
        private const val LEECH_KEY = "k7v2m4x9bqwpnj6t"
    }
}

class Share4MaxExtractor : ArabicMegaMaxExtractor("Share4Max", "https://share4max.com")

open class ArabicByseExtractor(
    override val name: String = "Byse",
    override val mainUrl: String = "https://byseqekaho.com"
) : ExtractorApi() {
    override val requiresReferer = true

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val origin = ArabicExtractorUtils.originOf(url) ?: mainUrl
        val code = Regex("""/(?:e|d|v)/([A-Za-z0-9_-]+)""").find(url)?.groupValues?.get(1)
            ?: url.trimEnd('/').substringAfterLast('/').substringBefore('?').substringBefore('.')
        if (code.isBlank()) return

        val embedReferer = "$origin/e/$code"
        val apiUrl = "$origin/api/videos/$code"
        val body = runCatching {
            app.get(
                apiUrl,
                headers = mapOf(
                    "User-Agent" to ArabicExtractorUtils.DEFAULT_UA,
                    "Accept" to "application/json",
                    "Referer" to (referer ?: embedReferer)
                )
            ).text
        }.getOrNull() ?: return

        val playback = runCatching { JSONObject(body).optJSONObject("playback") }.getOrNull() ?: return
        val keyParts = playback.optJSONArray("key_parts") ?: return
        val version = playback.optInt("version", 1)
        val iv = ArabicExtractorUtils.b64UrlDecode(playback.optString("iv"))
        val payload = ArabicExtractorUtils.b64UrlDecode(playback.optString("payload"))
        val total = keyParts.length()
        val candidateKeys = ArrayList<ByteArray>()
        if (version in 1..20 && version <= total && (31 - version) in 1..total) {
            candidateKeys.add(
                ArabicExtractorUtils.b64UrlDecode(keyParts.optString(version - 1)) +
                    ArabicExtractorUtils.b64UrlDecode(keyParts.optString(30 - version))
            )
        }
        if (total >= 2) {
            candidateKeys.add(
                ArabicExtractorUtils.b64UrlDecode(keyParts.optString(0)) +
                    ArabicExtractorUtils.b64UrlDecode(keyParts.optString(1))
            )
        }

        var decryptedJson: String? = null
        for (k in candidateKeys) {
            val dec = ArabicExtractorUtils.aesGcmDecrypt(k, iv, payload)
            if (dec != null && dec.contains("sources")) {
                decryptedJson = dec
                break
            }
        }
        if (decryptedJson == null) return

        val decObj = runCatching { JSONObject(decryptedJson) }.getOrNull() ?: return
        val sources = decObj.optJSONArray("sources") ?: return
        val streamHeaders = mapOf(
            "User-Agent" to ArabicExtractorUtils.DEFAULT_UA,
            "Referer" to "$origin/",
            "Origin" to origin
        )

        for (i in 0 until sources.length()) {
            val s = sources.optJSONObject(i) ?: continue
            val file = s.optString("url").ifBlank { s.optString("file") }.trim()
            if (!file.startsWith("http")) continue
            val label = s.optString("label").ifBlank { s.optString("quality") }
            val q = ArabicExtractorUtils.parseQuality(label, "${s.optInt("height", 0)}p")

            if (file.contains(".m3u8")) {
                ArabicExtractorUtils.emitHls(name, "$name ($label)", file, "$origin/", streamHeaders, q, callback)
            } else {
                callback(
                    newExtractorLink(name, "$name ($label) [MP4]", file, ExtractorLinkType.VIDEO) {
                        this.referer = "$origin/"
                        this.quality = q
                        this.headers = streamHeaders
                    }
                )
            }
        }
    }
}
class ByseSxExtractor : ArabicByseExtractor("Byse", "https://byse.sx")
class ByseQekahoExtractor : ArabicByseExtractor("ByseQekaho", "https://byseqekaho.com")
class ByseRaguciExtractor : ArabicByseExtractor("ByseRaguci", "https://byseraguci.com")
class ByseZejataosExtractor : ArabicByseExtractor("ByseZejataos", "https://bysezejataos.com")

class ArabicFireStreamExtractor : ExtractorApi() {
    override val name = "FireStream"
    override val mainUrl = "https://firestream.to"
    override val requiresReferer = true

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val slug = Regex("""/(?:e|d|v)/([A-Za-z0-9_-]+)""").find(url)?.groupValues?.get(1)
            ?: url.substringBefore('?').substringBefore('#').trimEnd('/').substringAfterLast('/')
        if (slug.isBlank()) return

        val initialOrigin = ArabicExtractorUtils.originOf(url) ?: mainUrl
        val embedUrl = "$initialOrigin/e/$slug"
        val res = runCatching {
            app.get(embedUrl, referer = referer ?: "$initialOrigin/", headers = mapOf("User-Agent" to ArabicExtractorUtils.DEFAULT_UA))
        }.getOrNull() ?: return

        val finalUrl = res.url.ifBlank { embedUrl }
        val finalOrigin = ArabicExtractorUtils.originOf(finalUrl) ?: "https://firestream.site"
        val doc = res.document
        doc.selectFirst("script#video-data")?.data()?.let { raw ->
            runCatching {
                val subs = JSONObject(raw).optJSONObject("video")?.optJSONArray("subtitles")
                if (subs != null) {
                    for (i in 0 until subs.length()) {
                        val s = subs.optJSONObject(i) ?: continue
                        val subUrl = s.optString("url").takeIf { it.startsWith("http") } ?: continue
                        val lang = s.optString("label").ifBlank { "Arabic" }
                        subtitleCallback(newSubtitleFile(lang, subUrl))
                    }
                }
            }
        }

        val tokenBlob = doc.selectFirst("script#token-blob")?.data()?.trim().takeIf { !it.isNullOrBlank() } ?: return
        val postBody = JSONObject().put("blob", tokenBlob).toString().toRequestBody(ArabicExtractorUtils.JSON_MEDIA_TYPE)

        val resolveRes = runCatching {
            app.post(
                url = "$finalOrigin/api/videos/$slug/resolve",
                headers = mapOf(
                    "User-Agent" to ArabicExtractorUtils.DEFAULT_UA,
                    "Accept" to "application/json",
                    "Content-Type" to "application/json",
                    "Referer" to finalUrl,
                    "Origin" to finalOrigin
                ),
                requestBody = postBody
            ).text
        }.getOrNull() ?: return

        val resolveJson = runCatching { JSONObject(resolveRes) }.getOrNull() ?: return
        val primary = resolveJson.optString("signedVideoUrl").takeIf { it.startsWith("http") }
        val sd = resolveJson.optString("signedVideoSdUrl").takeIf { it.startsWith("http") }
        val headers = mapOf("User-Agent" to ArabicExtractorUtils.DEFAULT_UA, "Referer" to "$finalOrigin/")

        listOf(primary to Qualities.P1080.value, sd to Qualities.P480.value).forEach { (mUrl, defQ) ->
            if (mUrl != null) {
                if (mUrl.contains(".m3u8")) {
                    ArabicExtractorUtils.emitHls(name, name, mUrl, "$finalOrigin/", headers, defQ, callback)
                } else {
                    callback(
                        newExtractorLink(name, "$name [MP4]", mUrl, ExtractorLinkType.VIDEO) {
                            this.referer = "$finalOrigin/"
                            this.quality = defQ
                            this.headers = headers
                        }
                    )
                }
            }
        }
    }
}

class ArabicVidaraExtractor : ExtractorApi() {
    override val name = "Vidara"
    override val mainUrl = "https://vidaraa.cc"
    override val requiresReferer = true

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val filecode = Regex("""/(?:e|d|v)/([A-Za-z0-9_-]+)""").find(url)?.groupValues?.get(1)
            ?: url.trimEnd('/').substringAfterLast('/').substringBefore('?')
        if (filecode.isBlank()) return

        val origin = ArabicExtractorUtils.originOf(url) ?: mainUrl
        val payload = JSONObject()
            .put("filecode", filecode)
            .put("device", "web")
            .put("codecs", JSONArray())
            .toString()
            .toRequestBody(ArabicExtractorUtils.JSON_MEDIA_TYPE)

        val resText = runCatching {
            app.post(
                url = "$origin/api/stream",
                headers = mapOf(
                    "User-Agent" to ArabicExtractorUtils.DEFAULT_UA,
                    "Accept" to "application/json",
                    "Referer" to "$origin/e/$filecode",
                    "Origin" to origin
                ),
                requestBody = payload
            ).text
        }.getOrNull() ?: return

        val json = runCatching { JSONObject(resText) }.getOrNull() ?: return
        val streamUrl = json.optString("streaming_url").takeIf { it.startsWith("http") } ?: return
        val q = ArabicExtractorUtils.parseQuality(json.optString("title"))

        json.optJSONArray("subtitles")?.let { subs ->
            for (i in 0 until subs.length()) {
                val s = subs.optJSONObject(i) ?: continue
                val subUrl = s.optString("file_path").takeIf { it.startsWith("http") } ?: continue
                val lang = s.optString("label").ifBlank { "Arabic" }
                subtitleCallback(newSubtitleFile(lang, subUrl))
            }
        }

        val headers = mapOf("User-Agent" to ArabicExtractorUtils.DEFAULT_UA, "Referer" to "$origin/")
        if (streamUrl.contains(".m3u8")) {
            ArabicExtractorUtils.emitHls(name, name, streamUrl, "$origin/", headers, q, callback)
        } else {
            callback(
                newExtractorLink(name, "$name [MP4]", streamUrl, ExtractorLinkType.VIDEO) {
                    this.referer = "$origin/"
                    this.quality = q
                    this.headers = headers
                }
            )
        }
    }
}

class ArabicYonaPlayExtractor : ExtractorApi() {
    override val name = "YonaPlay"
    override val mainUrl = "https://mid.yonaplay.net"
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

        var code = ""
        var embedToken = ""
        var pageKey = ""

        Regex("""window\._E\s*=\s*(\{[^;]+\})""").find(pageHtml)?.groupValues?.get(1)?.let { raw ->
            runCatching { JSONObject(raw) }.getOrNull()?.let { obj ->
                code = obj.optString("c")
                embedToken = obj.optString("t")
                pageKey = obj.optString("k")
            }
        }

        if (code.isEmpty() || pageKey.isEmpty()) return
        val apiHeaders = mutableMapOf(
            "User-Agent" to ArabicExtractorUtils.DEFAULT_UA,
            "Origin" to origin,
            "Referer" to url
        )
        if (embedToken.isNotEmpty()) apiHeaders["X-Embed-Token"] = embedToken

        val srcBody = runCatching {
            val body = JSONObject().put("code", code).toString().toRequestBody(ArabicExtractorUtils.JSON_MEDIA_TYPE)
            app.post("$origin/api/sources.php", headers = apiHeaders, requestBody = body).text
        }.getOrNull() ?: return

        val qualitiesObj = runCatching { JSONObject(srcBody).optJSONObject("qualities") }.getOrNull() ?: return

        for (qKey in listOf("4k", "fhd", "hd", "sd")) {
            val qGroup = qualitiesObj.optJSONObject(qKey) ?: continue
            val servers = qGroup.optJSONArray("servers") ?: continue

            for (i in 0 until servers.length()) {
                val srv = servers.optJSONObject(i) ?: continue
                val token = srv.optString("token").takeIf { it.isNotBlank() } ?: continue
                val srvName = srv.optString("name", "Server")

                val reqJson = JSONObject().put("code", code).put("token", token).put("key", pageKey).toString()
                    .toRequestBody(ArabicExtractorUtils.JSON_MEDIA_TYPE)
                val aRes = runCatching {
                    app.post("$origin/api/api.php", headers = apiHeaders, requestBody = reqJson).text
                }.getOrNull() ?: continue

                val encD = runCatching { JSONObject(aRes).optString("d") }.getOrNull() ?: continue
                val subUrl = decryptYonaPlay(encD, pageKey) ?: continue

                if (subUrl.contains("dotplay.")) {
                    extractDotPlay(subUrl, "$origin/", "$name ($srvName)", ArabicExtractorUtils.parseQuality(qKey), callback)
                }
            }
        }
    }

    private fun decryptYonaPlay(b64Data: String, pageKey: String): String? = runCatching {
        val keyBytes = MessageDigest.getInstance("SHA-256").digest(pageKey.toByteArray(Charsets.UTF_8))
        val raw = Base64.decode(b64Data.trim(), Base64.DEFAULT)
        val iv = raw.copyOfRange(0, 12)
        val tag = raw.copyOfRange(12, 28)
        val ciphertext = raw.copyOfRange(28, raw.size)
        val ctWithTag = ByteArray(ciphertext.size + tag.size)
        System.arraycopy(ciphertext, 0, ctWithTag, 0, ciphertext.size)
        System.arraycopy(tag, 0, ctWithTag, ciphertext.size, tag.size)

        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(keyBytes, "AES"), GCMParameterSpec(128, iv))
        String(cipher.doFinal(ctWithTag), Charsets.UTF_8).trim()
    }.getOrNull()

    private suspend fun extractDotPlay(
        dotUrl: String,
        referer: String,
        label: String,
        quality: Int,
        callback: (ExtractorLink) -> Unit
    ) {
        val origin = ArabicExtractorUtils.originOf(dotUrl) ?: "https://dotplay.net"
        val html = runCatching {
            app.get(dotUrl, referer = referer, headers = mapOf("User-Agent" to ArabicExtractorUtils.DEFAULT_UA)).text
        }.getOrNull() ?: return

        val code = Regex("""window\.embedCode\s*=\s*["']([^"']+)["']""").find(html)?.groupValues?.get(1) ?: return
        val apiJson = runCatching {
            app.get("$origin/api.php?code=$code", referer = dotUrl, headers = mapOf("User-Agent" to ArabicExtractorUtils.DEFAULT_UA)).text
        }.getOrNull() ?: return

        val encoded = runCatching { JSONObject(apiJson).optString("video_url") }.getOrNull() ?: return
        val decoded = runCatching {
            String(Base64.decode(encoded, Base64.DEFAULT), Charsets.UTF_8).substringBefore('|').trim()
        }.getOrNull()?.takeIf { it.startsWith("http") } ?: return

        val isHls = decoded.contains(".m3u8")
        callback(
            newExtractorLink(label, label, decoded, if (isHls) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO) {
                this.referer = "$origin/"
                this.quality = quality
            }
        )
    }
}

class DotPlayExtractor : ExtractorApi() {
    override val name = "DotPlay"
    override val mainUrl = "https://dotplay.net"
    override val requiresReferer = true

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        ArabicYonaPlayExtractor().getUrl(url, referer, subtitleCallback, callback)
    }
}
