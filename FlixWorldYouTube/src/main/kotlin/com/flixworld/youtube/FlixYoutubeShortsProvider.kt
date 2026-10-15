package com.flixworld.youtube

import com.lagradost.cloudstream3.*
import org.schabi.newpipe.extractor.InfoItem
import org.schabi.newpipe.extractor.ServiceList
import org.schabi.newpipe.extractor.stream.StreamInfoItem

/**
 * Flix YouTube Shorts — the vertical Shorts feed.
 *
 * NewPipeExtractor has no dedicated Shorts tab, so Shorts are pulled from
 * `#shorts <topic>` searches and filtered by duration (a Short is <= 180s).
 * This mirrors TOPICS in the app's youtube_shorts.py.
 *
 * Home / search / load-links are inherited from [FlixYoutubeProvider], so a
 * Short plays through the exact same NewPipe extraction path as a normal video.
 */
class FlixYoutubeShortsProvider : FlixYoutubeProvider() {
    override var name = "Flix YouTube Shorts"
    override var lang = "en"
    override val supportedTypes = setOf(TvType.Others)

    override val mainPage = mainPageOf("shorts" to "Shorts")

    private val topics = listOf(
        "food", "comedy", "music", "sports", "animals", "travel",
        "cars", "gaming", "fitness", "science", "diy", "cute",
    )

    private val maxShortSeconds = 180L

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val service = ServiceList.YouTube
        val perPage = 4
        val start = ((page - 1) * perPage) % topics.size
        val seen = HashSet<String>()
        val out = ArrayList<SearchResponse>()

        for (i in 0 until perPage) {
            val topic = topics[(start + i) % topics.size]
            try {
                val ex = service.getSearchExtractor("#shorts $topic")
                ex.fetchPage()
                for (item in ex.initialPage.items) {
                    val sr = item.toShort() ?: continue
                    if (seen.add(sr.url)) out.add(sr)
                }
            } catch (_: Exception) {
            }
        }
        return newHomePageResponse(listOf(HomePageList("Shorts", out, false)), false)
    }

    override suspend fun search(query: String, page: Int): SearchResponseList {
        val q = query.trim()
        if (q.isBlank()) return newSearchResponseList(emptyList(), false)
        return try {
            val ex = ServiceList.YouTube.getSearchExtractor("$q #shorts")
            ex.fetchPage()
            newSearchResponseList(
                ex.initialPage.items.mapNotNull { it.toShort() },
                ex.initialPage.hasNextPage(),
            )
        } catch (_: Exception) {
            newSearchResponseList(emptyList(), false)
        }
    }

    private fun InfoItem.toShort(): SearchResponse? {
        if (this !is StreamInfoItem) return null
        val dur = duration
        if (dur <= 0 || dur > maxShortSeconds) return null
        val u = url ?: return null
        val t = name ?: return null
        return newMovieSearchResponse(t, u, TvType.Others) {
            this.posterUrl = thumbnails.lastOrNull()?.url
        }
    }
}
