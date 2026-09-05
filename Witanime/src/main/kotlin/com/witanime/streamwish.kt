package com.witanime
import com.lagradost.api.Log
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.USER_AGENT
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.M3u8Helper
import com.lagradost.cloudstream3.utils.getAndUnpack
import com.lagradost.cloudstream3.utils.getPacked
import com.lagradost.cloudstream3.network.WebViewResolver
import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.Prerelease
import com.lagradost.cloudstream3.newSubtitleFile
import com.lagradost.cloudstream3.utils.AppUtils.tryParseJson

import com.lagradost.cloudstream3.utils.newExtractorLink
import kotlin.collections.orEmpty

class Mwish : StreamWishExtractor() {
    override val name = "Mwish"
    override val mainUrl = "https://mwish.pro"
}
class hgcloud : StreamWishExtractor() {
    override val name = "hgcloud"
    override val mainUrl = "https://hgcloud.to"
}
class Dwish : StreamWishExtractor() {
    override val name = "Dwish"
    override val mainUrl = "https://dwish.pro"
}

class Ewish : StreamWishExtractor() {
    override val name = "Embedwish"
    override val mainUrl = "https://embedwish.com"
}

class WishembedPro : StreamWishExtractor() {
    override val name = "Wishembed"
    override val mainUrl = "https://wishembed.pro"
}

class Kswplayer : StreamWishExtractor() {
    override val name = "Kswplayer"
    override val mainUrl = "https://kswplayer.info"
}

class Wishfast : StreamWishExtractor() {
    override val name = "Wishfast"
    override val mainUrl = "https://wishfast.top"
}

class Streamwish2 : StreamWishExtractor() {
    override val mainUrl = "https://streamwish.site"
}

class SfastwishCom : StreamWishExtractor() {
    override val name = "Sfastwish"
    override val mainUrl = "https://sfastwish.com"
}

class Strwish : StreamWishExtractor() {
    override val name = "Strwish"
    override val mainUrl = "https://strwish.xyz"
}

class Strwish2 : StreamWishExtractor() {
    override val name = "Strwish"
    override val mainUrl = "https://strwish.com"
}

class FlaswishCom : StreamWishExtractor() {
    override val name = "Flaswish"
    override val mainUrl = "https://flaswish.com"
}

class Awish : StreamWishExtractor() {
    override val name = "Awish"
    override val mainUrl = "https://awish.pro"
}

class Obeywish : StreamWishExtractor() {
    override val name = "Obeywish"
    override val mainUrl = "https://obeywish.com"
}

class Jodwish : StreamWishExtractor() {
    override val name = "Jodwish"
    override val mainUrl = "https://jodwish.com"
}

class Swhoi : StreamWishExtractor() {
    override val name = "Swhoi"
    override val mainUrl = "https://swhoi.com"
}

class Multimovies : StreamWishExtractor() {
    override val name = "Multimovies"
    override val mainUrl = "https://multimovies.cloud"
}

class UqloadsXyz : StreamWishExtractor() {
    override val name = "Uqloads"
    override val mainUrl = "https://uqloads.xyz"
}

class Doodporn : StreamWishExtractor() {
    override val name = "Doodporn"
    override val mainUrl = "https://doodporn.xyz"
}

class CdnwishCom : StreamWishExtractor() {
    override val name = "Cdnwish"
    override val mainUrl = "https://cdnwish.com"
}

class Asnwish : StreamWishExtractor() {
    override val name = "Asnwish"
    override val mainUrl = "https://asnwish.com"
}

class Nekowish : StreamWishExtractor() {
    override val name = "Nekowish"
    override val mainUrl = "https://nekowish.my.id"
}

class Nekostream : StreamWishExtractor() {
    override val name = "Nekostream"
    override val mainUrl = "https://neko-stream.click"
}

class Swdyu : StreamWishExtractor() {
    override val name = "Swdyu"
    override val mainUrl = "https://swdyu.com"
}

