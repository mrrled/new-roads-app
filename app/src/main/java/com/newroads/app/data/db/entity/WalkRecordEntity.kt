package com.newroads.app.data.db.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "walk_records")
data class WalkRecordEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val startedAt: Long,
    val endedAt: Long,
    val distanceMeters: Double,
    val steps: Int,
    val pathJson: String,
    val newRoadFraction: Double
)
