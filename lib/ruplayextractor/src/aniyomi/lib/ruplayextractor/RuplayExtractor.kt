package aniyomi.lib.ruplayextractor

import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.network.GET
import okhttp3.Headers
import okhttp3.OkHttpClient

/**
 * Extracts video streams from Ruplay embeds.
 */
class RuplayExtractor(private val client: OkHttpClient) {
    /**
     * Returns the available video streams from the given embed URL.
     */
    fun videosFromUrl(url: String, headers: Headers): List<Video> = client.newCall(GET(url, headers)).execute().use { response ->
        response.body.string()
            .substringAfter("Playerjs({")
            .substringAfter("file:\"")
            .substringBefore("\"")
            .split(",")
            .map { file ->
                val videoUrl = file.substringAfter("]")
                val quality = file.substringAfter("[", "")
                    .substringBefore("]")
                    .ifEmpty { "Default" }

                Triple(
                    videoUrl,
                    quality,
                    quality.filter { it.isDigit() }.toIntOrNull() ?: 0,
                )
            }
            .sortedByDescending { it.third }
            .map { (videoUrl, quality) ->
                val videoHeaders = headers.newBuilder()
                    .set("Referer", videoUrl)
                    .build()

                Video(
                    videoUrl,
                    "Ruplay - $quality",
                    videoUrl,
                    videoHeaders,
                )
            }
    }
}
