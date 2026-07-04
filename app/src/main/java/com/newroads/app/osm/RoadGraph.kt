package com.newroads.app.osm

import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

data class GeoPt(val lat: Double, val lon: Double)

data class GraphNode(val id: Long, val lat: Double, val lon: Double)

/** An edge represents a whole OSM way between its first and last node. */
data class GraphEdge(
    val wayId: Long,
    val fromNode: Long,
    val toNode: Long,
    val points: List<GeoPt>,
    val lengthMeters: Double
)

class RoadGraph(
    val nodes: Map<Long, GraphNode>,
    val edgesByNode: Map<Long, List<GraphEdge>>
) {
    fun nearestNode(lat: Double, lon: Double): GraphNode? =
        nodes.values.minByOrNull { haversineMeters(it.lat, it.lon, lat, lon) }

    val isEmpty: Boolean get() = nodes.isEmpty()
}

fun otherEnd(edge: GraphEdge, fromNodeId: Long): Long =
    if (edge.fromNode == fromNodeId) edge.toNode else edge.fromNode

/** Returns the edge's geometry oriented so it starts at [fromNodeId]. */
fun orientedPoints(edge: GraphEdge, fromNodeId: Long): List<GeoPt> =
    if (edge.fromNode == fromNodeId) edge.points else edge.points.asReversed()

fun haversineMeters(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
    val r = 6371000.0
    val dLat = Math.toRadians(lat2 - lat1)
    val dLon = Math.toRadians(lon2 - lon1)
    val a = sin(dLat / 2).pow(2) +
        cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) * sin(dLon / 2).pow(2)
    val c = 2 * atan2(sqrt(a), sqrt(1 - a))
    return r * c
}
