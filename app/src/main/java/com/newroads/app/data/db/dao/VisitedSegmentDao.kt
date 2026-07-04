package com.newroads.app.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.newroads.app.data.db.entity.VisitedSegmentEntity

@Dao
interface VisitedSegmentDao {

    @Query("SELECT * FROM visited_segments")
    suspend fun getAll(): List<VisitedSegmentEntity>

    @Query("SELECT * FROM visited_segments WHERE wayId = :wayId AND fromNode = :fromNode AND toNode = :toNode LIMIT 1")
    suspend fun find(wayId: Long, fromNode: Long, toNode: Long): VisitedSegmentEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(segment: VisitedSegmentEntity)
}
