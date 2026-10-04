package com.eshk

import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addTrailer
import com.lagradost.cloudstream3.utils.*
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import java.security.MessageDigest

class eishk : MainAPI() {

    override var mainUrl = "https://raw.githubusercontent.com/Abodabodd/re-3arabi/refs/heads/builds"
    override var name = "تقييم الإضافات"
    override var lang = "ar"

    override val hasMainPage = true

    override val supportedTypes = setOf(
        TvType.Movie
    )

    companion object {

        private const val PLUGINS_URL =
            "https://raw.githubusercontent.com/Abodabodd/re-3arabi/refs/heads/builds/plugins.json"

        private const val COUNTER_API =
            "https://counterapi.com/api"

        private const val SALT =
            "#funny-salt"

        private fun transformUrl(url: String): String {
            return MessageDigest
                .getInstance("SHA-256")
                .digest((url + SALT).toByteArray())
                .joinToString("") {
                    "%02x".format(it)
                }
        }

        private fun getRepository(pluginUrl: String): String {
            return pluginUrl
                .split("/")
                .drop(2)
                .take(3)
                .joinToString("-")
        }
    }

    /**
     * الصفحة الرئيسية
     */
    override val mainPage = mainPageOf(
        "votes" to "⭐ ترتيب الإضافات حسب التقييم"
    )

    /**
     * جلب الإضافات والأصوات
     */
    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse {

        if (page > 1) {
            return newHomePageResponse(
                request.name,
                emptyList(),
                hasNext = false
            )
        }
        val plugins = app
            .get(PLUGINS_URL)
            .parsedSafe<Array<PluginInfo>>()
            ?.toList()
            ?: emptyList()

        /*
         * جلب أصوات جميع الإضافات بالتوازي
         */
        val results = coroutineScope {

            plugins.map { plugin ->

                async {

                    val votes = getVotes(plugin.url)

                    PluginVote(
                        plugin = plugin,
                        votes = votes
                    )
                }

            }.awaitAll()
        }

        /*
         * ترتيب من الأعلى تصويتاً إلى الأقل
         */
        val sorted = results
            .sortedByDescending { it.votes }

        /*
         * تحويلها إلى بطاقات CloudStream
         */
        val home = sorted.mapIndexedNotNull { index, item ->

            val plugin = item.plugin

            val title =
                "${index + 1}. ${plugin.name}  ⭐ ${item.votes}"

            newMovieSearchResponse(
    title,
    plugin.url,
    TvType.Movie
) {
    this.posterUrl = plugin.iconUrl
}
        }

        return newHomePageResponse(
            "⭐ ترتيب الإضافات حسب التقييم",
            home,
            hasNext = false
        )
    }

    /**
     * قراءة عدد الأصوات من CounterAPI
     */
    private suspend fun getVotes(
        pluginUrl: String
    ): Int {

        return try {

            val repository = getRepository(pluginUrl)

            val key = transformUrl(pluginUrl)

            val url =
                "$COUNTER_API/" +
                "cs-$repository/" +
                "vote/$key" +
                "?readOnly=true"

            app
                .get(url)
                .parsedSafe<CounterResult>()
                ?.value
                ?: 0

        } catch (e: Exception) {

            0
        }
    }

    /**
     * عند البحث
     *
     * لا نحتاج بحث في هذه الإضافة،
     * لذلك نرجع القائمة نفسها.
     */
    override suspend fun search(
        query: String,
        page: Int
    ): SearchResponseList {

        return newSearchResponseList(
            emptyList(),
            hasNext = false
        )
    }

    /**
     * لا يوجد تشغيل فيديو.
     */
    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {

        return false
    }

    /**
     * بيانات الإضافة من plugins.json
     */
    data class PluginInfo(

        @JsonProperty("url")
        val url: String,

        @JsonProperty("name")
        val name: String,

        @JsonProperty("internalName")
        val internalName: String? = null,

        @JsonProperty("version")
        val version: Int? = null,

        @JsonProperty("description")
        val description: String? = null,

        @JsonProperty("iconUrl")
        val iconUrl: String? = null,

        @JsonProperty("language")
        val language: String? = null,

        @JsonProperty("repositoryUrl")
        val repositoryUrl: String? = null

    )

    data class PluginVote(
        val plugin: PluginInfo,
        val votes: Int
    )

    /**
     * استجابة CounterAPI
     */
    data class CounterResult(

        @JsonProperty("value")
        val value: Int? = null

    )
}
