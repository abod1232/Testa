package com.eshk

import android.content.Context
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class AnimeRift : Plugin() {
    override fun load(context: Context) {
        registerMainAPI(Anime3rb(context))
    }
}
