package com.animewitcher

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import kotlinx.coroutines.*
import org.json.JSONArray
import org.json.JSONObject
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import java.net.URL
import java.net.URLDecoder
import java.net.URLEncoder
import java.util.*
import kotlin.collections.HashMap
import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.net.Uri
import android.os.Bundle
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class animewitcherPlugin : Plugin() {
    override fun load(context: Context) {
        registerMainAPI(AnimeWitcherProvider())

        openSettings = { activityContext ->
            (activityContext as? AppCompatActivity)?.let { activity ->
                Settings().show(activity.supportFragmentManager, "AnimewitcherSettings")
            }
        }
    }
}

class Settings : BottomSheetDialogFragment() {

    private fun dp(dp: Int): Int = (dp * resources.displayMetrics.density + 0.5f).toInt()
    private class PathDrawable(
        pathData: String,
        private val iconColor: Int,
        private val viewportWidth: Float = 24f,
        private val viewportHeight: Float = 24f
    ) : Drawable() {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = iconColor
            style = Paint.Style.FILL
        }
        private val path: Path = PathParser.createPathFromPathData(pathData)

        override fun draw(canvas: Canvas) {
            val b = bounds
            val scaleX = b.width().toFloat() / viewportWidth
            val scaleY = b.height().toFloat() / viewportHeight
            val count = canvas.save()
            canvas.translate(b.left.toFloat(), b.top.toFloat())
            canvas.scale(scaleX, scaleY)
            canvas.drawPath(path, paint)
            canvas.restoreToCount(count)
        }

