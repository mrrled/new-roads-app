package com.newroads.app.data.db.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

/** Caches a raw Overpass response for an area so it doesn't need to be re-fetched every time. */
@Entity(tableName = "road_graph_cache")
data class RoadGraphCacheEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val centerLatRounded: Double,
    val centerLonRounded: Double,
    val radiusBucketMeters: Int,
    val fetchedAt: Long,
    val rawJson: String
)
