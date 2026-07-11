package com.newroads.app.routing

import com.newroads.app.osm.GeoPt
import com.newroads.app.osm.GraphEdge
import com.newroads.app.osm.GraphNode
import com.newroads.app.osm.RoadGraph
import com.newroads.app.osm.haversineMeters
import com.newroads.app.osm.orientedPoints
import com.newroads.app.osm.otherEnd
import com.newroads.app.osm.segmentKey
import java.util.PriorityQueue
import kotlin.random.Random

data class RouteResult(
    val points: List<GeoPt>,
    val edges: List<GraphEdge>,
    val distanceMeters: Double,
    val estimatedSteps: Int,
    val newRoadFraction: Double
)

/**
 * Builds a walking loop of roughly [targetDistanceMeters], starting and ending at the
 * user's current location, that prefers roads the user has not walked before.
 *
 * Strategy:
 *  1. "Outbound" leg: a weighted random walk away from the start, strongly preferring
 *     edges that are not in [visitedKeys] and avoiding immediate backtracking.
 *  2. "Return" leg: a shortest path back to the start (Dijkstra), where the edge cost
 *     is inflated for already-visited edges so the algorithm still favors new roads
 *     when a reasonable alternative exists.
 */
class RouteGenerator(
    private val graph: RoadGraph,
    private val visitedKeys: Set<String>,
    private val strideMeters: Double = 0.75,
    private val random: Random = Random.Default
) {

    /**
     * Builds a loop close to [targetDistanceMeters], allowing up to [toleranceMeters] of slack.
     * Since the walk is randomized, a single attempt can land outside that tolerance (or fail to
     * find a loop at all) — so this retries up to [maxAttempts] times, returning immediately once
     * an attempt lands within tolerance, and otherwise keeping the closest candidate found.
     */
    fun generateLoop(
        startLat: Double,
        startLon: Double,
        targetDistanceMeters: Double,
        toleranceMeters: Double = 500 * strideMeters,
        maxAttempts: Int = 8
    ): RouteResult? {
        var best: RouteResult? = null
        var bestDiff = Double.MAX_VALUE

        repeat(maxAttempts) {
            val candidate = attemptLoop(startLat, startLon, targetDistanceMeters) ?: return@repeat
            val diff = kotlin.math.abs(candidate.distanceMeters - targetDistanceMeters)
            if (diff <= toleranceMeters) return candidate
            if (diff < bestDiff) {
                bestDiff = diff
                best = candidate
            }
        }

        return best
    }

    private fun attemptLoop(startLat: Double, startLon: Double, targetDistanceMeters: Double): RouteResult? {
        if (graph.isEmpty) return null
        val startNode = graph.nearestNode(startLat, startLon) ?: return null

        // Aim the outbound leg a bit past half: the return leg is a shortest path, so it's typically
        // shorter than the meandering way out, and without this the loop lands well under target.
        val half = targetDistanceMeters * OUTBOUND_FRACTION
        val outboundEdges = mutableListOf<GraphEdge>()
        val usedInRoute = mutableSetOf<String>()

        var currentId = startNode.id
        var previousEdgeKey: String? = null
        var distanceSoFar = 0.0

        while (distanceSoFar < half) {
            val allEdges = graph.edgesByNode[currentId].orEmpty()
            if (allEdges.isEmpty()) break
            // Prefer not to immediately backtrack, but if that's the only option (a dead-end /
            // cul-de-sac) take it rather than abandoning the whole walk far short of the target.
            val candidates = allEdges.filter { edgeKey(it) != previousEdgeKey }.ifEmpty { allEdges }

            val chosen = pickOutbound(candidates, usedInRoute, currentId, startNode)
            outboundEdges += chosen
            usedInRoute += edgeKey(chosen)
            distanceSoFar += chosen.lengthMeters
            previousEdgeKey = edgeKey(chosen)
            currentId = otherEnd(chosen, currentId)
        }

        if (outboundEdges.isEmpty()) return null

        // If no path back exists in the fetched graph (e.g. the outbound walk reached the edge
        // of the area we queried), fall back to retracing the outbound edges in reverse — that
        // path is always available, so a loop can basically always be produced.
        val returnEdges = dijkstraPreferUnvisited(currentId, startNode.id) ?: outboundEdges.asReversed()

        val allPoints = mutableListOf<GeoPt>()
        var cursor = startNode.id
        allPoints += graph.nodes.getValue(cursor).let { GeoPt(it.lat, it.lon) }
        for (edge in outboundEdges) {
            val oriented = orientedPoints(edge, cursor)
            allPoints += oriented.drop(1)
            cursor = otherEnd(edge, cursor)
        }
        for (edge in returnEdges) {
            val oriented = orientedPoints(edge, cursor)
            allPoints += oriented.drop(1)
            cursor = otherEnd(edge, cursor)
        }

        val allEdges = outboundEdges + returnEdges
        val totalDistance = allEdges.sumOf { it.lengthMeters }
        val newEdgeCount = allEdges.count { edgeKey(it) !in visitedKeys }
        val newRoadFraction = if (allEdges.isEmpty()) 0.0 else newEdgeCount.toDouble() / allEdges.size

        return RouteResult(
            points = allPoints,
            edges = allEdges,
            distanceMeters = totalDistance,
            estimatedSteps = (totalDistance / strideMeters).toInt(),
            newRoadFraction = newRoadFraction
        )
    }

    private fun edgeKey(e: GraphEdge): String =
        segmentKey(e.wayId, e.fromNode, e.toNode)

    private fun edgeCost(e: GraphEdge): Double {
        val visited = edgeKey(e) in visitedKeys
        return e.lengthMeters * if (visited) 3.0 else 1.0
    }

    /**
     * Chooses the next edge for the outbound leg. To actually reach far enough from the start that a
     * loop of the target length is possible, edges that move *away* from the start are strongly
     * preferred (a plain random walk mostly mills around near the start). Among those, roads not yet
     * used in this route are preferred, and roads the user hasn't walked before are preferred again —
     * so the walk both travels outward and favours new streets.
     */
    private fun pickOutbound(
        candidates: List<GraphEdge>,
        usedInRoute: Set<String>,
        currentId: Long,
        startNode: GraphNode
    ): GraphEdge {
        val fresh = candidates.filterNot { edgeKey(it) in usedInRoute }
        val pool = if (fresh.isNotEmpty()) fresh else candidates

        val currentNode = graph.nodes[currentId]
        val currentDist = currentNode?.let { haversineMeters(it.lat, it.lon, startNode.lat, startNode.lon) } ?: 0.0

        val weights = pool.map { edge ->
            val endId = otherEnd(edge, currentId)
            val endNode = graph.nodes[endId]
            val outward = if (endNode != null &&
                haversineMeters(endNode.lat, endNode.lon, startNode.lat, startNode.lon) > currentDist
            ) 4.0 else 1.0
            val novelty = if (edgeKey(edge) in visitedKeys) 1.0 else 2.0
            outward * novelty
        }
        val total = weights.sum()
        var r = random.nextDouble() * total
        for (i in pool.indices) {
            r -= weights[i]
            if (r <= 0.0) return pool[i]
        }
        return pool.last()
    }

    private fun dijkstraPreferUnvisited(fromId: Long, toId: Long): List<GraphEdge>? {
        if (fromId == toId) return emptyList()

        val dist = HashMap<Long, Double>()
        val prevEdge = HashMap<Long, GraphEdge>()
        val prevNode = HashMap<Long, Long>()
        val visited = HashSet<Long>()
        val pq = PriorityQueue<Pair<Long, Double>>(compareBy { it.second })

        dist[fromId] = 0.0
        pq.add(fromId to 0.0)

        while (pq.isNotEmpty()) {
            val (nodeId, d) = pq.poll() ?: break
            if (!visited.add(nodeId)) continue
            if (nodeId == toId) break

            for (edge in graph.edgesByNode[nodeId].orEmpty()) {
                val neighbor = otherEnd(edge, nodeId)
                val nd = d + edgeCost(edge)
                if (nd < (dist[neighbor] ?: Double.MAX_VALUE)) {
                    dist[neighbor] = nd
                    prevEdge[neighbor] = edge
                    prevNode[neighbor] = nodeId
                    pq.add(neighbor to nd)
                }
            }
        }

        if (toId !in dist) return null

        val path = mutableListOf<GraphEdge>()
        var cur = toId
        while (cur != fromId) {
            val edge = prevEdge[cur] ?: return null
            path.add(0, edge)
            cur = prevNode[cur] ?: return null
        }
        return path
    }

    private companion object {
        const val OUTBOUND_FRACTION = 0.6
    }
}
