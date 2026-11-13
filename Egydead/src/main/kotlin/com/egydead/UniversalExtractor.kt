package com.egydead

import android.util.Log
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.json.JSONObject
import java.net.URL
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap

object UniversalExtractor {
    private const val TAG = "UniversalExtractor"
    private const val DEFAULT_UA = "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"

    /**
     * الدالة العامة لاستخراج أي مشغل أو Iframe مجهول
     */
    suspend fun extract(
        url: String,
        referer: String? = null,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        return try {
            val serverUrl = fixUrl(url)
            val uri = URL(serverUrl)
            val domain = "${uri.protocol}://${uri.host}/"
            val serverName = uri.host.replace("www.", "").substringBefore(".")

            val emittedUrls = Collections.newSetFromMap(ConcurrentHashMap<String, Boolean>())
            val deduplicatedCallback: (ExtractorLink) -> Unit = { link ->
                if (emittedUrls.add(link.url.trim())) {
                    callback(link)
                }
            }

            val headers = mapOf(
                "Referer" to (referer ?: domain),
                "User-Agent" to DEFAULT_UA,
                "Accept" to "*/*"
            )
            val response = app.get(serverUrl, headers = headers, timeout = 20)
            if (response.code != 200) return false

            val rawHtml = response.text
            val unpackedContent = extractLogic(rawHtml)
            extractSubtitles(unpackedContent, domain, subtitleCallback)
            val videoLinks = findVideoLinks(unpackedContent)
            var extractedAny = false

            for (videoLink in videoLinks) {
                val finalVideoUrl = fixUrl(videoLink)
                if (emittedUrls.contains(finalVideoUrl.trim())) continue

                val isHls = finalVideoUrl.contains(".m3u8") || finalVideoUrl.contains(".txt")
                val verifyHeaders = mapOf(
                    "Referer" to domain,
                    "Origin" to domain.removeSuffix("/"),
                    "User-Agent" to DEFAULT_UA
                )
                val check = app.get(finalVideoUrl, headers = verifyHeaders, timeout = 10)
                if (check.code == 200) {
                    if (isHls) {
                        val m3u8Links = runCatching {
                            M3u8Helper.generateM3u8(
                                source = serverName,
                                streamUrl = finalVideoUrl,
                                referer = domain,
                                headers = verifyHeaders,
                                name = serverName
                            )
                        }.getOrNull()

                        if (!m3u8Links.isNullOrEmpty()) {
                            m3u8Links.forEach { deduplicatedCallback(it) }
                            extractedAny = true
                        } else {
                            val (qualityVal, qualityName) = parseQualityFromUrl(finalVideoUrl)
                            deduplicatedCallback(
                                newExtractorLink(
                                    source = serverName,
                                    name = "$serverName $qualityName".trim(),
                                    url = finalVideoUrl,
                                    type = ExtractorLinkType.M3U8
                                ) {
                                    this.referer = domain
                                    this.quality = qualityVal
                                    this.headers = verifyHeaders
                                }
                            )
                            extractedAny = true
                        }
                    } else {
                        val (qualityVal, qualityName) = parseQualityFromUrl(finalVideoUrl)
                        deduplicatedCallback(
                            newExtractorLink(
                                source = serverName,
                                name = "$serverName [$qualityName]",
                                url = finalVideoUrl,
                                type = ExtractorLinkType.VIDEO
                            ) {
                                this.referer = domain
                                this.quality = qualityVal
                                this.headers = verifyHeaders
                            }
                        )
                        extractedAny = true
                    }
                }
            }

            extractedAny
        } catch (e: Exception) {
            Log.e(TAG, "Extraction failed for $url: ${e.message}")
            false
        }
    }

    /**
     * خوارزمية فك تشفير Dean Edwards Packer
     */
    private fun deobfuscatePacker(packed: String): String {
        return try {
            val packerRegex = """eval\(function\(p,a,c,k,e,[rd]\)\{.*?\}\((['"])(.*?)\1\s*,\s*(\d+)\s*,\s*(\d+)\s*,\s*(['"])(.*?)\5\.split\(['"]\|['"]\)""".toRegex()
            val match = packerRegex.find(packed) ?: return ""
            val (_, p, aStr, cStr, _, kStr) = match.destructured
            val a = aStr.toIntOrNull() ?: return ""
            val c = cStr.toIntOrNull() ?: return ""
            val k = kStr.split('|')

            fun getBaseNString(num: Int, base: Int): String {
                val chars = "0123456789abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ"
                if (base <= 36) return num.toString(base)
                var n = num
                var res = ""
                while (n > 0) {
                    res = chars[n % base] + res
                    n /= base
                }
                return if (res.isEmpty()) "0" else res
            }

            val dict = mutableMapOf<String, String>()
            for (i in 0 until c) {
                val key = getBaseNString(i, a)
                val value = if (i < k.size && k[i].isNotEmpty()) k[i] else key
                dict[key] = value
            }

            val wordRegex = """\b\w+\b""".toRegex()
            wordRegex.replace(p) { matchResult ->
                val word = matchResult.value
                dict[word] ?: word
            }
        } catch (e: Exception) {
            ""
        }
    }

