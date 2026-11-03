package com.eshk

import android.content.Context
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class AnimeRiftt : Plugin() {
    override fun load(context: Context) {
        registerMainAPI(AnimeRift(context))
    }
}
