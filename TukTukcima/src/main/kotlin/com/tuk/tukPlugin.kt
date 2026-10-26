package com.tuk

import android.content.Context
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class TukTukHdPlugin : Plugin() {

    override fun load(context: Context) {
        registerMainAPI(TukTukHd())
        registerCustomExtractors()
    }

    private fun registerCustomExtractors() {
        val extractors = listOf(
            videoland(),
            UpnShareExtractor(),
            UpnShareTukTukExtractor(),
            UpnShareBioExtractor(),
            UpnShareMegaMaxExtractor(),
            StreamP2PExtractor(),
            StreamP2PMegaMaxExtractor(),
            P2PStreamVipExtractor(),
            RpmShareExtractor(),
            RpmShareMegaMaxExtractor(),
            ArabicMegaMaxExtractor(),
            Share4MaxExtractor(),
            ArabicByseExtractor(),
            ByseSxExtractor(),
            ByseQekahoExtractor(),
            ByseRaguciExtractor(),
            ByseZejataosExtractor(),
            ArabicFireStreamExtractor(),
            ArabicVidaraExtractor(),
            ArabicYonaPlayExtractor(),
            DotPlayExtractor(),
            ArabicStreamWishExtractor(),
            VidHideExtractor(),
            EarnVidsExtractor(),
            StreamHgExtractor(),
            HanerixExtractor(),
            FilelionsExtractor(),
            ArabicPackerExtractor(),
            LuluStreamExtractor(),
            TukTukVipExtractor(),
            VideolandExtractor(),
            UpDownExtractor(),
            OkStreamExtractor(),
            ArabicDoodExtractor(),
            PlaymogoExtractor(),
            MyVidPlayExtractor(),
            ArabicVoeExtractor(),
            ArabicStreamTapeExtractor(),
            ArabicMixdropExtractor(),
            ArabicMp4UploadExtractor(),
            ArabicVideasExtractor(),
            ArabicVideaExtractor(),
            ArabicOkRuExtractor(),
            ArabicVkExtractor(),
            ArabicMediaFireExtractor(),
            ArabicPixelDrainExtractor(),
            ArabicFourSharedExtractor(),
            ArabicGoFileExtractor()
        )
        extractors.forEach { registerExtractorAPI(it) }
    }
}
