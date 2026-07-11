package com.newroads.app.osm

import java.io.IOException
import java.io.InterruptedIOException
import java.net.SocketTimeoutException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.Call
import okhttp3.Callback
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.json.JSONException
import org.json.JSONObject

/**
 * Fetches walkable ways (streets, paths, footways...) inside a bounding box around a point
 * from a public Overpass API instance.
 *
 * Public Overpass instances are shared and frequently overloaded, so rather than trying them one
 * after another (which stacks up timeouts — a hung primary could cost ~30s before we even try a
 * backup), all mirrors are queried *in parallel* and the first valid response wins. The remaining
 * in-flight requests are then cancelled.
 */
class OverpassClient {

    private val client = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(PER_ATTEMPT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .callTimeout(PER_ATTEMPT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .writeTimeout(15, TimeUnit.SECONDS)
        .build()

    /**
     * [radiusMeters] is roughly how far from the center point to fetch road data.
     * [onStatus] is invoked with a short status string while the request is in flight.
     */
    suspend fun fetchGraphJson(
        centerLat: Double,
        centerLon: Double,
        radiusMeters: Double,
        onStatus: (String) -> Unit = {}
    ): String {
        val body = FormBody.Builder().add("data", buildQuery(centerLat, centerLon, radiusMeters)).build()
        onStatus("Загружаем карту дорог поблизости...")
        return raceEndpoints(body)
    }

    /**
     * "Hedged" requests: the primary endpoint is queried first; a backup is only added if no result
     * has arrived within [HEDGE_DELAY_MS] (or as soon as the current requests all fail). This keeps
     * a healthy primary on the user's full bandwidth — important because the road graph can be many
     * megabytes, and firing every mirror at once would make them fight over one connection and all
     * time out — while still failing over quickly when the primary is slow or down.
     */
    private suspend fun raceEndpoints(body: FormBody): String = coroutineScope {
        val results = Channel<Result<String>>(Channel.UNLIMITED)
        val jobs = ArrayList<Job>()
        var launched = 0

        fun launchNext() {
            if (launched >= ENDPOINTS.size) return
            val endpoint = ENDPOINTS[launched]
            launched++
            jobs += launch {
                val outcome = runCatching {
                    val json = requestJson(endpoint, body)
                    validate(json)
                    json
                }
                // A cancelled request lost the race; don't report it as a failure.
                if (outcome.exceptionOrNull() !is CancellationException) {
                    results.trySend(outcome)
                }
            }
        }

        try {
            launchNext()
            var lastError: Throwable? = null
            var emptyResponse: String? = null
            var received = 0
            while (received < ENDPOINTS.size) {
                val moreToLaunch = launched < ENDPOINTS.size
                // Wait for a result, but if none arrives within the hedge delay, add a backup.
                val result = if (moreToLaunch) {
                    withTimeoutOrNull(HEDGE_DELAY_MS) { results.receive() }
                } else {
                    results.receive()
                }
                if (result == null) {
                    launchNext()
                    continue
                }
                received++
                val json = result.getOrNull()
                if (json != null) {
                    // A response with actual roads wins outright. An empty-but-valid response (e.g.
                    // from a mirror holding only a regional extract) must NOT beat a slower full
                    // response, so it's only used as a last resort if every endpoint comes back empty.
                    if (hasRoads(json)) return@coroutineScope json
                    emptyResponse = json
                } else {
                    lastError = result.exceptionOrNull()
                }
                // All in-flight requests have reported; don't wait out the hedge delay to try more.
                if (received >= launched) launchNext()
            }
            // Every endpoint answered but none had roads: fall back to an empty response (the
            // caller reports "no roads here") rather than a network error, if we got one at all.
            emptyResponse?.let { return@coroutineScope it }
            throw OverpassException(
                if (lastError is SocketTimeoutException || lastError is InterruptedIOException) {
                    "Все серверы карт долго отвечают. Попробуйте ещё раз или уменьшите цель по шагам."
                } else {
                    "Не удалось загрузить карту дорог: ${lastError?.message}"
                }
            )
        } finally {
            jobs.forEach { it.cancel() }
        }
    }

    /** Cheap check for whether a validated response actually contains any ways (skel geom ways carry "nodes"). */
    internal fun hasRoads(json: String): Boolean = json.contains("\"nodes\"")

    /** Performs one Overpass request, cancelling the underlying HTTP call if the coroutine is cancelled. */
    private suspend fun requestJson(endpoint: String, body: FormBody): String =
        suspendCancellableCoroutine { cont ->
            val request = Request.Builder()
                .url(endpoint)
                // Overpass mirrors reject requests with a generic/missing User-Agent (HTTP 406);
                // they expect clients to identify themselves.
                .header("User-Agent", "NewRoadsApp/1.0 (+https://github.com/mrrled/new-roads-app)")
                .header("Accept", "*/*")
                .post(body)
                .build()

            val call = client.newCall(request)
            cont.invokeOnCancellation { call.cancel() }

            call.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    if (cont.isActive) cont.resumeWithException(e)
                }

                override fun onResponse(call: Call, response: Response) {
                    try {
                        response.use {
                            if (!it.isSuccessful) {
                                val detail = it.body?.string()?.take(200)
                                throw OverpassException(
                                    "Overpass request failed: ${it.code}${detail?.let { d -> " - $d" } ?: ""}"
                                )
                            }
                            val json = it.body?.string() ?: throw OverpassException("Empty Overpass response")
                            if (cont.isActive) cont.resume(json)
                        }
                    } catch (e: Exception) {
                        if (cont.isActive) cont.resumeWithException(e)
                    }
                }
            })
        }

    /**
     * Overpass sometimes answers with a normal HTTP 200 even when the query failed server-side
     * (e.g. it ran out of its own [timeout:...] budget under load) — the body then carries a
     * "remark" instead of an "elements" array. Treating that as success would silently cache an
     * empty road graph for this area for days, so it's rejected here to let another mirror win.
     */
    private fun validate(json: String) {
        val root = try {
            JSONObject(json)
        } catch (e: JSONException) {
            throw OverpassException("Overpass returned invalid JSON")
        }
        if (root.has("remark")) {
            throw OverpassException("Overpass error: ${root.optString("remark")}")
        }
        if (!root.has("elements")) {
            throw OverpassException("Overpass response has no data (likely a server-side timeout)")
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
        private const val QUERY_TIMEOUT_SECONDS = 25L
        private const val PER_ATTEMPT_TIMEOUT_SECONDS = 35L
        private const val HEDGE_DELAY_MS = 3000L

        // Only global (whole-planet) instances. A regional mirror (e.g. overpass.openstreetmap.ru,
        // which mainly holds Russia/CIS data) would return an empty result for other areas, which
        // used to wrongly win the parallel race and make the app think there were no roads.
        private val ENDPOINTS = listOf(
            "https://overpass-api.de/api/interpreter",
            "https://overpass.kumi.systems/api/interpreter",
            "https://overpass.osm.ch/api/interpreter"
        )
    }
}

class OverpassException(message: String) : Exception(message)
