package com.animewitcher

import android.content.Context
import android.net.Uri
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class animewitcherPlugin : Plugin() {
    override fun load(context: Context) {
        registerMainAPI(AnimeWitcherProvider(context))
        openSettings = { activityContext ->
            (activityContext as? AppCompatActivity)?.let { activity ->
                Settings().show(activity.supportFragmentManager, "AnimewitcherSettings")
            }
        }
    }
}
