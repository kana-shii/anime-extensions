package eu.kanade.tachiyomi.animeextension.en.blzone

import android.util.Log
import androidx.preference.ListPreference
import androidx.preference.PreferenceScreen
import aniyomi.lib.filemoonextractor.FilemoonExtractor
import aniyomi.lib.mixdropextractor.MixDropExtractor
import aniyomi.lib.mp4uploadextractor.Mp4uploadExtractor
import aniyomi.lib.p2pplayerextractor.P2PPlayerExtractor
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
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.nio.charset.StandardCharsets
import java.util.concurrent.TimeUnit
import kotlin.time.Duration.Companion.milliseconds

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
        private const val TAG = "BLZone"

        /*
         * Total time BLZone waits for each extractor.
         *
         * They all run at the same time, so these do not add together.
         */
        private const val DEFAULT_EXTRACTOR_TIMEOUT_MS = 4_000L
        private const val MIXDROP_EXTRACTOR_TIMEOUT_MS = 3_000L
        private const val UPN_EXTRACTOR_TIMEOUT_MS = 3_500L
        private const val P2P_EXTRACTOR_TIMEOUT_MS = 5_000L
        private const val FILEMOON_EXTRACTOR_TIMEOUT_MS = 6_000L

        /*
         * OkHttp time limits used by the extractor-specific clients.
         */
        private const val FAST_CALL_TIMEOUT_SECONDS = 4L
        private const val UPN_CALL_TIMEOUT_SECONDS = 4L
        private const val P2P_CALL_TIMEOUT_SECONDS = 5L
        private const val FILEMOON_CALL_TIMEOUT_SECONDS = 6L

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

        private val directMediaExtensions = listOf(
            ".m3u8",
            ".mp4",
            ".webm",
            ".mkv",
            ".mov",
            ".m4v",
        )
    }

    // ---- FILTERS ----

    override fun getFilterList(): AnimeFilterList = AnimeFilterList(TypeFilter())

    private class TypeFilter :
        UriPartFilter(
            "Type",
            arrayOf(
                "Both" to "",
                "Anime" to "anime",
                "Drama" to "dorama",
            ),
        )

    open class UriPartFilter(
        displayName: String,
        private val options: Array<Pair<String, String>>,
    ) : AnimeFilter.Select<String>(
        displayName,
        options.map { it.first }.toTypedArray(),
    ) {
        fun toUriPart(): String = options[state].second

        fun isDefault(): Boolean = state == 0
    }

    // ---- POPULAR ----

    override fun popularAnimeRequest(page: Int): Request = GET("$baseUrl/trending/", headers)

    override fun popularAnimeParse(response: Response): AnimesPage {
        Log.d(
            TAG,
            "popularAnimeParse: HTTP ${response.code} for ${response.request.url}",
        )

        val animeList = response
            .asJsoup()
            .select("#dt-tvshows .item.tvshows, #dt-movies .item.tvshows")
            .map(::popularAnimeFromElement)

        return AnimesPage(
            animeList,
            hasNextPage = false,
        )
    }

    private fun popularAnimeFromElement(element: Element): SAnime {
        val anime = SAnime.create()
        val poster = element.selectFirst(".poster")
        val image = poster?.selectFirst("img")

        val link = poster
            ?.selectFirst("a")
            ?.attr("href")
            .orEmpty()

        anime.title = image?.attr("alt")
            ?: element.selectFirst("h3 a")?.text()
            ?: "No title"

        anime.thumbnail_url = image?.attr("src")
        anime.setUrlWithoutDomain(link)

        return anime
    }

    // ---- LATEST ----

    override fun latestUpdatesRequest(page: Int): Request {
        val url = if (page == 1) {
            "$baseUrl/anime/"
        } else {
            "$baseUrl/anime/page/$page/"
        }

        return GET(url, headers)
    }

    override fun latestUpdatesParse(response: Response): AnimesPage {
        Log.d(
            TAG,
            "latestUpdatesParse: HTTP ${response.code} for ${response.request.url}",
        )

        val document = response.asJsoup()

        val animeList = document
            .select(".items.full .item.tvshows")
            .map(::latestAnimeFromElement)
            .toMutableList()

        if (response.request.url.encodedPath.endsWith("/anime/")) {
            runCatching {
                client.newCall(
                    GET("$baseUrl/dorama/", headers),
                ).execute().use { doramaResponse ->
                    if (doramaResponse.isSuccessful) {
                        animeList.addAll(
                            doramaResponse
                                .asJsoup()
                                .select(".items.full .item.tvshows")
                                .map(::latestAnimeFromElement),
                        )
                    }
                }
            }.onFailure { error ->
                Log.e(
                    TAG,
                    "latestUpdatesParse: dorama request failed",
                    error,
                )
            }
        }

        return AnimesPage(
            animeList,
            hasNextPage = hasNextPage(document),
        )
    }

    private fun latestAnimeFromElement(element: Element): SAnime = popularAnimeFromElement(element)

    private fun hasNextPage(document: Document): Boolean = document.selectFirst(
        ".pagination .next:not(.disabled)",
    ) != null

    // ---- SEARCH ----

    override fun searchAnimeRequest(
        page: Int,
        query: String,
        filters: AnimeFilterList,
    ): Request {
        val typeFilter = filters
            .filterIsInstance<TypeFilter>()
            .firstOrNull()

        val url = baseUrl
            .toHttpUrl()
            .newBuilder()
            .apply {
                if (
                    typeFilter != null &&
                    !typeFilter.isDefault()
                ) {
                    addPathSegment(typeFilter.toUriPart())
                    addPathSegment("")
                }

                addQueryParameter("s", query.trim())
            }
            .build()

        return GET(url.toString(), headers)
    }

    override fun searchAnimeParse(response: Response): AnimesPage {
        val animeList = response
            .asJsoup()
            .select(".search-page .result-item article")
            .map(::searchAnimeFromElement)

        return AnimesPage(
            animeList,
            hasNextPage = false,
        )
    }

    private fun searchAnimeFromElement(element: Element): SAnime {
        val anime = SAnime.create()
        val image = element.selectFirst(".thumbnail img")

        val link = element
            .selectFirst(".thumbnail a")
            ?.attr("href")
            .orEmpty()

        anime.title = image?.attr("alt")
            ?: element.selectFirst(".title a")?.text()
            ?: "No title"

        anime.thumbnail_url = image?.attr("src")
        anime.setUrlWithoutDomain(link)

        return anime
    }

    // ---- DETAILS ----

    override fun animeDetailsParse(response: Response): SAnime {
        val document = response.asJsoup()
        val anime = SAnime.create()
        val poster = document.selectFirst(".sheader .poster img")

        anime.title = document
            .selectFirst(".sheader .data h1")
            ?.text()
            ?: poster?.attr("alt")
            ?: "No title"

        anime.thumbnail_url = poster?.attr("src")

        anime.genre = document
            .select(".sheader .sgeneros a")
            .joinToString { it.text() }

        val description = document
            .selectFirst(".sbox .wp-content p")
            ?.text()
            ?.takeIf { it.isNotBlank() }

        val alternativeTitle = document
            .selectFirst(
                ".custom_fields b.variante:contains(Original Title) + span.valor",
            )
            ?.text()
            ?.takeIf { it.isNotBlank() }

        anime.description = listOfNotNull(
            description,
            alternativeTitle,
        )
            .joinToString("\n\n")
            .ifBlank {
                "No description available."
            }

        return anime
    }

    // ---- EPISODES ----

    override fun episodeListParse(
        response: Response,
    ): List<SEpisode> = response
        .asJsoup()
        .select("#episodes ul.episodios2 > li")
        .map(::episodeFromElement)
        .reversed()

    private val episodeNumRegex = Regex(
        """Episode (\d+)""",
        RegexOption.IGNORE_CASE,
    )

    private fun episodeFromElement(element: Element): SEpisode {
        val episode = SEpisode.create()
        val linkElement = element.selectFirst(".episodiotitle a")

        episode.setUrlWithoutDomain(
            linkElement?.attr("href").orEmpty(),
        )

        episode.name = linkElement?.text() ?: "Episode"

        episodeNumRegex
            .find(episode.name)
            ?.groupValues
            ?.getOrNull(1)
            ?.toFloatOrNull()
            ?.let { episodeNumber ->
                episode.episode_number = episodeNumber
            }

        return episode
    }

    // ---- EXTRACTOR CLIENTS ----

    private val fastExtractorClient: OkHttpClient by lazy {
        client.newBuilder()
            .callTimeout(
                FAST_CALL_TIMEOUT_SECONDS,
                TimeUnit.SECONDS,
            )
            .build()
    }

    private val upnExtractorClient: OkHttpClient by lazy {
        client.newBuilder()
            .callTimeout(
                UPN_CALL_TIMEOUT_SECONDS,
                TimeUnit.SECONDS,
            )
            .build()
    }

    private val p2pExtractorClient: OkHttpClient by lazy {
        client.newBuilder()
            .callTimeout(
                P2P_CALL_TIMEOUT_SECONDS,
                TimeUnit.SECONDS,
            )
            .build()
    }

    private val filemoonExtractorClient: OkHttpClient by lazy {
        client.newBuilder()
            .callTimeout(
                FILEMOON_CALL_TIMEOUT_SECONDS,
                TimeUnit.SECONDS,
            )
            .build()
    }

    // ---- VIDEO EXTRACTORS ----

    private val filemoonExtractor by lazy {
        FilemoonExtractor(filemoonExtractorClient)
    }

    private val streamtapeExtractor by lazy {
        StreamTapeExtractor(fastExtractorClient)
    }

    private val mixDropExtractor by lazy {
        MixDropExtractor(fastExtractorClient)
    }

    private val vidGuardExtractor by lazy {
        VidGuardExtractor(fastExtractorClient)
    }

    private val pixelDrainExtractor by lazy {
        PixelDrainExtractor()
    }

    private val mp4uploadExtractor by lazy {
        Mp4uploadExtractor(fastExtractorClient)
    }

    private val upnShareExtractor by lazy {
        UPnShareExtractor(upnExtractorClient)
    }

    private val p2pPlayerExtractor by lazy {
        P2PPlayerExtractor(p2pExtractorClient)
    }

    // ---- VIDEO LIST PARSE ----

    override fun videoListParse(
        response: Response,
    ): List<Video> {
        Log.d(
            TAG,
            "videoListParse: HTTP ${response.code} for ${response.request.url}",
        )

        val document = response.asJsoup()

        val serverNames = document
            .select("#playeroptionsul li span.title")
            .map { element ->
                element.text().trim()
            }

        val serverBoxes = document
            .select(".dooplay_player .source-box")
            .drop(1)

        Log.d(
            TAG,
            "videoListParse: serverNames=${serverNames.size} " +
                "serverBoxes=${serverBoxes.size}",
        )

        return serverBoxes
            .mapIndexedNotNull { index, box ->
                val rawServerName = serverNames.getOrNull(index)
                    ?: return@mapIndexedNotNull null

                val serverName = SERVER_LIST.firstOrNull { knownServer ->
                    rawServerName.contains(
                        knownServer,
                        ignoreCase = true,
                    )
                } ?: rawServerName

                val source = box
                    .selectFirst("iframe.metaframe")
                    ?.attr("src")
                    ?.trim()
                    .orEmpty()

                if (source.isBlank()) {
                    Log.d(
                        TAG,
                        "videoListParse: blank iframe " +
                            "index=$index server=$serverName",
                    )

                    return@mapIndexedNotNull null
                }

                val videoUrl = if (
                    source.contains("/diclaimer/?url=")
                ) {
                    runCatching {
                        java.net.URLDecoder.decode(
                            source.substringAfter(
                                "/diclaimer/?url=",
                            ),
                            StandardCharsets.UTF_8.name(),
                        )
                    }.getOrElse { error ->
                        Log.e(
                            TAG,
                            "videoListParse: URL decode failed",
                            error,
                        )

                        source
                    }
                } else {
                    source
                }

                Log.d(
                    TAG,
                    "videoListParse: index=$index " +
                        "server=$serverName url=$videoUrl",
                )

                Video(
                    url = videoUrl,
                    quality = serverName,
                    videoUrl = videoUrl,
                )
            }
            .also { candidates ->
                Log.d(
                    TAG,
                    "videoListParse: candidates=${candidates.size}",
                )
            }
    }

    // ---- GET VIDEO LIST ----

    override suspend fun getVideoList(
        episode: SEpisode,
    ): List<Video> {
        Log.d(
            TAG,
            "getVideoList: start episodeUrl=${episode.url}",
        )

        val response = runCatching {
            client.newCall(
                GET(baseUrl + episode.url),
            ).await()
        }.getOrElse { error ->
            Log.e(
                TAG,
                "getVideoList: episode request failed",
                error,
            )

            return emptyList()
        }

        Log.d(
            TAG,
            "getVideoList: episode page HTTP ${response.code}",
        )

        if (!response.isSuccessful) {
            Log.e(
                TAG,
                "getVideoList: episode page returned HTTP ${response.code}",
            )

            response.close()
            return emptyList()
        }

        val candidates = response.use {
            videoListParse(it)
        }

        Log.d(
            TAG,
            "getVideoList: resolving ${candidates.size} candidates concurrently",
        )

        /*
         * Every candidate starts immediately.
         *
         * Because the URLs belong to different hosts, one slow server does
         * not block another server's network request.
         */
        return coroutineScope {
            candidates
                .map { candidate ->
                    async(Dispatchers.IO) {
                        resolveAndValidateCandidate(candidate)
                    }
                }
                .awaitAll()
                .flatten()
                .also { videos ->
                    Log.i(
                        TAG,
                        "getVideoList: final resolved count=${videos.size}",
                    )
                }
        }
    }

    private suspend fun resolveAndValidateCandidate(
        candidate: Video,
    ): List<Video> {
        Log.d(
            TAG,
            "getVideoList: resolving " +
                "server=${candidate.quality} " +
                "url=${candidate.url}",
        )

        val timeoutMillis = extractorTimeoutFor(candidate.url)

        val resolvedVideos = withTimeoutOrNull(
            timeoutMillis.milliseconds,
        ) {
            serverVideoResolver(
                url = candidate.url,
                quality = candidate.quality,
            )
        } ?: run {
            Log.w(
                TAG,
                "getVideoList: extractor timeout " +
                    "server=${candidate.quality} " +
                    "timeout=${timeoutMillis}ms " +
                    "url=${candidate.url}",
            )

            emptyList()
        }

        Log.d(
            TAG,
            "getVideoList: server=${candidate.quality} " +
                "results=${resolvedVideos.size}",
        )

        return resolvedVideos.mapNotNull { resolved ->
            val directUrl = resolved.videoUrl
                ?.trim()
                ?.takeIf(::isValidPlayableUrl)
                ?: run {
                    Log.w(
                        TAG,
                        "getVideoList: discarded malformed " +
                            "result server=${candidate.quality} " +
                            "videoUrl=${resolved.videoUrl}",
                    )

                    return@mapNotNull null
                }

            val sourceUrl = resolved.url
                .trim()
                .takeIf(::isValidHttpUrl)
                ?: directUrl

            val finalVideo = Video(
                url = sourceUrl,
                quality = formatResolvedQuality(
                    serverName = candidate.quality,
                    resolvedQuality = resolved.quality,
                ),
                videoUrl = directUrl,
                headers = resolved.headers,
                subtitleTracks = resolved.subtitleTracks,
                audioTracks = resolved.audioTracks,
            )

            logVideoDiagnostics(
                sourceQuality = candidate.quality,
                originalUrl = candidate.url,
                resolved = finalVideo,
            )

            finalVideo
        }
    }

    private fun extractorTimeoutFor(url: String): Long = when {
        url.contains(
            "filemoon",
            ignoreCase = true,
        ) -> FILEMOON_EXTRACTOR_TIMEOUT_MS

        url.contains(
            "p2pplay.online",
            ignoreCase = true,
        ) -> P2P_EXTRACTOR_TIMEOUT_MS

        url.contains(
            "upns.online",
            ignoreCase = true,
        ) -> UPN_EXTRACTOR_TIMEOUT_MS

        url.contains(
            "mixdrop",
            ignoreCase = true,
        ) -> MIXDROP_EXTRACTOR_TIMEOUT_MS

        else -> DEFAULT_EXTRACTOR_TIMEOUT_MS
    }

    // ---- SERVER RESOLVER ----

    private suspend fun serverVideoResolver(
        url: String,
        quality: String,
    ): List<Video> {
        Log.d(
            TAG,
            "serverVideoResolver: quality=$quality url=$url",
        )

        return when {
            url.contains(
                "filemoon",
                ignoreCase = true,
            ) -> {
                runExtractor(
                    serverName = "FileMoon",
                ) {
                    filemoonExtractor.videosFromUrl(
                        url,
                        "FileMoon",
                    )
                }
            }

            url.contains(
                "streamtape",
                ignoreCase = true,
            ) -> {
                runExtractor(
                    serverName = "StreamTape",
                ) {
                    streamtapeExtractor.videosFromUrl(
                        url,
                        "StreamTape",
                    )
                }
            }

            url.contains(
                "mixdrop",
                ignoreCase = true,
            ) -> {
                runExtractor(
                    serverName = "MixDrop",
                ) {
                    /*
                     * Do not pass "MixDrop" as the language argument.
                     * The extractor already names its result MixDrop.
                     */
                    mixDropExtractor.videosFromUrl(
                        url = url,
                        referer = mixDropReferer(url),
                    )
                }
            }

            url.contains(
                "vgembed",
                ignoreCase = true,
            ) -> {
                runExtractor(
                    serverName = "VidGuard",
                ) {
                    vidGuardExtractor.videosFromUrl(
                        url,
                        "VidGuard",
                    )
                }
            }

            url.contains(
                "pixeldrain",
                ignoreCase = true,
            ) -> {
                runExtractor(
                    serverName = "Pixel",
                ) {
                    pixelDrainExtractor.videosFromUrl(
                        url.substringBefore("?"),
                        "Pixel",
                    )
                }
            }

            url.contains(
                "mp4upload",
                ignoreCase = true,
            ) -> {
                runExtractor(
                    serverName = "MP4",
                ) {
                    mp4uploadExtractor.videosFromUrl(
                        url,
                        headers,
                    )
                }
            }

            url.contains(
                "upns.online",
                ignoreCase = true,
            ) -> {
                /*
                 * UPnShare has been reliable, so retrying it only wastes time.
                 */
                resolveWithRetry(
                    serverName = "UPnShare",
                    attempts = 1,
                ) {
                    upnShareExtractor.videosFromUrl(
                        url = url,
                        prefix = "UPnShare",
                    )
                }
            }

            url.contains(
                "p2pplay.online",
                ignoreCase = true,
            ) -> {
                /*
                 * Keep one retry because its API occasionally returns an
                 * empty result on the first request.
                 */
                resolveWithRetry(
                    serverName = "P2P",
                    attempts = 2,
                ) {
                    p2pPlayerExtractor.videosFromUrl(
                        url = url,
                        prefix = "P2P",
                    )
                }
            }

            else -> {
                Log.w(
                    TAG,
                    "serverVideoResolver: unsupported URL $url",
                )

                emptyList()
            }
        }
    }

    private inline fun runExtractor(
        serverName: String,
        block: () -> List<Video>,
    ): List<Video> = runCatching(block)
        .getOrElse { error ->
            Log.e(
                TAG,
                "$serverName extractor failed",
                error,
            )

            emptyList()
        }

    private suspend fun resolveWithRetry(
        serverName: String,
        attempts: Int,
        block: () -> List<Video>,
    ): List<Video> {
        repeat(attempts) { index ->
            val attempt = index + 1

            val result = runCatching(block)
                .getOrElse { error ->
                    Log.e(
                        TAG,
                        "$serverName attempt=$attempt failed",
                        error,
                    )

                    emptyList()
                }

            Log.d(
                TAG,
                "$serverName resolution attempt=$attempt " +
                    "resultCount=${result.size}",
            )

            if (result.isNotEmpty()) {
                return result
            }

            if (attempt < attempts) {
                delay(150.milliseconds)
            }
        }

        return emptyList()
    }

    // ---- VIDEO HELPERS ----

    private fun mixDropReferer(url: String): String {
        val parsedUrl = url.toHttpUrlOrNull()
            ?: return "https://mixdrop.co/"

        return "${parsedUrl.scheme}://${parsedUrl.host}/"
    }

    private fun isValidPlayableUrl(value: String): Boolean {
        if (
            value.contains(
                "MDCore.",
                ignoreCase = true,
            ) ||
            value.contains(
                "undefined",
                ignoreCase = true,
            ) ||
            value.contains(
                "null",
                ignoreCase = true,
            ) ||
            value.any {
                it == '\n' ||
                    it == '\r' ||
                    it == '\t' ||
                    it == ' '
            }
        ) {
            return false
        }

        return isValidHttpUrl(value)
    }

    private fun isValidHttpUrl(value: String): Boolean {
        if (
            !value.startsWith(
                "https://",
                ignoreCase = true,
            ) &&
            !value.startsWith(
                "http://",
                ignoreCase = true,
            )
        ) {
            return false
        }

        val parsedUrl = value.toHttpUrlOrNull()
            ?: return false

        return (
            parsedUrl.scheme == "http" ||
                parsedUrl.scheme == "https"
            ) &&
            parsedUrl.host.isNotBlank() &&
            (
                parsedUrl.host.contains('.') ||
                    parsedUrl.host == "localhost"
                )
    }

    private fun cleanServerName(rawName: String): String {
        val matched = SERVER_LIST.firstOrNull { server ->
            rawName.contains(
                server,
                ignoreCase = true,
            )
        } ?: rawName

        return if (
            matched.equals(
                "Filemoon",
                ignoreCase = true,
            )
        ) {
            "FileMoon"
        } else {
            matched
        }
    }

    private fun formatResolvedQuality(
        serverName: String,
        resolvedQuality: String,
    ): String {
        val cleanServer = cleanServerName(serverName)
        val cleanResolved = resolvedQuality.trim()

        /*
         * Correct duplicated labels such as PixelPixelDrain or
         * MixDrop(MixDrop).
         */
        if (
            cleanServer.equals(
                "Pixel",
                ignoreCase = true,
            ) &&
            cleanResolved.contains(
                "PixelDrain",
                ignoreCase = true,
            )
        ) {
            return "Pixel"
        }

        if (
            cleanServer.equals(
                "MixDrop",
                ignoreCase = true,
            ) &&
            cleanResolved.contains(
                "MixDrop",
                ignoreCase = true,
            )
        ) {
            return "MixDrop"
        }

        return when {
            cleanResolved.isBlank() -> cleanServer

            cleanResolved.equals(
                "Video",
                ignoreCase = true,
            ) -> cleanServer

            cleanResolved.equals(
                cleanServer,
                ignoreCase = true,
            ) -> cleanServer

            cleanResolved.startsWith(
                cleanServer,
                ignoreCase = true,
            ) -> cleanResolved

            else -> "$cleanServer - $cleanResolved"
        }
    }

    private fun logVideoDiagnostics(
        sourceQuality: String,
        originalUrl: String,
        resolved: Video,
    ) {
        val resolvedUrl = resolved.videoUrl.orEmpty()
        val lowercaseUrl = resolvedUrl.lowercase()
        val urlWithoutQuery = lowercaseUrl.substringBefore("?")
        val headersCount = resolved.headers?.size ?: 0

        val malformed = !isValidPlayableUrl(resolvedUrl)

        val directMedia =
            directMediaExtensions.any { extension ->
                urlWithoutQuery.endsWith(extension)
            } ||
                lowercaseUrl.contains(".m3u8")

        val hostPage =
            lowercaseUrl.contains("/e/") ||
                lowercaseUrl.contains("/embed") ||
                lowercaseUrl.contains("/u/")

        Log.d(
            TAG,
            "videoDiag[resolved]: " +
                "sourceQuality=$sourceQuality " +
                "resolvedQuality=${resolved.quality} " +
                "malformed=$malformed " +
                "directMedia=$directMedia " +
                "hostPage=$hostPage " +
                "headersCount=$headersCount " +
                "originalUrl=$originalUrl " +
                "resolvedUrl=$resolvedUrl",
        )
    }

    // ---- SORTING ----

    override fun List<Video>.sort(): List<Video> {
        val preferredServer = preferences.getString(
            PREF_SERVER_KEY,
            PREF_SERVER_DEFAULT,
        ) ?: PREF_SERVER_DEFAULT

        Log.d(
            TAG,
            "sort: preferredServer=$preferredServer",
        )

        return sortedWith(
            compareByDescending { video ->
                video.quality.contains(
                    preferredServer,
                    ignoreCase = true,
                )
            },
        )
    }

    // ---- PREFERENCES ----

    override fun setupPreferenceScreen(
        screen: PreferenceScreen,
    ) {
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
