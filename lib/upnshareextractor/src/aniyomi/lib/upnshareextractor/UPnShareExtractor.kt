package aniyomi.lib.upnshareextractor

import android.util.Base64
import eu.kanade.tachiyomi.animesource.model.Video
import keiyoushi.lib.cryptoaes.CryptoAES
import okhttp3.Headers
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request

class UPnShareExtractor(
    private val client: OkHttpClient,
) {
    companion object {
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
        val token = extractToken(url)
            ?: return emptyList()

        val baseUrl = url
            .substringBefore("#")
            .trimEnd('/')

        val baseHttpUrl = runCatching {
            baseUrl.toHttpUrl()
        }.getOrElse {
            return emptyList()
        }

        val referer = "$baseUrl/"

        val requestHeaders = Headers.Builder()
            .add(
                "User-Agent",
                USER_AGENT,
            )
            .add(
                "Referer",
                referer,
            )
            .build()

        val apiUrl = baseHttpUrl
            .newBuilder()
            .addPathSegments("api/v1/video")
            .addQueryParameter(
                "id",
                token,
            )
            .addQueryParameter(
                "w",
                "1920",
            )
            .addQueryParameter(
                "h",
                "1200",
            )
            .addQueryParameter(
                "r",
                "",
            )
            .build()

        val responseText = requestText(
            url = apiUrl.toString(),
            headers = requestHeaders,
        ) ?: return emptyList()

        val decodedPayload = decodeResponse(responseText)
            ?: return emptyList()

        val streamUrl = extractStreamUrl(decodedPayload)
            ?: return emptyList()

        return listOf(
            Video(
                url = streamUrl,
                quality = prefix,
                videoUrl = streamUrl,
                headers = requestHeaders,
            ),
        )
    }

    private fun extractToken(
        url: String,
    ): String? = url
        .substringAfter("#", "")
        .substringBefore("&")
        .substringBefore("?")
        .trim()
        .takeIf {
            it.matches(VALID_TOKEN_REGEX)
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
                    if (!response.isSuccessful) {
                        return@use null
                    }

                    response.body
                        .string()
                        .trim()
                }
        }.getOrNull()
    }

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

        require(encryptedBytes.size > 16)

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

        val normalizedPayload = unescapeJsonString(payload)

        return DIRECT_MEDIA_REGEX
            .find(normalizedPayload)
            ?.value
            ?.replace("&amp;", "&")
            ?.trim()
    }

    private fun String.isValidHex(): Boolean = length >= 64 &&
        length % 2 == 0 &&
        all {
            it.isDigit() ||
                it.lowercaseChar() in 'a'..'f'
        }

    private fun String.hexToByteArray(): ByteArray {
        require(length % 2 == 0)

        require(
            all {
                it.isDigit() ||
                    it.lowercaseChar() in 'a'..'f'
            },
        )

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
