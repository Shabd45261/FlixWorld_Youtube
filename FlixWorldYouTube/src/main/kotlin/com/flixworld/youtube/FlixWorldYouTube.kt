package com.flixworld.youtube

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.schabi.newpipe.extractor.InfoItem
import org.schabi.newpipe.extractor.Page
import org.schabi.newpipe.extractor.ServiceList
import org.schabi.newpipe.extractor.stream.StreamInfo
import org.schabi.newpipe.extractor.stream.StreamInfoItem

/**
 * Flix World — YouTube. ONE provider combining videos AND Shorts.
 *
 * Home rows mirror youtube_scrapers.py's HOME_SHELVES (Trending Now, Music,
 * Lofi & Chill, Documentaries, Science & Tech, Cooking) plus a "Shorts" row.
 *
 * Extraction is delegated to NewPipeExtractor; the actual streams are resolved
 * by CloudStream's own YouTube extractor via loadExtractor().
 *
 * Shorts note: NewPipe only reports duration for some search entries (unknown
 * = -1). Filtering strictly on duration emptied the Shorts row, so entries
 * with an UNKNOWN duration are kept; only a KNOWN duration > 3 min is dropped.
 */
class FlixWorldYouTube : MainAPI() {
    override var mainUrl = "https://www.youtube.com"
    override var name = "FlixWorld YouTube"
    override var lang = "en"
    override val hasMainPage = true
    override val supportedTypes = setOf(TvType.Others, TvType.Live, TvType.TvSeries)

    private val service = ServiceList.YouTube

    private data class Shelf(val title: String, val kind: String, val query: String)

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

        // Shelves in parallel (one network call each).
        val shelfResults = try {
            shelves.amap { s ->
                val items = try {
                    if (s.kind == "trending") kioskItems(s.query, 18) else searchItems(s.query, 18)
                } catch (_: Exception) {
                    emptyList<SearchResponse>()
                }
                Pair(s.title, items)
            }
        } catch (_: Exception) {
            emptyList()
        }
        for ((title, items) in shelfResults) {
            if (items.isNotEmpty()) rows.add(HomePageList(title, items, true))
        }

        // Shorts row.
        try {
            val shorts = shortsItems(24)
            if (shorts.isNotEmpty()) rows.add(HomePageList("Shorts", shorts, false))
        } catch (_: Exception) {
        }

        return newHomePageResponse(rows, false)
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

    private fun shortsItems(limit: Int): List<SearchResponse> {
        val seen = HashSet<String>()
        val out = ArrayList<SearchResponse>()
        for (q in listOf("#shorts", "#shorts viral", "#shorts trending")) {
            try {
                val ex = service.getSearchExtractor(q)
                ex.fetchPage()
                for (item in ex.initialPage.items) {
                    val sr = item.toShortItem() ?: continue
                    if (seen.add(sr.url)) out.add(sr)
                }
            } catch (_: Exception) {
            }
            if (out.size >= limit) break
        }
        return out.take(limit)
    }

    private fun InfoItem.toItem(): SearchResponse? {
        val u = url ?: return null
        val t = name ?: return null
        return newMovieSearchResponse(t, u, TvType.Others) {
            this.posterUrl = thumbnails.lastOrNull()?.url
        }
    }

    private fun InfoItem.toShortItem(): SearchResponse? {
        if (this !is StreamInfoItem) return null
        val d = duration
        if (d > 0 && d > 180) return null
        val u = url ?: return null
        val t = name ?: return null
        return newMovieSearchResponse(t, u, TvType.Others) {
            this.posterUrl = thumbnails.lastOrNull()?.url
        }
    }

    /**
     * CloudStream renders `plot` as Markdown (Markwon). Bare URLs are only
     * "linkified" (coloured) and a tap falls through to the expand/collapse
     * handler; only real Markdown links `[text](url)` become clickable spans
     * that open the browser. So convert bare URLs into Markdown links.
     */
    private fun markdownLinks(text: String): String {
        if (text.isBlank()) return text
        return Regex("https?://[^\\s<>()\\[\\]]+").replace(text) { m ->
            "[${m.value}](${m.value})"
        }
    }

    // ---- search / load / links ------------------------------------------

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
            plot = markdownLinks(info.description?.content?.toString() ?: "")
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
