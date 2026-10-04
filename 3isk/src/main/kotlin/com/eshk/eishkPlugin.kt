package com.eshk

import android.content.Context
import android.content.SharedPreferences
import androidx.appcompat.app.AppCompatActivity
import androidx.preference.*
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

class RatingPlugin : Plugin() {

    override fun load(context: Context) {

        val sharedPref = context.getSharedPreferences(
            "ExtensionRating",
            Context.MODE_PRIVATE
        )

        registerMainAPI(eishk(sharedPref))

        openSettings = { ctx ->

            val activity = ctx as? AppCompatActivity

            if (activity != null) {
                RatingSettings.show(
                    activity.supportFragmentManager,
                    sharedPref
                )
            }
        }
    }
}
