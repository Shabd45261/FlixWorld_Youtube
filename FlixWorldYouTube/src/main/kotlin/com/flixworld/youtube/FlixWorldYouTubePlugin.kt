package com.flixworld.youtube

import com.lagradost.cloudstream3.plugins.BasePlugin
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin

/**
 * Flix World — YouTube extension.
 *
 * Registers ONE provider ("FlixWorld YouTube") that carries both the video
 * shelves and the Shorts feed.
 */
@CloudstreamPlugin
class FlixWorldYouTubePlugin : BasePlugin() {
    override fun load() {
        registerMainAPI(FlixWorldYouTube())
    }
}
