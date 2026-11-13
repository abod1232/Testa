package com.egydead

import android.util.Log
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.json.JSONArray
import org.json.JSONObject
import java.net.URL
import java.net.URLDecoder
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.min

object UniversalExtractor {
    private const val TAG = "UniversalExtractor"
    private const val DEFAULT_UA =
        "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"
    private const val FALLBACK_UA =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/122.0.0.0 Safari/537.36"
    suspend fun extract(
        url: String,
        referer: String? = null,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        return try {
            val serverUrl = fixUrl(url)
            if (serverUrl.isBlank()) return false

            val uri = runCatching { URL(serverUrl) }.getOrNull() ?: return false
            val domain = "${uri.protocol}://${uri.host}/"
            val serverName = uri.host.replace("www.", "").substringBefore(".")

            val emittedUrls = Collections.newSetFromMap(ConcurrentHashMap<String, Boolean>())
            val dedup: (ExtractorLink) -> Unit = { link ->
                val key = normalizeForDedup(link.url)
                if (emittedUrls.add(key)) callback(link)
            }
            var rawHtml = fetchHtml(serverUrl, referer ?: domain) ?: return false
            val cookieMap = extractCookiesFromHtml(rawHtml)
            val cookieHeader = cookieMap.entries.joinToString("; ") { "${it.key}=${it.value}" }
            Log.d(TAG, "Cookies found: $cookieMap")
            if (cookieMap.isNotEmpty()) {
                val withCookies = fetchHtml(serverUrl, referer ?: domain, cookieHeader)
                if (!withCookies.isNullOrBlank() && withCookies.length >= rawHtml.length) {
                    rawHtml = withCookies
                }
            }
            val unpacked = extractLogic(rawHtml)
            if (unpacked.length > rawHtml.length) {
                Log.d(TAG, "Unpacked content expanded: ${rawHtml.length} → ${unpacked.length}")
            }
            safeRun { extractSubtitles(unpacked, domain, subtitleCallback) }
            val videoLinks = findVideoLinks(unpacked)
                .map { fixUrl(it, domain) }
                .filter { it.isNotBlank() && (it.startsWith("http") || it.startsWith("/")) }
                .distinct()

            Log.d(TAG, "Candidate video links (${videoLinks.size}): ${videoLinks.take(10)}")
            val extraLinks = safeRunResult { tryDlEndpoint(rawHtml, serverUrl, domain, cookieHeader) } ?: emptyList()
            val allLinks = (videoLinks + extraLinks).distinct()

            var extractedAny = false

            for (rawLink in allLinks) {
                val finalUrl = fixUrl(rawLink, domain)
                if (!finalUrl.startsWith("http")) continue

                val isHls = isHlsUrl(finalUrl)
                val isVideo = isDirectVideo(finalUrl)

                if (!isHls && !isVideo) continue

                val verifyHeaders = buildVerifyHeaders(domain, finalUrl, cookieHeader)
                val ok = verifyUrl(finalUrl, verifyHeaders)
                if (!ok) {
                    Log.d(TAG, "Verify failed for: $finalUrl")
                    val altHeaders = verifyHeaders + mapOf("User-Agent" to FALLBACK_UA)
                    if (!verifyUrl(finalUrl, altHeaders)) continue
                }

                if (isHls) {
                    val m3u8Links = safeRunResult {
                        M3u8Helper.generateM3u8(
                            source = serverName,
                            streamUrl = finalUrl,
                            referer = domain,
                            headers = verifyHeaders,
                            name = serverName
                        )
                    }.orEmpty()

                    if (m3u8Links.isNotEmpty()) {
                        m3u8Links.forEach(dedup)
                        extractedAny = true
                    } else {
                        val (q, qName) = parseQualityFromUrl(finalUrl)
                        dedup(newExtractorLink(
                            source = serverName,
                            name = "$serverName $qName".trim(),
                            url = finalUrl,
                            type = ExtractorLinkType.M3U8
                        ) {
                            this.referer = domain
                            this.quality = q
                            this.headers = verifyHeaders
                            
                        })
                        extractedAny = true
                    }
                } else {
                    val (q, qName) = parseQualityFromUrl(finalUrl)
                    dedup(newExtractorLink(
                        source = serverName,
                        name = "$serverName [$qName]",
                        url = finalUrl,
                        type = ExtractorLinkType.VIDEO
                    ) {
                        this.referer = domain
                        this.quality = q
                        this.headers = verifyHeaders
                        
                    })
                    extractedAny = true
                }
            }

            extractedAny
        } catch (e: Exception) {
            Log.e(TAG, "Extraction failed for $url: ${e.message}", e)
            false
        }
    }
    private suspend fun fetchHtml(
        url: String,
        referer: String,
        cookie: String = ""
    ): String? {
        val h = mutableMapOf(
            "Referer" to referer,
            "User-Agent" to DEFAULT_UA,
            "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
            "Accept-Language" to "en-US,en;q=0.9,ar;q=0.8"
        )
        if (cookie.isNotBlank()) h["Cookie"] = cookie
        return safeRunResult {
            val r = app.get(url, headers = h, timeout = 20)
            if (r.code in 200..399) r.text else null
        }
    }

