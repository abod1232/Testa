package com.eshk

import android.content.Context
import android.content.ContextWrapper
import androidx.fragment.app.FragmentActivity
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin
import com.lagradost.cloudstream3.utils.*

@CloudstreamPlugin
class EishkPlugin : Plugin() {

    override fun load(context: Context) {
        registerMainAPI(eishk())

        openSettings = { ctx ->
            ctx.findFragmentActivity()?.supportFragmentManager?.let { fm ->
                RatingSettings.show(fm)
            }
        }
    }

    private fun Context.findFragmentActivity(): FragmentActivity? {
        var currentContext: Context? = this
        while (currentContext is ContextWrapper) {
            if (currentContext is FragmentActivity) {
                return currentContext
            }
            currentContext = currentContext.baseContext
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
            "⚙️ اضغط على أيقونة الترس ⚙️ في قائمة الإضافات لفتح التقييمات",
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
            this.plot = "اضغط على أيقونة الإعدادات ⚙️ الخاصة بهذه الإضافة لفتح نافذة التقييمات والتصويت مباشرة"
        }
    }
}
