package com.newroads.app.osm

import org.json.JSONObject

/** A single OSM way as returned by Overpass "out skel geom": node ids plus matching geometry. */
data class RawWay(
    val wayId: Long,
    val nodeIds: List<Long>,
    val points: List<GeoPt>
)

/** Parses an Overpass "out skel geom" JSON response into a routable [RoadGraph]. */
object GraphBuilder {

    fun build(root: JSONObject): RoadGraph = buildGraph(parseWays(root))

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
     * Builds a routable graph from already-parsed [ways].
     *
     * The crucial part is *where* an OSM way becomes a graph edge. A way is a polyline of many nodes;
     * other ways very often connect to it at one of its intermediate nodes (a T-junction, a side
     * street joining mid-block, etc.), not only at its first/last node. If we only made the way's two
     * endpoints into graph vertices, every such mid-way connection would be lost and the graph would
     * shatter into disconnected pieces — which is exactly what made routes collapse to a few metres on
     * real data even though the graph looked non-empty.
     *
     * So every node shared by more than one way (an intersection), plus each way's own endpoints, is a
     * graph vertex, and each way is split into one edge per span between consecutive vertices, carrying
     * the geometry in between. Nodes that Overpass returned but that no longer anchor an edge are
     * dropped.
     */
    fun buildGraph(ways: List<RawWay>): RoadGraph {
        // How many ways reference each node — a node used by 2+ ways is an intersection.
        val nodeUseCount = HashMap<Long, Int>()
        for (way in ways) {
            if (way.nodeIds.size < 2 || way.nodeIds.size != way.points.size) continue
            for (nid in way.nodeIds) nodeUseCount[nid] = (nodeUseCount[nid] ?: 0) + 1
        }

        val nodes = HashMap<Long, GraphNode>()
        val edgesByNode = HashMap<Long, MutableList<GraphEdge>>()

        for (way in ways) {
            val ids = way.nodeIds
            val pts = way.points
            if (ids.size < 2 || ids.size != pts.size) continue

            var segStart = 0
            for (i in 1 until ids.size) {
                val isVertex = i == ids.lastIndex || (nodeUseCount[ids[i]] ?: 0) >= 2
                if (!isVertex) continue

                val fromId = ids[segStart]
                val toId = ids[i]
                if (fromId != toId) {
                    val segPoints = pts.subList(segStart, i + 1).toList()
                    var length = 0.0
                    for (p in 1 until segPoints.size) {
                        length += haversineMeters(
                            segPoints[p - 1].lat, segPoints[p - 1].lon,
                            segPoints[p].lat, segPoints[p].lon
                        )
                    }
                    if (length > 0.0) {
                        nodes.getOrPut(fromId) { GraphNode(fromId, segPoints.first().lat, segPoints.first().lon) }
                        nodes.getOrPut(toId) { GraphNode(toId, segPoints.last().lat, segPoints.last().lon) }
                        val edge = GraphEdge(way.wayId, fromId, toId, segPoints, length)
                        edgesByNode.getOrPut(fromId) { mutableListOf() }.add(edge)
                        edgesByNode.getOrPut(toId) { mutableListOf() }.add(edge)
                    }
                }
                segStart = i
            }
        }

        return RoadGraph(nodes, edgesByNode)
    }
}
