package com.eshk

import android.content.Context
import android.content.ContextWrapper
import androidx.fragment.app.FragmentActivity
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin
import com.lagradost.cloudstream3.utils.*

@CloudstreamPlugin
class eishkPlugin : Plugin() {

    override fun load(context: Context) {
        registerMainAPI(eishk())

        openSettings = { ctx ->
            findActivity(ctx)?.supportFragmentManager?.let { fm ->
                RatingSettings.show(fm)
            }
        }
    }

    private fun findActivity(context: Context?): FragmentActivity? {
        var current = context
        while (current is ContextWrapper) {
            if (current is FragmentActivity) {
                return current
            }
            current = current.baseContext
        }
        return null
    }
}

class eishk : MainAPI() {

    override var mainUrl = "https://raw.githubusercontent.com/abod1232/Testa/refs/heads/builds"
    override var name = "تقييم الإضافات"
    override var lang = "ar"

    override val hasMainPage = true

    override val supportedTypes = setOf(
        TvType.Movie
    )

    override val mainPage = mainPageOf(
        "ratings" to "⭐ تقييمات الإضافات"
    )

    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse {

        val item = newMovieSearchResponse(
            "⚙️ اضغط على أيقونة الترس ⚙️ لفتح التقييمات",
            "$mainUrl/open_settings",
            TvType.Movie
        ) {
            this.posterUrl = "https://raw.githubusercontent.com/Abodabodd/Oldarabrepo/refs/heads/main/img/file_0000000042f861f49090744dc097ee2f.png"
        }

        return newHomePageResponse(
            request.name,
            listOf(item),
            hasNext = false
        )
    }

    override suspend fun load(url: String): LoadResponse {
        return newMovieLoadResponse(
            "⚙️ إعدادات وتقييمات الإضافات",
            url,
            TvType.Movie,
            url
        ) {
            this.posterUrl = "https://raw.githubusercontent.com/Abodabodd/Oldarabrepo/refs/heads/main/img/file_0000000042f861f49090744dc097ee2f.png"
            this.plot = "اضغط على أيقونة الإعدادات ⚙️ الخاصة بالإضافة لفتح نافذة التقييمات والتصويت مباشرة"
        }
    }
}
