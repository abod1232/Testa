package com.eshk

import android.app.Dialog
import android.content.Context
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
                    32.dp(context),
                    32.dp(context),
                    32.dp(context),
                    32.dp(context)
                )

                setBackgroundColor(
                    Color.rgb(8, 13, 25)
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
                    25.dp(context)
                )
            }

            val ratings = TextView(requireContext()).apply {
                text = "⭐  التقييمات"
                textSize = 18f
                setTextColor(Color.WHITE)

                gravity =
                    Gravity.CENTER_VERTICAL

                setPadding(
                    20.dp(context),
                    22.dp(context),
                    20.dp(context),
                    22.dp(context)
                )

                background =
                    roundedBackground(
                        Color.rgb(20, 28, 46),
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

            root.addView(
                ratings,
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                )
            )

            dialog.setContentView(root)

            dialog.window?.setBackgroundDrawable(
                roundedBackground(
                    Color.rgb(8, 13, 25),
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

            val dialog =
                Dialog(requireContext())

            val root =
                LinearLayout(requireContext()).apply {

                    orientation =
                        LinearLayout.VERTICAL

                    setPadding(
                        14.dp(context),
                        15.dp(context),
                        14.dp(context),
                        10.dp(context)
                    )

                    setBackgroundColor(
                        Color.rgb(
                            7,
                            12,
                            24
                        )
                    )
                }

            val header =
                FrameLayout(requireContext()).apply {

                    layoutParams =
                        LinearLayout.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            65.dp(context)
                        )
                }

            val title =
                TextView(requireContext()).apply {

                    text =
                        "⭐ تقييمات الإضافات"

                    textSize =
                        23f

                    setTextColor(
                        Color.WHITE
                    )

                    gravity =
                        Gravity.CENTER

                    layoutParams =
                        FrameLayout.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            ViewGroup.LayoutParams.MATCH_PARENT
                        )
                }

            val close =
                TextView(requireContext()).apply {

                    text =
                        "×"

                    textSize =
                        42f

                    setTextColor(
                        Color.rgb(
                            190,
                            198,
                            220
                        )
                    )

                    gravity =
                        Gravity.CENTER

                    layoutParams =
                        FrameLayout.LayoutParams(
                            55.dp(context),
                            55.dp(context)
                        ).apply {

                            gravity =
                                Gravity.START or
                                    Gravity.CENTER_VERTICAL
                        }

                    setOnClickListener {
                        dismiss()
                    }
                }

            header.addView(title)
            header.addView(close)

            root.addView(header)

            val progress =
                ProgressBar(
                    requireContext(),
                    null,
                    android.R.attr.progressBarStyleHorizontal
                ).apply {

                    isIndeterminate =
                        true

                    visibility =
                        View.VISIBLE
                }

            root.addView(
                progress,
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    3.dp(context)
                )
            )

            val recycler =
                RecyclerView(
                    requireContext()
                ).apply {

                    layoutManager =
                        LinearLayoutManager(
                            requireContext()
                        )

                    overScrollMode =
                        View.OVER_SCROLL_NEVER

                    clipToPadding =
                        false

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
                    Color.rgb(
                        7,
                        12,
                        24
                    ),
                    28f
                )
            )

            dialog.setOnShowListener {

                dialog.window?.setLayout(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT
                )
            }

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

            CoroutineScope(
                Dispatchers.IO
            ).launch {

                try {

                    val response =
                        app.get(
                            PLUGINS_URL
                        )

                    val array =
                        JSONArray(
                            response.text
                        )

                    val plugins =
                        mutableListOf<PluginRating>()

                    for (
                        i in 0 until array.length()
                    ) {

                        val obj =
                            array.getJSONObject(i)

                        val name =
                            obj.optString(
                                "name"
                            )

                        val url =
                            obj.optString(
                                "url"
                            )

                        val icon =
                            when {

                                obj.optString(
                                    "iconUrl"
                                ).isNotBlank() ->
                                    obj.optString(
                                        "iconUrl"
                                    )

                                obj.optString(
                                    "icon"
                                ).isNotBlank() ->
                                    obj.optString(
                                        "icon"
                                    )

                                else ->
                                    ""
                            }

                        val description =
                            when {

                                obj.optString(
                                    "description"
                                ).isNotBlank() ->
                                    obj.optString(
                                        "description"
                                    )

                                obj.optString(
                                    "type"
                                ).isNotBlank() ->
                                    obj.optString(
                                        "type"
                                    )

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
                            getVotes(
                                url
                            )

                        plugins.add(
                            PluginRating(
                                name =
                                    name,
                                url =
                                    url,
                                icon =
                                    icon,
                                description =
                                    description,
                                votes =
                                    votes
                            )
                        )
                    }

                    val sorted =
                        plugins.sortedByDescending {
                            it.votes
                        }

                    withContext(
                        Dispatchers.Main
                    ) {

                        progress.visibility =
                            View.GONE

                        recycler.adapter =
                            RatingAdapter(
                                sorted
                            )
                    }

                } catch (
                    e: Exception
                ) {

                    withContext(
                        Dispatchers.Main
                    ) {

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
                    getRepository(
                        pluginUrl
                    )

                val key =
                    transformUrl(
                        pluginUrl
                    )

                val url =
                    "$COUNTER_API/cs-$repository/vote/$key?readOnly=true"

                val response =
                    app.get(
                        url
                    )

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
            val card: FrameLayout,
            val icon: ImageView,
            val name: TextView,
            val description: TextView,
            val votes: TextView,
            val rank: TextView,
            val heart: TextView
        ) : RecyclerView.ViewHolder(
            card
        )

        override fun onCreateViewHolder(
            parent: ViewGroup,
            viewType: Int
        ): ViewHolder {

            val ctx =
                parent.context

            val card =
                FrameLayout(ctx).apply {

                    /*
                     * مهم جدًا:
                     * نثبت اتجاه الكرت LTR
                     * حتى لا تتخربط العناصر بسبب العربية.
                     */
                    layoutDirection =
                        View.LAYOUT_DIRECTION_LTR

                    background =
                        roundedBackground(
                            Color.rgb(
                                15,
                                22,
                                38
                            ),
                            23f
                        )

                    layoutParams =
                        RecyclerView.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            105.dp(ctx)
                        ).apply {

                            setMargins(
                                0,
                                5.dp(ctx),
                                0,
                                5.dp(ctx)
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
                                29,
                                36,
                                55
                            ),
                            17f
                        )

                    layoutParams =
                        FrameLayout.LayoutParams(
                            76.dp(ctx),
                            76.dp(ctx)
                        ).apply {

                            gravity =
                                Gravity.START or
                                    Gravity.CENTER_VERTICAL

                            leftMargin =
                                12.dp(ctx)
                        }
                }

            val name =
                TextView(ctx).apply {

                    textSize =
                        17f

                    setTextColor(
                        Color.WHITE
                    )

                    maxLines =
                        1

                    ellipsize =
                        TextUtils.TruncateAt.END

                    gravity =
                        Gravity.CENTER_VERTICAL

                    layoutDirection =
                        View.LAYOUT_DIRECTION_LTR

                    layoutParams =
                        FrameLayout.LayoutParams(
                            0,
                            27.dp(ctx)
                        ).apply {

                            leftMargin =
                                101.dp(ctx)

                            rightMargin =
                                185.dp(ctx)

                            topMargin =
                                11.dp(ctx)
                        }
                }

            val description =
                TextView(ctx).apply {

                    textSize =
                        12.5f

                    setTextColor(
                        Color.rgb(
                            155,
                            163,
                            185
                        )
                    )

                    maxLines =
                        1

                    ellipsize =
                        TextUtils.TruncateAt.END

                    gravity =
                        Gravity.CENTER_VERTICAL

                    layoutDirection =
                        View.LAYOUT_DIRECTION_LTR

                    layoutParams =
                        FrameLayout.LayoutParams(
                            0,
                            22.dp(ctx)
                        ).apply {

                            leftMargin =
                                101.dp(ctx)

                            rightMargin =
                                185.dp(ctx)

                            topMargin =
                                39.dp(ctx)
                        }
                }

            val votes =
                TextView(ctx).apply {

                    textSize =
                        13f

                    setTextColor(
                        Color.rgb(
                            225,
                            228,
                            240
                        )
                    )

                    gravity =
                        Gravity.CENTER_VERTICAL

                    layoutDirection =
                        View.LAYOUT_DIRECTION_LTR

                    layoutParams =
                        FrameLayout.LayoutParams(
                            150.dp(ctx),
                            22.dp(ctx)
                        ).apply {

                            leftMargin =
                                101.dp(ctx)

                            topMargin =
                                66.dp(ctx)
                        }
                }

            val rankLine =
                View(ctx).apply {

                    setBackgroundColor(
                        Color.rgb(
                            40,
                            48,
                            70
                        )
                    )

                    layoutParams =
                        FrameLayout.LayoutParams(
                            1.dp(ctx),
                            65.dp(ctx)
                        ).apply {

                            gravity =
                                Gravity.END or
                                    Gravity.CENTER_VERTICAL

                            rightMargin =
                                140.dp(ctx)
                        }
                }

            val rank =
                TextView(ctx).apply {

                    textSize =
                        18f

                    setTextColor(
                        Color.WHITE
                    )

                    gravity =
                        Gravity.CENTER

                    layoutDirection =
                        View.LAYOUT_DIRECTION_LTR

                    layoutParams =
                        FrameLayout.LayoutParams(
                            60.dp(ctx),
                            55.dp(ctx)
                        ).apply {

                            gravity =
                                Gravity.END or
                                    Gravity.CENTER_VERTICAL

                            rightMargin =
                                82.dp(ctx)
                        }
                }

            val heartLine =
                View(ctx).apply {

                    setBackgroundColor(
                        Color.rgb(
                            40,
                            48,
                            70
                        )
                    )

                    layoutParams =
                        FrameLayout.LayoutParams(
                            1.dp(ctx),
                            65.dp(ctx)
                        ).apply {

                            gravity =
                                Gravity.END or
                                    Gravity.CENTER_VERTICAL

                            rightMargin =
                                74.dp(ctx)
                        }
                }

            val heart =
                TextView(ctx).apply {

                    text =
                        "♡"

                    textSize =
                        34f

                    gravity =
                        Gravity.CENTER

                    setTextColor(
                        Color.rgb(
                            205,
                            212,
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
                            18f
                        )

                    layoutParams =
                        FrameLayout.LayoutParams(
                            60.dp(ctx),
                            60.dp(ctx)
                        ).apply {

                            gravity =
                                Gravity.END or
                                    Gravity.CENTER_VERTICAL

                            rightMargin =
                                10.dp(ctx)
                        }
                }

            card.addView(
                icon
            )

            card.addView(
                name
            )

            card.addView(
                description
            )

            card.addView(
                votes
            )

            card.addView(
                rankLine
            )

            card.addView(
                rank
            )

            card.addView(
                heartLine
            )

            card.addView(
                heart
            )

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
                rank.toString()

            if (
                item.icon.isNotBlank()
            ) {

                CoroutineScope(
                    Dispatchers.IO
                ).launch {

                    try {

                        val response =
                            app.get(
                                item.icon
                            )

                        val body =
                            response.body

                        val bytes =
                            body.bytes()

                        body.close()

                        val bitmap =
                            BitmapFactory.decodeByteArray(
                                bytes,
                                0,
                                bytes.size
                            )

                        withContext(
                            Dispatchers.Main
                        ) {

                            if (
                                bitmap != null &&
                                holder.bindingAdapterPosition ==
                                    position
                            ) {

                                holder.icon
                                    .setImageBitmap(
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
                holder.itemView.context
                    .getSharedPreferences(
                        PREFS,
                        Context.MODE_PRIVATE
                    )

            val key =
                RatingSettings.transformUrl(
                    item.url
                )

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

                if (
                    alreadyVoted
                ) {

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
                        RatingSettings.vote(
                            item.url
                        )

                    withContext(
                        Dispatchers.Main
                    ) {

                        holder.heart.isEnabled =
                            true

                        if (
                            success
                        ) {

                            prefs.edit()
                                .putBoolean(
                                    "voted_$key",
                                    true
                                )
                                .apply()

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
                getRepository(
                    pluginUrl
                )

            val key =
                transformUrl(
                    pluginUrl
                )

            val url =
                "$COUNTER_API/cs-$repository/vote/$key"

            val response =
                app.get(
                    url
                )

            JSONObject(
                response.text
            ).has(
                "value"
            )

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

            heart.text =
                "♥"

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
                    18f
                )

        } else {

            heart.text =
                "♡"

            heart.setTextColor(
                Color.rgb(
                    205,
                    212,
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
                    18f
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
            .getInstance(
                "SHA-256"
            )
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

            setColor(
                color
            )

            cornerRadius =
                radius
        }
    }

    private fun Int.dp(
    context: Context?
): Int {

    val safeContext =
        context ?: return this

    return (
        this *
            safeContext.resources
                .displayMetrics
                .density
        ).toInt()
}
}