    private fun buildVerifyHeaders(domain: String, finalUrl: String, cookie: String): Map<String, String> {
        val map = mutableMapOf(
            "Referer" to domain,
            "Origin" to domain.removeSuffix("/"),
            "User-Agent" to DEFAULT_UA,
            "Accept" to "*/*",
            "Accept-Language" to "en-US,en;q=0.9",
            "Sec-Fetch-Dest" to "empty",
            "Sec-Fetch-Mode" to "cors",
            "Sec-Fetch-Site" to "cross-site",
            "Range" to "bytes=0-"
        )
        if (cookie.isNotBlank()) map["Cookie"] = cookie
        return map
    }

    private suspend fun verifyUrl(url: String, headers: Map<String, String>): Boolean {
        return safeRunResult {
            val r = app.get(url, headers = headers, timeout = 12, allowRedirects = true)
            r.code in listOf(200, 206, 301, 302, 304)
        } ?: false
    }
    private fun extractCookiesFromHtml(html: String): Map<String, String> {
        val result = mutableMapOf<String, String>()
        val patterns = listOf(
            """\$\.cookie\(\s*['"]([^'"]+)['"]\s*,\s*['"]([^'"]*)['"]""",
            """document\.cookie\s*=\s*['"]([^'"=]+)=([^;'"]+)""",
            """setCookie\(\s*['"]([^'"]+)['"]\s*,\s*['"]([^'"]*)['"]"""
        )
        for (p in patterns) {
            Regex(p).findAll(html).forEach { m ->
                if (m.groupValues.size >= 3) {
                    result[m.groupValues[1]] = m.groupValues[2]
                }
            }
        }
        return result
    }
    private fun deobfuscatePacker(packed: String): String {
        return try {
            val packerRegex = Regex(
                """eval\s*\(\s*function\s*\(\s*p\s*,\s*a\s*,\s*c\s*,\s*k\s*,\s*e\s*(?:,\s*[a-zA-Z])?\s*\)\s*\{.*?\}\s*\(\s*(['"])(.*?)\1\s*,\s*(\d+)\s*,\s*(\d+)\s*,\s*(['"])(.*?)\5\.split\s*\(\s*(['"])\|?\7\s*\)""",
                RegexOption.DOT_MATCHES_ALL
            )
            val match = packerRegex.find(packed) ?: return ""
            val p = match.groupValues[2]
            val a = match.groupValues[3].toIntOrNull() ?: return ""
            val c = match.groupValues[4].toIntOrNull() ?: return ""
            val k = match.groupValues[6].split('|')

            fun toBaseN(num: Int, base: Int): String {
                if (base == 36) return num.toString(36)
                if (base == 62) {
                    val chars = "0123456789abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ"
                    if (num == 0) return "0"
                    var n = num
                    val sb = StringBuilder()
                    while (n > 0) { sb.append(chars[n % 62]); n /= 62 }
                    return sb.reverse().toString()
                }
                if (num == 0) return "0"
                var n = num
                val sb = StringBuilder()
                while (n > 0) { sb.append(Character.forDigit(n % base, base)); n /= base }
                return sb.reverse().toString()
            }

            val dict = HashMap<String, String>(c * 2)
            for (i in 0 until c) {
                val key = toBaseN(i, a)
                val value = if (i < k.size && k[i].isNotEmpty()) k[i] else key
                dict[key] = value
            }

            Regex("""\b\w+\b""").replace(p) { mr ->
                dict[mr.value] ?: mr.value
            }
        } catch (e: Exception) {
            Log.w(TAG, "Packer deobfuscation failed: ${e.message}")
            ""
        }
    }
    private fun extractLogic(html: String): String {
        var current = html
        var iter = 0
        while (iter < 5) {
            iter++
            val startIdx = current.indexOf("eval(function(p,a,c,k,e,")
            if (startIdx == -1) break
            val endMarkers = listOf(".split('|')))", ".split('|'))", ".split(\"|\")))")
            var endIdx = -1
            var endLen = 0
            for (em in endMarkers) {
                val i = current.indexOf(em, startIdx)
                if (i != -1 && (endIdx == -1 || i < endIdx)) {
                    endIdx = i
                    endLen = em.length
                }
            }
            if (endIdx == -1) break
            val packed = current.substring(startIdx, endIdx + endLen)
            val unpacked = deobfuscatePacker(packed)
            if (unpacked.isBlank()) break
            current = current.substring(0, startIdx) + unpacked + current.substring(min(current.length, endIdx + endLen))
        }
        current = current.replace("\\/", "/").replace("\\u002F", "/").replace("\\u002f", "/")

        return current
    }
    private fun findVideoLinks(text: String): List<String> {
        val found = LinkedHashSet<String>()
        val objectJsonRegex = Regex(
            """(?:var|let|const)\s+\w+\s*=\s*(\{[^{}]*(?:hls|m3u8|mp4|stream|file)[^{}]*\})""",
            RegexOption.IGNORE_CASE
        )
        objectJsonRegex.findAll(text).forEach { m ->
            safeRun {
                val obj = JSONObject(m.groupValues[1])
                for (key in obj.keys()) {
                    val v = obj.optString(key).trim()
                    val u = cleanUrl(v)
                    if (looksLikeVideoUrl(u)) found.add(u)
                }
            }
        }
        Regex("""getElementById\(['"][^'"]+['"]\)\.innerHTML\s*=\s*['"]([^'"]+)['"]\s*\+\s*\(?['"]([^'"]+)['"]\)?""")
            .find(text)?.let { m ->
                var direct = m.groupValues[1] + m.groupValues[2]
                if (direct.startsWith("//")) direct = "https:$direct"
                if (!direct.contains("&stream=")) direct += "&stream=1"
                if (looksLikeVideoUrl(direct)) found.add(direct)
            }
        Regex("""(?:file|sources?|src|url|stream)\s*[:=]\s*\[?\s*["']([^"']+\.(?:m3u8|mp4|txt|php)[^"']*)["']""", RegexOption.IGNORE_CASE)
            .findAll(text).forEach { m ->
                val u = cleanUrl(m.groupValues[1])
                if (looksLikeVideoUrl(u)) found.add(u)
            }
        Regex("""(https?:[^\s"'<>]+?\.m3u8[^\s"'<>?]*(?:\?[^\s"'<>]*)?)""")
            .findAll(text).forEach { m -> found.add(cleanUrl(m.groupValues[1])) }
        Regex("""(https?:[^\s"'<>]+?\.mp4[^\s"'<>?]*(?:\?[^\s"'<>]*)?)""")
            .findAll(text).forEach { m -> found.add(cleanUrl(m.groupValues[1])) }
        Regex("""(https?:[^\s"'<>]+?/(?:hls|stream|index)[^\s"'<>]*\.txt[^\s"'<>]*)""", RegexOption.IGNORE_CASE)
            .findAll(text).forEach { m -> found.add(cleanUrl(m.groupValues[1])) }
        Regex("""["'](/[^"']+\.m3u8[^"']*)["']""").findAll(text).forEach { m ->
            found.add(m.groupValues[1].trim())
        }

        return found.toList()
    }
    private suspend fun tryDlEndpoint(
        html: String,
        baseUrl: String,
        domain: String,
        cookie: String
    ): List<String> {
        val fileId = Regex("""\$\.cookie\(['"]file_id['"]\s*,\s*['"]([^'"]+)['"]""")
            .find(html)?.groupValues?.get(1) ?: return emptyList()

        val results = mutableListOf<String>()
        val tries = listOf(
            "${domain}dl?op=view4&file_code=$fileId&stream=1",
            "${domain}dl?op=view4&file_code=$fileId",
            "${domain}dl?op=view&file_code=$fileId"
        )
        for (u in tries) {
            val body = safeRunResult {
                val r = app.get(u, headers = mapOf(
                    "Referer" to baseUrl,
                    "User-Agent" to DEFAULT_UA,
                    "Accept" to "*/*",
                    "X-Requested-With" to "XMLHttpRequest",
                    "Cookie" to cookie
                ), timeout = 12)
                if (r.code == 200) r.text else null
            }
            if (!body.isNullOrBlank()) {
                results += findVideoLinks(body)
                if (results.isNotEmpty()) break
            }
        }
        return results.distinct()
    }
    private fun extractSubtitles(text: String, domain: String, cb: (SubtitleFile) -> Unit) {
        val patterns = listOf(
            """["'](?:file|src|url)["']?\s*:\s*["']([^"']+\.(?:vtt|srt)[^"']*)["']""",
            """(https?:[^\s"'<>]+\.(?:vtt|srt)[^\s"'<>]*)"""
        )
        for (p in patterns) {
            Regex(p, RegexOption.IGNORE_CASE).findAll(text).forEach { m ->
                val raw = m.groupValues[1].trim()
                if (raw.isEmpty()) return@forEach
                val subUrl = when {
                    raw.startsWith("//") -> "https:$raw"
                    raw.startsWith("http") -> raw
                    raw.startsWith("/") -> "${domain.trimEnd('/')}$raw"
                    else -> safeRunResult { URL(URL(domain), raw).toString() } ?: raw
                }
                val lang = when {
                    subUrl.contains("ar", true) -> "Arabic"
                    subUrl.contains("en", true) -> "English"
                    else -> "Arabic"
                }
                safeRun { cb(SubtitleFile(lang, subUrl)) }
            }
        }
    }
    private fun parseQualityFromUrl(url: String): Pair<Int, String> {
        val lower = url.lowercase()
        return when {
            lower.contains("2160") || lower.contains("4k")   -> Qualities.P2160.value to "4K"
            lower.contains("1440") || lower.contains("2k")   -> Qualities.P1440.value to "2K"
            lower.contains("1080") || lower.contains("fhd")  -> Qualities.P1080.value to "1080p"
            lower.contains("720")  || lower.contains("hd")   -> Qualities.P720.value  to "720p"
            lower.contains("480")  || lower.contains("sd")   -> Qualities.P480.value  to "480p"
            lower.contains("360")                            -> Qualities.P360.value  to "360p"
            else -> {
                val q = getQualityFromName(lower)
                if (q > 0) q to "${q}p" else Qualities.P720.value to "720p"
            }
        }
    }

