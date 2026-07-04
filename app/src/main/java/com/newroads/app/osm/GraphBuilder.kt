package com.newroads.app.osm

import org.json.JSONObject

/** Parses an Overpass "out body geom" JSON response into a [RoadGraph]. */
object GraphBuilder {

    fun build(root: JSONObject): RoadGraph {
        val elements = root.optJSONArray("elements") ?: return RoadGraph(emptyMap(), emptyMap())

        val nodes = HashMap<Long, GraphNode>()
        val edgesByNode = HashMap<Long, MutableList<GraphEdge>>()

        for (i in 0 until elements.length()) {
            val element = elements.getJSONObject(i)
            if (element.optString("type") != "way") continue

            val nodeIds = element.optJSONArray("nodes") ?: continue
            val geometry = element.optJSONArray("geometry") ?: continue
            if (nodeIds.length() < 2 || geometry.length() != nodeIds.length()) continue

            val wayId = element.optLong("id")
            val points = (0 until geometry.length()).map { idx ->
                val g = geometry.getJSONObject(idx)
                GeoPt(g.getDouble("lat"), g.getDouble("lon"))
            }

            val fromId = nodeIds.getLong(0)
            val toId = nodeIds.getLong(nodeIds.length() - 1)
            if (fromId == toId) continue

            nodes.getOrPut(fromId) { GraphNode(fromId, points.first().lat, points.first().lon) }
            nodes.getOrPut(toId) { GraphNode(toId, points.last().lat, points.last().lon) }

            var length = 0.0
            for (p in 1 until points.size) {
                length += haversineMeters(points[p - 1].lat, points[p - 1].lon, points[p].lat, points[p].lon)
            }
            if (length <= 0.0) continue

            val edge = GraphEdge(wayId, fromId, toId, points, length)
            edgesByNode.getOrPut(fromId) { mutableListOf() }.add(edge)
            edgesByNode.getOrPut(toId) { mutableListOf() }.add(edge)
        }

        return RoadGraph(nodes, edgesByNode)
    }
}
