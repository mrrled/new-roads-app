package com.newroads.app.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.newroads.app.data.db.entity.RoadGraphCacheEntity

@Dao
interface RoadGraphCacheDao {

    @Query(
        """
        SELECT * FROM road_graph_cache
        WHERE centerLatRounded = :lat AND centerLonRounded = :lon
          AND radiusBucketMeters >= :minRadiusMeters
          AND fetchedAt >= :notOlderThan
        ORDER BY radiusBucketMeters ASC
        LIMIT 1
        """
    )
    suspend fun findFresh(lat: Double, lon: Double, minRadiusMeters: Int, notOlderThan: Long): RoadGraphCacheEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(entity: RoadGraphCacheEntity)

    @Query("DELETE FROM road_graph_cache WHERE fetchedAt < :cutoff")
    suspend fun deleteOlderThan(cutoff: Long)
}