class Wishonly : StreamWishExtractor() {
    override val name = "Wishonly"
    override val mainUrl = "https://wishonly.site"
}

class Playerwish : StreamWishExtractor() {
    override val name = "Playerwish"
    override val mainUrl = "https://playerwish.com"
}

class StreamHLS : StreamWishExtractor() {
    override val name = "StreamHLS"
    override val mainUrl = "https://streamhls.to"
}

class HlsWish : StreamWishExtractor() {
    override val name = "HlsWish"
    override val mainUrl = "https://hlswish.com"
}

open class StreamWishExtractor : ExtractorApi() {
    override val name = "Streamwish"
    override val mainUrl = "https://streamwish.to"
    override val requiresReferer = true

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val headers = mapOf(
            "Accept" to "*/*",
            "Connection" to "keep-alive",
            "Sec-Fetch-Dest" to "empty",
            "Sec-Fetch-Mode" to "cors",
            "Sec-Fetch-Site" to "cross-site",
            "Referer" to "$mainUrl/",
            "Origin" to "$mainUrl/",
            "User-Agent" to USER_AGENT
        )

        val pageResponse = app.get(resolveEmbedUrl(url), referer = referer)

        val playerScriptData = when {
            !getPacked(pageResponse.text).isNullOrEmpty() -> getAndUnpack(pageResponse.text)
            pageResponse.document.select("script")
                .any { it.html().contains("jwplayer(\"vplayer\").setup(") } ->
                pageResponse.document.select("script").firstOrNull {
                    it.html().contains("jwplayer(\"vplayer\").setup(")
                }?.html()

            else -> pageResponse.document.selectFirst("script:containsData(sources:)")?.data()
        }

        val linkFound = JwPlayerHelper.extractStreamLinks(
            playerScriptData.orEmpty(),
            name,
            mainUrl,
            callback,
            subtitleCallback,
            headers
        )

