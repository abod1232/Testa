package com.eshk

import android.annotation.SuppressLint
import android.app.AlertDialog
import android.app.Dialog
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Outline
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.text.TextUtils
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.fragment.app.DialogFragment
import androidx.fragment.app.FragmentManager
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.lagradost.cloudstream3.app
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

object RatingSettings {

    private const val PLUGINS_URL =
        "https://raw.githubusercontent.com/abod1232/Testa/refs/heads/builds/plugins.json"

    private const val COUNTER_API =
        "https://counterapi.com/api"

    private const val NAMESPACE =
        "cs_re3arabi_ratings"

    private const val PREFS =
        "extension_ratings_prefs"

    private val imageCache = ConcurrentHashMap<String, Bitmap>()

    fun show(fragmentManager: FragmentManager) {
        RatingListDialog().show(
            fragmentManager,
            "rating_list"
        )
    }

    private fun calculateScore(likes: Int, dislikes: Int): Double {
        val total = likes + dislikes
        if (total == 0) return 0.0

        var score = 1.0 + ((likes.toDouble() / total) * 4.0)

        if (total < 10) {
            score -= 1.0
        }

        return score.coerceIn(1.0, 5.0)
    }

    data class ThemeColor(
        val rootBg: String,
        val cardBg: String,
        val cardStroke: String,
        val titleColor: String
    )

    private val THEMES = listOf(
        ThemeColor("#040C1A", "#081326", "#0E3A6E", "#38BDF8"),
        ThemeColor("#120C03", "#1F1406", "#5C3A08", "#FBBF24"),
        ThemeColor("#0D051A", "#160A2E", "#4C1D95", "#C084FC")
    )

    class RatingListDialog : DialogFragment() {

        private var originalList: List<PluginRating> = emptyList()
        private var sortMode = 0
        private var adapter: RatingAdapter? = null

        override fun onCreateDialog(
            savedInstanceState: Bundle?
        ): Dialog {

            val dialog = Dialog(requireContext())

            val root = LinearLayout(requireContext()).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(10.dp(context), 14.dp(context), 10.dp(context), 8.dp(context))
                setBackgroundColor(Color.parseColor(THEMES[0].rootBg))
            }

