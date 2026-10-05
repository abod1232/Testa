package com.eshk

import android.app.AlertDialog
import android.app.Dialog
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
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

                setPadding(
                    14.dp(context),
                    15.dp(context),
                    14.dp(context),
                    10.dp(context)
                )

                setBackgroundColor(
                    Color.rgb(7, 12, 24)
                )
            }

            val header = FrameLayout(requireContext()).apply {
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    65.dp(context)
                )
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
                textSize = 42f
                setTextColor(Color.rgb(190, 198, 220))
                gravity = Gravity.CENTER

                layoutParams = FrameLayout.LayoutParams(
                    55.dp(context),
                    55.dp(context)
                ).apply {
                    gravity = Gravity.START or Gravity.CENTER_VERTICAL
                }

                setOnClickListener {
                    dismiss()
                }
            }

            val sortBtn = TextView(requireContext()).apply {
                text = "🔥 الأكثر إعجاباً"
                textSize = 12f
                setTextColor(Color.rgb(205, 215, 240))
                gravity = Gravity.CENTER

                background = roundedBackground(
                    Color.rgb(24, 34, 56),
                    14f
                )

                setPadding(
                    10.dp(context),
                    6.dp(context),
                    10.dp(context),
                    6.dp(context)
                )

                layoutParams = FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply {
                    gravity = Gravity.END or Gravity.CENTER_VERTICAL
                    rightMargin = 6.dp(context)
                }

                setOnClickListener {
                    if (originalList.isEmpty()) return@setOnClickListener

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

                setPadding(
                    0,
                    8.dp(context),
                    0,
                    20.dp(context)
                )
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
                roundedBackground(
                    Color.rgb(7, 12, 24),
                    28f
                )
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

        private fun mapTvTypes(typesArray: JSONArray?): String {
            if (typesArray == null || typesArray.length() == 0) return "إضافة عامة"

            val map = mapOf(
                "TvSeries" to "مسلسلات",
                "Movie" to "أفلام",
                "Anime" to "أنمي",
                "AsianDrama" to "دراما آسيوية",
                "Live" to "بث مباشر",
                "Drama" to "دراما",
                "Music" to "موسيقى",
                "Documentary" to "وثائقي",
                "Others" to "منوعات"
            )

            val list = mutableListOf<String>()
            for (i in 0 until typesArray.length()) {
                val type = typesArray.optString(i)
                map[type]?.let {
                    if (!list.contains(it)) list.add(it)
                }
            }

            return if (list.isNotEmpty()) list.joinToString(" • ") else "إضافة عامة"
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

                                val description = when {
                                    obj.optString("description").isNotBlank() -> obj.optString("description")
                                    obj.has("tvTypes") -> mapTvTypes(obj.optJSONArray("tvTypes"))
                                    obj.optString("type").isNotBlank() -> obj.optString("type")
                                    else -> "إضافة عامة"
                                }

                                val likesDeferred = async { getCount(url, "like") }
                                val dislikesDeferred = async { getCount(url, "dislike") }

                                PluginRating(
                                    name = name,
                                    url = url,
                                    icon = icon,
                                    description = description,
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
        val description: String,
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
            val description: TextView,
            val votes: TextView,
            val dislike: TextView,
            val heart: TextView
        ) : RecyclerView.ViewHolder(card)

        override fun onCreateViewHolder(
            parent: ViewGroup,
            viewType: Int
        ): ViewHolder {
            val ctx = parent.context

            val card = FrameLayout(ctx).apply {
                layoutDirection = View.LAYOUT_DIRECTION_LTR

                background = roundedBackground(
                    Color.rgb(15, 22, 38),
                    23f
                )

                layoutParams = RecyclerView.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    105.dp(ctx)
                ).apply {
                    setMargins(0, 5.dp(ctx), 0, 5.dp(ctx))
                }
            }

            val icon = ImageView(ctx).apply {
                scaleType = ImageView.ScaleType.CENTER_CROP
                background = roundedBackground(
                    Color.rgb(29, 36, 55),
                    17f
                )

                layoutParams = FrameLayout.LayoutParams(
                    76.dp(ctx),
                    76.dp(ctx)
                ).apply {
                    gravity = Gravity.START or Gravity.CENTER_VERTICAL
                    leftMargin = 12.dp(ctx)
                }
            }

            val name = TextView(ctx).apply {
                textSize = 17f
                setTextColor(Color.WHITE)
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
                gravity = Gravity.CENTER_VERTICAL
                layoutDirection = View.LAYOUT_DIRECTION_LTR

                layoutParams = FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    27.dp(ctx)
                ).apply {
                    leftMargin = 98.dp(ctx)
                    rightMargin = 118.dp(ctx)
                    topMargin = 11.dp(ctx)
                }
            }

            val description = TextView(ctx).apply {
                textSize = 12f
                setTextColor(Color.rgb(155, 163, 185))
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
                gravity = Gravity.CENTER_VERTICAL
                layoutDirection = View.LAYOUT_DIRECTION_RTL

                layoutParams = FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    22.dp(ctx)
                ).apply {
                    leftMargin = 98.dp(ctx)
                    rightMargin = 118.dp(ctx)
                    topMargin = 39.dp(ctx)
                }
            }

            val votes = TextView(ctx).apply {
                textSize = 13f
                setTextColor(Color.rgb(225, 228, 240))
                gravity = Gravity.CENTER_VERTICAL
                layoutDirection = View.LAYOUT_DIRECTION_LTR

                layoutParams = FrameLayout.LayoutParams(
                    170.dp(ctx),
                    22.dp(ctx)
                ).apply {
                    leftMargin = 98.dp(ctx)
                    topMargin = 66.dp(ctx)
                }
            }

            val dislike = TextView(ctx).apply {
                text = "👎"
                textSize = 18f
                gravity = Gravity.CENTER

                background = roundedBackground(
                    Color.rgb(28, 35, 55),
                    16f
                )

                layoutParams = FrameLayout.LayoutParams(
                    46.dp(ctx),
                    46.dp(ctx)
                ).apply {
                    gravity = Gravity.END or Gravity.CENTER_VERTICAL
                    rightMargin = 64.dp(ctx)
                }
            }

            val heart = TextView(ctx).apply {
                text = "♡"
                textSize = 28f
                gravity = Gravity.CENTER
                setTextColor(Color.rgb(205, 212, 235))

                background = roundedBackground(
                    Color.rgb(28, 35, 55),
                    16f
                )

                layoutParams = FrameLayout.LayoutParams(
                    46.dp(ctx),
                    46.dp(ctx)
                ).apply {
                    gravity = Gravity.END or Gravity.CENTER_VERTICAL
                    rightMargin = 12.dp(ctx)
                }
            }

            card.addView(icon)
            card.addView(name)
            card.addView(description)
            card.addView(votes)
            card.addView(dislike)
            card.addView(heart)

            return ViewHolder(
                card,
                icon,
                name,
                description,
                votes,
                dislike,
                heart
            )
        }

        override fun onBindViewHolder(
            holder: ViewHolder,
            position: Int
        ) {
            val item = items[position]

            holder.name.text = item.name
            holder.description.text = item.description
            holder.votes.text = "👍 ${item.likes}   •   👎 ${item.dislikes}"

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

            val prefs = holder.itemView.context.getSharedPreferences(
                PREFS,
                Context.MODE_PRIVATE
            )

            val baseKey = getBaseHash(item.url)
            val userVote = prefs.getString("user_voted_$baseKey", "none")

            updateButtonsState(holder.heart, holder.dislike, userVote)

            holder.heart.setOnClickListener {
                if (prefs.getString("user_voted_$baseKey", "none") != "none") {
                    Toast.makeText(
                        holder.itemView.context,
                        "لقد قمت بالتصويت مسبقاً",
                        Toast.LENGTH_SHORT
                    ).show()
                    return@setOnClickListener
                }

                holder.heart.isEnabled = false
                holder.dislike.isEnabled = false

                CoroutineScope(Dispatchers.IO).launch {
                    val success = RatingSettings.incrementVote(item.url, "like")

                    withContext(Dispatchers.Main) {
                        holder.heart.isEnabled = true
                        holder.dislike.isEnabled = true

                        if (success) {
                            prefs.edit().putString("user_voted_$baseKey", "like").apply()
                            item.likes += 1
                            holder.votes.text = "👍 ${item.likes}   •   👎 ${item.dislikes}"
                            updateButtonsState(holder.heart, holder.dislike, "like")
                        } else {
                            Toast.makeText(
                                holder.itemView.context,
                                "تعذر تسجيل الإعجاب",
                                Toast.LENGTH_SHORT
                            ).show()
                        }
                    }
                }
            }

            holder.dislike.setOnClickListener {
                if (prefs.getString("user_voted_$baseKey", "none") != "none") {
                    Toast.makeText(
                        holder.itemView.context,
                        "لقد قمت بالتصويت مسبقاً",
                        Toast.LENGTH_SHORT
                    ).show()
                    return@setOnClickListener
                }

                AlertDialog.Builder(holder.itemView.context)
                    .setTitle("تأكيد التصويت")
                    .setMessage("هل أنت متأكد أنك تريد تسجيل عدم الإعجاب بالإضافة؟")
                    .setPositiveButton("نعم") { _, _ ->
                        holder.heart.isEnabled = false
                        holder.dislike.isEnabled = false

                        CoroutineScope(Dispatchers.IO).launch {
                            val success = RatingSettings.incrementVote(item.url, "dislike")

                            withContext(Dispatchers.Main) {
                                holder.heart.isEnabled = true
                                holder.dislike.isEnabled = true

                                if (success) {
                                    prefs.edit().putString("user_voted_$baseKey", "dislike").apply()
                                    item.dislikes += 1
                                    holder.votes.text = "👍 ${item.likes}   •   👎 ${item.dislikes}"
                                    updateButtonsState(holder.heart, holder.dislike, "dislike")
                                } else {
                                    Toast.makeText(
                                        holder.itemView.context,
                                        "تعذر تسجيل عدم الإعجاب",
                                        Toast.LENGTH_SHORT
                                    ).show()
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

    private fun updateButtonsState(
        heart: TextView,
        dislike: TextView,
        voteType: String?
    ) {
        when (voteType) {
            "like" -> {
                heart.text = "♥"
                heart.setTextColor(Color.rgb(255, 75, 105))
                heart.background = roundedBackground(Color.rgb(55, 28, 45), 16f)

                dislike.text = "👎"
                dislike.background = roundedBackground(Color.rgb(28, 35, 55), 16f)
            }
            "dislike" -> {
                heart.text = "♡"
                heart.setTextColor(Color.rgb(205, 212, 235))
                heart.background = roundedBackground(Color.rgb(28, 35, 55), 16f)

                dislike.text = "💔"
                dislike.background = roundedBackground(Color.rgb(55, 28, 45), 16f)
            }
            else -> {
                heart.text = "♡"
                heart.setTextColor(Color.rgb(205, 212, 235))
                heart.background = roundedBackground(Color.rgb(28, 35, 55), 16f)

                dislike.text = "👎"
                dislike.background = roundedBackground(Color.rgb(28, 35, 55), 16f)
            }
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

    private fun Int.dp(context: Context?): Int {
        val safeContext = context ?: return this
        return (this * safeContext.resources.displayMetrics.density).toInt()
    }
}
