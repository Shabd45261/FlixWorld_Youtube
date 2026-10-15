package com.flixworld.youtube

import com.lagradost.cloudstream3.plugins.BasePlugin
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin

/**
 * Flix World — YouTube extension.
 *
 * Both the video provider and the Shorts provider ship in this ONE extension,
 * mirroring the Flix World app where the YouTube profile carries normal videos
 * and a Shorts feed.
 */
@CloudstreamPlugin
class FlixWorldYouTubePlugin : BasePlugin() {
    override fun load() {
        registerMainAPI(FlixYoutubeProvider())
        registerMainAPI(FlixYoutubeShortsProvider())
    }
}
