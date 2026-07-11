package com.newroads.app.routing

import com.newroads.app.osm.GraphBuilder
import com.newroads.app.osm.RoadGraph
import com.newroads.app.osm.TestGraphs
import com.newroads.app.osm.haversineMeters
import kotlin.random.Random
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RouteGeneratorTest {

    private val centerLat = 43.60841
    private val centerLon = 1.47644
    private val stride = 0.75

    private fun gridGraph(cols: Int = 41, rows: Int = 41, spacing: Double = 100.0): RoadGraph =
        GraphBuilder.buildGraph(
            TestGraphs.grid(cols, rows, spacing, centerLat, centerLon),
            centerLat, centerLon, requestedRadiusMeters = spacing * maxOf(cols, rows)
        )

    @Test
    fun generateLoop_onGrid_returnsRouteNearTargetLength() {
        val graph = gridGraph()
        val target = 2000.0
        val result = RouteGenerator(graph, visitedKeys = emptySet(), strideMeters = stride, random = Random(42))
            .generateLoop(centerLat, centerLon, target, toleranceMeters = 500 * stride, maxAttempts = 12)

        assertNotNull("a loop should be found on a dense grid", result)
        result!!
        assertTrue("route should have geometry", result.points.size > 2)
        assertTrue("estimated steps should be positive", result.estimatedSteps > 0)
        // Loose sanity bound; the tolerance path aims much tighter but randomness varies per seed.
        assertTrue(
            "distance ${result.distanceMeters} should be roughly around target $target",
            result.distanceMeters in (target * 0.4)..(target * 1.8)
        )
    }

    @Test
    fun generateLoop_returnsClosedLoop() {
        val graph = gridGraph()
        val result = RouteGenerator(graph, emptySet(), stride, Random(1))
            .generateLoop(centerLat, centerLon, 1500.0, maxAttempts = 12)

        assertNotNull(result)
        val first = result!!.points.first()
        val last = result.points.last()
        assertTrue(
            "loop should return to its start (gap ${haversineMeters(first.lat, first.lon, last.lat, last.lon)}m)",
            haversineMeters(first.lat, first.lon, last.lat, last.lon) < 1.0
        )
    }

    @Test
    fun generateLoop_prefersUnvisitedRoads() {
        val graph = gridGraph()
        // Mark a large fraction of edges as already visited; a fresh route should still find new roads.
        val allKeys = graph.edgesByNode.values.flatten()
            .map { com.newroads.app.osm.segmentKey(it.wayId, it.fromNode, it.toNode) }
            .toSet()
        val visited = allKeys.take(allKeys.size / 2).toSet()

        val result = RouteGenerator(graph, visited, stride, Random(7))
            .generateLoop(centerLat, centerLon, 2000.0, maxAttempts = 16)

        assertNotNull(result)
        assertTrue(
            "route should include some unwalked roads (fraction=${result!!.newRoadFraction})",
            result.newRoadFraction > 0.0
        )
    }

    /**
     * The app fetches roads only within ~0.8 * (target/2) of the start to keep the download small.
     * This checks a long (10000-step ≈ 7500m) loop can still be built when the graph is limited to
     * that tighter radius, i.e. the radius trimming doesn't collapse routes to a tiny loop.
     */
    @Test
    fun generateLoop_longTargetWithinTrimmedRadius_stillBuildsSubstantialLoop() {
        val target = 7500.0
        val radius = target / 2.0 * 0.8 // ≈ 3000m, matching MainViewModel
        // Grid spanning ~2 * radius across, at 120m spacing.
        val cols = (2 * radius / 120).toInt()
        val graph = GraphBuilder.buildGraph(
            TestGraphs.grid(cols, cols, 120.0, centerLat, centerLon),
            centerLat, centerLon, requestedRadiusMeters = radius
        )

        val result = RouteGenerator(graph, emptySet(), stride, Random(3))
            .generateLoop(centerLat, centerLon, target, toleranceMeters = 500 * stride, maxAttempts = 16)

        assertNotNull("a long loop should still be found within the trimmed radius", result)
        assertTrue(
            "loop ${result!!.distanceMeters}m should be a substantial fraction of target $target",
            result.distanceMeters > target * 0.5
        )
    }

    @Test
    fun generateLoop_onEmptyGraph_returnsNull() {
        val empty = RoadGraph(emptyMap(), emptyMap())
        val result = RouteGenerator(empty, emptySet(), stride, Random(0))
            .generateLoop(centerLat, centerLon, 2000.0)
        assertNull(result)
    }

    @Test
    fun generateLoop_visitedRouteMarking_keyIsDirectionIndependent() {
        assertEquals(
            com.newroads.app.osm.segmentKey(10, 1, 2),
            com.newroads.app.osm.segmentKey(10, 2, 1)
        )
    }
}
