package com.witanime

import android.content.Context
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class WitAnimePlugin : Plugin() {
    override fun load(context: Context) {
        // تسجيل الإضافة الأساسية
        registerMainAPI(WitAnime())

        // تسجيل كافة المستخرجات
        registerExtractorAPI(MegaExtractor())
        registerExtractorAPI(GoFileExtractor())
        registerExtractorAPI(VideaExtractor())
        registerExtractorAPI(MailruExtractor())
        registerExtractorAPI(FourSharedExtractor())
    }
}
