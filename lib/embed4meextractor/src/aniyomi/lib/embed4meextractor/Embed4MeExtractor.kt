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
        referrer: String = "",
        height: Int = 1080,
    ): List<Video> {
        val id = extractId(url) ?: return emptyList()
        val embedOrigin = extractOrigin(url) ?: return emptyList()
        val requestReferer = referer ?: "$embedOrigin/"

        val apiUrl = buildApiUrl(embedOrigin, id, referrer, height)
        val apiHeaders = headers.newBuilder()
            .set("Referer", requestReferer)
            .set("Origin", embedOrigin)
            .set("Accept", "*/*")
            .build()

        val raw = try {
            client.newCall(GET(apiUrl, apiHeaders)).awaitSuccess().bodyString().trim()
        } catch (_: Exception) {
            return emptyList()
        }
        if (raw.isBlank()) return emptyList()

        val jsonStr = decryptIfNeeded(raw) ?: return emptyList()
        if (jsonStr.isBlank()) return emptyList()

        val candidates = buildCandidates(jsonStr, embedOrigin) ?: return emptyList()
        if (candidates.isEmpty()) return emptyList()

        val videoHeaders = headers.newBuilder()
            .set("Referer", requestReferer)
            .set("Origin", embedOrigin)
            .build()

        val videoNameGen: (String) -> String = { quality ->
            val label = listOfNotNull(
                prefix.trim().takeIf { it.isNotEmpty() },
                name,
            ).joinToString(" ")

            if (quality.equals("Video", ignoreCase = true)) {
                label
            } else {
                "$label - $quality"
            }
        }

        return candidates.parallelCatchingFlatMap { candidateUrl ->
            try {
                if (candidateUrl.substringBefore("?").endsWith(".mp4", ignoreCase = true)) {
                    listOf(
                        Video(
                            candidateUrl,
                            videoNameGen("Video"),
                            candidateUrl,
                            videoHeaders,
                        ),
                    )
                } else {
                    playlistUtils.extractFromHls(
                        candidateUrl,
                        referer = embedOrigin,
                        masterHeaders = videoHeaders,
                        videoHeaders = videoHeaders,
                        videoNameGen = videoNameGen,
                    ).ifEmpty {
                        listOf(
                            Video(
                                candidateUrl,
                                videoNameGen("Video"),
                                candidateUrl,
                                videoHeaders,
                            ),
                        )
                    }
                }
            } catch (_: Exception) {
                emptyList()
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
        val httpUrl = url.substringBefore("#").toHttpUrlOrNull()
            ?: return null

        return "${httpUrl.scheme}://${httpUrl.host}"
    }

    private fun buildApiUrl(
        origin: String,
        id: String,
        referrer: String,
        height: Int,
    ): String = origin.toHttpUrl().newBuilder()
        .addPathSegments("api/v1/video")
        .addQueryParameter("id", id)
        .addQueryParameter("w", "1920")
        .addQueryParameter("h", height.toString())
        .addQueryParameter("r", referrer)
        .build()
        .toString()

    private fun decryptIfNeeded(raw: String): String? {
        val trimmed = raw.trim().removeSurrounding("\"")

        if (trimmed.startsWith("{")) return trimmed

        return try {
            if (trimmed.matches(Regex("^[0-9a-fA-F]+$")) && trimmed.length % 2 == 0) {
                decryptHex(trimmed)
            } else {
                val element = try {
                    json.parseToJsonElement(trimmed)
                } catch (_: Exception) {
                    null
                }

                val obj = element as? JsonObject
                val hexField = obj?.get("data")?.jsonPrimitive?.contentOrNull
                    ?: obj?.get("payload")?.jsonPrimitive?.contentOrNull
                    ?: obj?.get("result")?.jsonPrimitive?.contentOrNull

                if (!hexField.isNullOrBlank() && hexField.matches(Regex("^[0-9a-fA-F]+$"))) {
                    decryptHex(hexField)
                } else {
                    null
                }
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun decryptHex(hex: String): String? {
        val encrypted = hex.decodeHex()
        val keySpec = SecretKeySpec(KEY.toByteArray(Charsets.UTF_8), "AES")

        return IVS.firstNotNullOfOrNull { iv ->
            runCatching {
                val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
                val ivSpec = IvParameterSpec(iv.toByteArray(Charsets.UTF_8))

                cipher.init(Cipher.DECRYPT_MODE, keySpec, ivSpec)

                String(cipher.doFinal(encrypted), Charsets.UTF_8)
            }.getOrNull()
        }
    }

    private fun buildCandidates(jsonStr: String, embedOrigin: String): List<String>? {
        val root = try {
            json.parseToJsonElement(jsonStr)
        } catch (_: Exception) {
            return null
        } as? JsonObject ?: return null

        val streamingConfig = root["streamingConfig"].toJsonObject()
        val order = (streamingConfig?.get("order") as? JsonArray)
            ?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
            ?: emptyList()

        val adjustMap = (streamingConfig?.get("adjust") as? JsonObject)
            ?.mapNotNull { entry ->
                val obj = entry.value as? JsonObject ?: return@mapNotNull null

                entry.key to Adjust(
                    disabled = (obj["disabled"] as? JsonPrimitive)?.booleanOrNull ?: false,
                    domain = (obj["domain"] as? JsonPrimitive)?.contentOrNull,
                    params = (obj["params"] as? JsonObject)
                        ?.mapValues { p -> (p.value as? JsonPrimitive)?.contentOrNull ?: "" }
                        ?.filterValues { it.isNotEmpty() }
                        ?: emptyMap(),
                )
            }?.toMap()
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
                val source = (value as? JsonPrimitive)?.contentOrNull
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

            if (fallback.isEmpty()) return emptyList()

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

        is JsonPrimitive -> if (isString && content.startsWith("{")) {
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
            if (adjust?.disabled == true) continue

            var url = rawUrl.replace("\\/", "/")

            if (adjust != null && adjust.params.isNotEmpty()) {
                url = appendParams(url, adjust.params)
            }

            if (adjust?.domain != null && "/hls/" in url) {
                url = url.replace(
                    "/hls/",
                    "/hlsmod/${adjust.domain}/",
                )
            }

            if (!url.startsWith("http")) {
                val base = embedOrigin.toHttpUrlOrNull()
                    ?: continue

                url = base.resolve(url)?.toString()
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

    private fun appendParams(url: String, params: Map<String, String>): String {
        if (params.isEmpty()) return url

        return try {
            val httpUrl = url.toHttpUrlOrNull()

            if (httpUrl != null) {
                val builder = httpUrl.newBuilder()

                params.forEach { (key, value) ->
                    builder.addQueryParameter(key, value)
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

        private val IVS = arrayOf(
            "1234567890oiuytr",
            "0123456789abcdef",
        )
    }
}
