package com.saloo.sites.blenderstudio

import android.content.Context
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class BlenderStudioPlugin : Plugin() {
    override fun load(context: Context) {
        registerMainAPI(BlenderStudioProvider())
    }
}
