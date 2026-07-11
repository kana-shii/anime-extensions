package aniyomi.lib.p2pplayerextractor

import android.util.Base64
import android.util.Log
import aniyomi.lib.playlistutils.PlaylistUtils
import eu.kanade.tachiyomi.animesource.model.Video
import keiyoushi.lib.cryptoaes.CryptoAES
import okhttp3.Headers
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request

class P2PPlayerExtractor(
    private val client: OkHttpClient,
) {
    companion object {
        private const val TAG = "P2PPlayerExtractor"

        private const val USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) " +
                "AppleWebKit/537.36 (KHTML, like Gecko) " +
                "Chrome/150.0.0.0 Safari/537.36"

        private const val SECRET_KEY =
            "kiemtienmua911ca"

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

    fun videosFromUrl(
        url: String,
        prefix: String = "P2P",
    ): List<Video> {
        val token = extractToken(url)
            ?: return emptyList()

        val baseUrl = url
            .substringBefore("#")
            .trimEnd('/')

        val baseHttpUrl = runCatching {
            baseUrl.toHttpUrl()
        }.getOrElse { error ->
            Log.e(
                TAG,
                "Invalid P2P URL: $baseUrl",
                error,
            )

            return emptyList()
        }

        val referer = "$baseUrl/"

        /*
         * P2PPlay needs these headers when requesting /api/v1/video.
         */
        val apiHeaders = Headers.Builder()
            .add("User-Agent", USER_AGENT)
            .add(
                "Accept",
                "application/json, text/plain, */*",
            )
            .add("Referer", referer)
            .add("Origin", baseUrl)
            .build()

        /*
         * The master playlist may also require Origin and Accept.
         */
        val masterHeaders = Headers.Builder()
            .add("User-Agent", USER_AGENT)
            .add("Accept", "*/*")
            .add("Referer", referer)
            .add("Origin", baseUrl)
            .build()

        /*
         * Child playlists and video segments should not receive Origin.
         */
        val playbackHeaders = Headers.Builder()
            .add("User-Agent", USER_AGENT)
            .add("Referer", referer)
            .build()

        val apiUrl = baseHttpUrl
            .newBuilder()
            .addPathSegments("api/v1/video")
            .addQueryParameter("id", token)
            .addQueryParameter("w", "1920")
            .addQueryParameter("h", "1200")
            .addQueryParameter("r", "")
            .build()

        Log.d(
            TAG,
            "Resolving token=$token " +
                "api=$apiUrl " +
                "apiHeaders=${apiHeaders.size} " +
                "masterHeaders=${masterHeaders.size} " +
                "playbackHeaders=${playbackHeaders.size}",
        )

        val responseText = requestText(
            url = apiUrl.toString(),
            headers = apiHeaders,
        ) ?: return emptyList()

        val decodedPayload = decodeResponse(responseText)
            ?: run {
                Log.e(
                    TAG,
                    "Failed to decode P2P response. " +
                        "Preview=${responseText.take(160)}",
                )

                return emptyList()
            }

        val streamUrl = extractStreamUrl(decodedPayload)
            ?: run {
                Log.e(
                    TAG,
                    "No P2P stream found. " +
                        "Payload=${decodedPayload.take(300)}",
                )

                return emptyList()
            }

        Log.d(
            TAG,
            "Resolved stream=${streamUrl.substringBefore('?')}",
        )

        /*
         * PlaylistUtils requests the master playlist with masterHeaders
         * and assigns playbackHeaders to the extracted child streams.
         */
        val extractedVideos = runCatching {
            playlistUtils.extractFromHls(
                playlistUrl = streamUrl,
                referer = referer,
                masterHeaders = masterHeaders,
                videoHeaders = playbackHeaders,
                videoNameGen = { quality ->
                    formatQuality(
                        prefix = prefix,
                        quality = quality,
                    )
                },
            )
        }.getOrElse { error ->
            Log.e(
                TAG,
                "P2P HLS extraction failed",
                error,
            )

            emptyList()
        }

        /*
         * PlaylistUtils uses masterHeaders when the URL is already a media
         * playlist without #EXT-X-STREAM-INF entries. Rebuild each result
         * so actual playback always uses playbackHeaders.
         */
        if (extractedVideos.isNotEmpty()) {
            return extractedVideos.mapNotNull { video ->
                val playableUrl = video.videoUrl
                    ?.trim()
                    ?.takeIf { it.isNotBlank() }
                    ?: video.url
                        .trim()
                        .takeIf { it.isNotBlank() }
                    ?: return@mapNotNull null

                val sourceUrl = video.url
                    .trim()
                    .takeIf { it.isNotBlank() }
                    ?: playableUrl

                Video(
                    url = sourceUrl,
                    quality = formatQuality(
                        prefix = prefix,
                        quality = video.quality,
                    ),
                    videoUrl = playableUrl,
                    headers = playbackHeaders,
                    subtitleTracks = video.subtitleTracks,
                    audioTracks = video.audioTracks,
                )
            }
        }

        /*
         * Fallback for unusual or temporarily inaccessible master playlists.
         */
        return listOf(
            Video(
                url = streamUrl,
                quality = prefix,
                videoUrl = streamUrl,
                headers = playbackHeaders,
            ),
        )
    }

    private fun extractToken(
        url: String,
    ): String? {
        val token = url
            .substringAfter("#", "")
            .substringBefore("&")
            .substringBefore("?")
            .trim()
            .takeIf {
                it.matches(VALID_TOKEN_REGEX)
            }

        if (token == null) {
            Log.e(
                TAG,
                "Missing or invalid token in URL: $url",
            )
        }

        return token
    }

    private fun requestText(
        url: String,
        headers: Headers,
    ): String? {
        val request = Request.Builder()
            .url(url)
            .headers(headers)
            .get()
            .build()

        return runCatching {
            client.newCall(request)
                .execute()
                .use { response ->
                    val body = response.body
                        .string()
                        .trim()

                    Log.d(
                        TAG,
                        "API response " +
                            "code=${response.code} " +
                            "contentType=${response.header("Content-Type")} " +
                            "length=${body.length}",
                    )

                    if (!response.isSuccessful) {
                        Log.e(
                            TAG,
                            "API request returned HTTP ${response.code}. " +
                                "Body=${body.take(200)}",
                        )

                        null
                    } else {
                        body
                    }
                }
        }.getOrElse { error ->
            Log.e(
                TAG,
                "API request failed for $url",
                error,
            )

            null
        }
    }

    private fun decodeResponse(
        responseText: String,
    ): String? {
        val normalized = responseText
            .trim()
            .removeSurrounding("\"")
            .trim()

        /*
         * Some responses may already contain readable JSON.
         */
        if (extractStreamUrl(normalized) != null) {
            return normalized
        }

        val encryptedHex = when {
            normalized.isValidHex() -> normalized

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

        require(encryptedBytes.size > 16) {
            "Encrypted payload is too short: " +
                "${encryptedBytes.size} bytes"
        }

            /*
             * CryptoAES.decryptCbcIV expects Base64 input where:
             *
             * - the first 16 decoded bytes are the IV
             * - the remaining bytes are the encrypted payload
             */
        val encryptedBase64 = Base64.encodeToString(
            encryptedBytes,
            Base64.NO_WRAP,
        )

        CryptoAES.decryptCbcIV(
            encryptedBase64 = encryptedBase64,
            secretKey = SECRET_KEY,
        )?.takeIf {
            it.isNotBlank()
        }
    }.getOrElse { error ->
        Log.e(
            TAG,
            "AES decryption failed",
            error,
        )

        null
    }

    private fun extractStreamUrl(
        payload: String,
    ): String? {
        sourceRegexes.forEach { regex ->
            val rawValue = regex
                .find(payload)
                ?.groupValues
                ?.getOrNull(1)
                ?.takeIf {
                    it.isNotBlank()
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

        val normalizedPayload =
            unescapeJsonString(payload)

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
        val cleaned = quality.trim()

        return when {
            cleaned.isBlank() -> prefix

            cleaned.equals(
                other = "Video",
                ignoreCase = true,
            ) -> prefix

            cleaned.equals(
                other = prefix,
                ignoreCase = true,
            ) -> prefix

            cleaned.startsWith(
                prefix = prefix,
                ignoreCase = true,
            ) -> cleaned

            else -> "$prefix - $cleaned"
        }
    }

    private fun String.isValidHex(): Boolean = length >= 64 &&
        length % 2 == 0 &&
        all {
            it.isDigit() ||
                it.lowercaseChar() in 'a'..'f'
        }

    private fun String.hexToByteArray(): ByteArray {
        require(length % 2 == 0) {
            "Hexadecimal string has an odd length"
        }

        require(
            all {
                it.isDigit() ||
                    it.lowercaseChar() in 'a'..'f'
            },
        ) {
            "String contains invalid hexadecimal characters"
        }

        return ByteArray(length / 2) { index ->
            val start = index * 2

            substring(
                start,
                start + 2,
            ).toInt(16)
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
