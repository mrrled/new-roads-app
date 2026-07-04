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
            onStatus("Используем сохранённую карту дорог поблизости...")
            return GraphBuilder.build(JSONObject(cached.rawJson))
        }

        val rawJson = overpassClient.fetchGraphJson(centerLat, centerLon, radiusMeters, onStatus)

        cacheDao.deleteOlderThan(cutoff)
        cacheDao.insert(
            RoadGraphCacheEntity(
                centerLatRounded = latRounded,
                centerLonRounded = lonRounded,
                radiusBucketMeters = radiusBucket,
                fetchedAt = System.currentTimeMillis(),
                rawJson = rawJson
            )
        )

        return GraphBuilder.build(JSONObject(rawJson))
    }

    // ~111m per 0.001 degree of latitude; close enough to bucket "the same spot" for caching.
    private fun roundCoord(value: Double): Double = round(value * 1000.0) / 1000.0

    companion object {
        private const val BUCKET_METERS = 500
        private val CACHE_TTL_MILLIS = TimeUnit.DAYS.toMillis(7)
    }
}