        override fun setAlpha(alpha: Int) { paint.alpha = alpha }
        override fun setColorFilter(colorFilter: ColorFilter?) { paint.colorFilter = colorFilter }
        @Deprecated("Deprecated in Java")
        override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
    }

    override fun onStart() {
        super.onStart()
        val dialog = dialog as? BottomSheetDialog
        dialog?.findViewById<View>(com.google.android.material.R.id.design_bottom_sheet)?.let { sheet ->
            sheet.background = null
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        val ctx = requireContext()

        val root = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(12), dp(20), dp(28))
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#181926"))
                cornerRadii = floatArrayOf(
                    dp(24).toFloat(), dp(24).toFloat(),
                    dp(24).toFloat(), dp(24).toFloat(),
                    0f, 0f, 0f, 0f
                )
            }
        }
        val handle = View(ctx).apply {
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#45475A"))
                cornerRadius = dp(3).toFloat()
            }
            layoutParams = LinearLayout.LayoutParams(dp(44), dp(5)).apply {
                gravity = Gravity.CENTER_HORIZONTAL
                bottomMargin = dp(20)
            }
        }
        root.addView(handle)

        val title = TextView(ctx).apply {
            text = "مجتمع ودعم الإضافة"
            textSize = 19f
            setTypeface(null, Typeface.BOLD)
            setTextColor(Color.parseColor("#CAD3F5"))
            gravity = Gravity.CENTER
        }
        root.addView(title)

        val subtitle = TextView(ctx).apply {
            text = "تابع التحديثات وانضم لمجتمع النقاش والمطورين"
            textSize = 12.5f
            setTextColor(Color.parseColor("#8087A2"))
            gravity = Gravity.CENTER
            setPadding(0, dp(4), 0, dp(22))
        }
        root.addView(subtitle)

        fun createPlatformCard(
            svgPath: String,
            iconTintColor: String,
            badgeBackgroundColor: String,
            titleText: String,
            descText: String,
            targetUrl: String
        ): View {
            val card = LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(16), dp(14), dp(16), dp(14))

                val shape = GradientDrawable().apply {
                    setColor(Color.parseColor("#24273A"))
                    cornerRadius = dp(16).toFloat()
                    setStroke(dp(1), Color.parseColor("#363A4F"))
                }
                background = RippleDrawable(
                    ColorStateList.valueOf(Color.parseColor("#3B4252")),
                    shape,
                    shape
                )

                isClickable = true
                isFocusable = true
                setOnClickListener {
                    try {
                        startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(targetUrl)))
                    } catch (e: Exception) { }
                }

                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply {
                    bottomMargin = dp(12)
                }
            }
            val iconBadge = ImageView(ctx).apply {
                setImageDrawable(PathDrawable(svgPath, Color.parseColor(iconTintColor)))
                setPadding(dp(10), dp(10), dp(10), dp(10))
                background = GradientDrawable().apply {
                    setColor(Color.parseColor(badgeBackgroundColor))
                    cornerRadius = dp(12).toFloat()
                }
                layoutParams = LinearLayout.LayoutParams(dp(44), dp(44)).apply {
                    marginEnd = dp(14)
                }
            }
            card.addView(iconBadge)

            val textContainer = LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            }

            val cardTitle = TextView(ctx).apply {
                text = titleText
                textSize = 15f
                setTypeface(null, Typeface.BOLD)
                setTextColor(Color.parseColor("#CAD3F5"))
            }
            textContainer.addView(cardTitle)

            val cardDesc = TextView(ctx).apply {
                text = descText
                textSize = 12f
                setTextColor(Color.parseColor("#A5ADCB"))
                setPadding(0, dp(2), 0, 0)
            }
            textContainer.addView(cardDesc)

            card.addView(textContainer)

            val arrow = TextView(ctx).apply {
                text = "↗"
                textSize = 17f
                setTextColor(Color.parseColor("#6E738D"))
                setTypeface(null, Typeface.BOLD)
            }
            card.addView(arrow)

            return card
        }
        val telegramSvg = "M9.78 18.65l.28-4.23 7.68-6.92c.34-.31-.07-.46-.52-.19L7.74 13.3 3.64 12c-.88-.25-.89-.86.2-1.3l15.97-6.16c.73-.33 1.43.18 1.15 1.3l-2.72 12.81c-.19.91-.74 1.13-1.5.71L12.6 16.3l-1.99 1.93c-.23.23-.42.42-.83.42z"
        val discordSvg = "M19.27 5.33C17.94 4.71 16.5 4.26 15 4a.09.09 0 0 0-.07.03c-.18.33-.39.76-.53 1.09a16.09 16.09 0 0 0-4.8 0c-.14-.34-.35-.76-.54-1.09-.01-.02-.04-.03-.07-.03-1.5.26-2.93.71-4.27 1.33-.01 0-.02.01-.03.02-2.72 4.07-3.47 8.03-3.1 11.95 0 .02.01.04.03.05 1.8 1.32 3.53 2.12 5.24 2.65.03.01.06 0 .07-.02.4-.55.76-1.13 1.07-1.74.02-.04 0-.08-.04-.09-.57-.22-1.11-.48-1.64-.78-.04-.02-.04-.08-.01-.11.11-.08.22-.17.33-.25.02-.02.05-.02.07-.01 3.44 1.57 7.15 1.57 10.55 0 .02-.01.05-.01.07.01.11.09.22.17.33.26.04.03.04.09-.01.11-.52.31-1.07.56-1.64.78-.04.01-.05.06-.04.09.32.61.68 1.19 1.07 1.74.03.01.06.02.09.01 1.72-.53 3.45-1.33 5.25-2.65.02-.01.03-.03.03-.05.44-4.53-.73-8.46-3.1-11.95-.01-.01-.02-.02-.04-.02zM8.52 14.91c-1.03 0-1.89-.95-1.89-2.12s.84-2.12 1.89-2.12c1.06 0 1.9.96 1.89 2.12 0 1.17-.84 2.12-1.89 2.12zm6.97 0c-1.03 0-1.89-.95-1.89-2.12s.84-2.12 1.89-2.12c1.06 0 1.9.96 1.89 2.12 0 1.17-.83 2.12-1.89 2.12z"
        val githubSvg = "M12 2C6.477 2 2 6.484 2 12.017c0 4.425 2.865 8.18 6.839 9.504.5.092.682-.217.682-.483 0-.237-.008-.868-.013-1.703-2.782.605-3.369-1.343-3.369-1.343-.454-1.158-1.11-1.466-1.11-1.466-.908-.62.069-.608.069-.608 1.003.07 1.53 1.032 1.53 1.032.892 1.53 2.341 1.088 2.91.832.092-.647.35-1.088.636-1.338-2.22-.253-4.555-1.113-4.555-4.951 0-1.093.39-1.988 1.029-2.688-.103-.253-.446-1.272.098-2.65 0 0 .84-.27 2.75 1.026A9.564 9.564 0 0112 6.844c.85.004 1.705.115 2.504.337 1.909-1.296 2.747-1.027 2.747-1.027.546 1.379.202 2.398.1 2.651.64.7 1.028 1.595 1.028 2.688 0 3.848-2.339 4.695-4.566 4.943.359.309.678.92.678 1.855 0 1.338-.012 2.419-.012 2.747 0 .268.18.58.688.482A10.019 10.019 0 0022 12.017C22 6.484 17.522 2 12 2z"

        root.addView(
            createPlatformCard(
                svgPath = telegramSvg,
                iconTintColor = "#FFFFFF",
                badgeBackgroundColor = "#229ED9",
                titleText = "قناة التليجرام",
                descText = "أحدث الإصدارات، الروابط والأخبار",
                targetUrl = "https://t.me/cs3iq"
            )
        )

        root.addView(
            createPlatformCard(
                svgPath = discordSvg,
                iconTintColor = "#FFFFFF",
                badgeBackgroundColor = "#5865F2",
                titleText = "سيرفر الديسكورد",
                descText = "نقاشات المجتمع، الدعم الفني والاقتراحات",
                targetUrl = "https://discord.gg/xY63qaQRw"
            )
        )

        root.addView(
            createPlatformCard(
                svgPath = githubSvg,
                iconTintColor = "#CAD3F5",
                badgeBackgroundColor = "#313244",
                titleText = "مشروع GitHub",
                descText = "المستودع المفتوح، الإبلاغ عن مشاكل والمساهمة",
                targetUrl = "https://github.com/Abodabodd/re-3arabi"
            )
        )

        return root
    }
}
class AnimeWitcherProvider : MainAPI() {
    override var mainUrl = "https://animewitcher.com"
    override var name = "AnimeWitcher"
    override val supportedTypes = setOf(TvType.Anime, TvType.AnimeMovie)
    override var lang = "ar"
    override val hasMainPage = true
    @Volatile
    private var algoliaAppId = "TVNESH8OZY"
    @Volatile
    private var algoliaApiKey = "b56c01ef52540ef334bcdbaa00ded9e4"
    @Volatile
    private var buAuthKey = "6f1ec205-bbe8-45ce-97eb-1bc25a94bedf"
    private val firebaseApiKey = "AIzaSyAcbWRwfFNnCpoydDXlEALWnM_TYVcJOMU"

