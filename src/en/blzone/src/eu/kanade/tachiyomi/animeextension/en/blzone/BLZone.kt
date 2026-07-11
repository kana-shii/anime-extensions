package eu.kanade.tachiyomi.animeextension.en.blzone

import android.util.Log
import androidx.preference.ListPreference
import androidx.preference.PreferenceScreen
import aniyomi.lib.filemoonextractor.FilemoonExtractor
import aniyomi.lib.mixdropextractor.MixDropExtractor
import aniyomi.lib.mp4uploadextractor.Mp4uploadExtractor
import aniyomi.lib.pixeldrainextractor.PixelDrainExtractor
import aniyomi.lib.streamtapeextractor.StreamTapeExtractor
import aniyomi.lib.upnshareextractor.UPnShareExtractor
import aniyomi.lib.vidguardextractor.VidGuardExtractor
import eu.kanade.tachiyomi.animesource.ConfigurableAnimeSource
import eu.kanade.tachiyomi.animesource.model.AnimeFilter
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.AnimesPage
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.animesource.online.AnimeHttpSource
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.await
import eu.kanade.tachiyomi.util.asJsoup
import keiyoushi.utils.getPreferencesLazy
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request
import okhttp3.Response
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.nio.charset.StandardCharsets

class BLZone :
    AnimeHttpSource(),
    ConfigurableAnimeSource {

    override val name = "BLZone"
    override val baseUrl = "https://blzone.net"
    override val lang = "en"
    override val supportsLatest = true

    private val preferences by getPreferencesLazy()

    companion object {
        private const val PREF_SERVER_KEY = "preferred_server"
        private const val PREF_SERVER_DEFAULT = "P2P"
        private val SERVER_LIST = arrayOf(
            "StreamTape",
            "Pixel",
            "MP4",
            "Filemoon",
            "MixDrop",
            "VidGuard",
            "P2P",
            "UPnShare",
        )

        private const val TAG = "BLZone"
        private val directMediaExtensions = listOf(".m3u8", ".mp4", ".webm", ".mkv", ".mov", ".m4v")
    }

    // ---- FILTERS ----
    override fun getFilterList(): AnimeFilterList = AnimeFilterList(TypeFilter())

    private class TypeFilter :
        UriPartFilter(
            "Type",
            arrayOf(
                Pair("Both", ""),
                Pair("Anime", "anime"),
                Pair("Drama", "dorama"),
            ),
        )

    open class UriPartFilter(displayName: String, private val vals: Array<Pair<String, String>>) : AnimeFilter.Select<String>(displayName, vals.map { it.first }.toTypedArray()) {
        fun toUriPart() = vals[state].second
        fun isEmpty() = vals[state].second == ""
        fun isDefault() = state == 0
    }

    // ---- POPULAR ----
    override fun popularAnimeRequest(page: Int): Request = GET("$baseUrl/trending/", headers)

    override fun popularAnimeParse(response: Response): AnimesPage {
        Log.d(TAG, "popularAnimeParse: HTTP ${response.code} for URL: ${response.request.url}")
        if (!response.isSuccessful) {
            Log.e(TAG, "popularAnimeParse ERROR: Server returned HTTP status code ${response.code}")
        }
        val document = response.asJsoup()
        val animeList = mutableListOf<SAnime>()
        val elements = document.select("#dt-tvshows .item.tvshows, #dt-movies .item.tvshows")
        Log.d(TAG, "popularAnimeParse: Found ${elements.size} popular anime layout items")
        animeList.addAll(elements.map { popularAnimeFromElement(it) })
        return AnimesPage(animeList, hasNextPage = false)
    }

    private fun popularAnimeFromElement(element: Element): SAnime {
        val anime = SAnime.create()
        val poster = element.selectFirst(".poster")
        val link = poster?.selectFirst("a")?.attr("href")
        if (link.isNullOrBlank()) {
            Log.w(TAG, "popularAnimeFromElement: Absolute link missing or null for element title: ${element.selectFirst("h3 a")?.text()}")
        }
        val img = poster?.selectFirst("img")
        anime.title = img?.attr("alt") ?: element.selectFirst("h3 a")?.text() ?: "No title"
        anime.thumbnail_url = img?.attr("src")
        anime.setUrlWithoutDomain(link.orEmpty())
        return anime
    }

    // ---- LATEST ----
    override fun latestUpdatesRequest(page: Int): Request {
        val animePageUrl = if (page == 1) "$baseUrl/anime/" else "$baseUrl/anime/page/$page/"
        return GET(animePageUrl, headers)
    }

    override fun latestUpdatesParse(response: Response): AnimesPage {
        Log.d(TAG, "latestUpdatesParse: HTTP ${response.code} for URL: ${response.request.url}")
        if (!response.isSuccessful) {
            Log.e(TAG, "latestUpdatesParse ERROR: Server returned HTTP status code ${response.code}")
        }
        val document = response.asJsoup()
        val animeList = document.select(".items.full .item.tvshows")
            .map { latestAnimeFromElement(it) }.toMutableList()

        if (response.request.url.encodedPath.endsWith("/anime/")) {
            runCatching {
                Log.d(TAG, "latestUpdatesParse: Fetching supplementary dorama items...")
                client.newCall(GET("$baseUrl/dorama/", headers)).execute().use { doramaResponse ->
                    Log.d(TAG, "latestUpdatesParse (Dorama side-request): HTTP ${doramaResponse.code}")
                    if (doramaResponse.isSuccessful) {
                        val doramaElements = doramaResponse.asJsoup().select(".items.full .item.tvshows")
                        Log.d(TAG, "latestUpdatesParse: Found ${doramaElements.size} dorama items to append")
                        doramaElements.map { latestAnimeFromElement(it) }.let { animeList.addAll(it) }
                    } else {
                        Log.e(TAG, "latestUpdatesParse Dorama Fetch failed with HTTP code: ${doramaResponse.code}")
                    }
                }
            }.onFailure { e ->
                Log.e(TAG, "latestUpdatesParse: Exception thrown during simultaneous dorama fetch pipeline", e)
            }
        }
        return AnimesPage(animeList, hasNextPage = hasNextPage(document))
    }

    private fun latestAnimeFromElement(element: Element): SAnime = popularAnimeFromElement(element)

    private fun hasNextPage(document: Document): Boolean = document.selectFirst(".pagination .next:not(.disabled)") != null

    // ---- SEARCH ----
    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request {
        val typeFilter = filters.filterIsInstance<TypeFilter>().firstOrNull()
        val url = baseUrl.toHttpUrl()
            .newBuilder().apply {
                if (typeFilter != null && !typeFilter.isDefault()) {
                    addPathSegment(typeFilter.toUriPart())
                    addPathSegment("")
                }
                addQueryParameter("s", query.trim())
            }
            .build()
        Log.d(TAG, "searchAnimeRequest: Generating search URL: $url with query: '$query'")
        return GET(url.toString(), headers)
    }

    override fun searchAnimeParse(response: Response): AnimesPage {
        Log.d(TAG, "searchAnimeParse: HTTP ${response.code} for search URL: ${response.request.url}")
        if (!response.isSuccessful) {
            Log.e(TAG, "searchAnimeParse ERROR: HTTP error code ${response.code}")
        }
        val document = response.asJsoup()
        val targets = document.select(".search-page .result-item article")
        Log.d(TAG, "searchAnimeParse: Found ${targets.size} raw search result records matching container")
        val animeList = targets.map { searchAnimeFromElement(it) }
        return AnimesPage(animeList, hasNextPage = false)
    }

    private fun searchAnimeFromElement(element: Element): SAnime {
        val anime = SAnime.create()
        val img = element.selectFirst(".thumbnail img")
        val link = element.selectFirst(".thumbnail a")?.attr("href")
        if (link.isNullOrBlank()) {
            Log.w(TAG, "searchAnimeFromElement: Target endpoint reference link missing entirely inside wrapper node structural layout")
        }
        anime.title = img?.attr("alt") ?: element.selectFirst(".title a")?.text() ?: "No title"
        anime.thumbnail_url = img?.attr("src")
        anime.setUrlWithoutDomain(link.orEmpty())
        return anime
    }

    // ---- DETAILS ----
    override fun animeDetailsParse(response: Response): SAnime {
        Log.d(TAG, "animeDetailsParse: HTTP ${response.code} processing payload info specs details from: ${response.request.url}")
        if (!response.isSuccessful) {
            Log.e(TAG, "animeDetailsParse FAILED: Code ${response.code} (e.g. 404 Not Found / 403 Forbidden)")
        }
        val document = response.asJsoup()
        val anime = SAnime.create()
        val poster = document.selectFirst(".sheader .poster img")
        (document.selectFirst(".sheader .data h1")?.text() ?: poster?.attr("alt"))?.let {
            anime.title = it
        }
        anime.thumbnail_url = poster?.attr("src")
        anime.genre = document.select(".sheader .sgeneros a").joinToString { it.text() }
        val desc = document.selectFirst(".sbox .wp-content p")?.text()?.takeIf { it.isNotBlank() }
        val altTitle = document.selectFirst(".custom_fields b.variante:contains(Original Title) + span.valor")?.text()?.takeIf { it.isNotBlank() }
        anime.description = listOfNotNull(desc, altTitle).joinToString("\n\n").ifBlank { "No description available." }

        Log.d(TAG, "animeDetailsParse Completed processing: Title='${anime.title}', Genres='${anime.genre}'")
        return anime
    }

    // ---- EPISODES ----
    override fun episodeListParse(response: Response): List<SEpisode> {
        Log.d(TAG, "episodeListParse: HTTP status returned = ${response.code} for path: ${response.request.url}")
        if (!response.isSuccessful) {
            Log.e(TAG, "episodeListParse Critical Warning: HTTP response failed with status code ${response.code}")
        }
        val document = response.asJsoup()
        val rawEpisodes = document.select("#episodes ul.episodios2 > li")
        Log.d(TAG, "episodeListParse: Located ${rawEpisodes.size} listing items matching selector target structural rule")
        if (rawEpisodes.isEmpty()) {
            Log.w(TAG, "episodeListParse WARNING: No items fetched from DOM container #episodes ul.episodios2 > li. Is the content restricted or changed?")
        }
        return rawEpisodes.map { episodeFromElement(it) }.reversed()
    }

    private val episodeNumRegex = Regex("""Episode (\d+)""", RegexOption.IGNORE_CASE)

    private fun episodeFromElement(element: Element): SEpisode {
        val ep = SEpisode.create()
        val link = element.selectFirst(".episodiotitle a")?.attr("href")
        if (link.isNullOrBlank()) {
            Log.e(TAG, "episodeFromElement Error: Underlying reference hyperlink tag href key attribute mapping is null or dead empty.")
        }
        ep.setUrlWithoutDomain(link.orEmpty())
        ep.name = element.selectFirst(".episodiotitle a")?.text() ?: "Episode"
        val episodeNum = episodeNumRegex.find(ep.name)?.groupValues?.getOrNull(1)
        episodeNum?.toFloatOrNull()?.let { ep.episode_number = it }
        return ep
    }

    // ---- VIDEO EXTRACTORS ----
    private val filemoonExtractor by lazy { FilemoonExtractor(client) }
    private val streamtapeExtractor by lazy { StreamTapeExtractor(client) }
    private val mixDropExtractor by lazy { MixDropExtractor(client) }
    private val vidGuardExtractor by lazy { VidGuardExtractor(client) }
    private val pixelDrainExtractor by lazy { PixelDrainExtractor() }
    private val mp4uploadExtractor by lazy { Mp4uploadExtractor(client) }
    private val upnShareExtractor by lazy { UPnShareExtractor(client) }

    // ---- VIDEO LIST PARSE ----
    override fun videoListParse(response: Response): List<Video> {
        Log.d(TAG, "videoListParse: HTTP status code code=${response.code} validation for url context: ${response.request.url}")
        if (!response.isSuccessful) {
            Log.e(TAG, "videoListParse: HTTP ERROR encountered! Status code received: ${response.code}. Content may be blocked/missing.")
        }

        val document = response.asJsoup()
        val serverNames = document.select("#playeroptionsul li span.title").map { it.text().trim() }
        val serverBoxes = document.select(".dooplay_player .source-box").drop(1)

        Log.d(TAG, "videoListParse: Extracted system tracking dimensions: serverNames.size=${serverNames.size}, serverBoxes.size=${serverBoxes.size}")

        if (serverNames.isEmpty() || serverBoxes.isEmpty()) {
            Log.w(TAG, "videoListParse: DOM Selectors mismatch or layout void! Zero matches discovered on structural markup nodes paths.")
        }

        return serverBoxes.mapIndexedNotNull { index, box ->
            val rawServerName = serverNames.getOrNull(index)
            if (rawServerName == null) {
                Log.w(TAG, "videoListParse: Missing corresponding element inside label mappings array index tracking point context node index=$index")
                return@mapIndexedNotNull null
            }

            val matchedServerName = SERVER_LIST.firstOrNull { rawServerName.contains(it, ignoreCase = true) } ?: rawServerName
            val iframe = box.selectFirst("iframe.metaframe")
            val src = iframe?.attr("src")?.trim().orEmpty()

            if (src.isBlank()) {
                Log.d(TAG, "videoListParse: Skipped blank frame source location reference tracking pointer context key coordinate at index=$index label=$matchedServerName [Raw label='$rawServerName']")
                return@mapIndexedNotNull null
            }

            val videoUrl = if (src.contains("/diclaimer/?url=")) {
                runCatching {
                    java.net.URLDecoder.decode(src.substringAfter("/diclaimer/?url="), StandardCharsets.UTF_8.name())
                }.getOrElse { e ->
                    Log.e(TAG, "videoListParse failed URL decode resolution step processing parameter input: $src", e)
                    src
                }
            } else {
                src
            }

            Log.d(TAG, "videoListParse Successfully Indexed node structural map coordinates: index=$index resolvedTargetHostName=$matchedServerName sourceRouteString=$videoUrl")
            Video(videoUrl, matchedServerName, videoUrl)
        }.also {
            Log.d(TAG, "videoListParse complete checklist operations. Generated aggregate matching parse records array size counts total: ${it.size} candidates")
        }
    }

    // ---- GET VIDEO LIST ----
    override suspend fun getVideoList(episode: SEpisode): List<Video> {
        Log.d(TAG, "getVideoList Invocation Initiated: Target relative subpath execution coordinate locator: target=${episode.url}")

        val response = try {
            client.newCall(GET(baseUrl + episode.url)).await()
        } catch (e: Exception) {
            Log.e(TAG, "getVideoList CRITICAL METRIC INTERRUPT: Network level exception was thrown when executing base fetch request call to endpoint: ${baseUrl + episode.url}", e)
            return emptyList()
        }

        Log.d(TAG, "getVideoList: Main source page network request responded with HTTP status code code=${response.code}")
        if (response.code == 404) {
            Log.e(TAG, "getVideoList ERROR: Page non-existent! Received 404 for ${episode.url}")
        } else if (response.code == 403) {
            Log.e(TAG, "getVideoList ERROR: Access Denied! Received 403 for ${episode.url} (Check Cloudflare protection changes)")
        }

        val videos = videoListParse(response)
        Log.d(TAG, "getVideoList pipeline process checkpoint: target elements validation array count total size = ${videos.size} items passed to resolver routing loop layer")

        return coroutineScope {
            videos.map { video ->
                async(Dispatchers.IO) {
                    try {
                        Log.d(TAG, "getVideoList [Asynchronous Dispatch Loop Handler]: Triggering decryption extractor processing routing mapping criteria logic on route parameter location: hostName='${video.quality}', endpointSource='${video.url}'")
                        val resolvedVideos = serverVideoResolver(video.url, video.quality)

                        Log.d(TAG, "getVideoList [Extractor Resolver Callback Matrix]: Engine completed operations. Returned output items count size tracking variable dimension = ${resolvedVideos.size} nodes for '${video.quality}'")

                        resolvedVideos.mapNotNull { resolved ->
                            val videoUrl = resolved.videoUrl
                            if (videoUrl.isNullOrBlank()) {
                                Log.w(TAG, "getVideoList Extractor Execution Discard: Extractor parsing handler execution structure passed back null/empty playable direct video links parameter attributes from underlying frame payload parsing operations logic for server element target '${video.quality}'")
                                return@mapNotNull null
                            }
                            val cleanName = cleanServerName(video.quality)
                            val newVideo = Video(videoUrl, cleanName, resolved.url, headers = resolved.headers)

                            logVideoDiagnostics(
                                stage = "Resolution Verification Validation Pipeline Execution Stage",
                                sourceQuality = video.quality,
                                originalUrl = video.url,
                                resolved = newVideo,
                            )
                            newVideo
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "getVideoList EXCEPTION FAILURE inside asynchronous parsing loop sequence thread routine mapping: failed runtime conversion handling parsing criteria logic execution for target processing locator configurations parameters -> hostLabelName='${video.quality}', absoluteSrcLocatorUrl='${video.url}'", e)
                        emptyList()
                    }
                }
            }.awaitAll().flatten().also {
                Log.i(TAG, "getVideoList COMPLETED ENTIRE STREAM SYSTEM PIPELINE: Final compiled displayable video playback assets object instance payload collection total elements size count = ${it.size}")
                if (it.isEmpty()) {
                    Log.e(TAG, "getVideoList CRITICAL WARNING: Compiled video collection result payload structure mapping contains 0 active playable file entries! The user will experience a blank source player state screen error display anomaly.")
                }
            }
        }
    }

    private fun cleanServerName(rawName: String): String {
        val matched = SERVER_LIST.firstOrNull { rawName.contains(it, ignoreCase = true) } ?: rawName
        return if (matched.equals("Filemoon", ignoreCase = true)) "FileMoon" else matched
    }

    private fun serverVideoResolver(url: String, quality: String): List<Video> {
        Log.d(TAG, "serverVideoResolver routing matching check processing engine run logic: identificationLabelStringKey='$quality' routeSourceUrl='$url'")

        return runCatching {
            when {
                url.contains("filemoon", ignoreCase = true) -> filemoonExtractor.videosFromUrl(url, "FileMoon")
                url.contains("streamtape", ignoreCase = true) -> streamtapeExtractor.videosFromUrl(url, "StreamTape")
                url.contains("mixdrop", ignoreCase = true) -> mixDropExtractor.videosFromUrl(url, "MixDrop")
                url.contains("vgembed", ignoreCase = true) -> vidGuardExtractor.videosFromUrl(url, "VidGuard")
                url.contains("pixeldrain", ignoreCase = true) -> {
                    val cleanUrl = url.substringBefore("?")
                    pixelDrainExtractor.videosFromUrl(cleanUrl, "Pixel")
                }
                url.contains("mp4upload", ignoreCase = true) -> mp4uploadExtractor.videosFromUrl(url, headers)
                url.contains("upns.online", ignoreCase = true) -> upnShareExtractor.videosFromUrl(url, "UPnShare")
                url.contains("p2pplay.online", ignoreCase = true) -> upnShareExtractor.videosFromUrl(url, "P2P")
                else -> {
                    Log.w(TAG, "serverVideoResolver NO COMPATIBLE MATCH FOUND: Host signature signature does not correspond to any known/linked engine extractor routines mapping profiles. Context verification checks parameters payload elements parameters metrics -> qualityDescriptorLabel='$quality', requestReferenceLinkString='$url'")
                    emptyList()
                }
            }
        }.getOrElse { e ->
            Log.e(TAG, "serverVideoResolver EXCEPTION INTERRUPT: Internal error caught inside core third party engine extractor module pipeline wrapper while scanning host string reference patterns -> destinationLabelIdentifier='$quality', trackingLocatorUrlReference='$url'", e)
            emptyList()
        }
    }

    private fun logVideoDiagnostics(stage: String, sourceQuality: String, originalUrl: String, resolved: Video) {
        val resolvedUrl = resolved.videoUrl ?: ""
        val lower = resolvedUrl.lowercase()
        val urlNoQuery = lower.substringBefore("?")
        val headersCount = resolved.headers?.size ?: 0

        val isMalformed = resolvedUrl.isBlank() || (!resolvedUrl.startsWith("http://") && !resolvedUrl.startsWith("https://"))
        val hasFragment = resolvedUrl.contains("#")
        val fragmentOnlyPattern = resolvedUrl.contains("/#")
        val looksDirectMedia = directMediaExtensions.any { urlNoQuery.endsWith(it) } || lower.contains(".m3u8")
        val looksHostPage = lower.contains("/e/") || lower.contains("/embed") || lower.contains("/u/")

        Log.d(
            TAG,
            "videoDiag[$stage]: sourceQuality=$sourceQuality resolvedQuality=${resolved.quality} " +
                "malformed=$isMalformed directMedia=$looksDirectMedia hostPage=$looksHostPage " +
                "hasFragment=$hasFragment fragmentOnly=$fragmentOnlyPattern headersCount=$headersCount " +
                "originalUrl=$originalUrl resolvedUrl=$resolvedUrl",
        )

        if (isMalformed) {
            Log.w(TAG, "videoDiag[$stage]: MALFORMED resolved URL (likely unplayable stream string value parsing error): $resolvedUrl")
        }
        if (fragmentOnlyPattern) {
            Log.w(TAG, "videoDiag[$stage]: URL contains static runtime token fragment dependencies token strings signature parameters references (#...), extraction parsing script structural mapping properties are incomplete or mismatched: $resolvedUrl")
        }
        if (!looksDirectMedia && !looksHostPage && !isMalformed) {
            Log.w(TAG, "videoDiag[$stage]: URL validation typing status flag warning context state: unverified media asset file format layout configuration footprint compatibility for player standard pipelines: $resolvedUrl")
        }
    }

    override fun List<Video>.sort(): List<Video> {
        val preferredServer = preferences.getString(PREF_SERVER_KEY, PREF_SERVER_DEFAULT)!!
        Log.d(TAG, "sort: Ordering resolved playlist items using user engine settings reference selection parameter key criteria = '$preferredServer'")
        return this.sortedWith(
            compareByDescending { it.quality.contains(preferredServer, ignoreCase = true) },
        )
    }

    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        ListPreference(screen.context).apply {
            key = PREF_SERVER_KEY
            title = "Preferred server"
            entries = SERVER_LIST
            entryValues = SERVER_LIST
            setDefaultValue(PREF_SERVER_DEFAULT)
            summary = "%s"
        }.also(screen::addPreference)
    }
}
