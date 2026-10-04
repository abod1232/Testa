package com.eshk

import android.content.Context
import androidx.appcompat.app.AppCompatActivity
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class RatingPlugin : Plugin() {

    override fun load(context: Context) {

        registerMainAPI(
            eishk()
        )

        openSettings = { ctx ->

            val activity =
                ctx as? AppCompatActivity
                    ?: return@openSettings

            RatingSettings.show(
                activity.supportFragmentManager
            )
        }
    }
}
