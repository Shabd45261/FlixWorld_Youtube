package com.flixworld.youtube

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.schabi.newpipe.extractor.InfoItem
import org.schabi.newpipe.extractor.Page
import org.schabi.newpipe.extractor.ServiceList
import org.schabi.newpipe.extractor.stream.StreamInfo
import org.schabi.newpipe.extractor.stream.StreamInfoItem

/**
 * Flix YouTube — videos, channels and playlists.
 *
 * Extraction is delegated to NewPipeExtractor (same library the official
 * CloudStream YouTube extension uses); CloudStream's own YouTube extractor
 * resolves the actual streams via loadExtractor().
 *
 * The Home tab rebuilds youtube_scrapers.py's HOME_SHELVES as rows, so the
 * layout matches the Flix World app.
 */
open class FlixYoutubeProvider : MainAPI() {
    override var mainUrl = "https://www.youtube.com"
    override var name = "Flix YouTube"
    override var lang = "en"
    override val hasMainPage = true
    override val supportedTypes = setOf(TvType.Others, TvType.Live, TvType.TvSeries)

    private val service = ServiceList.YouTube

    private data class Shelf(val title: String, val kind: String, val query: String)

    // Mirrors HOME_SHELVES in youtube_scrapers.py. kind "trending" -> kiosk, else search.
    private val shelves = listOf(
        Shelf("Trending Now", "trending", "Trending"),
        Shelf("Music", "search", "music videos"),
        Shelf("Lofi & Chill", "search", "lofi beats to relax study"),
        Shelf("Documentaries", "search", "documentary"),
        Shelf("Science & Tech", "search", "science technology documentaries"),
        Shelf("Cooking", "search", "cooking recipes"),
    )

