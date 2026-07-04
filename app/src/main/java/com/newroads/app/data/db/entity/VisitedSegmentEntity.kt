package com.newroads.app.data.db.entity

import androidx.room.Entity

@Entity(tableName = "visited_segments", primaryKeys = ["wayId", "fromNode", "toNode"])
data class VisitedSegmentEntity(
    val wayId: Long,
    val fromNode: Long,
    val toNode: Long,
    val pointsJson: String,
    val lengthMeters: Double,
    val timesVisited: Int,
    val lastVisitedAt: Long
)
