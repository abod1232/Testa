package com.eshk

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

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
                val activity = MainActivity.activity
                activity?.supportFragmentManager?.let { fm ->
                    RatingSettings.show(fm)
                }
            } catch (e: Exception) {
            }
        }

        val item = newMovieSearchResponse(
            "⭐ فتح قائمة التقييمات والتصويت",
            "$mainUrl/open_ratings",
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
                val activity = MainActivity.activity
                activity?.supportFragmentManager?.let { fm ->
                    RatingSettings.show(fm)
                }
            } catch (e: Exception) {
            }
        }

        return newMovieLoadResponse(
            "⭐ تقييمات الإضافات",
            url,
            TvType.Movie,
            url
        ) {
            this.posterUrl = "https://raw.githubusercontent.com/Abodabodd/Oldarabrepo/refs/heads/main/img/file_0000000042f861f49090744dc097ee2f.png"
            this.plot = "نافذة تقييم ومراجعة الإضافات والتصويت عليها مباشرة"
        }
    }
}
