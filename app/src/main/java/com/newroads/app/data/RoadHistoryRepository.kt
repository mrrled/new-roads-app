package com.newroads.app.data

import com.newroads.app.data.db.AppDatabase
import com.newroads.app.data.db.entity.VisitedSegmentEntity
import com.newroads.app.data.db.entity.WalkRecordEntity
import com.newroads.app.osm.GeoPt
import com.newroads.app.osm.GraphEdge

/**
 * Bridges the OSM road graph (in-memory) with the locally persisted
 * history of which road segments the user has already walked.
 */
class RoadHistoryRepository(private val db: AppDatabase) {

    suspend fun getVisitedKeys(): Set<String> =
        db.visitedSegmentDao().getAll().map { segmentKey(it.wayId, it.fromNode, it.toNode) }.toSet()

    suspend fun getAllVisitedSegments(): List<VisitedSegmentEntity> =
        db.visitedSegmentDao().getAll()

    /** Marks the given road segments as walked (used with the map-matched edges of a GPS track). */
    suspend fun markEdgesVisited(edges: List<GraphEdge>) {
        val now = System.currentTimeMillis()
        for (edge in edges) {
            val from = minOf(edge.fromNode, edge.toNode)
            val to = maxOf(edge.fromNode, edge.toNode)
            val existing = db.visitedSegmentDao().find(edge.wayId, from, to)
            val updated = existing?.copy(
                timesVisited = existing.timesVisited + 1,
                lastVisitedAt = now
            ) ?: VisitedSegmentEntity(
                wayId = edge.wayId,
                fromNode = from,
                toNode = to,
                pointsJson = encodePoints(edge.points),
                lengthMeters = edge.lengthMeters,
                timesVisited = 1,
                lastVisitedAt = now
            )
            db.visitedSegmentDao().upsert(updated)
        }
    }

    suspend fun saveWalkRecord(record: WalkRecordEntity): Long =
        db.walkRecordDao().insert(record)

    suspend fun getAllWalkRecords(): List<WalkRecordEntity> =
        db.walkRecordDao().getAll()

    companion object {
        fun segmentKey(wayId: Long, fromNode: Long, toNode: Long): String =
            com.newroads.app.osm.segmentKey(wayId, fromNode, toNode)

        fun encodePoints(points: List<GeoPt>): String =
            points.joinToString(";") { "${it.lat},${it.lon}" }

        fun decodePoints(json: String): List<GeoPt> =
            if (json.isBlank()) emptyList() else json.split(";").map {
                val (lat, lon) = it.split(",")
                GeoPt(lat.toDouble(), lon.toDouble())
            }
    }
}
