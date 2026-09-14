package aniyomi.lib.embed4meextractor

import aniyomi.lib.playlistutils.PlaylistUtils
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.awaitSuccess
import keiyoushi.utils.bodyString
import keiyoushi.utils.commonEmptyHeaders
import keiyoushi.utils.decodeHex
import keiyoushi.utils.parallelCatchingFlatMap
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.Headers
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

class Embed4MeExtractor(
    private val client: OkHttpClient,
    private val headers: Headers = commonEmptyHeaders,
) {
    private val playlistUtils by lazy { PlaylistUtils(client, headers) }

    private val json = Json {
        isLenient = true
        ignoreUnknownKeys = true
    }

    suspend fun videosFromUrl(
        url: String,
        prefix: String = "",
        name: String = "Embed4Me",
        referer: String? = null,
        apiBaseUrl: String? = null,
        height: Int = 1080,
    ): List<Video> {
        val id = extractId(url) ?: return emptyList()
        val embedOrigin = extractOrigin(url) ?: return emptyList()
        val requestBaseUrl = apiBaseUrl ?: embedOrigin
        val requestReferer = referer ?: "${requestBaseUrl.trimEnd('/')}/"
        val referrer = referer?.toHttpUrlOrNull()?.host.orEmpty()

        val apiUrl = buildApiUrl(requestBaseUrl, id, referrer, height)
        val apiHeaders = headers.newBuilder()
            .set("Accept", "application/json, text/plain, */*")
            .set("Referer", requestReferer)
            .set("Origin", embedOrigin)
            .build()

        val raw = try {
            client.newCall(GET(apiUrl, apiHeaders))
                .awaitSuccess()
                .bodyString()
                .trim()
        } catch (_: Exception) {
            return emptyList()
        }

        if (raw.isBlank()) return emptyList()

        val payload = decryptIfNeeded(raw)
        if (payload.isNullOrBlank()) return emptyList()

        val candidates = buildCandidates(payload, embedOrigin)
            ?.takeIf { it.isNotEmpty() }
            ?: extractStreamUrl(payload)?.let(::listOf)
            ?: emptyList()

        if (candidates.isEmpty()) return emptyList()

        val hlsHeaders = headers.newBuilder()
            .set("Accept", "*/*")
            .set("Referer", requestReferer)
            .set("Origin", embedOrigin)
            .build()

        return candidates.parallelCatchingFlatMap { candidateUrl ->
            try {
                val title = listOfNotNull(
                    prefix.trim().takeIf { it.isNotEmpty() },
                    name,
                ).joinToString(" ")

                if (
                    candidateUrl
                        .substringBefore("?")
                        .endsWith(".mp4", ignoreCase = true)
                ) {
                    return@parallelCatchingFlatMap listOf(
                        Video(
                            candidateUrl,
                            title,
                            candidateUrl,
                            hlsHeaders,
                        ),
                    )
                }

                val extractedVideos = playlistUtils.extractFromHls(
                    playlistUrl = candidateUrl,
                    referer = requestReferer,
                    masterHeaders = hlsHeaders,
                    videoHeaders = hlsHeaders,
                    videoNameGen = { quality ->
                        if (quality.equals("Video", ignoreCase = true)) {
                            title
                        } else {
                            "$title - $quality"
                        }
                    },
                )

                if (extractedVideos.isEmpty()) {
                    listOf(
                        Video(
                            candidateUrl,
                            title,
                            candidateUrl,
                            hlsHeaders,
                        ),
                    )
                } else {
                    extractedVideos
                }
            } catch (_: Exception) {
                val title = listOfNotNull(
                    prefix.trim().takeIf { it.isNotEmpty() },
                    name,
                ).joinToString(" ")

                listOf(
                    Video(
                        candidateUrl,
                        title,
                        candidateUrl,
                        hlsHeaders,
                    ),
                )
            }
        }
    }

    private fun extractId(url: String): String? {
        val fragment = url.substringAfter("#", "")
        if (fragment.isBlank()) return null

        return fragment.substringBefore("&")
            .substringBefore("?")
            .substringBefore("/")
            .trim()
            .takeIf { it.isNotBlank() }
    }

    private fun extractOrigin(url: String): String? {
        val withoutFragment = url.substringBefore("#")

        return try {
            val httpUrl = withoutFragment.toHttpUrl()
            "${httpUrl.scheme}://${httpUrl.host}"
        } catch (_: Exception) {
            null
        }
    }

    private fun buildApiUrl(
        origin: String,
        id: String,
        referrer: String,
        height: Int,
    ): String = try {
        origin.toHttpUrl().newBuilder()
            .addPathSegments("api/v1/video")
            .addQueryParameter("id", id)
            .addQueryParameter("w", "1920")
            .addQueryParameter("h", height.toString())
            .addQueryParameter("r", referrer)
            .build()
            .toString()
    } catch (_: Exception) {
        "${origin.trimEnd('/')}/api/v1/video?id=$id&w=1920&h=$height&r=$referrer"
    }

    private fun decryptIfNeeded(raw: String): String? {
        val trimmed = raw
            .trim()
            .removeSurrounding("\"")
            .trim()

        if (extractStreamUrl(trimmed) != null) {
            return trimmed
        }

        if (trimmed.startsWith("{")) {
            val obj = try {
                json.parseToJsonElement(trimmed) as? JsonObject
            } catch (_: Exception) {
                null
            }

            if (obj != null) {
                val hexField = obj["data"]?.jsonPrimitive?.contentOrNull
                    ?: obj["payload"]?.jsonPrimitive?.contentOrNull
                    ?: obj["result"]?.jsonPrimitive?.contentOrNull

                if (
                    !hexField.isNullOrBlank() &&
                    hexField.matches(HEX_REGEX) &&
                    hexField.length % 2 == 0
                ) {
                    return decryptHex(hexField)
                }

                return trimmed
            }
        }

        val encryptedHex = when {
            trimmed.matches(HEX_REGEX) && trimmed.length % 2 == 0 -> trimmed
            else -> HEX_PAYLOAD_REGEX.find(trimmed)?.value
        } ?: return null

        return decryptHex(encryptedHex)
    }

    private fun decryptHex(hex: String): String? {
        val encrypted = runCatching {
            hex.decodeHex()
        }.getOrNull() ?: return null

        val key = SecretKeySpec(
            KEY.toByteArray(Charsets.UTF_8),
            "AES",
        )

        runCatching {
            val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")

            cipher.init(
                Cipher.DECRYPT_MODE,
                key,
                IvParameterSpec(IV.toByteArray(Charsets.UTF_8)),
            )

            String(
                cipher.doFinal(encrypted),
                Charsets.UTF_8,
            )
        }.getOrNull()
            ?.takeIf { it.isValidPayload() }
            ?.let {
                return it
            }

        if (encrypted.size > 16) {
            runCatching {
                val iv = encrypted.copyOfRange(0, 16)
                val cipherText = encrypted.copyOfRange(16, encrypted.size)

                val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")

                cipher.init(
                    Cipher.DECRYPT_MODE,
                    key,
                    IvParameterSpec(iv),
                )

                String(
                    cipher.doFinal(cipherText),
                    Charsets.UTF_8,
                )
            }.getOrNull()
                ?.takeIf { it.isValidPayload() }
                ?.let {
                    return it
                }
        }

        return null
    }

    private fun String.isValidPayload(): Boolean =
        extractStreamUrl(this) != null ||
            trim().startsWith("{")

    private fun extractStreamUrl(payload: String): String? {
        SOURCE_REGEXES.forEach { regex ->
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

        val normalized = unescapeJsonString(payload)

        return DIRECT_MEDIA_REGEX
            .find(normalized)
            ?.value
            ?.replace("&amp;", "&")
            ?.trim()
    }

    private fun unescapeJsonString(value: String): String = value
        .replace("\\/", "/")
        .replace("\\u0026", "&", ignoreCase = true)
        .replace("\\u002F", "/", ignoreCase = true)
        .replace("\\u003A", ":", ignoreCase = true)
        .replace("\\u003F", "?", ignoreCase = true)
        .replace("\\u003D", "=", ignoreCase = true)
        .replace("\\u0025", "%", ignoreCase = true)
        .replace("\\\"", "\"")
        .replace("\\\\", "\\")

    private fun buildCandidates(
        jsonStr: String,
        embedOrigin: String,
    ): List<String>? {
        val root = try {
            json.parseToJsonElement(jsonStr)
        } catch (_: Exception) {
            return null
        } as? JsonObject ?: return null

        val streamingConfig = root["streamingConfig"].toJsonObject()

        val order = (streamingConfig?.get("order") as? JsonArray)
            ?.mapNotNull {
                (it as? JsonPrimitive)?.contentOrNull
            }
            ?: emptyList()

        val adjustMap = (streamingConfig?.get("adjust") as? JsonObject)
            ?.mapNotNull { entry ->
                val obj = entry.value as? JsonObject
                    ?: return@mapNotNull null

                entry.key to Adjust(
                    disabled = (obj["disabled"] as? JsonPrimitive)
                        ?.booleanOrNull
                        ?: false,
                    domain = (obj["domain"] as? JsonPrimitive)
                        ?.contentOrNull,
                    params = (obj["params"] as? JsonObject)
                        ?.mapValues { p ->
                            (p.value as? JsonPrimitive)
                                ?.contentOrNull
                                ?: ""
                        }
                        ?.filterValues { it.isNotEmpty() }
                        ?: emptyMap(),
                )
            }
            ?.toMap()
            ?: emptyMap()

        val pkObj = (root["pk"] ?: root["PK"]).toJsonObject()

        val pk = pkObj?.let {
            Pk(
                k = (it["k"] as? JsonPrimitive)?.contentOrNull,
                kx = (it["kx"] as? JsonPrimitive)?.contentOrNull,
            )
        }

        val sourceKeys = listOf(
            "cf",
            "cfNative",
            "hlsVideoTiktok",
            "hlsVideoGoogle",
            "source",
            "file",
        )

        val sourceMap = sourceKeys.mapNotNull { key ->
            val value = (root[key] as? JsonPrimitive)
                ?.contentOrNull
                ?.takeIf { it.isNotBlank() }

            if (value != null) {
                key to value
            } else {
                null
            }
        }.toMap()

        val namesInOrder = if (order.isNotEmpty()) {
            order
        } else {
            sourceMap.keys.toList()
        }

        if (namesInOrder.isEmpty() && sourceMap.isEmpty()) {
            val fallback = root.entries.mapNotNull { (key, value) ->
                val source = (value as? JsonPrimitive)
                    ?.contentOrNull
                    ?: return@mapNotNull null

                if (
                    source.startsWith("http") &&
                    (
                        "/hls/" in source ||
                            ".m3u8" in source ||
                            ".mp4" in source ||
                            "/v4/" in source
                        )
                ) {
                    key to source
                } else {
                    null
                }
            }.toMap()

            if (fallback.isEmpty()) {
                return emptyList()
            }

            return buildUrlsFromMap(
                fallback,
                adjustMap,
                pk,
                embedOrigin,
                fallback.keys.toList(),
            )
        }

        return buildUrlsFromMap(
            sourceMap,
            adjustMap,
            pk,
            embedOrigin,
            namesInOrder,
        )
    }

    private fun JsonElement?.toJsonObject(): JsonObject? = when (this) {
        is JsonObject -> this

        is JsonPrimitive -> if (
            isString &&
            content.startsWith("{")
        ) {
            try {
                json.parseToJsonElement(content) as? JsonObject
            } catch (_: Exception) {
                null
            }
        } else {
            null
        }

        else -> null
    }

    private fun buildUrlsFromMap(
        sourceMap: Map<String, String>,
        adjustMap: Map<String, Adjust>,
        pk: Pk?,
        embedOrigin: String,
        order: List<String>,
    ): List<String> {
        val result = mutableListOf<String>()

        for (name in order) {
            val rawUrl = sourceMap[name] ?: continue
            if (rawUrl.isBlank()) continue

            val adjust = adjustMap[name]

            if (adjust?.disabled == true) {
                continue
            }

            var url = rawUrl

            if (
                adjust != null &&
                adjust.params.isNotEmpty()
            ) {
                url = appendParams(
                    url,
                    adjust.params,
                )
            }

            if (
                adjust?.domain != null &&
                "/hls/" in url
            ) {
                url = url.replace(
                    "/hls/",
                    "/hlsmod/${adjust.domain}/",
                )
            }

            if (!url.startsWith("http")) {
                val base = embedOrigin.toHttpUrlOrNull()
                    ?: continue

                url = base.resolve(url)
                    ?.toString()
                    ?: continue
            }

            if (
                "/v4/" in url &&
                pk != null &&
                !pk.k.isNullOrBlank() &&
                !pk.kx.isNullOrBlank()
            ) {
                url = appendParams(
                    url,
                    mapOf(
                        "k" to pk.k!!,
                        "kx" to pk.kx!!,
                    ),
                )
            }

            val fixed = url.toHttpUrlOrNull()
                ?: continue

            result.add(fixed.toString())
        }

        return result.distinct()
    }

    private fun appendParams(
        url: String,
        params: Map<String, String>,
    ): String {
        if (params.isEmpty()) {
            return url
        }

        return try {
            val httpUrl = url.toHttpUrlOrNull()

            if (httpUrl != null) {
                val builder = httpUrl.newBuilder()

                params.forEach { (key, value) ->
                    builder.addQueryParameter(
                        key,
                        value,
                    )
                }

                builder.build().toString()
            } else {
                val separator = if ("?" in url) "&" else "?"

                url + separator + params.entries.joinToString("&") {
                    "${it.key}=${it.value}"
                }
            }
        } catch (_: Exception) {
            val separator = if ("?" in url) "&" else "?"

            url + separator + params.entries.joinToString("&") {
                "${it.key}=${it.value}"
            }
        }
    }

    private data class Adjust(
        val disabled: Boolean = false,
        val domain: String? = null,
        val params: Map<String, String> = emptyMap(),
    )

    private data class Pk(
        val k: String? = null,
        val kx: String? = null,
    )

    companion object {
        private const val KEY = "kiemtienmua911ca"
        private const val IV = "1234567890oiuytr"

        private val HEX_REGEX =
            Regex("^[0-9a-fA-F]+$")

        private val HEX_PAYLOAD_REGEX =
            Regex("[0-9a-fA-F]{64,}")

        private val SOURCE_REGEXES = listOf(
            Regex(
                """["']source["']\s*:\s*["']((?:\\.|[^"'\\])*)["']""",
                RegexOption.IGNORE_CASE,
            ),
            Regex(
                """["']file["']\s*:\s*["']((?:\\.|[^"'\\])*)["']""",
                RegexOption.IGNORE_CASE,
            ),
        )

        private val DIRECT_MEDIA_REGEX = Regex(
            """https?://[^\s"']+?\.(?:m3u8|mp4)(?:\?[^\s"']*)?""",
            RegexOption.IGNORE_CASE,
        )
    }
}