        if (!linkFound) {
            val webViewM3u8Resolver = WebViewResolver(
                interceptUrl = Regex("""txt|m3u8"""),
                additionalUrls = listOf(Regex("""txt|m3u8""")),
                useOkhttp = false,
                timeout = 15_000L
            )

            val interceptedStreamUrl = app.get(
                url,
                referer = referer,
                interceptor = webViewM3u8Resolver
            ).url

            if (interceptedStreamUrl.isNotEmpty()) {
                M3u8Helper.generateM3u8(
                    name,
                    interceptedStreamUrl,
                    mainUrl,
                    headers = headers
                ).forEach(callback)
            } else {
                Log.d("StreamwishExtractor", "No m3u8 found in fallback either.")
            }
        }
    }

    private fun resolveEmbedUrl(inputUrl: String): String {
        return if (inputUrl.contains("/f/")) {
            val videoId = inputUrl.substringAfter("/f/")
            "$mainUrl/$videoId"
        } else if (inputUrl.contains("/e/")) {
            val videoId = inputUrl.substringAfter("/e/")
            "$mainUrl/$videoId"
        } else {
            inputUrl
        }
    }

    @Prerelease
    object JwPlayerHelper {
        private val sourceRegex = Regex(""""?sources"?:\s*(\[.*?\])""")
        private val tracksRegex = Regex(""""?tracks"?:\s*(\[.*?\])""")
        private val m3u8Regex = Regex("""[:=]\s*\"([^\"\s]+(\.m3u8|master\.txt)[^\"\s]*)""")

        /**
         * Get stream links the "sources" attribute inside a JWPlayer script, e.g.
         *
         * ```js
         * <script>
         * jwplayer("vplayer").setup({
         *     sources: [{file:"https://example.com/master.m3u8"}],
         *     tracks: [{file: "https://example.com/subtitles.vtt", kind: "captions", label: "en"}],
         * }
         *  ```
         *
         *  @param script The content of a HTML <script> tag containing the jwplayer code.
         *  @return whether any extractor or subtitle link was found
         */
        suspend fun extractStreamLinks(
            script: String,
            sourceName: String,
            mainUrl: String,
            callback: (ExtractorLink) -> Unit,
            subtitleCallback: (SubtitleFile) -> Unit,
            headers: Map<String, String> = mapOf()
        ): Boolean {
            val sourceMatches = sourceRegex.findAll(script).flatMap { sourceMatch ->
                val match = sourceMatch.groupValues[1]
                    .addMarks("file")
                    .addMarks("label")
                    .addMarks("type")
                tryParseJson<List<Source>>(match).orEmpty()
            }.toList()

            var extractedLinks = sourceMatches.flatMap { link ->
                if (link.file.contains(".m3u8")) {
                    try {
                        M3u8Helper.generateM3u8(
                            source = sourceName,
                            streamUrl = link.file,
                            referer = mainUrl,
                            headers = headers
                        )
                    } catch (e: Exception) {
                        Log.d("JW_PLAYER_HELPER", "Error generating M3U8 links: ${e.message}")
                        emptyList()
                    }
                } else {
                    listOf(
                        newExtractorLink(
                            source = sourceName,
                            name = sourceName,
                            url = fixUrl(link.file, mainUrl),
                        ) {
                            this.referer = url
                            this.headers = headers
                        }
                    )
                }
            }

            // Fallback to searching for HLS streams, e.g.
            // var links = {
            //  "hls3": "https://mmmmmmmmmm.qqqqqqqqqqqq.space/#########/hls3/01/00000/ggggggggg_l/master.txt",
            //  "hls4": "/stream/zzzzzzzzzzzzzzz/hhhhhhhhhhh/123456789/123456/master.m3u8",
            //  "hls2": "https://mmmmmmmmmm.qqqqqqqqqqqq.com/hls2/01/00000/ggggggggg_l/master.m3u8?t=##################&s=123456"
            // };
            // jwplayer("vplayer").setup({
            //  sources: [{
            //    file: links.hls4 || links.hls3 || links.hls2,
            //    type: "hls"
            //  }],
            if (extractedLinks.isEmpty()) {
                extractedLinks = m3u8Regex.findAll(script).toList().map { match ->
                    val link = match.groupValues[1]

                    newExtractorLink(
                        source = sourceName,
                        name = sourceName,
                        url = fixUrl(link, mainUrl)
                    ) {
                        this.referer = url
                        this.headers = headers
                    }
                }
            }

            val tracksMatches = tracksRegex.findAll(script).flatMap { trackMatch ->
                val match = trackMatch.groupValues[1]
                    .addMarks("file")
                    .addMarks("label")
                    .addMarks("kind")
                tryParseJson<List<Track>>(match).orEmpty()
            }.toList()

            val subtitleFiles =
                tracksMatches.filter {
                    (it.kind.orEmpty().contains("caption") || it.kind.orEmpty()
                        .contains("subtitle")) && it.file != null && it.label != null
                }.map {
                    newSubtitleFile(
                        lang = it.label!!,
                        url = fixUrl(it.file!!, mainUrl)
                    )
                }

            extractedLinks.forEach { callback.invoke(it) }
            subtitleFiles.forEach { subtitleCallback.invoke(it) }

            return sourceMatches.isNotEmpty() || subtitleFiles.isNotEmpty()
        }

        fun canParseJwScript(script: String): Boolean {
            return sourceRegex.containsMatchIn(script)
        }

        private fun fixUrl(url: String, mainUrl: String): String {
            return when {
                url.startsWith("/") -> mainUrl + url
                url.startsWith("http") -> url
                else -> "$mainUrl/$url"
            }
        }

        private fun String.addMarks(str: String): String {
            return this.replace(Regex("\"?$str\"?"), "\"$str\"")
        }

        private data class Source(
            @JsonProperty("file") val file: String,
            @JsonProperty("label") val label: String?,
            @JsonProperty("type") val type: String?,
        )

        data class Track(
            @JsonProperty("file") val file: String? = null,
            @JsonProperty("label") val label: String? = null,
            @JsonProperty("kind") val kind: String? = null,
        )
    }
}