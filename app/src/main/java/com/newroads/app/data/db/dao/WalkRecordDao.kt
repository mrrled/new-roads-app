package com.newroads.app.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import com.newroads.app.data.db.entity.WalkRecordEntity

@Dao
interface WalkRecordDao {

    @Insert
    suspend fun insert(record: WalkRecordEntity): Long

    @Query("SELECT * FROM walk_records ORDER BY startedAt DESC")
    suspend fun getAll(): List<WalkRecordEntity>
}
