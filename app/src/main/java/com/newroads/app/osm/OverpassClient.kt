package com.newroads.app.osm

import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.net.SocketTimeoutException
import java.util.concurrent.TimeUnit

/**
 * Fetches walkable ways (streets, paths, footways...) inside a bounding box
 * around a point from the public Overpass API, and builds a [RoadGraph] from them.
 */
class OverpassClient {

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(QUERY_TIMEOUT_SECONDS + 15L, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()

    /** [radiusMeters] is roughly how far from the center point to fetch road data. */
    fun fetchGraph(centerLat: Double, centerLon: Double, radiusMeters: Double): RoadGraph {
        val query = buildQuery(centerLat, centerLon, radiusMeters)
        val body = FormBody.Builder().add("data", query).build()
        val request = Request.Builder()
            .url("https://overpass-api.de/api/interpreter")
            // Overpass API rejects requests with a generic/missing User-Agent (HTTP 406);
            // it expects clients to identify themselves.
            .header("User-Agent", "NewRoadsApp/1.0 (+https://github.com/mrrled/new-roads-app)")
            .header("Accept", "*/*")
            .post(body)
            .build()

        try {
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    val detail = response.body?.string()?.take(300)
                    throw OverpassException("Overpass request failed: ${response.code}${detail?.let { " - $it" } ?: ""}")
                }
                val json = response.body?.string() ?: throw OverpassException("Empty Overpass response")
                return GraphBuilder.build(JSONObject(json))
            }
        } catch (e: SocketTimeoutException) {
            throw OverpassException(
                "Сервер карт долго отвечает (радиус поиска ${radiusMeters.toInt()} м). " +
                    "Попробуйте ещё раз или уменьшите цель по шагам."
            )
        }
    }

    private fun buildQuery(lat: Double, lon: Double, radiusMeters: Double): String {
        val highways = "footway|path|residential|living_street|pedestrian|track|unclassified|" +
            "tertiary|secondary|primary|service|cycleway|steps"
        return """
            [out:json][timeout:$QUERY_TIMEOUT_SECONDS];
            way(around:$radiusMeters,$lat,$lon)["highway"~"^($highways)$"]["area"!~"yes"];
            out body geom;
        """.trimIndent()
    }

    companion object {
        private const val QUERY_TIMEOUT_SECONDS = 45L
    }
}

class OverpassException(message: String) : Exception(message)
