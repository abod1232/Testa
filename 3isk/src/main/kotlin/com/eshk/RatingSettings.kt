package com.eshk

import android.app.Dialog
import android.content.Context
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.fragment.app.DialogFragment
import androidx.fragment.app.FragmentManager
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.lagradost.cloudstream3.AcraApplication.Companion.context
import com.lagradost.cloudstream3.app
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest

object RatingSettings {

    private const val PLUGINS_URL =
        "https://raw.githubusercontent.com/Abodabodd/re-3arabi/refs/heads/builds/plugins.json"

    private const val COUNTER_API =
        "https://counterapi.com/api"

    private const val PREFS =
        "extension_ratings"

    fun show(fragmentManager: FragmentManager) {
        SettingsDialog().show(
            fragmentManager,
            "rating_settings"
        )
    }

    class SettingsDialog : DialogFragment() {

        override fun onCreateDialog(
            savedInstanceState: Bundle?
        ): Dialog {

            val dialog = Dialog(requireContext())

            val root = LinearLayout(requireContext()).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(
                    35,
                    35,
                    35,
                    35
                )
            }

            val title = TextView(requireContext()).apply {
                text = "⚙️ إعدادات"
                textSize = 23f
                setTextColor(Color.WHITE)

                setPadding(
                    0,
                    0,
                    0,
                    30
                )
            }

            val rating = TextView(requireContext()).apply {
                text = "⭐  التقييمات"
                textSize = 18f
                setTextColor(Color.WHITE)

                gravity = Gravity.CENTER_VERTICAL

                setPadding(
                    25,
                    30,
                    25,
                    30
                )

                background =
                    roundedBackground(
                        Color.rgb(25, 31, 48),
                        20f
                    )

                setOnClickListener {

                    RatingListDialog().show(
                        parentFragmentManager,
                        "rating_list"
                    )
                }
            }

            root.addView(title)
            root.addView(rating)

            dialog.setContentView(root)

            dialog.window?.setBackgroundDrawable(
                roundedBackground(
                    Color.rgb(10, 15, 28),
                    28f
                )
            )

