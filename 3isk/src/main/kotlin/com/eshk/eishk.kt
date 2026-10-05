package com.eshk

import android.content.Context
import android.content.ContextWrapper
import androidx.fragment.app.FragmentActivity
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin
import com.lagradost.cloudstream3.utils.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@CloudstreamPlugin
class eishkPlugin : Plugin() {

    companion object {
        var pluginContext: Context? = null
    }

    override fun load(context: Context) {
        pluginContext = context
        registerMainAPI(eishk())
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

        withContext(Dispatchers.Main) {
            try {
                findActivity(eishkPlugin.pluginContext)?.supportFragmentManager?.let { fm ->
                    RatingSettings.show(fm)
                }
            } catch (e: Exception) {
            }
        }

        val item = newMovieSearchResponse(
            "⚙️ فتح إعدادات وتقييمات الإضافات",
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

        withContext(Dispatchers.Main) {
            try {
                findActivity(eishkPlugin.pluginContext)?.supportFragmentManager?.let { fm ->
                    RatingSettings.show(fm)
                }
            } catch (e: Exception) {
            }
        }

        return newMovieLoadResponse(
            "⚙️ إعدادات وتقييمات الإضافات",
            url,
            TvType.Movie,
            url
        ) {
            this.posterUrl = "https://raw.githubusercontent.com/Abodabodd/Oldarabrepo/refs/heads/main/img/file_0000000042f861f49090744dc097ee2f.png"
            this.plot = "نافذة تقييم ومراجعة الإضافات والتصويت عليها مباشرة"
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
