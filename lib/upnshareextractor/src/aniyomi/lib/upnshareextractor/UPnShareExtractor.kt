package aniyomi.lib.upnshareextractor

import android.util.Log
import eu.kanade.tachiyomi.animesource.model.Video
import okhttp3.Headers
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

        /*
         * Hex:
         * 6b69656d7469656e6d75613931316361
         *
         * ASCII:
         * kiemtienmua911ca
         */
        private const val DECRYPTION_KEY_HEX =
            "6b69656d7469656e6d75613931316361"

        private const val USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) " +
                "AppleWebKit/537.36 (KHTML, like Gecko) " +
                "Chrome/150.0.0.0 Safari/537.36"
    }

    private val sourceRegex =
        Regex("""["']source["']\s*:\s*["']([^"']+)["']""", RegexOption.IGNORE_CASE)

    fun videosFromUrl(
        url: String,
        prefix: String = "UPnShare",
    ): List<Video> {
        val token = url
            .substringAfter("#", "")
            .substringBefore("&")
            .trim()
            .takeIf { it.matches(Regex("""[A-Za-z0-9]+""")) }
            ?: run {
                Log.e(TAG, "Missing or invalid fragment token: $url")
                return emptyList()
            }

        val baseUrl = url
            .substringBefore("#")
            .trimEnd('/')

        if (
            !baseUrl.startsWith("https://") &&
            !baseUrl.startsWith("http://")
        ) {
            Log.e(TAG, "Invalid base URL: $baseUrl")
            return emptyList()
        }

        val referer = "$baseUrl/"
        val apiUrl =
            "$baseUrl/api/v1/video?id=$token&w=1920&h=1200&r="

        val requestHeaders = Headers.Builder()
            .add("User-Agent", USER_AGENT)
            .add("Referer", referer)
            .build()

        Log.d(
            TAG,
            "Resolving host=$baseUrl token=$token api=$apiUrl",
        )

        val encryptedHex = runCatching {
            val request = Request.Builder()
                .url(apiUrl)
                .headers(requestHeaders)
                .get()
                .build()

            client.newCall(request).execute().use { response ->
                val body = response.body.string().trim()

                Log.d(
                    TAG,
                    "API response: code=${response.code} " +
                        "contentType=${response.header("Content-Type")} " +
                        "bodyLength=${body.length}",
                )

                if (!response.isSuccessful) {
                    Log.e(
                        TAG,
                        "UPN API failed: HTTP ${response.code}",
                    )
                    return emptyList()
                }

                body
            }
        }.getOrElse { error ->
            Log.e(TAG, "UPN API request failed", error)
            return emptyList()
        }

        if (
            encryptedHex.isBlank() ||
            encryptedHex.length % 2 != 0 ||
            !encryptedHex.all { it.isDigit() || it.lowercaseChar() in 'a'..'f' }
        ) {
            Log.e(
                TAG,
                "API response is not valid hexadecimal: " +
                    "length=${encryptedHex.length} " +
                    "preview=${encryptedHex.take(80)}",
            )
            return emptyList()
        }

        val decryptedText = runCatching {
            decryptPayload(encryptedHex)
        }.getOrElse { error ->
            Log.e(TAG, "Failed to decrypt API response", error)
            return emptyList()
        }

        Log.d(
            TAG,
            "Decrypted payload length=${decryptedText.length}",
        )

        val rawSource = sourceRegex
            .find(decryptedText)
            ?.groupValues
            ?.getOrNull(1)
            ?.takeIf { it.isNotBlank() }
            ?: run {
                Log.e(
                    TAG,
                    "No source property found in decrypted response. " +
                        "Preview=${decryptedText.take(300)}",
                )
                return emptyList()
            }

        val streamUrl = rawSource
            .replace("\\/", "/")
            .replace("\\u0026", "&")
            .replace("\\u002F", "/")
            .replace("&amp;", "&")
            .trim()

        if (
            !streamUrl.startsWith("https://") &&
            !streamUrl.startsWith("http://")
        ) {
            Log.e(TAG, "Invalid decrypted stream URL: $streamUrl")
            return emptyList()
        }

        Log.d(
            TAG,
            "Successfully resolved ${streamUrl.substringBefore("?")}",
        )

        return listOf(
            Video(
                url = streamUrl,
                quality = prefix,
                videoUrl = streamUrl,
                headers = requestHeaders,
            ),
        )
    }

    private fun decryptPayload(encryptedHex: String): String {
        val key = hexToByteArray(DECRYPTION_KEY_HEX)
        val payload = hexToByteArray(encryptedHex)

        require(key.size == 16) {
            "Expected a 16-byte AES-128 key, got ${key.size}"
        }

        require(payload.size > 16) {
            "Encrypted payload is too short: ${payload.size} bytes"
        }

        val iv = payload.copyOfRange(0, 16)
        val ciphertext = payload.copyOfRange(16, payload.size)

        require(ciphertext.size % 16 == 0) {
            "Ciphertext length is not aligned to the AES block size: " +
                "${ciphertext.size}"
        }

        val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
        cipher.init(
            Cipher.DECRYPT_MODE,
            SecretKeySpec(key, "AES"),
            IvParameterSpec(iv),
        )

        return cipher
            .doFinal(ciphertext)
            .toString(Charsets.UTF_8)
    }

    private fun hexToByteArray(hex: String): ByteArray {
        require(hex.length % 2 == 0) {
            "Hexadecimal string has an odd length"
        }

        return ByteArray(hex.length / 2) { index ->
            val start = index * 2
            hex.substring(start, start + 2)
                .toInt(16)
                .toByte()
        }
    }
}
