package com.animerco

import com.lagradost.api.Log
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.fasterxml.jackson.annotation.JsonProperty

class MailruExtractor : ExtractorApi() {
    override val name = "MailRue"
    override val mainUrl = "https://my.mail.ru"
    override val requiresReferer = false

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val TAG = "MAILRU_EXTRACTOR"
        val extRef = referer ?: url

        val vidId = url.substringAfter("video/embed/").trim()
        if (vidId.isBlank()) {
            Log.w(TAG, "video id not found in url: $url")
            return
        }

        // جلب ميتا
        val videoReq = try {
            app.get("${mainUrl}/+/video/meta/${vidId}", referer = extRef)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to fetch meta: ${e.message}", )
            return
        }

        // اقرأ video_key من الكوكيز إن وُجد (غالبًا يستخدمه CDN)
        val videoKey = videoReq.cookies["video_key"]?.toString().orEmpty()

        // parse JSON آمن
        val videoData = AppUtils.tryParseJson<MailRuData>(videoReq.text)
        if (videoData == null) {
            Log.e(TAG, "MailRu: invalid json or no videos")
            return
        }

        for (video in videoData.videos) {
            var videoUrl = if (video.url.startsWith("//")) "https:${video.url}" else video.url

            // لو CDN يحتاج video_key كـ query param يمكنك إضافته هنا
            if (videoKey.isNotBlank()) {
                videoUrl = if (videoUrl.contains("?")) {
                    "$videoUrl&video_key=$videoKey"
                } else {
                    "$videoUrl?video_key=$videoKey"
                }
            }

            try {
                callback.invoke(
                    newExtractorLink(
                        source = this.name,
                        name = this.name,
                        url = videoUrl,
                        type = if (videoUrl.contains(".m3u8", ignoreCase = true))
                            ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                    ) {
                        // نعطي referer لأن بعض السيرفرات تحتاجه
                        this.referer = extRef
                        // لا نستخدم this.headers لأنها قد لا تكون مدعومة في نسختك من المكتبة
                        this.quality = getQualityFromName(video.key)
                    }
                )
            } catch (e: Exception) {
                Log.e(TAG, "Failed to create extractor link for $videoUrl : ${e.message}", )
            }
        }
    }

    data class MailRuData(
        @JsonProperty("provider") val provider: String,
        @JsonProperty("videos") val videos: List<MailRuVideoData>
    )

    data class MailRuVideoData(
        @JsonProperty("url") val url: String,
        @JsonProperty("key") val key: String
    )
}
