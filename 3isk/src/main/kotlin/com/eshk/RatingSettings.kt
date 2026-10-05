package com.eshk

import android.annotation.SuppressLint
import android.app.AlertDialog
import android.app.Dialog
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Outline
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

    class RatingListDialog : DialogFragment() {

        private var originalList: List<PluginRating> = emptyList()
        private var isSortedByLikes = false
        private var adapter: RatingAdapter? = null

        override fun onCreateDialog(
            savedInstanceState: Bundle?
        ): Dialog {

            val dialog = Dialog(requireContext())

            val root = LinearLayout(requireContext()).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(12.dp(context), 16.dp(context), 12.dp(context), 8.dp(context))
                setBackgroundColor(Color.parseColor("#050811"))
            }

            val header = FrameLayout(requireContext()).apply {
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    60.dp(context)
                ).apply {
                    bottomMargin = 8.dp(context)
                }
            }

            val title = TextView(requireContext()).apply {
                text = "⭐ تقييمات الإضافات"
                textSize = 20f
                setTextColor(Color.WHITE)
                gravity = Gravity.CENTER
                layoutParams = FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT
                )
            }

            val close = TextView(requireContext()).apply {
                text = "×"
                textSize = 38f
                setTextColor(Color.parseColor("#94A3B8"))
                gravity = Gravity.CENTER
                layoutParams = FrameLayout.LayoutParams(
                    48.dp(context),
                    48.dp(context)
                ).apply {
                    gravity = Gravity.START or Gravity.CENTER_VERTICAL
                }
                addTouchScaleEffect { dismiss() }
            }

            val sortBtn = TextView(requireContext()).apply {
                text = "🔥 الأكثر إعجاباً"
                textSize = 12.5f
                setTextColor(Color.parseColor("#38BDF8"))
                gravity = Gravity.CENTER
                background = roundedStrokeBackground(
                    Color.parseColor("#0C1B33"),
                    Color.parseColor("#0284C7"),
                    1.dp(context),
                    14f
                )
                setPadding(12.dp(context), 8.dp(context), 12.dp(context), 8.dp(context))
                layoutParams = FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply {
                    gravity = Gravity.END or Gravity.CENTER_VERTICAL
                }

                addTouchScaleEffect {
                    if (originalList.isEmpty()) return@addTouchScaleEffect
                    isSortedByLikes = !isSortedByLikes
                    if (isSortedByLikes) {
                        text = "📋 الافتراضي"
                        val sorted = originalList.sortedByDescending { it.likes }
                        adapter?.updateData(sorted)
                    } else {
                        text = "🔥 الأكثر إعجاباً"
                        adapter?.updateData(originalList)
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
                setPadding(0, 8.dp(context), 0, 24.dp(context))
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
                roundedBackground(Color.parseColor("#050811"), 28f)
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

        private fun parseTypes(typesArray: JSONArray?): List<String> {
            if (typesArray == null || typesArray.length() == 0) return listOf("إضافة عامة")

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

            return if (list.isNotEmpty()) list else listOf("عام")
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

                                val tags = parseTypes(obj.optJSONArray("tvTypes"))

                                val likesDeferred = async { getCount(url, "like") }
                                val dislikesDeferred = async { getCount(url, "dislike") }

                                PluginRating(
                                    name = name,
                                    url = url,
                                    icon = icon,
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
                        adapter = RatingAdapter(originalList.toMutableList())
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
        val tags: List<String>,
        var likes: Int,
        var dislikes: Int
    )

    class RatingAdapter(
        private var items: List<PluginRating>
    ) : RecyclerView.Adapter<RatingAdapter.ViewHolder>() {

        fun updateData(newItems: List<PluginRating>) {
            items = newItems
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

            val card = FrameLayout(ctx).apply {
                layoutDirection = View.LAYOUT_DIRECTION_LTR
                background = roundedStrokeBackground(
                    Color.parseColor("#0C101F"),
                    Color.parseColor("#172038"),
                    1.dp(ctx),
                    22f
                )

                layoutParams = RecyclerView.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    94.dp(ctx)
                ).apply {
                    setMargins(0, 5.dp(ctx), 0, 5.dp(ctx))
                }
            }

            val icon = ImageView(ctx).apply {
                scaleType = ImageView.ScaleType.CENTER_CROP
                background = roundedBackground(Color.parseColor("#172138"), 18f)
                outlineProvider = object : ViewOutlineProvider() {
                    override fun getOutline(view: View, outline: Outline) {
                        outline.setRoundRect(0, 0, view.width, view.height, 18.dp(ctx).toFloat())
                    }
                }
                clipToOutline = true

                layoutParams = FrameLayout.LayoutParams(
                    68.dp(ctx),
                    68.dp(ctx)
                ).apply {
                    gravity = Gravity.START or Gravity.CENTER_VERTICAL
                    leftMargin = 12.dp(ctx)
                }
            }

            val infoContainer = LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER_VERTICAL

                layoutParams = FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.MATCH_PARENT
                ).apply {
                    leftMargin = 90.dp(ctx)
                    rightMargin = 126.dp(ctx)
                }
            }

            val name = TextView(ctx).apply {
                textSize = 15.5f
                setTextColor(Color.WHITE)
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
                gravity = Gravity.START
                layoutDirection = View.LAYOUT_DIRECTION_LTR
                setTypeface(typeface, android.graphics.Typeface.BOLD)
            }

            val tagsContainer = LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                layoutDirection = View.LAYOUT_DIRECTION_LTR
                setPadding(0, 4.dp(ctx), 0, 4.dp(ctx))
            }

            val ratingText = TextView(ctx).apply {
                textSize = 12.5f
                setTextColor(Color.parseColor("#94A3B8"))
                gravity = Gravity.START
                layoutDirection = View.LAYOUT_DIRECTION_LTR
            }

            infoContainer.addView(name)
            infoContainer.addView(tagsContainer)
            infoContainer.addView(ratingText)

            val actionsContainer = LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER

                layoutParams = FrameLayout.LayoutParams(
                    116.dp(ctx),
                    FrameLayout.LayoutParams.MATCH_PARENT
                ).apply {
                    gravity = Gravity.END or Gravity.CENTER_VERTICAL
                    rightMargin = 8.dp(ctx)
                }
            }

            val likeBtn = LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                background = roundedStrokeBackground(
                    Color.parseColor("#09241B"),
                    Color.parseColor("#10B981"),
                    1.2.dp(ctx),
                    16f
                )

                layoutParams = LinearLayout.LayoutParams(
                    52.dp(ctx),
                    68.dp(ctx)
                ).apply {
                    rightMargin = 6.dp(ctx)
                }
            }

            val likeIcon = TextView(ctx).apply {
                text = "👍"
                textSize = 16f
                gravity = Gravity.CENTER
            }

            val likeCount = TextView(ctx).apply {
                textSize = 12.5f
                setTextColor(Color.WHITE)
                gravity = Gravity.CENTER
                setTypeface(typeface, android.graphics.Typeface.BOLD)
            }

            likeBtn.addView(likeIcon)
            likeBtn.addView(likeCount)

            val dislikeBtn = LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                background = roundedStrokeBackground(
                    Color.parseColor("#290C13"),
                    Color.parseColor("#EF4444"),
                    1.2.dp(ctx),
                    16f
                )

                layoutParams = LinearLayout.LayoutParams(
                    52.dp(ctx),
                    68.dp(ctx)
                )
            }

            val dislikeIcon = TextView(ctx).apply {
                text = "👎"
                textSize = 16f
                gravity = Gravity.CENTER
            }

            val dislikeCount = TextView(ctx).apply {
                textSize = 12.5f
                setTextColor(Color.WHITE)
                gravity = Gravity.CENTER
                setTypeface(typeface, android.graphics.Typeface.BOLD)
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

            holder.name.text = item.name

            val total = item.likes + item.dislikes
            val score = if (total == 0) {
                "5.0"
            } else {
                val calculated = 1.0 + ((item.likes.toDouble() / total) * 4.0)
                String.format(Locale.US, "%.1f", calculated)
            }
            holder.ratingText.text = "⭐ $score  ($total صوت)"

            holder.tagsContainer.removeAllViews()
            item.tags.take(3).forEach { tag ->
                val tagView = TextView(ctx).apply {
                    text = tag
                    textSize = 10.5f
                    setTextColor(Color.parseColor("#93C5FD"))
                    background = roundedBackground(Color.parseColor("#16233B"), 8f)
                    setPadding(7.dp(ctx), 2.dp(ctx), 7.dp(ctx), 2.dp(ctx))
                    layoutParams = LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT
                    ).apply {
                        rightMargin = 5.dp(ctx)
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
            val userVote = prefs.getString("user_voted_$baseKey", "none")

            updateButtonsVisual(ctx, holder.likeBtn, holder.dislikeBtn, userVote)

            holder.card.addTouchScaleEffect()

            holder.likeBtn.addTouchScaleEffect {
                if (prefs.getString("user_voted_$baseKey", "none") != "none") {
                    Toast.makeText(ctx, "لقد قمت بالتصويت مسبقاً", Toast.LENGTH_SHORT).show()
                    return@addTouchScaleEffect
                }

                holder.likeBtn.isEnabled = false
                holder.dislikeBtn.isEnabled = false

                CoroutineScope(Dispatchers.IO).launch {
                    val success = RatingSettings.incrementVote(item.url, "like")

                    withContext(Dispatchers.Main) {
                        holder.likeBtn.isEnabled = true
                        holder.dislikeBtn.isEnabled = true

                        if (success) {
                            prefs.edit().putString("user_voted_$baseKey", "like").apply()
                            item.likes += 1
                            holder.likeCount.text = item.likes.toString()

                            val updatedTotal = item.likes + item.dislikes
                            val updatedScore = String.format(Locale.US, "%.1f", 1.0 + ((item.likes.toDouble() / updatedTotal) * 4.0))
                            holder.ratingText.text = "⭐ $updatedScore  ($updatedTotal صوت)"

                            updateButtonsVisual(ctx, holder.likeBtn, holder.dislikeBtn, "like")
                        } else {
                            Toast.makeText(ctx, "تعذر تسجيل الإعجاب", Toast.LENGTH_SHORT).show()
                        }
                    }
                }
            }

            holder.dislikeBtn.addTouchScaleEffect {
                if (prefs.getString("user_voted_$baseKey", "none") != "none") {
                    Toast.makeText(ctx, "لقد قمت بالتصويت مسبقاً", Toast.LENGTH_SHORT).show()
                    return@addTouchScaleEffect
                }

                AlertDialog.Builder(ctx)
                    .setTitle("تأكيد التصويت")
                    .setMessage("هل أنت متأكد أنك تريد تسجيل عدم الإعجاب بالإضافة؟")
                    .setPositiveButton("نعم") { _, _ ->
                        holder.likeBtn.isEnabled = false
                        holder.dislikeBtn.isEnabled = false

                        CoroutineScope(Dispatchers.IO).launch {
                            val success = RatingSettings.incrementVote(item.url, "dislike")

                            withContext(Dispatchers.Main) {
                                holder.likeBtn.isEnabled = true
                                holder.dislikeBtn.isEnabled = true

                                if (success) {
                                    prefs.edit().putString("user_voted_$baseKey", "dislike").apply()
                                    item.dislikes += 1
                                    holder.dislikeCount.text = item.dislikes.toString()

                                    val updatedTotal = item.likes + item.dislikes
                                    val updatedScore = String.format(Locale.US, "%.1f", 1.0 + ((item.likes.toDouble() / updatedTotal) * 4.0))
                                    holder.ratingText.text = "⭐ $updatedScore  ($updatedTotal صوت)"

                                    updateButtonsVisual(ctx, holder.likeBtn, holder.dislikeBtn, "dislike")
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

    private fun updateButtonsVisual(
        ctx: Context,
        likeBtn: LinearLayout,
        dislikeBtn: LinearLayout,
        voteType: String?
    ) {
        when (voteType) {
            "like" -> {
                likeBtn.background = roundedStrokeBackground(
                    Color.parseColor("#054734"),
                    Color.parseColor("#34D399"),
                    2.dp(ctx),
                    16f
                )
                dislikeBtn.background = roundedStrokeBackground(
                    Color.parseColor("#1C0D13"),
                    Color.parseColor("#5A212D"),
                    1.dp(ctx),
                    16f
                )
            }
            "dislike" -> {
                likeBtn.background = roundedStrokeBackground(
                    Color.parseColor("#081A15"),
                    Color.parseColor("#1B4538"),
                    1.dp(ctx),
                    16f
                )
                dislikeBtn.background = roundedStrokeBackground(
                    Color.parseColor("#4D121D"),
                    Color.parseColor("#F87171"),
                    2.dp(ctx),
                    16f
                )
            }
            else -> {
                likeBtn.background = roundedStrokeBackground(
                    Color.parseColor("#09241B"),
                    Color.parseColor("#10B981"),
                    1.2.dp(ctx),
                    16f
                )
                dislikeBtn.background = roundedStrokeBackground(
                    Color.parseColor("#290C13"),
                    Color.parseColor("#EF4444"),
                    1.2.dp(ctx),
                    16f
                )
            }
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun View.addTouchScaleEffect(onClick: (() -> Unit)? = null) {
        setOnTouchListener { v, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    v.animate().scaleX(0.93f).scaleY(0.93f).setDuration(90).start()
                }
                MotionEvent.ACTION_UP -> {
                    v.animate().scaleX(1.0f).scaleY(1.0f).setDuration(100).withEndAction {
                        onClick?.invoke()
                    }.start()
                }
                MotionEvent.ACTION_CANCEL -> {
                    v.animate().scaleX(1.0f).scaleY(1.0f).setDuration(90).start()
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