    private fun extractLogic(htmlText: String): String {
        return try {
            val startMarker = "eval(function(p,a,c,k,e,"
            val startIdx = htmlText.indexOf(startMarker)
            if (startIdx != -1) {
                val endMarker = ".split('|')))"
                val endIdx = htmlText.indexOf(endMarker, startIdx)
                if (endIdx != -1) {
                    val packedJs = htmlText.substring(startIdx, endIdx + endMarker.length)
                    val unpacked = deobfuscatePacker(packedJs)
                    if (unpacked.isNotEmpty()) return unpacked
                }
            }
            htmlText
        } catch (e: Exception) {
            htmlText
        }
    }

    /**
     * البحث بالـ Regex عن كل أشكال روابط الفيديو المباشرة والـ JSON
     */
    private fun findVideoLinks(text: String): List<String> {
        val found = LinkedHashSet<String>()
        Regex("""(?:var|let|const)\s+links\s*=\s*(\{[^;]+\})""").find(text)?.groupValues?.get(1)?.let { rawJson ->
            runCatching {
                val obj = JSONObject(rawJson)
                for (k in listOf("hls4", "hls2", "hls3", "hls", "file", "stream")) {
                    val l = obj.optString(k).trim().replace("\\/", "/")
                    if (l.startsWith("http")) found.add(l)
                }
            }
        }
        Regex("""getElementById\(['"][^'"]+['"]\)\.innerHTML\s*=\s*['"]([^'"]+)['"]\s*\+\s*\(?['"]([^'"]+)['"]\)?""").find(text)?.let { m ->
            var direct = m.groupValues[1] + m.groupValues[2]
            if (direct.startsWith("//")) direct = "https:$direct"
            if (!direct.contains("&stream=")) direct += "&stream=1"
            found.add(direct)
        }
        val fileRegex = """(?:file|sources?|src)\s*[:=]\s*\[?["']([^"']+\.(?:m3u8|mp4)[^"']*)["']\]?""".toRegex(RegexOption.IGNORE_CASE)
        fileRegex.findAll(text).forEach { m ->
            var l = m.groupValues[1].trim().replace("\\/", "/")
            if (l.startsWith("//")) l = "https:$l"
            if (l.startsWith("http")) found.add(l)
        }
        val m3u8Regex = """(https?://[^\s"\'<>]+?\.m3u8[^\s"\'<>?]*(?:\?[^\s"\'<>]*)?)""".toRegex()
        m3u8Regex.findAll(text).forEach { m ->
            found.add(m.groupValues[1].trim().replace("\\/", "/"))
        }
        val mp4Regex = """(https?://[^\s"\'<>]+?\.mp4[^\s"\'<>?]*(?:\?[^\s"\'<>]*)?)""".toRegex()
        mp4Regex.findAll(text).forEach { m ->
            found.add(m.groupValues[1].trim().replace("\\/", "/"))
        }

        return found.toList()
    }

    private fun extractSubtitles(text: String, domain: String, subtitleCallback: (SubtitleFile) -> Unit) {
        val subRegex = """["'](?:file)?["']?\s*:\s*["']([^"']+\.(?:vtt|srt)[^"']*)["']""".toRegex()
        subRegex.findAll(text).forEach { match ->
            var subUrl = match.groupValues[1].trim().replace("\\/", "/")
            if (subUrl.startsWith("//")) subUrl = "https:$subUrl"
            if (!subUrl.startsWith("http")) subUrl = URL(URL(domain), subUrl).toString()

            subtitleCallback(
                SubtitleFile(
                    lang = "Arabic",
                    url = subUrl
                )
            )
        }
    }

    private fun parseQualityFromUrl(url: String): Pair<Int, String> {
        val lower = url.lowercase()
        return when {
            lower.contains("2160") || lower.contains("4k") -> Pair(Qualities.P2160.value, "4K")
            lower.contains("1440") || lower.contains("2k") -> Pair(Qualities.P1440.value, "2K")
            lower.contains("1080") || lower.contains("fhd") -> Pair(Qualities.P1080.value, "1080p")
            lower.contains("720") || lower.contains("hd") -> Pair(Qualities.P720.value, "720p")
            lower.contains("480") || lower.contains("sd") -> Pair(Qualities.P480.value, "480p")
            lower.contains("360") -> Pair(Qualities.P360.value, "360p")
            else -> {
                val q = getQualityFromName(lower)
                if (q > 0) Pair(q, "${q}p") else Pair(Qualities.P720.value, "720p")
            }
        }
    }
}