            return dialog
        }
    }

    class RatingListDialog : DialogFragment() {

        override fun onCreateDialog(
            savedInstanceState: Bundle?
        ): Dialog {

            val dialog = Dialog(requireContext())

            val root = LinearLayout(requireContext()).apply {
                orientation = LinearLayout.VERTICAL

                setPadding(
                    18,
                    20,
                    18,
                    15
                )

                setBackgroundColor(
                    Color.rgb(8, 13, 25)
                )
            }
            val header = LinearLayout(requireContext()).apply {

                orientation =
                    LinearLayout.HORIZONTAL

                gravity =
                    Gravity.CENTER_VERTICAL

                setPadding(
                    10,
                    5,
                    10,
                    15
                )
            }

            val title = TextView(requireContext()).apply {
                text = "⭐ تقييمات الإضافات"
                textSize = 23f
                setTextColor(Color.WHITE)

                layoutParams =
                    LinearLayout.LayoutParams(
                        0,
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                        1f
                    )
            }

            val close = TextView(requireContext()).apply {
                text = "✕"
                textSize = 25f
                setTextColor(
                    Color.rgb(
                        180,
                        185,
                        205
                    )
                )

                setPadding(
                    15,
                    5,
                    10,
                    5
                )

                setOnClickListener {
                    dismiss()
                }
            }

            header.addView(title)
            header.addView(close)

            root.addView(header)
            val progress =
                ProgressBar(requireContext()).apply {
                    visibility = View.VISIBLE
                }

            root.addView(
                progress,
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    4
                )
            )
            val recycler =
                RecyclerView(requireContext()).apply {

                    layoutManager =
                        LinearLayoutManager(
                            requireContext()
                        )

                    overScrollMode =
                        View.OVER_SCROLL_NEVER
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
                    Color.rgb(8, 13, 25),
                    28f
                )
            )

            loadRatings(
                recycler,
                progress
            )

            return dialog
        }

        private fun loadRatings(
            recycler: RecyclerView,
            progress: ProgressBar
        ) {

            CoroutineScope(Dispatchers.IO).launch {

                try {

                    val response =
                        app.get(PLUGINS_URL)

                    val array =
                        JSONArray(response.text)

                    val plugins =
                        mutableListOf<PluginRating>()

                    for (
                        i in 0 until array.length()
                    ) {

                        val obj =
                            array.getJSONObject(i)

                        val name =
                            obj.optString("name")

                        val url =
                            obj.optString("url")
                        val icon =
                            when {
                                obj.optString("iconUrl")
                                    .isNotBlank() ->
                                    obj.optString("iconUrl")

                                obj.optString("icon")
                                    .isNotBlank() ->
                                    obj.optString("icon")

                                else -> ""
                            }
                        val description =
                            when {
                                obj.optString("description")
                                    .isNotBlank() ->
                                    obj.optString("description")

                                obj.optString("type")
                                    .isNotBlank() ->
                                    obj.optString("type")

                                else ->
                                    "إضافة Cloudstream"
                            }

                        if (
                            name.isBlank() ||
                            url.isBlank()
                        ) {
                            continue
                        }

                        val votes =
                            getVotes(url)

                        plugins.add(
                            PluginRating(
                                name = name,
                                url = url,
                                icon = icon,
                                description = description,
                                votes = votes
                            )
                        )
                    }

                    val sorted =
                        plugins.sortedByDescending {
                            it.votes
                        }

                    withContext(Dispatchers.Main) {

                        progress.visibility =
                            View.GONE

                        recycler.adapter =
                            RatingAdapter(sorted)
                    }

                } catch (e: Exception) {

                    withContext(Dispatchers.Main) {

                        progress.visibility =
                            View.GONE

                        Toast.makeText(
                            requireContext(),
                            "حدث خطأ أثناء تحميل التقييمات",
                            Toast.LENGTH_LONG
                        ).show()
                    }
                }
            }
        }

        private suspend fun getVotes(
            pluginUrl: String
        ): Int {

            return try {

                val repository =
                    getRepository(pluginUrl)

                val key =
                    transformUrl(pluginUrl)

                val url =
                    "$COUNTER_API/cs-$repository/vote/$key?readOnly=true"

                val response =
                    app.get(url)

                JSONObject(
                    response.text
                ).optInt(
                    "value",
                    0
                )

            } catch (
                e: Exception
            ) {

                0
            }
        }
    }

    data class PluginRating(
        val name: String,
        val url: String,
        val icon: String,
        val description: String,
        val votes: Int
    )

    class RatingAdapter(
        private val items: List<PluginRating>
    ) : RecyclerView.Adapter<RatingAdapter.ViewHolder>() {

        class ViewHolder(
            val card: LinearLayout,
            val icon: ImageView,
            val name: TextView,
            val description: TextView,
            val votes: TextView,
            val rank: TextView,
            val heart: TextView
        ) : RecyclerView.ViewHolder(card)

        override fun onCreateViewHolder(
            parent: ViewGroup,
            viewType: Int
        ): ViewHolder {

            val ctx =
                parent.context

            val card =
                LinearLayout(ctx).apply {

                    orientation =
                        LinearLayout.HORIZONTAL

                    gravity =
                        Gravity.CENTER_VERTICAL

                    setPadding(
                        14,
                        14,
                        14,
                        14
                    )

                    background =
                        roundedBackground(
                            Color.rgb(
                                15,
                                22,
                                38
                            ),
                            24f
                        )

                    layoutParams =
                        LinearLayout.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            125
                        ).apply {

                            setMargins(
                                0,
                                6,
                                0,
                                6
                            )
                        }
                }

            val icon =
                ImageView(ctx).apply {

                    scaleType =
                        ImageView.ScaleType.CENTER_CROP

                    background =
                        roundedBackground(
                            Color.rgb(
                                25,
                                30,
                                45
                            ),
                            18f
                        )

                    layoutParams =
                        LinearLayout.LayoutParams(
                            78,
                            78
                        ).apply {

                            marginEnd = 15
                        }
                }

            val info =
                LinearLayout(ctx).apply {

                    orientation =
                        LinearLayout.VERTICAL

                    gravity =
                        Gravity.CENTER_VERTICAL

                    layoutParams =
                        LinearLayout.LayoutParams(
                            0,
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            1f
                        )
                }

            val name =
                TextView(ctx).apply {

                    textSize = 17f

                    setTextColor(
                        Color.WHITE
                    )

                    maxLines = 1

                    ellipsize =
                        android.text.TextUtils.TruncateAt.END
                }

            val description =
                TextView(ctx).apply {

                    textSize = 13f

                    setTextColor(
                        Color.rgb(
                            165,
                            171,
                            190
                        )
                    )

                    maxLines = 1

                    ellipsize =
                        android.text.TextUtils.TruncateAt.END

                    setPadding(
                        0,
                        4,
                        0,
                        2
                    )
                }

            val votes =
                TextView(ctx).apply {

                    textSize = 14f

                    setTextColor(
                        Color.WHITE
                    )
                }

            info.addView(name)
            info.addView(description)
            info.addView(votes)

            val rank =
                TextView(ctx).apply {

                    textSize = 19f

                    setTextColor(
                        Color.WHITE
                    )

                    gravity =
                        Gravity.CENTER

                    layoutParams =
                        LinearLayout.LayoutParams(
                            55,
                            ViewGroup.LayoutParams.MATCH_PARENT
                        )

                    setPadding(
                        8,
                        0,
                        8,
                        0
                    )
                }

            val heart =
                TextView(ctx).apply {

                    text = "♡"

                    textSize = 35f

                    gravity =
                        Gravity.CENTER

                    setTextColor(
                        Color.rgb(
                            210,
                            215,
                            235
                        )
                    )

                    background =
                        roundedBackground(
                            Color.rgb(
                                28,
                                35,
                                55
                            ),
                            20f
                        )

                    layoutParams =
                        LinearLayout.LayoutParams(
                            62,
                            62
                        ).apply {

                            marginStart = 10
                        }
                }

            card.addView(icon)
            card.addView(info)
            card.addView(rank)
            card.addView(heart)

            return ViewHolder(
                card,
                icon,
                name,
                description,
                votes,
                rank,
                heart
            )
        }

        override fun onBindViewHolder(
            holder: ViewHolder,
            position: Int
        ) {

            val item =
                items[position]

            val rank =
                position + 1

            holder.name.text =
                item.name

            holder.description.text =
                item.description

            holder.votes.text =
                "⭐  ${item.votes} صوت"

            holder.rank.text =
                when (rank) {

                    1 -> "1"

                    2 -> "2"

                    3 -> "3"

                    else -> rank.toString()
                }
            if (item.icon.isNotBlank()) {

                CoroutineScope(
                    Dispatchers.IO
                ).launch {

                    try {

                        val response =
                            app.get(
                                item.icon
                            )

                        val bitmap =
                            BitmapFactory.decodeByteArray(
                                response.bytes,
                                0,
                                response.bytes.size
                            )

                        withContext(
                            Dispatchers.Main
                        ) {

                            if (
                                bitmap != null &&
                                holder.bindingAdapterPosition ==
                                    position
                            ) {

                                holder.icon.setImageBitmap(
                                    bitmap
                                )
                            }
                        }

                    } catch (
                        e: Exception
                    ) {
                    }
                }
            }
            val prefs =
                context.getSharedPreferences(
                    PREFS,
                    Context.MODE_PRIVATE
                )

            val key =
                transformUrl(item.url)

            val hasVoted =
                prefs.getBoolean(
                    "voted_$key",
                    false
                )

            updateHeart(
                holder.heart,
                hasVoted
            )

            holder.heart.setOnClickListener {

                val alreadyVoted =
                    prefs.getBoolean(
                        "voted_$key",
                        false
                    )

                if (alreadyVoted) {

                    Toast.makeText(
                        holder.itemView.context,
                        "لقد صوتت لهذه الإضافة مسبقًا",
                        Toast.LENGTH_SHORT
                    ).show()

                    return@setOnClickListener
                }

                holder.heart.isEnabled =
                    false

                CoroutineScope(
                    Dispatchers.IO
                ).launch {

                    val success =
                        vote(item.url)

                    withContext(
                        Dispatchers.Main
                    ) {

                        holder.heart.isEnabled =
                            true

                        if (success) {

                            prefs.edit()
                                .putBoolean(
                                    "voted_$key",
                                    true
                                )
                                .apply()

                            item.copy(
                                votes = item.votes + 1
                            )

                            holder.votes.text =
                                "⭐  ${item.votes + 1} صوت"

                            updateHeart(
                                holder.heart,
                                true
                            )

                        } else {

                            Toast.makeText(
                                holder.itemView.context,
                                "تعذر تسجيل التصويت",
                                Toast.LENGTH_SHORT
                            ).show()
                        }
                    }
                }
            }
        }

        override fun getItemCount(): Int =
            items.size
    }

    private suspend fun vote(
        pluginUrl: String
    ): Boolean {

        return try {

            val repository =
                getRepository(pluginUrl)

            val key =
                transformUrl(pluginUrl)

            val url =
                "$COUNTER_API/cs-$repository/vote/$key"

            val response =
                app.get(url)

            JSONObject(
                response.text
            ).has("value")

        } catch (
            e: Exception
        ) {

            false
        }
    }

    private fun updateHeart(
        heart: TextView,
        voted: Boolean
    ) {

        if (voted) {

            heart.text = "♥"

            heart.setTextColor(
                Color.rgb(
                    255,
                    75,
                    105
                )
            )

            heart.background =
                roundedBackground(
                    Color.rgb(
                        55,
                        28,
                        45
                    ),
                    20f
                )

        } else {

            heart.text = "♡"

            heart.setTextColor(
                Color.rgb(
                    210,
                    215,
                    235
                )
            )

            heart.background =
                roundedBackground(
                    Color.rgb(
                        28,
                        35,
                        55
                    ),
                    20f
                )
        }
    }

    private fun getRepository(
        pluginUrl: String
    ): String {

        return pluginUrl
            .split("/")
            .drop(2)
            .take(3)
            .joinToString("-")
    }

    private fun transformUrl(
        url: String
    ): String {

        return MessageDigest
            .getInstance("SHA-256")
            .digest(
                "$url#funny-salt"
                    .toByteArray()
            )
            .joinToString("") {
                "%02x".format(it)
            }
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
}