    private fun isHlsUrl(u: String): Boolean {
        val l = u.lowercase()
        return l.contains(".m3u8") ||
               (l.contains(".txt") && (l.contains("hls") || l.contains("index") || l.contains("master")))
    }

    private fun isDirectVideo(u: String): Boolean {
        val l = u.lowercase()
        return l.contains(".mp4") || l.contains(".mkv") || l.contains(".webm") || l.contains(".avi")
    }

    private fun looksLikeVideoUrl(u: String): Boolean {
        if (u.isBlank()) return false
        val l = u.lowercase()
        return l.startsWith("http") || l.startsWith("/") || l.startsWith("//")
    }

    private fun cleanUrl(u: String): String {
        return u.trim()
            .replace("\\/", "/")
            .replace("\\u002F", "/")
            .replace("\\u002f", "/")
            .replace("&amp;", "&")
    }

    private fun normalizeForDedup(u: String): String {
        return u.substringBefore("#").trim()
    }

    private fun fixUrl(url: String, baseDomain: String = ""): String {
        var t = url.trim()
        if (t.startsWith("//")) return "https:$t"
        if (t.startsWith("/") && baseDomain.isNotBlank()) return "${baseDomain.trimEnd('/')}$t"
        return t
    }

    private inline fun <T> safeRun(block: () -> T?): T? =
        try { block() } catch (e: Exception) { Log.w(TAG, "safeRun: ${e.message}"); null }

    private inline fun <T> safeRunResult(block: () -> T?): T? = safeRun(block)
}
