package com.eshk

import android.app.Dialog
import android.content.Context
import android.os.Bundle
import android.view.*
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.fragment.app.DialogFragment
import androidx.fragment.app.FragmentManager
import androidx.preference.Preference
import androidx.preference.PreferenceFragmentCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.lagradost.cloudstream3.AcraApplication.Companion.app
import com.lagradost.cloudstream3.utils.AppUtils
import com.lagradost.cloudstream3.utils.UIHelper
import com.lagradost.cloudstream3.utils.getImage
import com.lagradost.cloudstream3.utils.loadImage
import com.lagradost.cloudstream3.utils.isTv
import com.lagradost.cloudstream3.utils.toast
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import java.security.MessageDigest

object RatingSettings {

    private const val PLUGINS_URL =
        "https://raw.githubusercontent.com/Abodabodd/re-3arabi/refs/heads/builds/plugins.json"

    private const val COUNTER_API =
        "https://counterapi.com/api"

    /**
     * فتح إعدادات الإضافة
     */
    fun show(
        fragmentManager: FragmentManager
    ) {
        SettingsBottomSheet().show(
            fragmentManager,
            "rating_settings"
        )
    }

    /**
     * إعدادات الإضافة
     */
    class SettingsBottomSheet : androidx.fragment.app.DialogFragment() {

        override fun onCreateDialog(
            savedInstanceState: Bundle?
        ): Dialog {

            val dialog = Dialog(requireContext())

            val view = LinearLayout(requireContext()).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(32, 32, 32, 32)
            }

            val title = TextView(requireContext()).apply {
                text = "⚙️ إعدادات"
                textSize = 22f
                setPadding(0, 0, 0, 32)
            }

            val ratings = TextView(requireContext()).apply {
                text = "⭐ التقييمات"
                textSize = 18f
                setPadding(16, 24, 16, 24)

                setOnClickListener {
                    RatingListDialog()
                        .show(
                            parentFragmentManager,
                            "rating_list"
                        )
                }
            }

            view.addView(title)
            view.addView(ratings)

            dialog.setContentView(view)

            return dialog
        }
    }

    /**
     * صفحة التقييمات
     */
    class RatingListDialog : DialogFragment() {

        override fun onCreateDialog(
            savedInstanceState: Bundle?
        ): Dialog {

            val dialog = Dialog(requireContext())

            val root = LinearLayout(requireContext()).apply {
                orientation = LinearLayout.VERTICAL
            }

            val title = TextView(requireContext()).apply {
                text = "⭐ تقييمات الإضافات"
                textSize = 22f
                setPadding(32, 32, 32, 24)
            }

            val progress = ProgressBar(requireContext()).apply {
                visibility = View.VISIBLE
            }

            val recyclerView = RecyclerView(requireContext()).apply {
                layoutManager =
                    LinearLayoutManager(requireContext())
            }

            root.addView(title)
            root.addView(progress)

            root.addView(
                recyclerView,
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    0,
                    1f
                )
            )

            dialog.setContentView(root)

            loadRatings(
                recyclerView,
                progress
            )

            return dialog
        }

        private fun loadRatings(
            recyclerView: RecyclerView,
            progress: ProgressBar
        ) {

            CoroutineScope(Dispatchers.IO).launch {

                try {

                    val json =
                        app.get(PLUGINS_URL)
                            .text

                    val array =
                        JSONArray(json)

                    val plugins =
                        mutableListOf<PluginRating>()

                    for (i in 0 until array.length()) {

                        val obj =
                            array.getJSONObject(i)

                        val name =
                            obj.optString("name")

                        val url =
                            obj.optString("url")

                        val icon =
                            obj.optString("icon")

                        if (name.isBlank() || url.isBlank())
                            continue

                        val votes =
                            getVotes(url)

                        plugins.add(
                            PluginRating(
                                name = name,
                                url = url,
                                icon = icon,
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

                        recyclerView.adapter =
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

                val json =
                    response.text

                val value =
                    org.json.JSONObject(json)
                        .optInt("value", 0)

                value

            } catch (e: Exception) {

                0
            }
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

        private fun getRepository(
            pluginUrl: String
        ): String {

            return pluginUrl
                .split("/")
                .drop(2)
                .take(3)
                .joinToString("-")
        }
    }

    /**
     * بيانات الإضافة
     */
    data class PluginRating(
        val name: String,
        val url: String,
        val icon: String,
        val votes: Int
    )

    /**
     * Adapter
     */
    class RatingAdapter(
        private val items: List<PluginRating>
    ) : RecyclerView.Adapter<RatingAdapter.ViewHolder>() {

        class ViewHolder(
            val layout: LinearLayout,
            val rank: TextView,
            val name: TextView,
            val votes: TextView
        ) : RecyclerView.ViewHolder(layout)

        override fun onCreateViewHolder(
            parent: ViewGroup,
            viewType: Int
        ): ViewHolder {

            val context = parent.context

            val layout =
                LinearLayout(context).apply {

                    orientation =
                        LinearLayout.HORIZONTAL

                    gravity =
                        Gravity.CENTER_VERTICAL

                    setPadding(
                        20,
                        20,
                        20,
                        20
                    )
                }

            val rank =
                TextView(context).apply {

                    textSize = 20f

                    gravity =
                        Gravity.CENTER

                    layoutParams =
                        LinearLayout.LayoutParams(
                            70,
                            LinearLayout.LayoutParams.WRAP_CONTENT
                        )
                }

            val info =
                LinearLayout(context).apply {

                    orientation =
                        LinearLayout.VERTICAL

                    layoutParams =
                        LinearLayout.LayoutParams(
                            0,
                            LinearLayout.LayoutParams.WRAP_CONTENT,
                            1f
                        )
                }

            val name =
                TextView(context).apply {

                    textSize = 17f
                }

            val votes =
                TextView(context).apply {

                    textSize = 14f
                }

            info.addView(name)
            info.addView(votes)

            layout.addView(rank)
            layout.addView(info)

            return ViewHolder(
                layout,
                rank,
                name,
                votes
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

            holder.rank.text =
                when (rank) {
                    1 -> "🥇"
                    2 -> "🥈"
                    3 -> "🥉"
                    else -> "$rank."
                }

            holder.name.text =
                item.name

            holder.votes.text =
                "⭐ ${item.votes} صوت"

            holder.layout.setOnClickListener {

                Toast.makeText(
                    holder.layout.context,
                    "${item.name}\n⭐ ${item.votes} صوت",
                    Toast.LENGTH_SHORT
                ).show()
            }
        }

        override fun getItemCount(): Int =
            items.size
    }
}
