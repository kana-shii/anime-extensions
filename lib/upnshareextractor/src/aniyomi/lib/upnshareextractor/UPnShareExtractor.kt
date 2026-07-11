package aniyomi.lib.upnshareextractor

import android.util.Log
import eu.kanade.tachiyomi.animesource.model.Video
import okhttp3.Headers
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

class UPnShareExtractor(
    private val client: OkHttpClient,
) {
    companion object {
        private const val TAG = "UPnShareExtractor"

        private const val USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) " +
                "AppleWebKit/537.36 (KHTML, like Gecko) " +
                "Chrome/150.0.0.0 Safari/537.36"

        private const val DECRYPTION_KEY_HEX =
            "6b69656d7469656e6d75613931316361"

        private val HEX_PAYLOAD_REGEX =
            Regex("""[0-9a-fA-F]{64,}""")

        private val DIRECT_MEDIA_REGEX = Regex(
            """https?://[^\s"']+?\.(?:m3u8|mp4)(?:\?[^\s"']*)?""",
            RegexOption.IGNORE_CASE,
        )
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
        prefix: String = "UPnShare",
    ): List<Video> {
        /*
         * The player identifier is kept after #.
         *
         * Example:
         * https://boyslove.upns.online/#pugrtr
         *
         * token = pugrtr
         */
        val token = url
            .substringAfter("#", "")
            .substringBefore("&")
            .substringBefore("?")
            .trim()
            .takeIf {
                it.matches(
                    Regex("""[A-Za-z0-9_-]+"""),
                )
            }
            ?: run {
                Log.e(
                    TAG,
                    "Missing or invalid fragment token: $url",
                )
                return emptyList()
            }

        val baseUrl = url
            .substringBefore("#")
            .trimEnd('/')

        val baseHttpUrl = runCatching {
            baseUrl.toHttpUrl()
        }.getOrElse { error ->
            Log.e(
                TAG,
                "Invalid host URL: $baseUrl",
                error,
            )
            return emptyList()
        }

        val referer = "$baseUrl/"

        /*
         * Headers for the API request.
         */
        val requestHeaders = Headers.Builder()
            .add("User-Agent", USER_AGENT)
            .add(
                "Accept",
                "application/json, text/plain, */*",
            )
            .add("Referer", referer)
            .add("Origin", baseUrl)
            .build()

        /*
         * These headers are passed to Aniyomi and used for:
         *
         * - master.m3u8
         * - child playlists
         * - encryption keys
         * - media segments
         *
         * Origin is especially important for P2PPlay.
         */
        val playbackHeaders = Headers.Builder()
            .add("User-Agent", USER_AGENT)
            .add("Accept", "*/*")
            .add("Referer", referer)
            .add("Origin", baseUrl)
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
            "Resolving prefix=$prefix " +
                "host=$baseUrl " +
                "token=$token " +
                "api=$apiUrl",
        )

        val responseText = requestText(
            url = apiUrl.toString(),
            headers = requestHeaders,
        ) ?: return emptyList()

        /*
         * The API may return:
         *
         * 1. The encrypted hexadecimal payload.
         * 2. A quoted hexadecimal payload.
         * 3. A JSON wrapper containing the hexadecimal payload.
         * 4. Plain JSON containing source/file.
         */
        val decodedPayload = decodeResponse(responseText)
            ?: run {
                Log.e(
                    TAG,
                    "Unable to decode API response " +
                        "prefix=$prefix " +
                        "length=${responseText.length} " +
                        "preview=${responseText.take(120)}",
                )
                return emptyList()
            }

        val streamUrl = extractStreamUrl(decodedPayload)
            ?: run {
                Log.e(
                    TAG,
                    "No media source found " +
                        "prefix=$prefix " +
                        "payloadPreview=${decodedPayload.take(300)}",
                )
                return emptyList()
            }

        Log.d(
            TAG,
            "Resolved prefix=$prefix " +
                "stream=${streamUrl.substringBefore('?')}",
        )

        return listOf(
            Video(
                url = streamUrl,
                quality = prefix,
                videoUrl = streamUrl,
                headers = playbackHeaders,
            ),
        )
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
                            "contentType=" +
                            "${response.header("Content-Type")} " +
                            "length=${body.length}",
                    )

                    if (!response.isSuccessful) {
                        Log.e(
                            TAG,
                            "API request failed with " +
                                "HTTP ${response.code}: " +
                                body.take(200),
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
         * Some versions of the API may return readable JSON.
         */
        if (extractStreamUrl(normalized) != null) {
            return normalized
        }

        /*
         * Otherwise locate the hexadecimal encrypted payload.
         */
        val encryptedHex = when {
            normalized.isValidHex() -> normalized

            else ->
                HEX_PAYLOAD_REGEX
                    .find(normalized)
                    ?.value
        } ?: return null

        return runCatching {
            decryptPayload(encryptedHex)
        }.getOrElse { error ->
            Log.e(
                TAG,
                "Failed to decrypt API response",
                error,
            )
            null
        }
    }

    private fun extractStreamUrl(
        payload: String,
    ): String? {
        sourceRegexes.forEach { regex ->
            val rawValue = regex
                .find(payload)
                ?.groupValues
                ?.getOrNull(1)
                ?.takeIf { it.isNotBlank() }
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

        /*
         * Fallback for a payload containing a direct media URL
         * without a source/file property.
         */
        val normalizedPayload = unescapeJsonString(payload)

        return DIRECT_MEDIA_REGEX
            .find(normalizedPayload)
            ?.value
            ?.replace("&amp;", "&")
            ?.trim()
    }

    private fun decryptPayload(
        encryptedHex: String,
    ): String {
        val key = hexToByteArray(
            DECRYPTION_KEY_HEX,
        )

        val payload = hexToByteArray(
            encryptedHex,
        )

        require(key.size == 16) {
            "Expected a 16-byte AES key, " +
                "got ${key.size}"
        }

        require(payload.size > 16) {
            "Encrypted payload is too short: " +
                "${payload.size} bytes"
        }

        /*
         * First 16 decoded bytes are the IV.
         * The remaining bytes are the ciphertext.
         */
        val initializationVector = payload.copyOfRange(
            0,
            16,
        )

        val ciphertext = payload.copyOfRange(
            16,
            payload.size,
        )

        require(ciphertext.size % 16 == 0) {
            "Ciphertext is not aligned to the " +
                "AES block size: ${ciphertext.size}"
        }

        val cipher = Cipher.getInstance(
            "AES/CBC/PKCS5Padding",
        )

        cipher.init(
            Cipher.DECRYPT_MODE,
            SecretKeySpec(key, "AES"),
            IvParameterSpec(initializationVector),
        )

        return cipher
            .doFinal(ciphertext)
            .toString(Charsets.UTF_8)
    }

    private fun String.isValidHex(): Boolean = length >= 64 &&
        length % 2 == 0 &&
        all {
            it.isDigit() ||
                it.lowercaseChar() in 'a'..'f'
        }

    private fun hexToByteArray(
        hex: String,
    ): ByteArray {
        require(hex.length % 2 == 0) {
            "Hexadecimal string has an odd length"
        }

        return ByteArray(hex.length / 2) { index ->
            val start = index * 2

            hex.substring(
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
