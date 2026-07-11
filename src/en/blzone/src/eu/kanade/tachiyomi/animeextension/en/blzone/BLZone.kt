package eu.kanade.tachiyomi.animeextension.en.blzone

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
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Request
import okhttp3.Response
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import kotlin.time.Duration.Companion.seconds

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
        private const val FASTEST_SERVER = "Fastest"

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

        private val SERVER_PREFERENCES = arrayOf(
            FASTEST_SERVER,
            *SERVER_LIST,
        )
    }

    private data class ResolvedCandidate(
        val serverName: String,
        val elapsedNanoseconds: Long,
        val videos: List<Video>,
    )

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

    override fun popularAnimeRequest(
        page: Int,
    ): Request = GET(
        "$baseUrl/trending/",
        headers,
    )

    override fun popularAnimeParse(
        response: Response,
    ): AnimesPage {
        val animeList = response
            .asJsoup()
            .select(
                "#dt-tvshows .item.tvshows, " +
                    "#dt-movies .item.tvshows",
            )
            .map(::popularAnimeFromElement)

        return AnimesPage(
            animeList,
            hasNextPage = false,
        )
    }

    private fun popularAnimeFromElement(
        element: Element,
    ): SAnime {
        val anime = SAnime.create()
        val poster = element.selectFirst(".poster")
        val image = poster?.selectFirst("img")

        val link = poster
            ?.selectFirst("a")
            ?.attr("href")
            .orEmpty()

        anime.title = image
            ?.attr("alt")
            ?.takeIf { it.isNotBlank() }
            ?: element
                .selectFirst("h3 a")
                ?.text()
                ?.takeIf { it.isNotBlank() }
            ?: "No title"

        anime.thumbnail_url = image
            ?.attr("src")
            ?.takeIf { it.isNotBlank() }

        anime.setUrlWithoutDomain(link)

        return anime
    }

    // ---- LATEST ----

    override fun latestUpdatesRequest(
        page: Int,
    ): Request {
        val url = if (page == 1) {
            "$baseUrl/anime/"
        } else {
            "$baseUrl/anime/page/$page/"
        }

        return GET(
            url,
            headers,
        )
    }

    override fun latestUpdatesParse(
        response: Response,
    ): AnimesPage {
        val document = response.asJsoup()

        val animeList = document
            .select(".items.full .item.tvshows")
            .map(::latestAnimeFromElement)
            .toMutableList()

        if (
            response.request.url.encodedPath.endsWith(
                "/anime/",
            )
        ) {
            runCatching {
                client.newCall(
                    GET(
                        "$baseUrl/dorama/",
                        headers,
                    ),
                ).execute().use { doramaResponse ->
                    if (doramaResponse.isSuccessful) {
                        val doramaList = doramaResponse
                            .asJsoup()
                            .select(
                                ".items.full .item.tvshows",
                            )
                            .map(::latestAnimeFromElement)

                        animeList.addAll(doramaList)
                    }
                }
            }
        }

        return AnimesPage(
            animeList,
            hasNextPage = hasNextPage(document),
        )
    }

    private fun latestAnimeFromElement(
        element: Element,
    ): SAnime = popularAnimeFromElement(element)

    private fun hasNextPage(
        document: Document,
    ): Boolean = document.selectFirst(
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
                    addPathSegment(
                        typeFilter.toUriPart(),
                    )

                    addPathSegment("")
                }

                addQueryParameter(
                    "s",
                    query.trim(),
                )
            }
            .build()

        return GET(
            url.toString(),
            headers,
        )
    }

    override fun searchAnimeParse(
        response: Response,
    ): AnimesPage {
        val animeList = response
            .asJsoup()
            .select(
                ".search-page .result-item article",
            )
            .map(::searchAnimeFromElement)

        return AnimesPage(
            animeList,
            hasNextPage = false,
        )
    }

    private fun searchAnimeFromElement(
        element: Element,
    ): SAnime {
        val anime = SAnime.create()
        val image = element.selectFirst(
            ".thumbnail img",
        )

        val link = element
            .selectFirst(".thumbnail a")
            ?.attr("href")
            .orEmpty()

        anime.title = image
            ?.attr("alt")
            ?.takeIf { it.isNotBlank() }
            ?: element
                .selectFirst(".title a")
                ?.text()
                ?.takeIf { it.isNotBlank() }
            ?: "No title"

        anime.thumbnail_url = image
            ?.attr("src")
            ?.takeIf { it.isNotBlank() }

        anime.setUrlWithoutDomain(link)

        return anime
    }

    // ---- DETAILS ----

    override fun animeDetailsParse(
        response: Response,
    ): SAnime {
        val document = response.asJsoup()
        val anime = SAnime.create()

        val poster = document.selectFirst(
            ".sheader .poster img",
        )

        anime.title = document
            .selectFirst(".sheader .data h1")
            ?.text()
            ?.takeIf { it.isNotBlank() }
            ?: poster
                ?.attr("alt")
                ?.takeIf { it.isNotBlank() }
            ?: "No title"

        anime.thumbnail_url = poster
            ?.attr("src")
            ?.takeIf { it.isNotBlank() }

        anime.genre = document
            .select(".sheader .sgeneros a")
            .joinToString {
                it.text()
            }

        val description = document
            .selectFirst(".sbox .wp-content p")
            ?.text()
            ?.takeIf { it.isNotBlank() }

        val alternativeTitle = document
            .selectFirst(
                ".custom_fields " +
                    "b.variante:contains(Original Title) " +
                    "+ span.valor",
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
        """Episode\s+(\d+(?:\.\d+)?)""",
        RegexOption.IGNORE_CASE,
    )

    private fun episodeFromElement(
        element: Element,
    ): SEpisode {
        val episode = SEpisode.create()

        val linkElement = element.selectFirst(
            ".episodiotitle a",
        )

        episode.setUrlWithoutDomain(
            linkElement
                ?.attr("href")
                .orEmpty(),
        )

        episode.name = linkElement
            ?.text()
            ?.takeIf { it.isNotBlank() }
            ?: "Episode"

        episodeNumRegex
            .find(episode.name)
            ?.groupValues
            ?.getOrNull(1)
            ?.toFloatOrNull()
            ?.let { episodeNumber ->
                episode.episode_number =
                    episodeNumber
            }

        return episode
    }

    // ---- EXTRACTORS ----

    private val filemoonExtractor by lazy {
        FilemoonExtractor(client)
    }

    private val streamtapeExtractor by lazy {
        StreamTapeExtractor(client)
    }

    private val mixDropExtractor by lazy {
        MixDropExtractor(client)
    }

    private val vidGuardExtractor by lazy {
        VidGuardExtractor(client)
    }

    private val pixelDrainExtractor by lazy {
        PixelDrainExtractor()
    }

    private val mp4uploadExtractor by lazy {
        Mp4uploadExtractor(client)
    }

    private val upnShareExtractor by lazy {
        UPnShareExtractor(client)
    }

    private val p2pPlayerExtractor by lazy {
        P2PPlayerExtractor(client)
    }

    // ---- VIDEO LIST PARSE ----

    override fun videoListParse(
        response: Response,
    ): List<Video> {
        val document = response.asJsoup()

        val serverNames = document
            .select(
                "#playeroptionsul li span.title",
            )
            .map {
                it.text().trim()
            }

        val serverBoxes = document
            .select(
                ".dooplay_player .source-box",
            )
            .drop(1)

        return serverBoxes.mapIndexedNotNull {
                index,
                box,
            ->
            val rawServerName =
                serverNames.getOrNull(index)
                    ?: return@mapIndexedNotNull null

            val serverName = SERVER_LIST
                .firstOrNull { knownServer ->
                    rawServerName.contains(
                        knownServer,
                        ignoreCase = true,
                    )
                }
                ?: rawServerName

            val source = box
                .selectFirst("iframe.metaframe")
                ?.attr("src")
                ?.trim()
                .orEmpty()

            if (source.isBlank()) {
                return@mapIndexedNotNull null
            }

            val videoUrl = decodeDisclaimerUrl(
                source,
            )

            if (!isValidHttpUrl(videoUrl)) {
                return@mapIndexedNotNull null
            }

            Video(
                url = videoUrl,
                quality = serverName,
                videoUrl = videoUrl,
            )
        }
    }

    private fun decodeDisclaimerUrl(
        source: String,
    ): String {
        if (
            !source.contains(
                "/diclaimer/?url=",
            )
        ) {
            return source
        }

        return runCatching {
            URLDecoder.decode(
                source.substringAfter(
                    "/diclaimer/?url=",
                ),
                StandardCharsets.UTF_8.name(),
            )
        }.getOrDefault(source)
    }

    // ---- VIDEO RESOLUTION ----

    override suspend fun getVideoList(
        episode: SEpisode,
    ): List<Video> {
        val response = runCatching {
            client.newCall(
                GET(
                    baseUrl + episode.url,
                    headers,
                ),
            ).await()
        }.getOrElse {
            return emptyList()
        }

        if (!response.isSuccessful) {
            response.close()
            return emptyList()
        }

        val candidates = response.use {
            videoListParse(it)
        }

        if (candidates.isEmpty()) {
            return emptyList()
        }

        /*
         * Every server starts at the same time.
         */
        val resolvedCandidates = coroutineScope {
            candidates.map { candidate ->
                async(Dispatchers.IO) {
                    resolveCandidate(candidate)
                }
            }.awaitAll()
        }

        /*
         * Keep every server with at least one validated video.
         * Faster successful servers are placed first.
         */
        return resolvedCandidates
            .filter {
                it.videos.isNotEmpty()
            }
            .sortedBy {
                it.elapsedNanoseconds
            }
            .flatMap {
                it.videos
            }
    }

    private suspend fun resolveCandidate(
        candidate: Video,
    ): ResolvedCandidate {
        val startedAt = System.nanoTime()

        val resolvedVideos = runCatching {
            resolveAndValidateCandidate(candidate)
        }.getOrDefault(emptyList())

        return ResolvedCandidate(
            serverName = candidate.quality,
            elapsedNanoseconds =
            System.nanoTime() - startedAt,
            videos = resolvedVideos,
        )
    }

    private suspend fun resolveAndValidateCandidate(
        candidate: Video,
    ): List<Video> {
        val extractedVideos = serverVideoResolver(
            candidate.url,
        )

        if (extractedVideos.isEmpty()) {
            return emptyList()
        }

        /*
         * A server such as P2P may return multiple qualities.
         * Validate those qualities concurrently as well.
         */
        return coroutineScope {
            extractedVideos.map { resolved ->
                async(Dispatchers.IO) {
                    createValidatedVideo(
                        candidate = candidate,
                        resolved = resolved,
                    )
                }
            }.awaitAll()
                .filterNotNull()
        }
    }

    private suspend fun createValidatedVideo(
        candidate: Video,
        resolved: Video,
    ): Video? {
        /*
         * The result is intentionally removed when videoUrl is empty.
         * Do not fall back to resolved.url for playback.
         */
        val directUrl = resolved.videoUrl
            ?.trim()
            ?.takeIf { it.isNotBlank() }
            ?: return null

        if (!isValidPlayableUrl(directUrl)) {
            return null
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

        return finalVideo.takeIf {
            isWorkingVideo(it)
        }
    }

    // ---- SERVER RESOLVER ----

    private suspend fun serverVideoResolver(
        url: String,
    ): List<Video> = when {
        url.contains(
            "filemoon",
            ignoreCase = true,
        ) -> runExtractor {
            filemoonExtractor.videosFromUrl(
                url,
                "FileMoon",
            )
        }

        url.contains(
            "streamtape",
            ignoreCase = true,
        ) -> runExtractor {
            streamtapeExtractor.videosFromUrl(
                url,
                "StreamTape",
            )
        }

        url.contains(
            "mixdrop",
            ignoreCase = true,
        ) -> runExtractor {
            mixDropExtractor.videosFromUrl(
                url = url,
                referer = mixDropReferer(url),
            )
        }

        url.contains(
            "vgembed",
            ignoreCase = true,
        ) -> runExtractor {
            vidGuardExtractor.videosFromUrl(
                url,
                "VidGuard",
            )
        }

        url.contains(
            "pixeldrain",
            ignoreCase = true,
        ) -> runExtractor {
            pixelDrainExtractor.videosFromUrl(
                url.substringBefore("?"),
                "Pixel",
            )
        }

        url.contains(
            "mp4upload",
            ignoreCase = true,
        ) -> runExtractor {
            mp4uploadExtractor.videosFromUrl(
                url,
                headers,
            )
        }

        url.contains(
            "upns.online",
            ignoreCase = true,
        ) -> runExtractor {
            upnShareExtractor.videosFromUrl(
                url = url,
                prefix = "UPnShare",
            )
        }

        url.contains(
            "p2pplay.online",
            ignoreCase = true,
        ) -> runExtractor {
            p2pPlayerExtractor.videosFromUrl(
                url = url,
                prefix = "P2P",
            )
        }

        else -> emptyList()
    }

    private inline fun runExtractor(
        block: () -> List<Video>,
    ): List<Video> = runCatching(block)
        .getOrDefault(emptyList())

    // ---- PLAYBACK VALIDATION ----

    private suspend fun isWorkingVideo(
        video: Video,
    ): Boolean {
        val videoUrl = video.videoUrl
            ?.trim()
            ?.takeIf { it.isNotBlank() }
            ?: return false

        return withTimeoutOrNull(8.seconds) {
            if (isHlsUrl(videoUrl)) {
                validateHlsVideo(video)
            } else {
                validateDirectVideo(video)
            }
        } ?: false
    }

    private suspend fun validateHlsVideo(
        video: Video,
    ): Boolean {
        val videoUrl = video.videoUrl
            ?.trim()
            ?: return false

        val requestBuilder = Request.Builder()
            .url(videoUrl)
            .get()

        video.headers?.let {
            requestBuilder.headers(it)
        }

        val response = runCatching {
            client.newCall(
                requestBuilder.build(),
            ).await()
        }.getOrElse {
            return false
        }

        return response.use {
            if (!it.isSuccessful) {
                return@use false
            }

            val contentType = it
                .header("Content-Type")
                .orEmpty()
                .lowercase()

            if (
                contentType.contains("text/html") ||
                contentType.contains("application/json")
            ) {
                return@use false
            }

            val playlist = runCatching {
                it.body.string()
            }.getOrElse {
                return@use false
            }

            val normalizedPlaylist = playlist
                .trimStart(
                    '\uFEFF',
                    ' ',
                    '\t',
                    '\r',
                    '\n',
                )

            normalizedPlaylist.startsWith(
                "#EXTM3U",
                ignoreCase = true,
            )
        }
    }

    private suspend fun validateDirectVideo(
        video: Video,
    ): Boolean {
        val videoUrl = video.videoUrl
            ?.trim()
            ?: return false

        val requestBuilder = Request.Builder()
            .url(videoUrl)
            .header(
                "Range",
                "bytes=0-63",
            )
            .get()

        video.headers?.let {
            requestBuilder.headers(it)

            /*
             * Reapply Range because headers() replaces existing headers.
             */
            requestBuilder.header(
                "Range",
                "bytes=0-63",
            )
        }

        val response = runCatching {
            client.newCall(
                requestBuilder.build(),
            ).await()
        }.getOrElse {
            return false
        }

        return response.use {
            if (
                !it.isSuccessful &&
                it.code != 206
            ) {
                return@use false
            }

            val contentType = it
                .header("Content-Type")
                .orEmpty()
                .lowercase()

            if (
                contentType.contains("text/html") ||
                contentType.contains("application/json") ||
                contentType.contains("text/plain")
            ) {
                return@use false
            }

            val prefixBuffer = ByteArray(64)

            val bytesRead = runCatching {
                it.body
                    .byteStream()
                    .read(prefixBuffer)
            }.getOrElse {
                return@use false
            }

            if (bytesRead <= 0) {
                return@use false
            }

            val prefix = String(
                prefixBuffer,
                0,
                bytesRead,
                StandardCharsets.UTF_8,
            )
                .trimStart()
                .lowercase()

            if (
                prefix.startsWith("<!doctype") ||
                prefix.startsWith("<html") ||
                prefix.startsWith("<head") ||
                prefix.startsWith("<body") ||
                prefix.startsWith("{") ||
                prefix.startsWith("[")
            ) {
                return@use false
            }

            true
        }
    }

    private fun isHlsUrl(
        url: String,
    ): Boolean {
        val normalized = url
            .substringBefore("#")
            .lowercase()

        return normalized.contains(".m3u8")
    }

    // ---- VIDEO HELPERS ----

    private fun mixDropReferer(
        url: String,
    ): String {
        val parsedUrl = url.toHttpUrlOrNull()
            ?: return "https://mixdrop.co/"

        return buildString {
            append(parsedUrl.scheme)
            append("://")
            append(parsedUrl.host)
            append("/")
        }
    }

    private fun isValidPlayableUrl(
        value: String,
    ): Boolean {
        if (
            value.isBlank() ||
            value.contains(
                "MDCore.",
                ignoreCase = true,
            ) ||
            value.contains(
                "undefined",
                ignoreCase = true,
            ) ||
            value.equals(
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

    private fun isValidHttpUrl(
        value: String,
    ): Boolean {
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

        return parsedUrl.host.isNotBlank()
    }

    private fun cleanServerName(
        rawName: String,
    ): String {
        val matched = SERVER_LIST.firstOrNull {
            rawName.contains(
                it,
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
        val cleanServer = cleanServerName(
            serverName,
        )

        val cleanResolved = resolvedQuality.trim()

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
            cleanResolved.isBlank() ->
                cleanServer

            cleanResolved.equals(
                "Video",
                ignoreCase = true,
            ) ->
                cleanServer

            cleanResolved.equals(
                cleanServer,
                ignoreCase = true,
            ) ->
                cleanServer

            cleanResolved.startsWith(
                cleanServer,
                ignoreCase = true,
            ) ->
                cleanResolved

            else ->
                "$cleanServer - $cleanResolved"
        }
    }

    // ---- SORTING ----

    override fun List<Video>.sort(): List<Video> {
        if (isEmpty()) {
            return this
        }

        val preferredServer = preferences.getString(
            PREF_SERVER_KEY,
            PREF_SERVER_DEFAULT,
        ) ?: PREF_SERVER_DEFAULT

        /*
         * Results are already ordered by successful extraction and
         * validation speed.
         */
        if (preferredServer == FASTEST_SERVER) {
            return this
        }

        val preferredVideos = filter { video ->
            video.quality.contains(
                preferredServer,
                ignoreCase = true,
            )
        }

        /*
         * The configured server is unavailable. Preserve the complete
         * fastest-first result list.
         */
        if (preferredVideos.isEmpty()) {
            return this
        }

        val otherVideos = filterNot { video ->
            video.quality.contains(
                preferredServer,
                ignoreCase = true,
            )
        }

        /*
         * Preferred server first, followed by every other validated
         * server in fastest-first order.
         */
        return preferredVideos + otherVideos
    }

    // ---- PREFERENCES ----

    override fun setupPreferenceScreen(
        screen: PreferenceScreen,
    ) {
        ListPreference(screen.context).apply {
            key = PREF_SERVER_KEY
            title = "Preferred server"
            entries = SERVER_PREFERENCES
            entryValues = SERVER_PREFERENCES
            setDefaultValue(PREF_SERVER_DEFAULT)
            summary = "%s"
        }.also(screen::addPreference)
    }
}
