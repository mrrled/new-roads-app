package com.newroads.app.osm

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GraphBuilderTest {

    private val centerLat = 43.60841
    private val centerLon = 1.47644

    @Test
    fun buildGraph_fromSmallGrid_isNotEmpty() {
        val ways = TestGraphs.grid(cols = 5, rows = 5, spacingMeters = 100.0, centerLat = centerLat, centerLon = centerLon)
        val graph = GraphBuilder.buildGraph(ways, centerLat, centerLon, requestedRadiusMeters = 450.0)

        assertFalse("grid should produce a non-empty graph", graph.isEmpty)
        assertEquals("all 25 grid nodes should be present", 25, graph.nodes.size)
    }

    /**
     * Reproduces the reported failure: a 10000-step target uses a ~4500m radius. A dense grid
     * spanning that area must still build a rich, non-empty graph — this is what "no roads found"
     * would violate.
     */
    @Test
    fun buildGraph_largeRadiusDenseArea_isNotEmpty() {
        // 61x61 grid at 150m spacing ≈ 9km across, comparable to a 4500m-radius query.
        val ways = TestGraphs.grid(cols = 61, rows = 61, spacingMeters = 150.0, centerLat = centerLat, centerLon = centerLon)
        val graph = GraphBuilder.buildGraph(ways, centerLat, centerLon, requestedRadiusMeters = 4500.0)

        assertFalse("large-area grid should produce a non-empty graph", graph.isEmpty)
        assertTrue("should retain thousands of nodes", graph.nodes.size > 3000)
    }

    @Test
    fun buildGraph_emptyWays_isEmpty() {
        val graph = GraphBuilder.buildGraph(emptyList(), centerLat, centerLon, requestedRadiusMeters = 450.0)
        assertTrue(graph.isEmpty)
    }

    /**
     * A long way whose vertices are all far outside the clip radius (Overpass matched it because its
     * line passes near the center) must be kept whole rather than dropped — dropping it is what once
     * emptied small-radius graphs.
     */
    @Test
    fun buildGraph_wayWithNoVertexInRadius_isKept() {
        // Two endpoints ~5km north and ~5km south of center; neither is within the clip radius.
        val far = listOf(
            RawWay(
                wayId = 1,
                nodeIds = listOf(100, 101),
                points = listOf(
                    GeoPt(centerLat + 0.045, centerLon),
                    GeoPt(centerLat - 0.045, centerLon)
                )
            )
        )
        val graph = GraphBuilder.buildGraph(far, centerLat, centerLon, requestedRadiusMeters = 450.0)
        assertFalse("tangent way should be kept, not dropped", graph.isEmpty)
        assertEquals(2, graph.nodes.size)
    }

    @Test
    fun buildGraph_clipsWayExtendingBeyondRadius() {
        // A way that starts at the center and marches far north; only the near portion should survive.
        val points = (0..50).map { GeoPt(centerLat + it * 0.002, centerLon) } // 0..~11km north
        val nodeIds = (0..50).map { it.toLong() }
        val graph = GraphBuilder.buildGraph(
            listOf(RawWay(1, nodeIds, points)),
            centerLat, centerLon, requestedRadiusMeters = 450.0
        )
        assertFalse(graph.isEmpty)
        // The retained edge must be far shorter than the full ~11km way (clipped to ~radius*buffer).
        val edge = graph.edgesByNode.values.first().first()
        assertTrue("edge should be clipped near the radius, got ${edge.lengthMeters}m", edge.lengthMeters < 2000.0)
    }

    @Test
    fun parseWays_skipsMalformedElements() {
        val json = org.json.JSONObject(
            """
            {"elements":[
              {"type":"way","id":1,"nodes":[1,2],"geometry":[{"lat":$centerLat,"lon":$centerLon},{"lat":${centerLat + 0.001},"lon":$centerLon}]},
              {"type":"node","id":9,"lat":$centerLat,"lon":$centerLon},
              {"type":"way","id":2,"nodes":[3,4,5],"geometry":[{"lat":$centerLat,"lon":$centerLon}]}
            ]}
            """.trimIndent()
        )
        val ways = GraphBuilder.parseWays(json)
        assertEquals("only the well-formed way should be parsed", 1, ways.size)
        assertEquals(1L, ways.first().wayId)
    }
}
