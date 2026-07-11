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
        val graph = GraphBuilder.buildGraph(ways)

        assertFalse("grid should produce a non-empty graph", graph.isEmpty)
        assertEquals("all 25 grid nodes should be present", 25, graph.nodes.size)
    }

    /**
     * Reproduces the reported failure: a 10000-step target uses a large radius. A dense grid spanning
     * that area must still build a rich, non-empty graph — this is what "no roads found" would violate.
     */
    @Test
    fun buildGraph_largeDenseArea_isNotEmpty() {
        val ways = TestGraphs.grid(cols = 61, rows = 61, spacingMeters = 150.0, centerLat = centerLat, centerLon = centerLon)
        val graph = GraphBuilder.buildGraph(ways)

        assertFalse("large-area grid should produce a non-empty graph", graph.isEmpty)
        assertTrue("should retain thousands of nodes", graph.nodes.size > 3000)
    }

    @Test
    fun buildGraph_emptyWays_isEmpty() {
        val graph = GraphBuilder.buildGraph(emptyList())
        assertTrue(graph.isEmpty)
    }

    /**
     * The core fix: two ways that meet at a node in the *middle* of the first way (a T-junction) must
     * end up connected. The first way must be split at the shared node so a walk can turn onto the
     * second — otherwise the graph fragments and routes collapse to a few metres on real data.
     */
    @Test
    fun buildGraph_splitsWayAtSharedIntersectionNode() {
        val d = 0.001
        // Way A runs west-east through nodes 1-2-3; node 2 is its middle.
        val wayA = RawWay(
            wayId = 1,
            nodeIds = listOf(1, 2, 3),
            points = listOf(GeoPt(centerLat, centerLon - d), GeoPt(centerLat, centerLon), GeoPt(centerLat, centerLon + d))
        )
        // Way B joins at node 2 and heads north to node 4.
        val wayB = RawWay(
            wayId = 2,
            nodeIds = listOf(2, 4),
            points = listOf(GeoPt(centerLat, centerLon), GeoPt(centerLat + d, centerLon))
        )
        val graph = GraphBuilder.buildGraph(listOf(wayA, wayB))

        assertTrue("shared middle node must be a graph vertex", graph.nodes.containsKey(2))
        val edgesAt2 = graph.edgesByNode[2].orEmpty()
        // Node 2 should connect to nodes 1, 3 (way A split in two) and 4 (way B) — degree 3.
        val neighbors = edgesAt2.map { com.newroads.app.osm.otherEnd(it, 2) }.toSet()
        assertEquals("node 2 should join both halves of A and way B", setOf(1L, 3L, 4L), neighbors)
    }

    @Test
    fun buildGraph_loneWayWithoutIntersections_isSingleEdge() {
        // A way that no other way touches stays one edge between its endpoints.
        val points = (0..5).map { GeoPt(centerLat + it * 0.001, centerLon) }
        val nodeIds = (0..5).map { it.toLong() }
        val graph = GraphBuilder.buildGraph(listOf(RawWay(1, nodeIds, points)))
        assertFalse(graph.isEmpty)
        assertEquals("only the two endpoints anchor the edge", 2, graph.nodes.size)
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