    private fun getAlgoliaHeaders(): Map<String, String> {
        return mapOf(
            "X-Algolia-Application-Id" to algoliaAppId,
            "X-Algolia-API-Key" to algoliaApiKey,
            "User-Agent" to "Algolia for Android (3.27.0); Android (16)",
            "Content-Type" to "application/json; charset=UTF-8",
        )
    }

    private val FIREBASE_PROJECT_ID = "animewitcher-1c66d"
    private val serverWordsCache = java.util.concurrent.ConcurrentHashMap<String, ServerWords>()
    data class EpisodeInfo(
        val id: String,
        val name: String?,
        val number: Int,
        val imageUrl: String? = null
    )
    data class ServerModel(
        val name: String?,
        val link: String?,
        val quality: String?,
        val originalLink: String?,
        val openBrowser: Boolean
    )
    data class ServerWords(
        val name: String,
        val word1: String?,
        val word2: String?,
        val word3: String?,
        val word4: String?
    )

    private fun logd(msg: String) {
        println("[AnimeWitcherLog] $msg")
    }

    private fun algoliaUrl(index: String) = "https://${algoliaAppId}-dsn.algolia.net/1/indexes/$index/query"

    private fun getQualityAsInt(quality: String?): Int {
        return quality?.filter { it.isDigit() }?.toIntOrNull() ?: 0
    }

    private suspend fun postAlgoliaQuery(index: String, body: okhttp3.RequestBody): String {
        val startTime = System.currentTimeMillis()
        return try {
            val response = app.post(algoliaUrl(index), requestBody = body, headers = getAlgoliaHeaders())
            if (response.code != 200) throw Exception("رمز الاستجابة ${response.code}")
            val duration = System.currentTimeMillis() - startTime
            logd("⚡ [Algolia POST Network] Succeeded in $duration ms.")
            response.text
        } catch (e: Exception) {
            val duration = System.currentTimeMillis() - startTime
            logd("⚠️ [Algolia POST Network] Failed after $duration ms: ${e.message}. Attempting keys refresh...")
            refreshAlgoliaKeys()

            val retryStartTime = System.currentTimeMillis()
            val response = app.post(algoliaUrl(index), requestBody = body, headers = getAlgoliaHeaders())
            if (response.code != 200) throw Exception("رمز الاستجابة بعد التجديد ${response.code}")
            logd("⚡ [Algolia POST Network] Retry Succeeded in ${System.currentTimeMillis() - retryStartTime} ms.")
            response.text
        }
    }

