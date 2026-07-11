package com.newroads.app.routing

import com.newroads.app.osm.GeoPt
import com.newroads.app.osm.GraphEdge
import com.newroads.app.osm.RoadGraph
import com.newroads.app.osm.haversineMeters
import com.newroads.app.osm.segmentKey
import kotlin.math.cos
import kotlin.math.floor

/**
 * Matches a recorded GPS track against the road graph to decide which road segments the user
 * actually walked — instead of blindly crediting the whole planned route.
 *
 * An edge counts as walked when at least [COVERAGE_FRACTION] of points sampled every
 * [SAMPLE_STEP_METERS] along its geometry have a GPS fix within [NEAR_METERS]. The threshold is
 * deliberately forgiving of ordinary GPS noise (urban multipath easily wobbles 10-20m) while still
 * rejecting roads the user merely crossed or passed by one end of.
 */
object MapMatcher {

    private const val NEAR_METERS = 25.0
    private const val SAMPLE_STEP_METERS = 15.0
    private const val COVERAGE_FRACTION = 0.6

    fun matchWalkedEdges(track: List<GeoPt>, graph: RoadGraph): List<GraphEdge> {
        if (track.size < 2) return emptyList()
        val index = TrackIndex(track, NEAR_METERS)

        val walked = ArrayList<GraphEdge>()
        val seen = HashSet<String>() // every edge is listed under both endpoints — visit it once
        for (edges in graph.edgesByNode.values) {
            for (edge in edges) {
                if (!seen.add(segmentKey(edge.wayId, edge.fromNode, edge.toNode))) continue
                if (isCovered(edge, index)) walked.add(edge)
            }
        }
        return walked
    }

    private fun isCovered(edge: GraphEdge, index: TrackIndex): Boolean {
        val samples = samplePolyline(edge.points)
        if (samples.isEmpty()) return false
        val near = samples.count { index.hasPointNear(it) }
        return near.toDouble() / samples.size >= COVERAGE_FRACTION
    }

    /** Points every [SAMPLE_STEP_METERS] along the polyline, always including its two ends. */
    private fun samplePolyline(points: List<GeoPt>): List<GeoPt> {
        if (points.isEmpty()) return emptyList()
        val samples = mutableListOf(points.first())
        var sinceLastSample = 0.0
        for (i in 1 until points.size) {
            val a = points[i - 1]
            val b = points[i]
            val segLen = haversineMeters(a.lat, a.lon, b.lat, b.lon)
            if (segLen <= 0.0) continue
            var d = SAMPLE_STEP_METERS - sinceLastSample
            while (d < segLen) {
                val t = d / segLen
                samples.add(GeoPt(a.lat + (b.lat - a.lat) * t, a.lon + (b.lon - a.lon) * t))
                d += SAMPLE_STEP_METERS
            }
            sinceLastSample = (sinceLastSample + segLen) % SAMPLE_STEP_METERS
        }
        samples.add(points.last())
        return samples
    }

    /** Spatial hash of the track's GPS fixes (cell size ≈ search radius) for cheap nearby lookups. */
    private class TrackIndex(track: List<GeoPt>, private val radiusMeters: Double) {
        private val cellLat = radiusMeters / METERS_PER_DEGREE_LAT
        private val cellLon: Double
        private val cells = HashMap<Pair<Long, Long>, MutableList<GeoPt>>()

        init {
            val cosLat = cos(Math.toRadians(track.first().lat)).coerceAtLeast(0.01)
            cellLon = radiusMeters / (METERS_PER_DEGREE_LAT * cosLat)
            for (p in track) {
                cells.getOrPut(cellOf(p)) { mutableListOf() }.add(p)
            }
        }

        fun hasPointNear(p: GeoPt): Boolean {
            val (row, col) = cellOf(p)
            for (dr in -1..1) {
                for (dc in -1..1) {
                    val bucket = cells[(row + dr) to (col + dc)] ?: continue
                    if (bucket.any { haversineMeters(it.lat, it.lon, p.lat, p.lon) <= radiusMeters }) {
                        return true
                    }
                }
            }
            return false
        }

        private fun cellOf(p: GeoPt): Pair<Long, Long> =
            floor(p.lat / cellLat).toLong() to floor(p.lon / cellLon).toLong()

        private companion object {
            const val METERS_PER_DEGREE_LAT = 111_000.0
        }
    }
}
