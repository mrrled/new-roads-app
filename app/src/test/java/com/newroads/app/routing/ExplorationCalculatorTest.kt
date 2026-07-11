package com.newroads.app.routing

import com.newroads.app.osm.GraphBuilder
import com.newroads.app.osm.RoadGraph
import com.newroads.app.osm.TestGraphs
import com.newroads.app.osm.segmentKey
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ExplorationCalculatorTest {

    private fun grid(): RoadGraph =
        GraphBuilder.buildGraph(TestGraphs.grid(cols = 3, rows = 3, spacingMeters = 100.0, centerLat = 55.0, centerLon = 37.0))

    private fun allKeys(graph: RoadGraph): List<String> =
        graph.edgesByNode.values.flatten()
            .map { segmentKey(it.wayId, it.fromNode, it.toNode) }
            .distinct()

    @Test
    fun nothingVisited_zeroFraction() {
        val data = ExplorationCalculator.compute(grid(), emptySet())
        assertEquals(0.0, data.fraction, 1e-9)
        assertTrue(data.walkedEdges.isEmpty())
        assertEquals(12, data.unwalkedEdges.size) // 3x3 grid has 12 segments
        assertTrue(data.totalMeters > 0)
    }

    @Test
    fun everythingVisited_fullFraction() {
        val graph = grid()
        val data = ExplorationCalculator.compute(graph, allKeys(graph).toSet())
        assertEquals(1.0, data.fraction, 1e-9)
        assertTrue(data.unwalkedEdges.isEmpty())
        assertEquals(data.totalMeters, data.walkedMeters, 1e-9)
    }

    @Test
    fun partialVisited_fractionMatchesLengthShare() {
        val graph = grid()
        val keys = allKeys(graph)
        val visited = keys.take(keys.size / 2).toSet()

        val data = ExplorationCalculator.compute(graph, visited)

        assertEquals(visited.size, data.walkedEdges.size)
        assertEquals(keys.size - visited.size, data.unwalkedEdges.size)
        val expected = data.walkedEdges.sumOf { it.lengthMeters } / data.totalMeters
        assertEquals(expected, data.fraction, 1e-9)
        assertTrue(data.fraction in 0.3..0.7)
    }

    @Test
    fun visitedKeysOutsideArea_areIgnored() {
        val data = ExplorationCalculator.compute(grid(), setOf("999_1_2", "998_3_4"))
        assertEquals(0.0, data.fraction, 1e-9)
    }
}