    private suspend fun getAlgoliaObjectByPost(index: String, objectId: String): JSONObject? {
        val startTime = System.currentTimeMillis()
        logd("⏱️ [Algolia Object] Starting POST query for ObjectID: '$objectId'")

        val escapedId = objectId.replace("\"", "\\\"")
        val payload = JSONObject().put("params", "filters=objectID:\"$escapedId\"")
        val body = payload.toString().toRequestBody("application/json; charset=UTF-8".toMediaType())

        return try {
            val res = postAlgoliaQuery(index, body)
            val hits = JSONObject(res).optJSONArray("hits")
            val duration = System.currentTimeMillis() - startTime
            if (hits != null && hits.length() > 0) {
                logd("✅ [Algolia Object] Found matching object. Time: $duration ms.")
                hits.getJSONObject(0)
            } else {
                logd("⚠️ [Algolia Object] Query returned empty hits. Time: $duration ms.")
                null
            }
        } catch (e: Exception) {
            val duration = System.currentTimeMillis() - startTime
            logd("❌ [Algolia Object] Query failed after $duration ms. Error: ${e.message}")
            null
        }
    }

    private suspend fun refreshAlgoliaKeys() {
        try {
            val url = firestoreDocUrl("Settings/constants")
            val res = app.get(url).text
            val json = JSONObject(res)
            val fields = json.optJSONObject("fields") ?: return

            if (fields.has("search_settings")) {
                val searchSettings = fields.getJSONObject("search_settings")
                    .optJSONObject("mapValue")
                    ?.optJSONObject("fields")

                if (searchSettings != null) {
                    val newAppId = searchSettings.optJSONObject("app_id_v3")?.optString("stringValue")
                        ?: searchSettings.optJSONObject("app_id")?.optString("stringValue")
                        ?: searchSettings.optJSONObject("app_id_v2")?.optString("stringValue")
                    val newApiKey = searchSettings.optJSONObject("api_key")?.optString("stringValue")

                    if (!newAppId.isNullOrEmpty() && !newApiKey.isNullOrEmpty()) {
                        algoliaAppId = newAppId
                        algoliaApiKey = newApiKey
                        logd("✅ [Firestore Keys] Algolia AppId Updated: $algoliaAppId")
                    }
                }
            }

            if (fields.has("BU")) {
                val buSettings = fields.getJSONObject("BU")
                    .optJSONObject("mapValue")
                    ?.optJSONObject("fields")
                if (buSettings != null) {
                    val newAuthKey = buSettings.optJSONObject("auth_key")?.optString("stringValue")
                    if (!newAuthKey.isNullOrEmpty()) {
                        buAuthKey = newAuthKey
                        logd("✅ [Firestore Keys] BU Auth Key Updated: $buAuthKey")
                    }
                }
            }
        } catch (e: Exception) {
            logd("❌ [Firestore Keys] Failed to refresh keys. Error: ${e.message}")
        }
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse = withContext(Dispatchers.IO) {
        val indexName = "recent"
        val pageParam = (page - 1).coerceAtLeast(0)

        val attributes = URLEncoder.encode(
            "[\"name\",\"date\",\"doc_ref\",\"episode_id\",\"anime_id\",\"episode_name\",\"title\",\"poster_uri\",\"poster_url_aniList\",\"type\",\"tags\",\"thumb_uri\",\"comments_closed\",\"filler\",\"note\"]",
            "utf-8"
        )
        val params = "attributesToRetrieve=$attributes&hitsPerPage=30&page=$pageParam&query="

        val payload = JSONObject().put("params", params)
        val body = payload.toString().toRequestBody("application/json; charset=UTF-8".toMediaType())

        val res = postAlgoliaQuery(indexName, body)
        val json = try { JSONObject(res) } catch (e: Exception) { JSONObject() }
        val hits = json.optJSONArray("hits") ?: JSONArray()

        val list = ArrayList<SearchResponse>()
        for (i in 0 until hits.length()) {
            val obj = hits.getJSONObject(i)
            val title = obj.optString("name")
            if (title.isNullOrEmpty()) continue
            val poster = obj.optString("poster_uri")
            val animeId = obj.optString("anime_id", obj.optString("objectID"))
            val fullData = URLEncoder.encode(obj.toString(), "utf-8")
            val url = "$mainUrl/watch/${URLEncoder.encode(animeId, "utf-8")}?data=$fullData"
            list.add(newAnimeSearchResponse(title, url, TvType.Anime) { this.posterUrl = poster })
        }
        return@withContext newHomePageResponse("أحدث الحلقات", list)
    }

    override suspend fun search(query: String): List<SearchResponse> = withContext(Dispatchers.IO) {
        val indexName = "series"
        val encodedQuery = URLEncoder.encode(query, "UTF-8")
        val attributes = URLEncoder.encode(
            "[\"objectID\",\"name\",\"poster_uri\",\"type\",\"details\",\"tags\",\"story\",\"english_title\",\"poster\",\"rating\",\"dubbed\",\"path\"]",
            "utf-8"
        )
        val params = "attributesToRetrieve=$attributes&hitsPerPage=50&page=0&query=$encodedQuery"
        val payload = JSONObject().put("params", params)
        val body = payload.toString().toRequestBody("application/json; charset=UTF-8".toMediaType())

        val res = postAlgoliaQuery(indexName, body)
        val json = try { JSONObject(res) } catch (e: Exception) { JSONObject() }
        val hits = json.optJSONArray("hits") ?: JSONArray()

        val results = ArrayList<SearchResponse>()
        for (i in 0 until hits.length()) {
            val obj = hits.getJSONObject(i)
            val title = obj.optString("name")
            if (title.isNullOrEmpty()) continue
            val poster = obj.optString("poster_uri")
            val animeId = obj.optString("objectID")
            val fullData = URLEncoder.encode(obj.toString(), "utf-8")
            val url = "$mainUrl/watch/${URLEncoder.encode(animeId, "utf-8")}?data=$fullData"
            results.add(newAnimeSearchResponse(title, url, TvType.Anime) { this.posterUrl = poster })
        }
        return@withContext results
    }

    override suspend fun load(url: String): LoadResponse = withContext(Dispatchers.IO) {
        val totalStartTime = System.currentTimeMillis()
        logd("====== [LOAD] PROCESS STARTED ======")
        val animeId = URLDecoder.decode(url.substringAfterLast('/').substringBefore('?'), "utf-8")
        logd("[LOAD] Extracted animeId: '$animeId'")
        val animeJson = getAlgoliaObjectByPost("series", animeId) ?: try {
            logd("⚠️ Algolia query failed or returned empty. Falling back to URL data parameter...")
            val encodedData = url.substringAfter("?data=", "")
            if (encodedData.isNotEmpty()) {
                val decodeStartTime = System.currentTimeMillis()
                val parsed = JSONObject(URLDecoder.decode(encodedData, "utf-8"))
                logd("✅ [LOAD] Decoded local fallback data in ${System.currentTimeMillis() - decodeStartTime} ms.")
                parsed
            } else {
                JSONObject()
            }
        } catch (e: Exception) {
            logd("❌ [LOAD] Failed to parse fallback URL data: ${e.message}")
            JSONObject()
        }

        val docRef = animeJson.optString("doc_ref", animeJson.optString("path", "anime_list/$animeId"))
        val cleanPath = docRef.substringAfter("anime_list/").removePrefix("/")
        val episodes = fetchEpisodes(cleanPath)

        val poster = animeJson.optString("poster_uri", animeJson.optJSONObject("poster")?.optString("medium"))

        val epList = episodes.map { info ->
            val dataStr = "$cleanPath|${info.id}"
            newEpisode(data = dataStr) {
                name = info.name ?: "الحلقة ${info.number}"
                episode = info.number
                posterUrl = info.imageUrl ?: poster
            }
        }

        val title = animeJson.optString("name", animeId)
        val details = animeJson.optJSONObject("details") ?: JSONObject()
        val plot = animeJson.optString("story").ifEmpty {
            animeJson.optJSONObject("_highlightResult")?.optJSONObject("story")?.optString("value")?.replace(Regex("</?em>"), "")
        } ?: ""

        val year = details.optString("year").toIntOrNull()
        val status = if (details.optString("state") == "مكتمل") ShowStatus.Completed else ShowStatus.Ongoing
        val tagsArray = animeJson.optJSONArray("tags")
        val tags = if (tagsArray != null) (0 until tagsArray.length()).map { tagsArray.getString(it) } else emptyList()

        logd("====== [LOAD] PROCESS ENDED - Total Duration: ${System.currentTimeMillis() - totalStartTime} ms ======")

        return@withContext newTvSeriesLoadResponse(title, url, TvType.Anime, epList) {
            this.posterUrl = poster
            this.year = year
            this.plot = plot
            this.showStatus = status
            this.tags = tags
        }
    }

    private suspend fun fetchEpisodes(animeId: String): List<EpisodeInfo> = withContext(Dispatchers.IO) {
        val startTime = System.currentTimeMillis()
        val encodedAnimeId = URLEncoder.encode(animeId, "utf-8").replace("+", "%20")
        val docUrl = firestoreDocUrl("anime_list/$encodedAnimeId/episodes_summery/summery")
        val list = ArrayList<EpisodeInfo>()

        logd("⏱ [Firestore Episodes] Fetching starting for URL: $docUrl")

        try {
            val res = app.get(docUrl).text
            val json = JSONObject(res)
            val fields = json.optJSONObject("fields") ?: return@withContext emptyList()
            val episodesObj = fields.optJSONObject("episodes") ?: return@withContext emptyList()
            val arrayValue = episodesObj.optJSONObject("arrayValue") ?: return@withContext emptyList()
            val values = arrayValue.optJSONArray("values") ?: JSONArray()

            for (i in 0 until values.length()) {
                val valueItem = values.getJSONObject(i)
                val mapValue = valueItem.optJSONObject("mapValue") ?: continue
                val epFields = mapValue.optJSONObject("fields") ?: continue

                val docId = epFields.optJSONObject("doc_id")?.optString("stringValue") ?: continue
                val titleTranslated = epFields.optJSONObject("title_translated")
                    ?.optJSONObject("mapValue")
                    ?.optJSONObject("fields")

                val arName = titleTranslated?.optJSONObject("ar")?.optString("stringValue")
                val epName = arName ?: epFields.optJSONObject("name")?.optString("stringValue") ?: "الحلقة $docId"

                val numberValObj = epFields.optJSONObject("number")
                val numberStr = numberValObj?.optString("integerValue") ?: numberValObj?.optString("stringValue")
                val number = numberStr?.toIntOrNull() ?: (i + 1)

                val image = epFields.optJSONObject("thumb_uri")?.optString("stringValue")

                list.add(EpisodeInfo(docId, epName, number, image))
            }

            list.sortBy { it.number }
            val duration = System.currentTimeMillis() - startTime
            logd("✅ [Firestore Episodes] Succeeded in $duration ms. Found ${list.size} episodes.")
            return@withContext list
        } catch (e: Exception) {
            val duration = System.currentTimeMillis() - startTime
            logd("❌ [Firestore Episodes] Failed after $duration ms. Error: ${e.message}")
            e.printStackTrace()
            return@withContext emptyList()
        }
    }

    private fun firestoreDocUrl(path: String): String =
        "https://firestore.googleapis.com/v1/projects/$FIREBASE_PROJECT_ID/databases/(default)/documents/$path?key=$firebaseApiKey"

    private suspend fun fetchServersForEpisode(animeId: String, episodeId: String): List<ServerModel> = withContext(Dispatchers.IO) {
        val encodedAnimeId = URLEncoder.encode(animeId, "utf-8").replace("+", "%20")
        val encodedEpisodeId = URLEncoder.encode(episodeId, "utf-8").replace("+", "%20")

        try {
            val docPath = "anime_list/$encodedAnimeId/episodes/$encodedEpisodeId/servers2/all_servers"
            val res = app.get(firestoreDocUrl(docPath)).text
            val json = try { JSONObject(res) } catch (e: Exception) { JSONObject() }
            val fields = json.optJSONObject("fields")
            if (fields != null && fields.has("servers")) {
                val arr = fields.getJSONObject("servers").optJSONObject("arrayValue")?.optJSONArray("values") ?: JSONArray()
                val list = ArrayList<ServerModel>()
                for (i in 0 until arr.length()) {
                    val map = arr.getJSONObject(i).getJSONObject("mapValue").getJSONObject("fields")
                    val name = map.optJSONObject("name")?.optString("stringValue")
                    val quality = map.optJSONObject("quality")?.optString("stringValue")
                    val link = map.optJSONObject("link")?.optString("stringValue")
                    val orig = map.optJSONObject("original_link")?.optString("stringValue")
                    val openBrowser = map.optJSONObject("open_browser")?.optBoolean("booleanValue") ?: false
                    if (!name.isNullOrEmpty() && !link.isNullOrEmpty()) {
                        list.add(ServerModel(name, link, quality, orig, openBrowser))
                    }
                }
                if (list.isNotEmpty()) {
                    list.sortByDescending { getQualityAsInt(it.quality) }
                    return@withContext list
                }
            }
        } catch (e: Exception) {
            logd("fetchServersForEpisode: servers2 doc failed: ${e.message}")
        }

        try {
            val collPath = "anime_list/$encodedAnimeId/episodes/$encodedEpisodeId/servers"
            val res = app.get(firestoreDocUrl(collPath)).text
            val json = try { JSONObject(res) } catch (e: Exception) { JSONObject() }
            val docs = json.optJSONArray("documents") ?: JSONArray()
            val list = ArrayList<ServerModel>()
            for (i in 0 until docs.length()) {
                val fields = docs.getJSONObject(i).optJSONObject("fields") ?: JSONObject()
                val name = fields.optJSONObject("name")?.optString("stringValue")
                val quality = fields.optJSONObject("quality")?.optString("stringValue")
                val link = fields.optJSONObject("link")?.optString("stringValue")
                val orig = fields.optJSONObject("original_link")?.optString("stringValue")
                val openBrowser = fields.optJSONObject("open_browser")?.optBoolean("booleanValue") ?: false
                val visible = fields.optJSONObject("visible")?.optBoolean("booleanValue") ?: true
                if (!name.isNullOrEmpty() && !link.isNullOrEmpty() && visible) {
                    list.add(ServerModel(name, link, quality, orig, openBrowser))
                }
            }
            list.sortByDescending { getQualityAsInt(it.quality) }
            return@withContext list
        } catch (e: Exception) {
            e.printStackTrace()
            return@withContext emptyList()
        }
    }

    private suspend fun getServerWords(serverName: String): ServerWords? = withContext(Dispatchers.IO) {
        if (serverWordsCache.containsKey(serverName)) return@withContext serverWordsCache[serverName]
        try {
            val path = "Settings/servers/servers/${URLEncoder.encode(serverName, "utf-8").replace("+", "%20")}"
            val res = app.get(firestoreDocUrl(path)).text
            val f = JSONObject(res).optJSONObject("fields") ?: JSONObject()
            val sw = ServerWords(
                name = serverName,
                word1 = f.optJSONObject("word1")?.optString("stringValue"),
                word2 = f.optJSONObject("word2")?.optString("stringValue"),
                word3 = f.optJSONObject("word3")?.optString("stringValue"),
                word4 = f.optJSONObject("word4")?.optString("stringValue")
            )
            serverWordsCache[serverName] = sw
            return@withContext sw
        } catch (e: Exception) {
            return@withContext null
        }
    }

    private suspend fun getFinalRedirectUrl(urlIn: String): String? = withContext(Dispatchers.IO) {
        return@withContext try {
            app.get(urlIn, allowRedirects = true).url
        } catch (e: Exception) {
            null
        }
    }

    private suspend fun getFinalDownloadUrl(urlIn: String): String? = withContext(Dispatchers.IO) {
        return@withContext try {
            app.get(urlIn, allowRedirects = false).headers["Location"]
        } catch (e: Exception) {
            null
        }
    }
    private suspend fun resolveServerModel(server: ServerModel): String? = withContext(Dispatchers.IO) {
        val name = server.name ?: return@withContext null
        val link = server.link ?: return@withContext null
        val words = getServerWords(name)

        try {
            when (name.uppercase(Locale.getDefault())) {
                "MF", "MF2", "ST", "MG", "SF" -> {
                    return@withContext link
                }
                "KF" -> {
                    if (words?.word1 == null || words.word2 == null) return@withContext link
                    val res = try { app.get(link).text } catch (e: Exception) { "" }
                    if (res.isBlank()) return@withContext link
                    return@withContext "https://${res.substringAfter(words.word1!!).substringBefore(words.word2!!).replace("amp;", "")}"
                }
                "PD" -> {
                    val html = try { app.get(link).text } catch (e: Exception) { "" }
                    val og = Regex("""<meta property="og:video" content="([^"]+)""").find(html)?.groupValues?.get(1)
                    if (!og.isNullOrEmpty()) return@withContext og.replace("&amp;", "&")
                    val m = Regex("""href="(/u/[A-Za-z0-9_-]+)"[^>]*>\s*(?:Download|تحميل|download)""", RegexOption.IGNORE_CASE).find(html)
                    if (m != null) {
                        val rel = m.groupValues[1]
                        val full = try { URL(link).let { base -> URL(base, rel).toString() } } catch (_: Exception) { rel }
                        return@withContext full
                    }
                    val m2 = Regex("(/u/[A-Za-z0-9_-]+)").find(html)
                    if (m2 != null) {
                        val rel = m2.groupValues[1]
                        val full = try { URL(link).let { base -> URL(base, rel).toString() } } catch (_: Exception) { rel }
                        return@withContext full
                    }
                    val abs = Regex("""https?://pixeldrain\.com/u/[A-Za-z0-9_-]+""").find(html)?.value
                    if (!abs.isNullOrEmpty()) return@withContext abs
                    return@withContext link
                }
                "VT" -> {
                    if (words?.word1 == null || words.word2 == null || words.word3 == null || words.word4 == null) return@withContext link
                    val res1 = try { app.get(link).text } catch (e: Exception) { "" }
                    val part1 = res1.substringAfter(words.word1!!).substringBefore(words.word2!!).replace("\">", "").trim()
                    val newLink = "https://vidtube.one$part1"
                    val res2 = try { app.get(newLink).text } catch (e: Exception) { "" }
                    return@withContext "https://${res2.substringAfter(words.word3!!).substringBefore(words.word4!!)}"
                }
                "AR" -> {
                    return@withContext getFinalRedirectUrl(link) ?: link
                }
                "WC" -> {
                    return@withContext getFinalDownloadUrl(link) ?: link
                }
                "BU" -> {
                    return@withContext link
                }
                "QI" -> {
                    return@withContext server.originalLink ?: link
                }
                else -> {
                    if (words?.word1 != null && words.word2 != null) {
                        val res = try { app.get(link).text } catch (e: Exception) { "" }
                        if (res.isNotBlank()) {
                            return@withContext res.substringAfter(words.word1!!).substringBefore(words.word2!!).replace("amp;", "")
                        }
                    }
                    return@withContext link
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
            return@withContext link
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean = withContext(Dispatchers.IO) {
        logd("====== LOADLINKS START (PARALLEL) ======")
        try {
            val parts = data.split('|')
            if (parts.size < 2) return@withContext false
            var rawAnimeId = parts[0].trim()
            val episodeId = parts[1].trim()

            if (rawAnimeId.startsWith("http", ignoreCase = true) || rawAnimeId.contains(mainUrl)) {
                try {
                    val pathPart = rawAnimeId.substringAfterLast('/').substringBefore('?')
                    rawAnimeId = URLDecoder.decode(pathPart, "utf-8").trim()
                } catch (e: Exception) {
                    logd("Failed to normalize animeId: ${e.message}")
                }
            }

            val animeId = rawAnimeId
            val servers = fetchServersForEpisode(animeId, episodeId)
            if (servers.isEmpty()) return@withContext false

            val sortedServers = servers.sortedWith(
                compareByDescending<ServerModel> { getQualityAsInt(it.quality) }
                    .thenBy {
                        when (it.name?.uppercase(Locale.getDefault())) {
                            "PD" -> 0
                            "PD EU TEST" -> 1
                            else -> 2
                        }
                    }
            )

            logd("⚡ Processing ${sortedServers.size} servers in parallel with coroutineScope...")
            coroutineScope {
                sortedServers.map { server ->
                    async {
                        try {
                            val finalUrl = resolveServerModel(server)
                            if (!finalUrl.isNullOrBlank() && !finalUrl.trim().startsWith("<")) {
                                val serverName = server.name ?: "Server"

                                when {
                                    serverName.equals("PD", ignoreCase = true) -> {
                                        try {
                                            loadExtractor(finalUrl, mainUrl, subtitleCallback, callback)
                                        } catch (e: Exception) {
                                            logd("Error invoking loadExtractor for PD: ${e.message}")
                                        }
                                    }
                                    serverName.uppercase(Locale.getDefault()) == "KF" -> {
                                        callback(
                                            newExtractorLink(
                                                source = name,
                                                name = serverName,
                                                url = finalUrl,
                                            ) {
                                                referer = "https://krakenfiles.com/"
                                                quality = getQualityFromName(server.quality)
                                            }
                                        )
                                    }
                                    else -> {
                                        try {
                                            val handled = loadExtractor(finalUrl, mainUrl, subtitleCallback, callback)
                                            if (!handled) {
                                                callback(
                                                    newExtractorLink(
                                                        source = name,
                                                        name = "$serverName ${server.quality ?: ""}".trim(),
                                                        url = finalUrl,
                                                    ) {
                                                        this.referer = mainUrl
                                                        this.quality = getQualityFromName(server.quality)
                                                    }
                                                )
                                            }
                                        } catch (e: Exception) {
                                            logd("Error resolving $serverName: ${e.message}")
                                        }
                                    }
                                }
                            }
                        } catch (e: Exception) {
                            logd("Exception on server ${server.name}: ${e.message}")
                        }
                    }
                }.awaitAll()
            }

            logd("====== LOADLINKS END ======")
            return@withContext true
        } catch (e: Exception) {
            e.printStackTrace()
            return@withContext false
        }
    }
}
