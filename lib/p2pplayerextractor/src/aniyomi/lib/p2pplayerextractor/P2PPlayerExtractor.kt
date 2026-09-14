package aniyomi.lib.p2pplayerextractor

import android.util.Base64
import aniyomi.lib.playlistutils.PlaylistUtils
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.network.GET
import keiyoushi.lib.cryptoaes.CryptoAES
import okhttp3.Headers
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient

/**
 * Extracts video streams from P2PPlayer embeds.
 */
class P2PPlayerExtractor(
    private val client: OkHttpClient,
) {
    companion object {
        private const val SECRET_KEY = "kiemtienmua911ca"

        private val VALID_TOKEN_REGEX =
            Regex("""[A-Za-z0-9_-]+""")

        private val HEX_PAYLOAD_REGEX =
            Regex("""[0-9a-fA-F]{64,}""")

        private val DIRECT_MEDIA_REGEX = Regex(
            """https?://[^\s"']+?\.(?:m3u8|mp4)(?:\?[^\s"']*)?""",
            RegexOption.IGNORE_CASE,
        )
    }

    private val playlistUtils by lazy {
        PlaylistUtils(client)
    }

    private val sourceRegexes = listOf(
        Regex(
            """["']source["']\s*:\s*["']((?:\\.|[^"'\\])*)["']""",
            RegexOption.IGNORE_CASE,
        ),
        Regex(
            """["']file["']\s*:\s*["']((?:\\.|[^"'\\])*)["']""",
            RegexOption.IGNORE_CASE,
        ),
    )

    /**
     * Returns the available video streams from the given P2PPlayer embed URL.
     */
    fun videosFromUrl(
        url: String,
        headers: Headers,
        prefix: String = "P2P",
    ): List<Video> {
        val httpUrl = url.toHttpUrlOrNull()
            ?: return emptyList()

        val token = extractToken(httpUrl)
            ?: return emptyList()

        val baseHttpUrl = httpUrl
            .newBuilder()
            .fragment(null)
            .build()

        val baseUrl = baseHttpUrl.toString().trimEnd('/')
        val referer = "$baseUrl/"

        val apiHeaders = headers.newBuilder()
            .set(
                "Accept",
                "application/json, text/plain, */*",
            )
            .set("Referer", referer)
            .set("Origin", baseUrl)
            .build()

        val masterHeaders = headers.newBuilder()
            .set("Accept", "*/*")
            .set("Referer", referer)
            .set("Origin", baseUrl)
            .build()

        val playbackHeaders = headers.newBuilder()
            .set("Referer", referer)
            .removeAll("Origin")
            .build()

        val apiUrl = baseHttpUrl
            .newBuilder()
            .addPathSegments("api/v1/video")
            .addQueryParameter("id", token)
            .addQueryParameter("w", "1920")
            .addQueryParameter("h", "1200")
            .addQueryParameter("r", "")
            .build()

        val responseText = requestText(
            url = apiUrl,
            headers = apiHeaders,
        ) ?: return emptyList()

        val decryptedPayload = decodeResponse(responseText)
            ?: return emptyList()

        val streamUrl = extractStreamUrl(decryptedPayload)
            ?: return emptyList()

        if (
            streamUrl
                .substringBefore("?")
                .endsWith(
                    ".mp4",
                    ignoreCase = true,
                )
        ) {
            return listOf(
                Video(
                    streamUrl,
                    prefix,
                    streamUrl,
                    playbackHeaders,
                ),
            )
        }

        val extractedVideos = runCatching {
            playlistUtils.extractFromHls(
                playlistUrl = streamUrl,
                referer = referer,
                masterHeaders = masterHeaders,
                videoHeaders = playbackHeaders,
                videoNameGen = { quality ->
                    quality
                },
            )
        }.getOrDefault(emptyList())

        if (extractedVideos.isEmpty()) {
            return listOf(
                Video(
                    streamUrl,
                    prefix,
                    streamUrl,
                    playbackHeaders,
                ),
            )
        }

        return extractedVideos.mapNotNull { video ->
            val videoUrl = video.videoUrl
                .trim()
                .takeIf {
                    it.isNotEmpty()
                }
                ?: return@mapNotNull null

            Video(
                videoUrl,
                formatQuality(
                    prefix = prefix,
                    quality = video.videoTitle,
                ),
                videoUrl,
                playbackHeaders,
                video.subtitleTracks,
                video.audioTracks,
            )
        }
    }

    private fun extractToken(
        url: HttpUrl,
    ): String? = url.fragment
        ?.substringBefore("&")
        ?.substringBefore("?")
        ?.trim()
        ?.takeIf {
            it.matches(VALID_TOKEN_REGEX)
        }

    private fun requestText(
        url: HttpUrl,
        headers: Headers,
    ): String? = runCatching {
        client.newCall(GET(url, headers))
            .execute()
            .use { response ->
                if (!response.isSuccessful) {
                    return@use null
                }

                response.body.string()
                    .trim()
            }
    }.getOrNull()

    private fun decodeResponse(
        responseText: String,
    ): String? {
        val normalized = responseText
            .trim()
            .removeSurrounding("\"")
            .trim()

        if (extractStreamUrl(normalized) != null) {
            return normalized
        }

        val encryptedHex = when {
            normalized.isValidHex() ->
                normalized

            else ->
                HEX_PAYLOAD_REGEX
                    .find(normalized)
                    ?.value
        } ?: return null

        return decryptHexPayload(encryptedHex)
    }

    private fun decryptHexPayload(
        encryptedHex: String,
    ): String? = runCatching {
        val encryptedBytes = encryptedHex
            .hexToByteArray()

        require(encryptedBytes.size > 16)

        val encryptedBase64 = Base64.encodeToString(
            encryptedBytes,
            Base64.NO_WRAP,
        )

        CryptoAES.decryptCbcIV(
            encryptedBase64 = encryptedBase64,
            secretKey = SECRET_KEY,
        )?.takeIf {
            it.isNotEmpty()
        }
    }.getOrNull()

    private fun extractStreamUrl(
        payload: String,
    ): String? {
        sourceRegexes.forEach { regex ->
            val rawValue = regex
                .find(payload)
                ?.groupValues
                ?.getOrNull(1)
                ?.takeIf {
                    it.isNotEmpty()
                }
                ?: return@forEach

            val cleaned = unescapeJsonString(rawValue)
                .replace("&amp;", "&")
                .trim()

            if (
                cleaned.startsWith("https://") ||
                cleaned.startsWith("http://")
            ) {
                return cleaned
            }
        }

        val normalizedPayload = unescapeJsonString(payload)

        return DIRECT_MEDIA_REGEX
            .find(normalizedPayload)
            ?.value
            ?.replace("&amp;", "&")
            ?.trim()
    }

    private fun formatQuality(
        prefix: String,
        quality: String,
    ): String {
        val cleanedQuality = quality.trim()

        return when {
            cleanedQuality.isEmpty() ->
                prefix

            cleanedQuality.equals(
                "Video",
                ignoreCase = true,
            ) ->
                prefix

            cleanedQuality.equals(
                prefix,
                ignoreCase = true,
            ) ->
                prefix

            cleanedQuality.startsWith(
                prefix,
                ignoreCase = true,
            ) ->
                cleanedQuality

            else ->
                "$prefix - $cleanedQuality"
        }
    }

    private fun String.isValidHex(): Boolean = length >= 64 &&
        length % 2 == 0 &&
        all {
            it.isDigit() ||
                it.lowercaseChar() in 'a'..'f'
        }

    private fun String.hexToByteArray(): ByteArray {
        require(length % 2 == 0)

        return ByteArray(length / 2) { index ->
            val start = index * 2

            substring(
                start,
                start + 2,
            )
                .toInt(16)
                .toByte()
        }
    }

    private fun unescapeJsonString(
        value: String,
    ): String = value
        .replace("\\/", "/")
        .replace(
            "\\u0026",
            "&",
            ignoreCase = true,
        )
        .replace(
            "\\u002F",
            "/",
            ignoreCase = true,
        )
        .replace(
            "\\u003A",
            ":",
            ignoreCase = true,
        )
        .replace(
            "\\u003F",
            "?",
            ignoreCase = true,
        )
        .replace(
            "\\u003D",
            "=",
            ignoreCase = true,
        )
        .replace(
            "\\u0025",
            "%",
            ignoreCase = true,
        )
        .replace("\\\"", "\"")
        .replace("\\\\", "\\")
}