    override val mainPage = mainPageOf("home" to "Home")

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val rows = ArrayList<HomePageList>()
        for (s in shelves) {
            val items = try {
                if (s.kind == "trending") kioskItems(s.query, 18) else searchItems(s.query, 18)
            } catch (e: Exception) {
                emptyList<SearchResponse>()
            }
            if (items.isNotEmpty()) rows.add(HomePageList(s.title, items, true))
        }
        return newHomePageResponse(rows, false)
    }

    private val searchPageCache = mutableMapOf<String, Page?>()

    override suspend fun search(query: String, page: Int): SearchResponseList {
        val ex = service.getSearchExtractor(query)
        val pageData = if (!searchPageCache.containsKey(query)) {
            ex.fetchPage()
            ex.initialPage.also { searchPageCache[query] = it.nextPage }
        } else {
            val next = searchPageCache[query] ?: return newSearchResponseList(emptyList(), false)
            ex.getPage(next).also { searchPageCache[query] = it.nextPage }
        }
        return newSearchResponseList(pageData.items.mapNotNull { it.toItem() }, pageData.hasNextPage())
    }

    private fun kioskItems(id: String, limit: Int): List<SearchResponse> {
        val ex = service.kioskList.getExtractorById(id, null)
            ?: service.kioskList.getDefaultKioskExtractor(null)
        ex.fetchPage()
        return ex.initialPage.items.take(limit).mapNotNull { it.toItem() }
    }

    private fun searchItems(query: String, limit: Int): List<SearchResponse> {
        val ex = service.getSearchExtractor(query)
        ex.fetchPage()
        return ex.initialPage.items.take(limit).mapNotNull { it.toItem() }
    }

    private fun InfoItem.toItem(): SearchResponse? {
        val u = url
        val t = name
        if (u.isNullOrBlank() || t.isNullOrBlank()) return null
        return newMovieSearchResponse(t, u, TvType.Others) {
            this.posterUrl = thumbnails.lastOrNull()?.url
        }
    }

    override suspend fun load(url: String): LoadResponse {
        return when (getUrlType(url)) {
            UrlType.Video -> loadVideo(url)
            UrlType.Channel -> loadChannel(url)
            UrlType.Playlist -> loadPlaylist(url)
            UrlType.Unknown -> throw RuntimeException("Unsupported YouTube URL")
        }
    }

    private enum class UrlType { Video, Channel, Playlist, Unknown }

    private fun getUrlType(url: String): UrlType = when {
        url.contains("/watch?v=") || url.contains("youtu.be/") || url.contains("/shorts/") -> UrlType.Video
        url.contains("/channel/") || url.contains("/@") || url.contains("/c/") -> UrlType.Channel
        url.contains("/playlist?list=") -> UrlType.Playlist
        else -> UrlType.Unknown
    }

    private suspend fun loadVideo(url: String): LoadResponse {
        val extractor = service.getStreamExtractor(url)
        extractor.fetchPage()
        val info = StreamInfo.getInfo(extractor)
        return newMovieLoadResponse(
            info.name,
            url,
            if (info.streamType?.name?.contains("LIVE") == true) TvType.Live else TvType.Others,
            url,
        ) {
            plot = info.description?.content?.toString() ?: ""

            // Prefer the 16:9 thumbnail; NewPipe returns several sizes.
            posterUrl = info.thumbnails
                .filter { (it.width ?: 0) > (it.height ?: 0) }
                .lastOrNull()?.url
                ?: info.thumbnails.lastOrNull()?.url

            duration = info.duration.toInt()
            info.uploaderName?.takeIf { it.isNotBlank() }?.let { up ->
                actors = listOf(ActorData(Actor(up, info.uploaderAvatars.lastOrNull()?.url ?: "")))
            }
            tags = info.tags?.take(5)?.toList()
        }
    }

    private suspend fun loadChannel(url: String): LoadResponse {
        val extractor = service.getChannelExtractor(url)
        extractor.fetchPage()
        val tabs = extractor.tabs
        val videosTab = tabs.firstOrNull { it.url.contains("/videos") } ?: tabs.firstOrNull()
            ?: throw RuntimeException("No videos tab found")

        val videosExtractor = service.getChannelTabExtractor(videosTab)
        val episodes = mutableListOf<Episode>()
        var page = videosExtractor.initialPage
        episodes.addAll(page.items.mapNotNull { item ->
            item.url?.let { u ->
                newEpisode(u) {
                    name = item.name ?: ""
                    posterUrl = item.thumbnails.lastOrNull()?.url
                }
            }
        })

        var loaded = 1
        while (page.hasNextPage() && loaded < 5) {
            page = videosExtractor.getPage(page.nextPage)
            episodes.addAll(page.items.mapNotNull { item ->
                item.url?.let { u ->
                    newEpisode(u) {
                        name = item.name ?: ""
                        posterUrl = item.thumbnails.lastOrNull()?.url
                    }
                }
            })
            loaded++
        }

        val banner = extractor.banners.lastOrNull()?.url
        return newTvSeriesLoadResponse(extractor.name, url, TvType.TvSeries, episodes) {
            plot = extractor.description
            posterUrl = banner
            backgroundPosterUrl = banner
            tags = listOf("Channel")
            actors = listOf(ActorData(Actor(extractor.name, extractor.avatars.lastOrNull()?.url ?: "")))
        }
    }

    private suspend fun loadPlaylist(url: String): LoadResponse {
        val extractor = service.getPlaylistExtractor(url)
        extractor.fetchPage()
        val episodes = mutableListOf<Episode>()
        var page = extractor.getInitialPage()
        episodes.addAll(page.items.mapNotNull { item ->
            item.url?.let { u ->
                newEpisode(u) {
                    name = item.name ?: ""
                    posterUrl = item.thumbnails.lastOrNull()?.url
                }
            }
        })
        var loaded = 1
        while (page.hasNextPage() && loaded < 5) {
            page = extractor.getPage(page.nextPage)
            episodes.addAll(page.items.mapNotNull { item ->
                item.url?.let { u ->
                    newEpisode(u) {
                        name = item.name ?: ""
                        posterUrl = item.thumbnails.lastOrNull()?.url
                    }
                }
            })
            loaded++
        }
        val uploader = extractor.uploaderName
        return newTvSeriesLoadResponse(extractor.name, url, TvType.TvSeries, episodes) {
            plot = extractor.description?.content?.toString() ?: ""
            posterUrl = extractor.thumbnails.lastOrNull()?.url
            tags = if (!uploader.isNullOrBlank()) listOf("Channel: $uploader") else listOf("Playlist")
            if (!uploader.isNullOrBlank()) {
                actors = listOf(ActorData(Actor(uploader, extractor.uploaderAvatars.lastOrNull()?.url ?: "")))
            }
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ): Boolean {
        val watchUrl = when {
            data.startsWith("http") -> data
            data.length == 11 -> "https://www.youtube.com/watch?v=$data"
            else -> "https://www.youtube.com/watch?v=$data"
        }
        return loadExtractor(watchUrl, subtitleCallback, callback)
    }
}
