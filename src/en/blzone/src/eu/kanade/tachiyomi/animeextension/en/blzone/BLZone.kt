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
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
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
                Pair("Both", ""),
                Pair("Anime", "anime"),
                Pair("Drama", "dorama"),
            ),
        )

    open class UriPartFilter(
        displayName: String,
        private val vals: Array<Pair<String, String>>,
    ) : AnimeFilter.Select<String>(
        displayName,
        vals.map { it.first }.toTypedArray(),
    ) {
        fun toUriPart() = vals[state].second

        fun isEmpty() = vals[state].second == ""

        fun isDefault() = state == 0
    }

    // ---- POPULAR ----

    override fun popularAnimeRequest(page: Int): Request = GET("$baseUrl/trending/", headers)

    override fun popularAnimeParse(response: Response): AnimesPage {
        Log.d(
            TAG,
            "popularAnimeParse: HTTP ${response.code} for ${response.request.url}",
        )

        if (!response.isSuccessful) {
            Log.e(
                TAG,
                "popularAnimeParse: server returned HTTP ${response.code}",
            )
        }

        val document = response.asJsoup()

        val animeList = document
            .select("#dt-tvshows .item.tvshows, #dt-movies .item.tvshows")
            .map(::popularAnimeFromElement)

        Log.d(
            TAG,
            "popularAnimeParse: found ${animeList.size} entries",
        )

        return AnimesPage(
            animes = animeList,
            hasNextPage = false,
        )
    }

    private fun popularAnimeFromElement(element: Element): SAnime {
        val anime = SAnime.create()
        val poster = element.selectFirst(".poster")
        val link = poster
            ?.selectFirst("a")
            ?.attr("href")
            .orEmpty()

        if (link.isBlank()) {
            Log.w(
                TAG,
                "popularAnimeFromElement: missing link for " +
                    element.selectFirst("h3 a")?.text(),
            )
        }

        val image = poster?.selectFirst("img")

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

        if (!response.isSuccessful) {
            Log.e(
                TAG,
                "latestUpdatesParse: server returned HTTP ${response.code}",
            )
        }

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
                    Log.d(
                        TAG,
                        "latestUpdatesParse: dorama HTTP ${doramaResponse.code}",
                    )

                    if (doramaResponse.isSuccessful) {
                        doramaResponse
                            .asJsoup()
                            .select(".items.full .item.tvshows")
                            .map(::latestAnimeFromElement)
                            .let(animeList::addAll)
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
            animes = animeList,
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

        Log.d(
            TAG,
            "searchAnimeRequest: query='$query' url=$url",
        )

        return GET(url.toString(), headers)
    }

    override fun searchAnimeParse(response: Response): AnimesPage {
        Log.d(
            TAG,
            "searchAnimeParse: HTTP ${response.code} for ${response.request.url}",
        )

        val animeList = response
            .asJsoup()
            .select(".search-page .result-item article")
            .map(::searchAnimeFromElement)

        return AnimesPage(
            animes = animeList,
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
        Log.d(
            TAG,
            "animeDetailsParse: HTTP ${response.code} for ${response.request.url}",
        )

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
        ).joinToString("\n\n")
            .ifBlank { "No description available." }

        return anime
    }

    // ---- EPISODES ----

    override fun episodeListParse(
        response: Response,
    ): List<SEpisode> {
        Log.d(
            TAG,
            "episodeListParse: HTTP ${response.code} for ${response.request.url}",
        )

        val elements = response
            .asJsoup()
            .select("#episodes ul.episodios2 > li")

        Log.d(
            TAG,
            "episodeListParse: found ${elements.size} episodes",
        )

        return elements
            .map(::episodeFromElement)
            .reversed()
    }

    private val episodeNumRegex = Regex(
        """Episode (\d+)""",
        RegexOption.IGNORE_CASE,
    )

    private fun episodeFromElement(element: Element): SEpisode {
        val episode = SEpisode.create()

        val linkElement = element.selectFirst(
            ".episodiotitle a",
        )

        episode.setUrlWithoutDomain(
            linkElement?.attr("href").orEmpty(),
        )

        episode.name = linkElement?.text() ?: "Episode"

        episodeNumRegex
            .find(episode.name)
            ?.groupValues
            ?.getOrNull(1)
            ?.toFloatOrNull()
            ?.let {
                episode.episode_number = it
            }

        return episode
    }

    // ---- VIDEO EXTRACTORS ----

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

    /*
     * UPnShare and P2PPlay use the same API family.
     *
     * Resolve them one at a time so two simultaneous requests do not
     * interfere with each other or trigger transient empty responses.
     */
    private val upnFamilyMutex = Mutex()

    // ---- VIDEO LIST PARSE ----

    override fun videoListParse(
        response: Response,
    ): List<Video> {
        Log.d(
            TAG,
            "videoListParse: HTTP ${response.code} for ${response.request.url}",
        )

        if (!response.isSuccessful) {
            Log.e(
                TAG,
                "videoListParse: page returned HTTP ${response.code}",
            )
        }

        val document = response.asJsoup()

        val serverNames = document
            .select("#playeroptionsul li span.title")
            .map { it.text().trim() }

        val serverBoxes = document
            .select(".dooplay_player .source-box")
            .drop(1)

        Log.d(
            TAG,
            "videoListParse: serverNames=${serverNames.size} " +
                "serverBoxes=${serverBoxes.size}",
        )

        return serverBoxes.mapIndexedNotNull { index, box ->
            val rawServerName = serverNames.getOrNull(index)
                ?: return@mapIndexedNotNull null

            val serverName = SERVER_LIST.firstOrNull {
                rawServerName.contains(
                    other = it,
                    ignoreCase = true,
                )
            } ?: rawServerName

            val iframeUrl = box
                .selectFirst("iframe.metaframe")
                ?.attr("src")
                ?.trim()
                .orEmpty()

            if (iframeUrl.isBlank()) {
                Log.d(
                    TAG,
                    "videoListParse: blank iframe " +
                        "index=$index server=$serverName",
                )
                return@mapIndexedNotNull null
            }

            val resolvedUrl = if (
                iframeUrl.contains("/diclaimer/?url=")
            ) {
                runCatching {
                    java.net.URLDecoder.decode(
                        iframeUrl.substringAfter(
                            "/diclaimer/?url=",
                        ),
                        StandardCharsets.UTF_8.name(),
                    )
                }.getOrElse { error ->
                    Log.e(
                        TAG,
                        "videoListParse: failed to decode $iframeUrl",
                        error,
                    )
                    iframeUrl
                }
            } else {
                iframeUrl
            }

            Log.d(
                TAG,
                "videoListParse: index=$index " +
                    "server=$serverName url=$resolvedUrl",
            )

            Video(
                url = resolvedUrl,
                quality = serverName,
                videoUrl = resolvedUrl,
            )
        }.also { candidates ->
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
                "getVideoList: failed to request " +
                    (baseUrl + episode.url),
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
                "getVideoList: episode page failed with " +
                    "HTTP ${response.code}",
            )
            return emptyList()
        }

        val candidates = videoListParse(response)

        Log.d(
            TAG,
            "getVideoList: resolving ${candidates.size} candidates",
        )

        return coroutineScope {
            candidates.map { candidate ->
                async(Dispatchers.IO) {
                    runCatching {
                        Log.d(
                            TAG,
                            "getVideoList: resolving " +
                                "server=${candidate.quality} " +
                                "url=${candidate.url}",
                        )

                        val resolvedVideos = serverVideoResolver(
                            url = candidate.url,
                            quality = candidate.quality,
                        )

                        Log.d(
                            TAG,
                            "getVideoList: server=${candidate.quality} " +
                                "results=${resolvedVideos.size}",
                        )

                        resolvedVideos.mapNotNull { resolved ->
                            /*
                             * videoUrl is the actual playable media URL.
                             *
                             * Reject malformed results such as:
                             * https:MDCore.31=
                             */
                            val directUrl = resolved.videoUrl
                                ?.trim()
                                ?.takeIf(::isValidHttpUrl)

                            if (directUrl == null) {
                                Log.w(
                                    TAG,
                                    "getVideoList: discarding malformed " +
                                        "result server=${candidate.quality} " +
                                        "videoUrl=${resolved.videoUrl}",
                                )
                                return@mapNotNull null
                            }

                            /*
                             * Keep the extractor URL when it is valid.
                             * Otherwise use the playable media URL.
                             */
                            val sourceUrl = resolved.url
                                .trim()
                                .takeIf(::isValidHttpUrl)
                                ?: directUrl

                            val finalVideo = Video(
                                url = sourceUrl,
                                quality = cleanServerName(
                                    candidate.quality,
                                ),
                                videoUrl = directUrl,
                                headers = resolved.headers,
                            )

                            logVideoDiagnostics(
                                stage = "resolved",
                                sourceQuality = candidate.quality,
                                originalUrl = candidate.url,
                                resolved = finalVideo,
                            )

                            finalVideo
                        }
                    }.getOrElse { error ->
                        Log.e(
                            TAG,
                            "getVideoList: resolver failed " +
                                "server=${candidate.quality} " +
                                "url=${candidate.url}",
                            error,
                        )
                        emptyList()
                    }
                }
            }.awaitAll()
                .flatten()
                .also { resolved ->
                    Log.i(
                        TAG,
                        "getVideoList: final resolved " +
                            "count=${resolved.size}",
                    )
                }
        }
    }

    private fun isValidHttpUrl(value: String): Boolean {
        val parsed = value.toHttpUrlOrNull()
            ?: return false

        return parsed.scheme == "http" ||
            parsed.scheme == "https"
    }

    private fun cleanServerName(
        rawName: String,
    ): String {
        val matched = SERVER_LIST.firstOrNull {
            rawName.contains(
                other = it,
                ignoreCase = true,
            )
        } ?: rawName

        return if (
            matched.equals(
                other = "Filemoon",
                ignoreCase = true,
            )
        ) {
            "FileMoon"
        } else {
            matched
        }
    }

    private suspend fun serverVideoResolver(
        url: String,
        quality: String,
    ): List<Video> {
        Log.d(
            TAG,
            "serverVideoResolver: quality=$quality url=$url",
        )

        return runCatching {
            when {
                url.contains(
                    "filemoon",
                    ignoreCase = true,
                ) -> {
                    filemoonExtractor.videosFromUrl(
                        url,
                        "FileMoon",
                    )
                }

                url.contains(
                    "streamtape",
                    ignoreCase = true,
                ) -> {
                    streamtapeExtractor.videosFromUrl(
                        url,
                        "StreamTape",
                    )
                }

                url.contains(
                    "mixdrop",
                    ignoreCase = true,
                ) -> {
                    mixDropExtractor.videosFromUrl(
                        url,
                        "MixDrop",
                    )
                }

                url.contains(
                    "vgembed",
                    ignoreCase = true,
                ) -> {
                    vidGuardExtractor.videosFromUrl(
                        url,
                        "VidGuard",
                    )
                }

                url.contains(
                    "pixeldrain",
                    ignoreCase = true,
                ) -> {
                    pixelDrainExtractor.videosFromUrl(
                        url = url.substringBefore("?"),
                        prefix = "Pixel",
                    )
                }

                url.contains(
                    "mp4upload",
                    ignoreCase = true,
                ) -> {
                    mp4uploadExtractor.videosFromUrl(
                        url,
                        headers,
                    )
                }

                url.contains(
                    "upns.online",
                    ignoreCase = true,
                ) -> {
                    resolveUpnFamily(
                        url = url,
                        prefix = "UPnShare",
                    )
                }

                url.contains(
                    "p2pplay.online",
                    ignoreCase = true,
                ) -> {
                    resolveUpnFamily(
                        url = url,
                        prefix = "P2P",
                    )
                }

                else -> {
                    Log.w(
                        TAG,
                        "serverVideoResolver: unsupported " +
                            "quality=$quality url=$url",
                    )
                    emptyList()
                }
            }
        }.getOrElse { error ->
            Log.e(
                TAG,
                "serverVideoResolver: failed " +
                    "quality=$quality url=$url",
                error,
            )
            emptyList()
        }
    }

    private suspend fun resolveUpnFamily(
        url: String,
        prefix: String,
    ): List<Video> = upnFamilyMutex.withLock {
        var resolved = emptyList<Video>()

        for (attempt in 1..3) {
            resolved = upnShareExtractor.videosFromUrl(
                url = url,
                prefix = prefix,
            )

            Log.d(
                TAG,
                "resolveUpnFamily: server=$prefix " +
                    "attempt=$attempt " +
                    "resultCount=${resolved.size}",
            )

            if (resolved.isNotEmpty()) {
                break
            }

            if (attempt < 3) {
                delay(250L * attempt)
            }
        }

        resolved
    }

    private fun logVideoDiagnostics(
        stage: String,
        sourceQuality: String,
        originalUrl: String,
        resolved: Video,
    ) {
        val resolvedUrl = resolved.videoUrl.orEmpty()
        val lowerUrl = resolvedUrl.lowercase()
        val urlWithoutQuery = lowerUrl.substringBefore("?")
        val headerCount = resolved.headers?.size ?: 0

        val malformed = !isValidHttpUrl(resolvedUrl)

        val directMedia =
            directMediaExtensions.any {
                urlWithoutQuery.endsWith(it)
            } ||
                lowerUrl.contains(".m3u8")

        val hostPage =
            lowerUrl.contains("/e/") ||
                lowerUrl.contains("/embed") ||
                lowerUrl.contains("/u/")

        Log.d(
            TAG,
            "videoDiag[$stage]: " +
                "sourceQuality=$sourceQuality " +
                "resolvedQuality=${resolved.quality} " +
                "malformed=$malformed " +
                "directMedia=$directMedia " +
                "hostPage=$hostPage " +
                "headersCount=$headerCount " +
                "originalUrl=$originalUrl " +
                "resolvedUrl=$resolvedUrl",
        )

        if (malformed) {
            Log.w(
                TAG,
                "videoDiag[$stage]: malformed URL: $resolvedUrl",
            )
        }
    }

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
            compareByDescending {
                it.quality.contains(
                    preferredServer,
                    ignoreCase = true,
                )
            },
        )
    }

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
