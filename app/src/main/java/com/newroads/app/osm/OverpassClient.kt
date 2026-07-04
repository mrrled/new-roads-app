package com.newroads.app.osm

import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.IOException
import java.net.SocketTimeoutException
import java.util.concurrent.TimeUnit

/**
 * Fetches walkable ways (streets, paths, footways...) inside a bounding box around a point
 * from a public Overpass API instance. The main instance (overpass-api.de) is a shared,
 * often-overloaded resource, so several mirrors are tried in turn before giving up.
 */
class OverpassClient {

    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(PER_ATTEMPT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .writeTimeout(15, TimeUnit.SECONDS)
        .build()

    /**
     * [radiusMeters] is roughly how far from the center point to fetch road data.
     * [onAttempt] is invoked with a short status string before each mirror attempt.
     */
    fun fetchGraphJson(
        centerLat: Double,
        centerLon: Double,
        radiusMeters: Double,
        onAttempt: (String) -> Unit = {}
    ): String {
        val body = FormBody.Builder().add("data", buildQuery(centerLat, centerLon, radiusMeters)).build()

        var lastError: Exception? = null
        ENDPOINTS.forEachIndexed { index, endpoint ->
            onAttempt(
                if (index == 0) {
                    "Загружаем карту дорог поблизости..."
                } else {
                    "Пробуем резервный сервер карт (попытка ${index + 1} из ${ENDPOINTS.size})..."
                }
            )
            try {
                return execute(endpoint, body)
            } catch (e: IOException) {
                lastError = e
            } catch (e: OverpassException) {
                lastError = e
            }
        }

        throw OverpassException(
            if (lastError is SocketTimeoutException) {
                "Все серверы карт долго отвечают. Попробуйте ещё раз или уменьшите цель по шагам."
            } else {
                "Не удалось загрузить карту дорог: ${lastError?.message}"
            }
        )
    }

    fun fetchGraph(centerLat: Double, centerLon: Double, radiusMeters: Double): RoadGraph =
        GraphBuilder.build(JSONObject(fetchGraphJson(centerLat, centerLon, radiusMeters)))

    private fun execute(endpoint: String, body: FormBody): String {
        val request = Request.Builder()
            .url(endpoint)
            // Overpass mirrors reject requests with a generic/missing User-Agent (HTTP 406);
            // they expect clients to identify themselves.
            .header("User-Agent", "NewRoadsApp/1.0 (+https://github.com/mrrled/new-roads-app)")
            .header("Accept", "*/*")
            .post(body)
            .build()

        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                val detail = response.body?.string()?.take(300)
                throw OverpassException("Overpass request failed: ${response.code}${detail?.let { " - $it" } ?: ""}")
            }
            return response.body?.string() ?: throw OverpassException("Empty Overpass response")
        }
    }

    private fun buildQuery(lat: Double, lon: Double, radiusMeters: Double): String {
        val highways = "footway|path|residential|living_street|pedestrian|track|unclassified|" +
            "tertiary|secondary|primary|service|cycleway|steps"
        return """
            [out:json][timeout:$QUERY_TIMEOUT_SECONDS];
            way(around:$radiusMeters,$lat,$lon)["highway"~"^($highways)$"]["area"!~"yes"];
            out skel geom;
        """.trimIndent()
    }

    companion object {
        private const val QUERY_TIMEOUT_SECONDS = 20L
        private const val PER_ATTEMPT_TIMEOUT_SECONDS = 28L

        private val ENDPOINTS = listOf(
            "https://overpass-api.de/api/interpreter",
            "https://overpass.kumi.systems/api/interpreter",
            "https://overpass.openstreetmap.ru/api/interpreter",
            "https://overpass.osm.ch/api/interpreter"
        )
    }
}

class OverpassException(message: String) : Exception(message)
