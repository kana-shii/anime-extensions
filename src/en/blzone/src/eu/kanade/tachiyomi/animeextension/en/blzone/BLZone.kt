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
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Request
import okhttp3.Response
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.net.URLDecoder
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

        private val DIRECT_MEDIA_EXTENSIONS = listOf(
            ".m3u8",
            ".mp4",
            ".webm",
            ".mkv",
            ".mov",
            ".m4v",
        )
    }

    private data class ResolvedCandidate(
        val elapsedNanoseconds: Long,
        val videos: List<Video>,
    )

    // ---- FILTERS ----

    override fun getFilterList(): AnimeFilterList =
        AnimeFilterList(TypeFilter())

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

    override fun popularAnimeRequest(page: Int): Request =
        GET("$baseUrl/trending/", headers)

    override fun popularAnimeParse(response: Response): AnimesPage {
        val animeList = response
            .asJsoup()
            .select("#dt-tvshows .item.tvshows, #dt-movies .item.tvshows")
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
            ?: element.selectFirst("h3 a")
                ?.text()
                ?.takeIf { it.isNotBlank() }
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
                    GET("$baseUrl/dorama/", headers),
                ).execute().use { doramaResponse ->
                    if (doramaResponse.isSuccessful) {
                        val doramaList = doramaResponse
                            .asJsoup()
                            .select(".items.full .item.tvshows")
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
    ): Boolean {
        return document.selectFirst(
            ".pagination .next:not(.disabled)",
        ) != null
    }

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
            .select(".search-page .result-item article")
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
        val image = element.selectFirst(".thumbnail img")

        val link = element
            .selectFirst(".thumbnail a")
            ?.attr("href")
            .orEmpty()

        anime.title = image
            ?.attr("alt")
            ?.takeIf { it.isNotBlank() }
            ?: element.selectFirst(".title a")
                ?.text()
                ?.takeIf { it.isNotBlank() }
                ?: "No title"

        anime.thumbnail_url = image?.attr("src")
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

        anime.thumbnail_url = poster?.attr("src")

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
    ): List<SEpisode> {
        return response
            .asJsoup()
            .select("#episodes ul.episodios2 > li")
            .map(::episodeFromElement)
            .reversed()
    }

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
                episode.episode_number = episodeNumber
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
            .select("#playeroptionsul li span.title")
            .map {
                it.text().trim()
            }

        val serverBoxes = document
            .select(".dooplay_player .source-box")
            .drop(1)

        return serverBoxes.mapIndexedNotNull { index, box ->
            val rawServerName = serverNames.getOrNull(index)
                ?: return@mapIndexedNotNull null

            val serverName = SERVER_LIST.firstOrNull {
                rawServerName.contains(
                    it,
                    ignoreCase = true,
                )
            } ?: rawServerName

            val source = box
                .selectFirst("iframe.metaframe")
                ?.attr("src")
                ?.trim()
                .orEmpty()

            if (source.isBlank()) {
                return@mapIndexedNotNull null
            }

            val videoUrl = decodeDisclaimerUrl(source)

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
        if (!source.contains("/diclaimer/?url=")) {
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
                GET(baseUrl + episode.url),
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

        val resolvedCandidates = coroutineScope {
            candidates.map { candidate ->
                async(Dispatchers.IO) {
                    val startedAt = System.nanoTime()

                    val videos = runCatching {
                        resolveAndValidateCandidate(candidate)
                    }.getOrDefault(emptyList())

                    ResolvedCandidate(
                        elapsedNanoseconds =
                            System.nanoTime() - startedAt,
                        videos = videos,
                    )
                }
            }.awaitAll()
        }

        return resolvedCandidates
            .asSequence()
            .filter {
                it.videos.isNotEmpty()
            }
            .sortedBy {
                it.elapsedNanoseconds
            }
            .flatMap {
                it.videos.asSequence()
            }
            .toList()
    }

    private suspend fun resolveAndValidateCandidate(
        candidate: Video,
    ): List<Video> {
        return serverVideoResolver(candidate.url)
            .mapNotNull { resolved ->
                val directUrl = resolved.videoUrl
                    ?.trim()
                    ?.takeIf(::isValidPlayableUrl)
                    ?: return@mapNotNull null

                val sourceUrl = resolved.url
                    .trim()
                    .takeIf(::isValidHttpUrl)
                    ?: directUrl

                Video(
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
            }
    }

    private suspend fun serverVideoResolver(
        url: String,
    ): List<Video> {
        return when {
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
    }

    private inline fun runExtractor(
        block: () -> List<Video>,
    ): List<Video> {
        return runCatching(block)
            .getOrDefault(emptyList())
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
        val cleanServer = cleanServerName(serverName)
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

    @Suppress("unused")
    private fun isDirectMediaUrl(
        url: String,
    ): Boolean {
        val normalizedUrl = url
            .substringBefore("?")
            .lowercase()

        return DIRECT_MEDIA_EXTENSIONS.any {
            normalizedUrl.endsWith(it)
        }
    }

    // ---- SORTING ----

    override fun List<Video>.sort(): List<Video> {
        val preferredServer = preferences.getString(
            PREF_SERVER_KEY,
            PREF_SERVER_DEFAULT,
        ) ?: PREF_SERVER_DEFAULT

        if (
            preferredServer == FASTEST_SERVER ||
            isEmpty()
        ) {
            return this
        }

        val preferredVideos = filter {
            it.quality.contains(
                preferredServer,
                ignoreCase = true,
            )
        }

        if (preferredVideos.isEmpty()) {
            /*
             * getVideoList already ordered the results by extractor
             * completion speed, so the fastest successful server remains
             * first when the preferred server is unavailable.
             */
            return this
        }

        val remainingVideos = filterNot {
            it.quality.contains(
                preferredServer,
                ignoreCase = true,
            )
        }

        return preferredVideos + remainingVideos
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
