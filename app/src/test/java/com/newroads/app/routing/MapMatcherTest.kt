package com.newroads.app.routing

import com.newroads.app.osm.GeoPt
import com.newroads.app.osm.GraphEdge
import com.newroads.app.osm.GraphNode
import com.newroads.app.osm.RoadGraph
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MapMatcherTest {

    private val lat = 55.0
    private val lon = 37.0
    private val dLat = 0.001 // ~111m of latitude

    /** Builds a graph out of straight two-point edges. */
    private fun graphOf(vararg edges: GraphEdge): RoadGraph {
        val nodes = HashMap<Long, GraphNode>()
        val byNode = HashMap<Long, MutableList<GraphEdge>>()
        for (e in edges) {
            nodes[e.fromNode] = GraphNode(e.fromNode, e.points.first().lat, e.points.first().lon)
            nodes[e.toNode] = GraphNode(e.toNode, e.points.last().lat, e.points.last().lon)
            byNode.getOrPut(e.fromNode) { mutableListOf() }.add(e)
            byNode.getOrPut(e.toNode) { mutableListOf() }.add(e)
        }
        return RoadGraph(nodes, byNode)
    }

    private fun edge(id: Long, from: Long, to: Long, a: GeoPt, b: GeoPt): GraphEdge {
        val len = com.newroads.app.osm.haversineMeters(a.lat, a.lon, b.lat, b.lon)
        return GraphEdge(id, from, to, listOf(a, b), len)
    }

    /** GPS fixes every ~step meters along the segment a->b, offset east by [offsetMeters]. */
    private fun trackAlong(a: GeoPt, b: GeoPt, stepMeters: Double = 10.0, offsetMeters: Double = 8.0): List<GeoPt> {
        val total = com.newroads.app.osm.haversineMeters(a.lat, a.lon, b.lat, b.lon)
        val n = (total / stepMeters).toInt().coerceAtLeast(1)
        val lonOffset = offsetMeters / (111_000.0 * kotlin.math.cos(Math.toRadians(a.lat)))
        return (0..n).map { i ->
            val t = i.toDouble() / n
            GeoPt(a.lat + (b.lat - a.lat) * t, a.lon + (b.lon - a.lon) * t + lonOffset)
        }
    }

    @Test
    fun edgeWalkedAlong_isMatched_despiteGpsOffset() {
        val a = GeoPt(lat, lon)
        val b = GeoPt(lat + 2 * dLat, lon) // ~222m street
        val e = edge(1, 1, 2, a, b)
        val walked = MapMatcher.matchWalkedEdges(trackAlong(a, b), graphOf(e))

        assertEquals(1, walked.size)
        assertEquals(1L, walked.first().wayId)
    }

    @Test
    fun distantEdge_isNotMatched() {
        val a = GeoPt(lat, lon)
        val b = GeoPt(lat + 2 * dLat, lon)
        val farA = GeoPt(lat, lon + 0.005) // ~320m east of the track
        val farB = GeoPt(lat + 2 * dLat, lon + 0.005)
        val near = edge(1, 1, 2, a, b)
        val far = edge(2, 3, 4, farA, farB)

        val walked = MapMatcher.matchWalkedEdges(trackAlong(a, b), graphOf(near, far))

        assertEquals(listOf(1L), walked.map { it.wayId })
    }

    @Test
    fun edgeOnlyCrossedAtOneEnd_isNotMatched() {
        // The user walks along street A; street B starts at A's end and runs east ~220m.
        val a = GeoPt(lat, lon)
        val b = GeoPt(lat + 2 * dLat, lon)
        val dLon = 0.002 / kotlin.math.cos(Math.toRadians(lat)) * (111.0 / 111.32) // ~220m east
        val c = GeoPt(lat + 2 * dLat, lon + dLon)
        val streetA = edge(1, 1, 2, a, b)
        val streetB = edge(2, 2, 3, b, c)

        val walked = MapMatcher.matchWalkedEdges(trackAlong(a, b), graphOf(streetA, streetB))

        assertTrue("street B was only touched at its endpoint, not walked",
            walked.none { it.wayId == 2L })
        assertTrue(walked.any { it.wayId == 1L })
    }

    @Test
    fun emptyTrack_matchesNothing() {
        val a = GeoPt(lat, lon)
        val b = GeoPt(lat + dLat, lon)
        val walked = MapMatcher.matchWalkedEdges(emptyList(), graphOf(edge(1, 1, 2, a, b)))
        assertTrue(walked.isEmpty())
    }
}
