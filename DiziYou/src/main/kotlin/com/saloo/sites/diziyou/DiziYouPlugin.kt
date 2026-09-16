package com.saloo.sites.diziyou

import android.content.Context
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class DiziYouPlugin : Plugin() {
    override fun load(context: Context) {
        registerMainAPI(DiziYou())
    }
}
