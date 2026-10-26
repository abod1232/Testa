package com.tuk

import android.content.Context
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class TukTukHdPlugin : Plugin() {

    override fun load(context: Context) {
        // 1. تسجيل موقع توك توك سينما
        registerMainAPI(TukTukHd())

        // 2. تسجيل المستخرجات المخصصة
        registerCustomExtractors()
    }

    private fun registerCustomExtractors() {
        val extractors = listOf(
            // --- عائلة Vidstack و UPNShare ومراياها ---
            UpnShareExtractor(),
            UpnShareTukTukExtractor(),
            UpnShareBioExtractor(),
            UpnShareMegaMaxExtractor(),
            StreamP2PExtractor(),
            StreamP2PMegaMaxExtractor(),
            P2PStreamVipExtractor(),
            RpmShareExtractor(),
            RpmShareMegaMaxExtractor(),

            // --- عائلة MegaMax و Share4Max ---
            ArabicMegaMaxExtractor(),
            Share4MaxExtractor(),

            // --- عائلة Byse بمختلف نطاقاتها ---
            ArabicByseExtractor(),
            ByseSxExtractor(),
            ByseQekahoExtractor(),
            ByseRaguciExtractor(),
            ByseZejataosExtractor(),

            // --- سيرفرات المحاور و الـ API المشفرة ---
            ArabicFireStreamExtractor(),
            ArabicVidaraExtractor(),
            ArabicYonaPlayExtractor(),
            DotPlayExtractor(),

            // --- عائلة StreamWish و VidHide و EarnVids و StreamHG ---
            ArabicStreamWishExtractor(),
            VidHideExtractor(),
            EarnVidsExtractor(),
            StreamHgExtractor(),
            HanerixExtractor(),
            FilelionsExtractor(),

            // --- عائلة LuluStream و مشغلات الـ Packer ---
            ArabicPackerExtractor(),
            LuluStreamExtractor(),
            TukTukVipExtractor(),
            VideolandExtractor(),
            UpDownExtractor(),
            OkStreamExtractor(),

            // --- عائلة DoodStream ومراياها ---
            ArabicDoodExtractor(),
            PlaymogoExtractor(),
            MyVidPlayExtractor(),

            // --- السيرفرات العالمية المشفرة ---
            ArabicVoeExtractor(),
            ArabicStreamTapeExtractor(),
            ArabicMixdropExtractor(),
            ArabicMp4UploadExtractor(),

            // --- سيرفرات التشفير المتقدم ---
            ArabicVideasExtractor(),
            ArabicVideaExtractor(),

            // --- السيرفرات الروسية المتعددة الجودات ---
            ArabicOkRuExtractor(),
            ArabicVkExtractor(),

            // --- سيرفرات التحميل والتخزين السحابي ---
            ArabicMediaFireExtractor(),
            ArabicPixelDrainExtractor(),
            ArabicFourSharedExtractor(),
            ArabicGoFileExtractor()
        )

        // تفعيل وتسجيل جميع المستخرجات
        extractors.forEach { registerExtractorAPI(it) }
    }
}
