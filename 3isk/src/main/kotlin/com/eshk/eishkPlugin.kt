package com.eshk

import android.content.Context
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class eishkPlugin : Plugin() {

    override fun load(context: Context) {
        // 1. تسجيل الموقع الأساسي
        registerMainAPI(AnimeRift())

        // 2. تسجيل المستخرجات العربية المخصصة
        registerCustomExtractors()
    }

    private fun registerCustomExtractors() {
        val extractors = listOf(
            // --- عائلة Vidstack (UpnShare, StreamP2P, RPMShare) ---
            UpnShareExtractor(),
            StreamP2PExtractor(),
            RpmShareExtractor(),

            // --- عائلة MegaMax & Share4Max ---
            ArabicMegaMaxExtractor(),
            Share4MaxExtractor(),

            // --- عائلة Byse ---
            ArabicByseExtractor(),
            ByseSxExtractor(),
            ByseQekahoExtractor(),
            ByseRaguciExtractor(),
            ByseZejataosExtractor(),

            // --- سيرفرات المحاور والـ API ---
            ArabicFireStreamExtractor(),
            ArabicVidaraExtractor(),
            ArabicYonaPlayExtractor(),
            DotPlayExtractor(),

            // --- عائلة StreamWish و VidHide و EarnVids ---
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

            // --- عائلة DoodStream ---
            ArabicDoodExtractor(),
            PlaymogoExtractor(),
            MyVidPlayExtractor(),

            // --- السيرفرات العالمية المشفرة ---
            ArabicVoeExtractor(),
            ArabicStreamTapeExtractor(),
            ArabicMixdropExtractor(),
            ArabicMp4UploadExtractor(),

            // --- سيرفرات التشفير الخاص ---
            ArabicVideasExtractor(),
            ArabicVideaExtractor(),

            // --- السيرفرات الروسية (الجودات المتعددة) ---
            ArabicOkRuExtractor(),
            ArabicVkExtractor(),

            // --- سيرفرات التحميل المباشر ---
            ArabicMediaFireExtractor(),
            ArabicPixelDrainExtractor(),
            ArabicFourSharedExtractor(),
            ArabicGoFileExtractor()
        )

        // تفعيل وتسجيل جميع المستخرجات داخل محرك كلاود ستريم
        extractors.forEach { registerExtractorAPI(it) }
    }
}
