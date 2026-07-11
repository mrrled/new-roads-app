package com.newroads.app.routing

import com.newroads.app.osm.GraphEdge
import com.newroads.app.osm.RoadGraph
import com.newroads.app.osm.segmentKey

/**
 * Splits an area's road graph into walked and unwalked streets and totals their lengths — the
 * basis for the "you have explored N% of the streets around you" stat and the fog-of-war map.
 */
data class ExplorationData(
    val walkedEdges: List<GraphEdge>,
    val unwalkedEdges: List<GraphEdge>,
    val walkedMeters: Double,
    val totalMeters: Double
) {
    val fraction: Double get() = if (totalMeters > 0.0) walkedMeters / totalMeters else 0.0
}

object ExplorationCalculator {

    fun compute(graph: RoadGraph, visitedKeys: Set<String>): ExplorationData {
        val walked = ArrayList<GraphEdge>()
        val unwalked = ArrayList<GraphEdge>()
        var walkedMeters = 0.0
        var totalMeters = 0.0

        val seen = HashSet<String>() // every edge is listed under both endpoints — count it once
        for (edges in graph.edgesByNode.values) {
            for (edge in edges) {
                val key = segmentKey(edge.wayId, edge.fromNode, edge.toNode)
                if (!seen.add(key)) continue
                totalMeters += edge.lengthMeters
                if (key in visitedKeys) {
                    walked.add(edge)
                    walkedMeters += edge.lengthMeters
                } else {
                    unwalked.add(edge)
                }
            }
        }
        return ExplorationData(walked, unwalked, walkedMeters, totalMeters)
    }
}
