package com.animewitcher

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
        // تم تصحيح الاستدعاء بحذف context
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
            badgeIcon: String,
            badgeColor: String,
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

            val iconBadge = TextView(ctx).apply {
                text = badgeIcon
                textSize = 18f
                gravity = Gravity.CENTER
                background = GradientDrawable().apply {
                    setColor(Color.parseColor(badgeColor))
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

        root.addView(
            createPlatformCard(
                badgeIcon = "📧",
                badgeColor = "#229ED9",
                titleText = "قناة التليجرام",
                descText = "أحدث الإصدارات، الروابط والأخبار",
                targetUrl = "https://t.me/cs3iq"
            )
        )

        root.addView(
            createPlatformCard(
                badgeIcon = "💬",
                badgeColor = "#5865F2",
                titleText = "سيرفر الديسكورد",
                descText = "نقاشات المجتمع، الدعم الفني والاقتراحات",
                targetUrl = "https://discord.gg/xY63qaQRw"
            )
        )

        root.addView(
            createPlatformCard(
                badgeIcon = "🔗",
                badgeColor = "#313244",
                titleText = "مشروع GitHub",
                descText = "المستودع المفتوح، الإبلاغ عن مشاكل والمساهمة",
                targetUrl = "https://github.com/Abodabodd/re-3arabi"
            )
        )

        return root
    }
}