            val header = LinearLayout(requireContext()).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    52.dp(context)
                ).apply {
                    bottomMargin = 8.dp(context)
                }
            }

            val close = TextView(requireContext()).apply {
                text = "×"
                textSize = 34f
                setTextColor(Color.parseColor("#94A3B8"))
                gravity = Gravity.CENTER
                layoutParams = LinearLayout.LayoutParams(
                    40.dp(context),
                    40.dp(context)
                )
                addTouchScaleEffect { dismiss() }
            }

            val title = TextView(requireContext()).apply {
                text = "⭐ التقييمات"
                textSize = 18f
                setTextColor(Color.parseColor(THEMES[0].titleColor))
                gravity = Gravity.CENTER
                setTypeface(typeface, Typeface.BOLD)
                layoutParams = LinearLayout.LayoutParams(
                    0,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    1f
                )
            }

            val sortBtn = TextView(requireContext()).apply {
                text = "🔥 الأكثر إعجاباً"
                textSize = 11.5f
                setTextColor(Color.parseColor("#38BDF8"))
                gravity = Gravity.CENTER
                background = roundedStrokeBackground(
                    Color.parseColor("#0C1C36"),
                    Color.parseColor("#0284C7"),
                    1.dp(context),
                    12f
                )
                setPadding(10.dp(context), 6.dp(context), 10.dp(context), 6.dp(context))
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                )

                addTouchScaleEffect {
                    if (originalList.isEmpty()) return@addTouchScaleEffect
                    sortMode = (sortMode + 1) % 3
                    val currentTheme = THEMES[sortMode]

                    root.setBackgroundColor(Color.parseColor(currentTheme.rootBg))
                    dialog.window?.setBackgroundDrawable(
                        roundedBackground(Color.parseColor(currentTheme.rootBg), 26f)
                    )
                    title.setTextColor(Color.parseColor(currentTheme.titleColor))

                    when (sortMode) {
                        1 -> {
                            text = "⭐ الأعلى تقييماً"
                            setTextColor(Color.parseColor("#FBBF24"))
                            background = roundedStrokeBackground(
                                Color.parseColor("#261C08"),
                                Color.parseColor("#D97706"),
                                1.dp(context),
                                12f
                            )
                            val sorted = originalList.sortedWith(
                                compareByDescending<PluginRating> { calculateScore(it.likes, it.dislikes) }
                                    .thenByDescending { it.likes + it.dislikes }
                                    .thenByDescending { it.likes }
                            )
                            adapter?.updateData(sorted, sortMode)
                        }
                        2 -> {
                            text = "📋 الافتراضي"
                            setTextColor(Color.parseColor("#C084FC"))
                            background = roundedStrokeBackground(
                                Color.parseColor("#1E1438"),
                                Color.parseColor("#7C3AED"),
                                1.dp(context),
                                12f
                            )
                            adapter?.updateData(originalList, sortMode)
                        }
                        else -> {
                            text = "🔥 الأكثر إعجاباً"
                            setTextColor(Color.parseColor("#38BDF8"))
                            background = roundedStrokeBackground(
                                Color.parseColor("#0C1C36"),
                                Color.parseColor("#0284C7"),
                                1.dp(context),
                                12f
                            )
                            val sorted = originalList.sortedWith(
                                compareByDescending<PluginRating> { it.likes }
                                    .thenByDescending { it.likes - it.dislikes }
                                    .thenBy { it.dislikes }
                            )
                            adapter?.updateData(sorted, sortMode)
                        }
                    }
                }
            }

            header.addView(close)
            header.addView(title)
            header.addView(sortBtn)

            root.addView(header)

            val progress = ProgressBar(
                requireContext(),
                null,
                android.R.attr.progressBarStyleHorizontal
            ).apply {
                isIndeterminate = true
                visibility = View.VISIBLE
            }

            root.addView(
                progress,
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    3.dp(context)
                )
            )

            val recycler = RecyclerView(requireContext()).apply {
                layoutManager = LinearLayoutManager(requireContext())
                overScrollMode = View.OVER_SCROLL_NEVER
                clipToPadding = false
                setPadding(0, 6.dp(context), 0, 20.dp(context))
            }

            root.addView(
                recycler,
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    0,
                    1f
                )
            )

            dialog.setContentView(root)

            dialog.window?.setBackgroundDrawable(
                roundedBackground(Color.parseColor(THEMES[0].rootBg), 26f)
            )

            dialog.setOnShowListener {
                dialog.window?.setLayout(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT
                )
            }

            loadRatings(recycler, progress)

            return dialog
        }

        private fun parseLanguage(lang: String): String {
            return when (lang.lowercase()) {
                "ar" -> "🇸🇦 عربي"
                "en" -> "🌐 EN"
                "iq" -> "🇮🇶 عراقي"
                else -> lang.uppercase()
            }
        }

        private fun parseTypes(typesArray: JSONArray?): List<String> {
            if (typesArray == null || typesArray.length() == 0) return emptyList()

            val map = mapOf(
                "TvSeries" to "مسلسلات",
                "Movie" to "أفلام",
                "Anime" to "أنمي",
                "AsianDrama" to "آسيوي",
                "Live" to "مباشر",
                "Drama" to "دراما",
                "Music" to "موسيقى",
                "Documentary" to "وثائقي",
                "Others" to "منوعة"
            )

            val list = mutableListOf<String>()
            for (i in 0 until typesArray.length()) {
                val type = typesArray.optString(i)
                map[type]?.let {
                    if (!list.contains(it)) list.add(it)
                }
            }

            return list
        }

        private fun loadRatings(
            recycler: RecyclerView,
            progress: ProgressBar
        ) {
            CoroutineScope(Dispatchers.IO).launch {
                try {
                    val response = app.get(PLUGINS_URL)
                    val array = JSONArray(response.text)
                    val rawList = mutableListOf<JSONObject>()

                    for (i in 0 until array.length()) {
                        rawList.add(array.getJSONObject(i))
                    }

                    val plugins = coroutineScope {
                        rawList.map { obj ->
                            async(Dispatchers.IO) {
                                val name = obj.optString("name")
                                val url = obj.optString("url")

                                if (name.isBlank() || url.isBlank()) return@async null

                                val icon = when {
                                    obj.optString("iconUrl").isNotBlank() -> obj.optString("iconUrl")
                                    obj.optString("icon").isNotBlank() -> obj.optString("icon")
                                    else -> ""
                                }

                                val language = parseLanguage(obj.optString("language", "ar"))
                                val tags = parseTypes(obj.optJSONArray("tvTypes"))

                                val likesDeferred = async { getCount(url, "like") }
                                val dislikesDeferred = async { getCount(url, "dislike") }

                                PluginRating(
                                    name = name,
                                    url = url,
                                    icon = icon,
                                    language = language,
                                    tags = tags,
                                    likes = likesDeferred.await(),
                                    dislikes = dislikesDeferred.await()
                                )
                            }
                        }.awaitAll().filterNotNull()
                    }

                    originalList = plugins

                    withContext(Dispatchers.Main) {
                        progress.visibility = View.GONE
                        adapter = RatingAdapter(originalList.toMutableList(), 0)
                        recycler.adapter = adapter
                    }

                } catch (e: Exception) {
                    withContext(Dispatchers.Main) {
                        progress.visibility = View.GONE
                        Toast.makeText(
                            requireContext(),
                            "حدث خطأ أثناء تحميل التقييمات",
                            Toast.LENGTH_LONG
                        ).show()
                    }
                }
            }
        }

        private suspend fun getCount(pluginUrl: String, action: String): Int {
            return try {
                val key = getKey(pluginUrl, action)
                val url = "$COUNTER_API/$NAMESPACE/$key?readOnly=true"
                val response = app.get(url)
                val json = JSONObject(response.text)
                json.optInt("count", json.optInt("value", 0))
            } catch (e: Exception) {
                0
            }
        }
    }

    data class PluginRating(
        val name: String,
        val url: String,
        val icon: String,
        val language: String,
        val tags: List<String>,
        var likes: Int,
        var dislikes: Int
    )

    class RatingAdapter(
        private var items: List<PluginRating>,
        private var currentThemeIndex: Int
    ) : RecyclerView.Adapter<RatingAdapter.ViewHolder>() {

        fun updateData(newItems: List<PluginRating>, themeIndex: Int) {
            items = newItems
            currentThemeIndex = themeIndex
            notifyDataSetChanged()
        }

        class ViewHolder(
            val card: FrameLayout,
            val icon: ImageView,
            val name: TextView,
            val tagsContainer: LinearLayout,
            val ratingText: TextView,
            val likeBtn: LinearLayout,
            val likeCount: TextView,
            val dislikeBtn: LinearLayout,
            val dislikeCount: TextView
        ) : RecyclerView.ViewHolder(card)

        override fun onCreateViewHolder(
            parent: ViewGroup,
            viewType: Int
        ): ViewHolder {
            val ctx = parent.context
            val theme = THEMES[currentThemeIndex]

            val card = FrameLayout(ctx).apply {
                layoutDirection = View.LAYOUT_DIRECTION_LTR
                background = roundedStrokeBackground(
                    Color.parseColor(theme.cardBg),
                    Color.parseColor(theme.cardStroke),
                    1.dp(ctx),
                    18f
                )

                layoutParams = RecyclerView.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    90.dp(ctx)
                ).apply {
                    setMargins(0, 3.dp(ctx), 0, 3.dp(ctx))
                }
            }

            val icon = ImageView(ctx).apply {
                scaleType = ImageView.ScaleType.CENTER_CROP
                background = roundedBackground(Color.parseColor("#151D33"), 15f)
                outlineProvider = object : ViewOutlineProvider() {
                    override fun getOutline(view: View, outline: Outline) {
                        outline.setRoundRect(0, 0, view.width, view.height, 15.dp(ctx).toFloat())
                    }
                }
                clipToOutline = true

                layoutParams = FrameLayout.LayoutParams(
                    58.dp(ctx),
                    58.dp(ctx)
                ).apply {
                    gravity = Gravity.START or Gravity.CENTER_VERTICAL
                    leftMargin = 10.dp(ctx)
                }
            }

            val infoContainer = LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER_VERTICAL

                layoutParams = FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.MATCH_PARENT
                ).apply {
                    leftMargin = 76.dp(ctx)
                    rightMargin = 72.dp(ctx)
                }
            }

            val name = TextView(ctx).apply {
                textSize = 14.5f
                setTextColor(Color.WHITE)
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
                gravity = Gravity.START
                layoutDirection = View.LAYOUT_DIRECTION_LTR
                setTypeface(typeface, Typeface.BOLD)
            }

            val tagsContainer = LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                layoutDirection = View.LAYOUT_DIRECTION_LTR
                setPadding(0, 3.dp(ctx), 0, 3.dp(ctx))
            }

            val ratingText = TextView(ctx).apply {
                textSize = 11.5f
                setTextColor(Color.parseColor("#94A3B8"))
                gravity = Gravity.START
                layoutDirection = View.LAYOUT_DIRECTION_LTR
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
            }

            infoContainer.addView(name)
            infoContainer.addView(tagsContainer)
            infoContainer.addView(ratingText)

            val actionsContainer = LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER

                layoutParams = FrameLayout.LayoutParams(
                    58.dp(ctx),
                    FrameLayout.LayoutParams.MATCH_PARENT
                ).apply {
                    gravity = Gravity.END or Gravity.CENTER_VERTICAL
                    rightMargin = 8.dp(ctx)
                }
            }

            val likeBtn = LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER
                background = roundedStrokeBackground(
                    Color.parseColor("#06241C"),
                    Color.parseColor("#10B981"),
                    1.dp(ctx),
                    10f
                )

                layoutParams = LinearLayout.LayoutParams(
                    56.dp(ctx),
                    26.dp(ctx)
                ).apply {
                    bottomMargin = 4.dp(ctx)
                }
            }

            val likeIcon = TextView(ctx).apply {
                text = "👍"
                textSize = 11f
                gravity = Gravity.CENTER
                setPadding(0, 0, 4.dp(ctx), 0)
            }

            val likeCount = TextView(ctx).apply {
                textSize = 11f
                setTextColor(Color.WHITE)
                gravity = Gravity.CENTER
                setTypeface(typeface, Typeface.BOLD)
            }

            likeBtn.addView(likeIcon)
            likeBtn.addView(likeCount)

            val dislikeBtn = LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER
                background = roundedStrokeBackground(
                    Color.parseColor("#260910"),
                    Color.parseColor("#EF4444"),
                    1.dp(ctx),
                    10f
                )

                layoutParams = LinearLayout.LayoutParams(
                    56.dp(ctx),
                    26.dp(ctx)
                )
            }

            val dislikeIcon = TextView(ctx).apply {
                text = "👎"
                textSize = 11f
                gravity = Gravity.CENTER
                setPadding(0, 0, 4.dp(ctx), 0)
            }

            val dislikeCount = TextView(ctx).apply {
                textSize = 11f
                setTextColor(Color.WHITE)
                gravity = Gravity.CENTER
                setTypeface(typeface, Typeface.BOLD)
            }

            dislikeBtn.addView(dislikeIcon)
            dislikeBtn.addView(dislikeCount)

            actionsContainer.addView(likeBtn)
            actionsContainer.addView(dislikeBtn)

            card.addView(icon)
            card.addView(infoContainer)
            card.addView(actionsContainer)

            return ViewHolder(
                card,
                icon,
                name,
                tagsContainer,
                ratingText,
                likeBtn,
                likeCount,
                dislikeBtn,
                dislikeCount
            )
        }

        override fun onBindViewHolder(
            holder: ViewHolder,
            position: Int
        ) {
            val ctx = holder.itemView.context
            val item = items[position]
            val theme = THEMES[currentThemeIndex]

            holder.card.background = roundedStrokeBackground(
                Color.parseColor(theme.cardBg),
                Color.parseColor(theme.cardStroke),
                1.dp(ctx),
                18f
            )

            holder.name.text = item.name

            val total = item.likes + item.dislikes
            if (total == 0) {
                holder.ratingText.text = "✨ جديد • بانتظار التقييم"
                holder.ratingText.setTextColor(Color.parseColor("#64748B"))
            } else {
                val score = calculateScore(item.likes, item.dislikes)
                val formattedScore = String.format(Locale.US, "%.1f", score)
                holder.ratingText.text = "⭐ $formattedScore  ($total صوت)"
                holder.ratingText.setTextColor(Color.parseColor("#FBBF24"))
            }

            holder.tagsContainer.removeAllViews()

            val langTag = TextView(ctx).apply {
                text = item.language
                textSize = 9.5f
                setTextColor(Color.parseColor("#E2E8F0"))
                background = roundedBackground(Color.parseColor("#334155"), 5f)
                setPadding(5.dp(ctx), 2.dp(ctx), 5.dp(ctx), 2.dp(ctx))
                maxLines = 1
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply {
                    rightMargin = 4.dp(ctx)
                }
            }
            holder.tagsContainer.addView(langTag)

            item.tags.take(2).forEach { tag ->
                val tagView = TextView(ctx).apply {
                    text = tag
                    textSize = 9.5f
                    setTextColor(Color.parseColor("#93C5FD"))
                    background = roundedBackground(Color.parseColor("#172554"), 5f)
                    setPadding(5.dp(ctx), 2.dp(ctx), 5.dp(ctx), 2.dp(ctx))
                    maxLines = 1
                    layoutParams = LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT
                    ).apply {
                        rightMargin = 4.dp(ctx)
                    }
                }
                holder.tagsContainer.addView(tagView)
            }

            holder.likeCount.text = item.likes.toString()
            holder.dislikeCount.text = item.dislikes.toString()

            holder.icon.setImageDrawable(null)
            holder.icon.tag = item.icon

            if (item.icon.isNotBlank()) {
                val cached = imageCache[item.icon]
                if (cached != null) {
                    holder.icon.setImageBitmap(cached)
                } else {
                    CoroutineScope(Dispatchers.IO).launch {
                        try {
                            val response = app.get(item.icon)
                            val body = response.body
                            val bytes = body.bytes()
                            body.close()

                            val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)

                            if (bitmap != null) {
                                imageCache[item.icon] = bitmap
                                withContext(Dispatchers.Main) {
                                    if (holder.icon.tag == item.icon) {
                                        holder.icon.setImageBitmap(bitmap)
                                    }
                                }
                            }
                        } catch (e: Exception) {
                        }
                    }
                }
            }

            val prefs = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            val baseKey = getBaseHash(item.url)
            val hasLiked = prefs.getBoolean("liked_$baseKey", false)
            val hasDisliked = prefs.getBoolean("disliked_$baseKey", false)

            updateLikeVisual(ctx, holder.likeBtn, hasLiked)
            updateDislikeVisual(ctx, holder.dislikeBtn, hasDisliked)

            holder.card.addTouchScaleEffect()

            holder.likeBtn.addTouchScaleEffect {
                if (prefs.getBoolean("liked_$baseKey", false)) {
                    Toast.makeText(ctx, "لقد سجلت إعجابك بهذه الإضافة مسبقاً", Toast.LENGTH_SHORT).show()
                    return@addTouchScaleEffect
                }

                holder.likeBtn.isEnabled = false

                CoroutineScope(Dispatchers.IO).launch {
                    val success = RatingSettings.incrementVote(item.url, "like")

                    withContext(Dispatchers.Main) {
                        holder.likeBtn.isEnabled = true

                        if (success) {
                            prefs.edit().putBoolean("liked_$baseKey", true).apply()
                            item.likes += 1
                            holder.likeCount.text = item.likes.toString()

                            val updatedTotal = item.likes + item.dislikes
                            val updatedScore = String.format(Locale.US, "%.1f", calculateScore(item.likes, item.dislikes))
                            holder.ratingText.text = "⭐ $updatedScore  ($updatedTotal صوت)"
                            holder.ratingText.setTextColor(Color.parseColor("#FBBF24"))

                            updateLikeVisual(ctx, holder.likeBtn, true)
                        } else {
                            Toast.makeText(ctx, "تعذر تسجيل الإعجاب", Toast.LENGTH_SHORT).show()
                        }
                    }
                }
            }

            holder.dislikeBtn.addTouchScaleEffect {
                if (prefs.getBoolean("disliked_$baseKey", false)) {
                    Toast.makeText(ctx, "لقد سجلت عدم إعجابك بهذه الإضافة مسبقاً", Toast.LENGTH_SHORT).show()
                    return@addTouchScaleEffect
                }

                AlertDialog.Builder(ctx)
                    .setTitle("تأكيد التصويت")
                    .setMessage("هل أنت متأكد أنك تريد تسجيل عدم الإعجاب بالإضافة؟")
                    .setPositiveButton("نعم") { _, _ ->
                        holder.dislikeBtn.isEnabled = false

                        CoroutineScope(Dispatchers.IO).launch {
                            val success = RatingSettings.incrementVote(item.url, "dislike")

                            withContext(Dispatchers.Main) {
                                holder.dislikeBtn.isEnabled = true

                                if (success) {
                                    prefs.edit().putBoolean("disliked_$baseKey", true).apply()
                                    item.dislikes += 1
                                    holder.dislikeCount.text = item.dislikes.toString()

                                    val updatedTotal = item.likes + item.dislikes
                                    val updatedScore = String.format(Locale.US, "%.1f", calculateScore(item.likes, item.dislikes))
                                    holder.ratingText.text = "⭐ $updatedScore  ($updatedTotal صوت)"
                                    holder.ratingText.setTextColor(Color.parseColor("#FBBF24"))

                                    updateDislikeVisual(ctx, holder.dislikeBtn, true)
                                } else {
                                    Toast.makeText(ctx, "تعذر تسجيل عدم الإعجاب", Toast.LENGTH_SHORT).show()
                                }
                            }
                        }
                    }
                    .setNegativeButton("إلغاء", null)
                    .show()
            }
        }

        override fun getItemCount(): Int = items.size
    }

    private suspend fun incrementVote(pluginUrl: String, action: String): Boolean {
        return try {
            val key = getKey(pluginUrl, action)
            val url = "$COUNTER_API/$NAMESPACE/$key"
            val response = app.get(url)
            val json = JSONObject(response.text)
            json.has("count") || json.has("value")
        } catch (e: Exception) {
            false
        }
    }

    private fun updateLikeVisual(ctx: Context, likeBtn: LinearLayout, active: Boolean) {
        if (active) {
            likeBtn.background = roundedStrokeBackground(
                Color.parseColor("#064E3B"),
                Color.parseColor("#34D399"),
                1.5.dp(ctx),
                10f
            )
        } else {
            likeBtn.background = roundedStrokeBackground(
                Color.parseColor("#06241C"),
                Color.parseColor("#10B981"),
                1.dp(ctx),
                10f
            )
        }
    }

    private fun updateDislikeVisual(ctx: Context, dislikeBtn: LinearLayout, active: Boolean) {
        if (active) {
            dislikeBtn.background = roundedStrokeBackground(
                Color.parseColor("#4C0519"),
                Color.parseColor("#F87171"),
                1.5.dp(ctx),
                10f
            )
        } else {
            dislikeBtn.background = roundedStrokeBackground(
                Color.parseColor("#260910"),
                Color.parseColor("#EF4444"),
                1.dp(ctx),
                10f
            )
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun View.addTouchScaleEffect(onClick: (() -> Unit)? = null) {
        setOnTouchListener { v, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    v.animate().scaleX(0.93f).scaleY(0.93f).setDuration(80).start()
                }
                MotionEvent.ACTION_UP -> {
                    v.animate().scaleX(1.0f).scaleY(1.0f).setDuration(100).withEndAction {
                        onClick?.invoke()
                    }.start()
                }
                MotionEvent.ACTION_CANCEL -> {
                    v.animate().scaleX(1.0f).scaleY(1.0f).setDuration(80).start()
                }
            }
            true
        }
    }

    private fun getBaseHash(url: String): String {
        return MessageDigest.getInstance("MD5")
            .digest(url.toByteArray())
            .joinToString("") { "%02x".format(it) }
    }

    private fun getKey(url: String, action: String): String {
        return "${action}_${getBaseHash(url)}"
    }

    private fun roundedBackground(
        color: Int,
        radius: Float
    ): GradientDrawable {
        return GradientDrawable().apply {
            setColor(color)
            cornerRadius = radius
        }
    }

    private fun roundedStrokeBackground(
        color: Int,
        strokeColor: Int,
        strokeWidth: Int,
        radius: Float
    ): GradientDrawable {
        return GradientDrawable().apply {
            setColor(color)
            setStroke(strokeWidth, strokeColor)
            cornerRadius = radius
        }
    }

    private fun Number.dp(context: Context?): Int {
        val safeContext = context ?: return this.toInt()
        return (this.toFloat() * safeContext.resources.displayMetrics.density).toInt()
    }
}
