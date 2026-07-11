package com.newroads.app.data

import com.newroads.app.data.db.dao.RoadGraphCacheDao
import com.newroads.app.data.db.entity.RoadGraphCacheEntity
import com.newroads.app.osm.GraphBuilder
import com.newroads.app.osm.OverpassClient
import com.newroads.app.osm.RoadGraph
import java.util.concurrent.TimeUnit
import kotlin.math.ceil
import kotlin.math.round
import org.json.JSONObject

/**
 * Wraps [OverpassClient] with a local cache: road networks rarely change, so a graph fetched
 * for roughly the same area recently is reused instead of hitting the network again.
 */
class RoadGraphRepository(
    private val cacheDao: RoadGraphCacheDao,
    private val overpassClient: OverpassClient = OverpassClient()
) {

    suspend fun getGraph(
        centerLat: Double,
        centerLon: Double,
        radiusMeters: Double,
        onStatus: (String) -> Unit = {}
    ): RoadGraph {
        val latRounded = roundCoord(centerLat)
        val lonRounded = roundCoord(centerLon)
        val radiusBucket = (ceil(radiusMeters / BUCKET_METERS).toInt() * BUCKET_METERS)
            .coerceAtLeast(BUCKET_METERS)
        val cutoff = System.currentTimeMillis() - CACHE_TTL_MILLIS

        cacheDao.findFresh(latRounded, lonRounded, radiusBucket, cutoff)?.let { cached ->
            val cachedGraph = GraphBuilder.build(
                JSONObject(cached.rawJson),
                centerLat,
                centerLon,
                cached.radiusBucketMeters.toDouble()
            )
            // A cached entry can be an empty graph from an earlier bad/incomplete response.
            // Don't trust it for a full week — fall through to a fresh fetch instead.
            if (!cachedGraph.isEmpty) {
                onStatus("Используем сохранённую карту дорог поблизости...")
                return cachedGraph
            }
        }

        val rawJson = overpassClient.fetchGraphJson(centerLat, centerLon, radiusMeters, onStatus)

        cacheDao.deleteOlderThan(cutoff)
        // Android's SQLite CursorWindow caps a single read row at ~2 MB, so caching a very large
        // response (a big radius over a dense city can be 10+ MB) would only blow up later when we
        // try to read it back. Skip caching those — they're re-fetched each time, but at least the
        // route still generates.
        if (rawJson.length <= MAX_CACHEABLE_CHARS) {
            cacheDao.insert(
                RoadGraphCacheEntity(
                    centerLatRounded = latRounded,
                    centerLonRounded = lonRounded,
                    radiusBucketMeters = radiusBucket,
                    fetchedAt = System.currentTimeMillis(),
                    rawJson = rawJson
                )
            )
        }

        return GraphBuilder.build(JSONObject(rawJson), centerLat, centerLon, radiusBucket.toDouble())
    }

    // ~111m per 0.001 degree of latitude; close enough to bucket "the same spot" for caching.
    private fun roundCoord(value: Double): Double = round(value * 1000.0) / 1000.0

    companion object {
        private const val BUCKET_METERS = 500
        private val CACHE_TTL_MILLIS = TimeUnit.DAYS.toMillis(7)
        // Stay well under the ~2 MB SQLite CursorWindow read limit.
        private const val MAX_CACHEABLE_CHARS = 1_500_000
    }
}
