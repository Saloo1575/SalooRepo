package com.saloo.sites.anizm

import android.content.Context
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class AnizmPlugin : Plugin() {
    override fun load(context: Context) {
        registerMainAPI(Anizm())
    }
}
