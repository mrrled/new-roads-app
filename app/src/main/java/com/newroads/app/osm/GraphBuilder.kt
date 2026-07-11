package com.newroads.app.osm

import org.json.JSONObject

/** A single OSM way as returned by Overpass "out skel geom": node ids plus matching geometry. */
data class RawWay(
    val wayId: Long,
    val nodeIds: List<Long>,
    val points: List<GeoPt>
)

/** Parses an Overpass "out skel geom" JSON response into a [RoadGraph]. */
object GraphBuilder {

    private const val CLIP_BUFFER_MULTIPLIER = 1.6

    fun build(root: JSONObject, centerLat: Double, centerLon: Double, requestedRadiusMeters: Double): RoadGraph =
        buildGraph(parseWays(root), centerLat, centerLon, requestedRadiusMeters)

    /** Extracts the well-formed ways from an Overpass response, ignoring anything that isn't a usable way. */
    fun parseWays(root: JSONObject): List<RawWay> {
        val elements = root.optJSONArray("elements") ?: return emptyList()
        val ways = ArrayList<RawWay>(elements.length())
        for (i in 0 until elements.length()) {
            val element = elements.getJSONObject(i)
            if (element.optString("type") != "way") continue

            val nodeIdsArray = element.optJSONArray("nodes") ?: continue
            val geometry = element.optJSONArray("geometry") ?: continue
            if (nodeIdsArray.length() < 2 || geometry.length() != nodeIdsArray.length()) continue

            val points = (0 until geometry.length()).map { idx ->
                val g = geometry.getJSONObject(idx)
                GeoPt(g.getDouble("lat"), g.getDouble("lon"))
            }
            val nodeIds = (0 until nodeIdsArray.length()).map { nodeIdsArray.getLong(it) }
            ways.add(RawWay(element.optLong("id"), nodeIds, points))
        }
        return ways
    }

    /**
     * Builds a [RoadGraph] from already-parsed [ways].
     *
     * Overpass returns each way in full even if only a small part of it falls within the requested
     * search radius (e.g. a long road that just clips the edge of the query area). Treating such a
     * way as a single edge from its first to its last node can produce an edge that reaches far
     * outside [centerLat]/[centerLon], with no other nearby edges to connect back to it. To keep the
     * graph roughly contained within the area we actually asked for, each way is clipped down to its
     * longest contiguous run of points within [requestedRadiusMeters] * [CLIP_BUFFER_MULTIPLIER] of
     * the center (plus one point of margin on each side). The buffer is deliberately generous —
     * Overpass's "around" filter matches a way if its line passes within radius even when no vertex
     * does, so clipping too tightly can end up dropping ways (and, in a small-radius query, leave the
     * graph empty). If a way has no vertex within the buffer at all, the whole way is kept.
     */
    fun buildGraph(
        ways: List<RawWay>,
        centerLat: Double,
        centerLon: Double,
        requestedRadiusMeters: Double
    ): RoadGraph {
        val maxDistanceMeters = requestedRadiusMeters * CLIP_BUFFER_MULTIPLIER
        val nodes = HashMap<Long, GraphNode>()
        val edgesByNode = HashMap<Long, MutableList<GraphEdge>>()

        for (way in ways) {
            val rawPoints = way.points
            if (rawPoints.size < 2 || way.nodeIds.size != rawPoints.size) continue

            val range = longestRunWithinRadius(rawPoints, centerLat, centerLon, maxDistanceMeters)
                ?: rawPoints.indices
            val start = (range.first - 1).coerceAtLeast(0)
            val end = (range.last + 1).coerceAtMost(rawPoints.lastIndex)

            val points = rawPoints.subList(start, end + 1)
            val ids = way.nodeIds.subList(start, end + 1)
            if (points.size < 2) continue

            val fromId = ids.first()
            val toId = ids.last()
            if (fromId == toId) continue

            nodes.getOrPut(fromId) { GraphNode(fromId, points.first().lat, points.first().lon) }
            nodes.getOrPut(toId) { GraphNode(toId, points.last().lat, points.last().lon) }

            var length = 0.0
            for (p in 1 until points.size) {
                length += haversineMeters(points[p - 1].lat, points[p - 1].lon, points[p].lat, points[p].lon)
            }
            if (length <= 0.0) continue

            val edge = GraphEdge(way.wayId, fromId, toId, points, length)
            edgesByNode.getOrPut(fromId) { mutableListOf() }.add(edge)
            edgesByNode.getOrPut(toId) { mutableListOf() }.add(edge)
        }

        return RoadGraph(nodes, edgesByNode)
    }

    /** Returns the index range (inclusive) of the longest contiguous run of points within radius. */
    private fun longestRunWithinRadius(
        points: List<GeoPt>,
        centerLat: Double,
        centerLon: Double,
        maxDistanceMeters: Double
    ): IntRange? {
        var bestStart = -1
        var bestLength = 0
        var i = 0
        while (i < points.size) {
            val within = haversineMeters(points[i].lat, points[i].lon, centerLat, centerLon) <= maxDistanceMeters
            if (within) {
                val runStart = i
                while (i < points.size &&
                    haversineMeters(points[i].lat, points[i].lon, centerLat, centerLon) <= maxDistanceMeters
                ) {
                    i++
                }
                val runLength = i - runStart
                if (runLength > bestLength) {
                    bestLength = runLength
                    bestStart = runStart
                }
            } else {
                i++
            }
        }
        return if (bestStart == -1) null else bestStart until (bestStart + bestLength)
    }
}
