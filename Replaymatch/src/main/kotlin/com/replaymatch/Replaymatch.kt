package com.replaymatch

import android.content.Context
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink

class FullMatchShowsProvider(private val context: Context) : MainAPI() {

    override var name = "FullMatchShows"
    override var mainUrl = "https://example.com" // رابط وهمي غير مستخدم
    override var lang = "en"
    override val hasMainPage = true
    override val supportedTypes = setOf(TvType.Movie)
    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        return newHomePageResponse(emptyList())
    }
}
