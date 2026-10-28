package com.witanime

import android.util.Log
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.newExtractorLink
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

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
            if (megaUrl.isBlank()) {
                Log.w(TAG, "Empty Mega URL provided")
                return
            }
            if (!megaUrl.contains("#") && !megaUrl.contains("!")) {
                Log.w(TAG, "Invalid Mega URL: missing file key token (# or !)")
                return
            }

            Log.d(TAG, "Registering stream with local AES-128-CTR proxy...")
            val localStreamUrl = WitAnimeMegaServer.registerMegaStream(megaUrl)

            if (localStreamUrl.isNullOrBlank()) {
                Log.e(TAG, "Failed to resolve Mega node or initialize local decryption proxy")
                return
            }

            Log.d(TAG, "Stream registered successfully -> $localStreamUrl")

            withContext(Dispatchers.Main) {
                try {
                    val extractorLink = newExtractorLink(
                        source = this@MegaExtractor.name,
                        name = "${this@MegaExtractor.name} (Direct Stream)",
                        url = localStreamUrl,
                        type = ExtractorLinkType.VIDEO
                    ) {
                        this.quality = Qualities.P1080.value
                    }

                    callback.invoke(extractorLink)
                    Log.d(TAG, "Extractor link dispatched to player")
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to dispatch extractor link", e)
                }
            }

        } catch (e: Exception) {
            Log.e(TAG, "Mega getUrl fatal error", e)
        }
    }
}
