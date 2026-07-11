package com.newroads.app.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.newroads.app.data.db.dao.RoadGraphCacheDao
import com.newroads.app.data.db.dao.VisitedSegmentDao
import com.newroads.app.data.db.dao.WalkRecordDao
import com.newroads.app.data.db.entity.RoadGraphCacheEntity
import com.newroads.app.data.db.entity.VisitedSegmentEntity
import com.newroads.app.data.db.entity.WalkRecordEntity

@Database(
    entities = [VisitedSegmentEntity::class, WalkRecordEntity::class, RoadGraphCacheEntity::class],
    version = 3,
    exportSchema = false
)
abstract class AppDatabase : RoomDatabase() {

    abstract fun visitedSegmentDao(): VisitedSegmentDao
    abstract fun walkRecordDao(): WalkRecordDao
    abstract fun roadGraphCacheDao(): RoadGraphCacheDao

    companion object {
        @Volatile
        private var INSTANCE: AppDatabase? = null

        /**
         * v2 -> v3: the Overpass query stopped fetching highway=service (courtyard/driveway roads),
         * but cached responses could keep serving them for up to a week. Schema is unchanged —
         * just drop the cached graphs; walk history and visited roads are preserved.
         */
        private val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("DELETE FROM road_graph_cache")
            }
        }

        fun get(context: Context): AppDatabase =
            INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "newroads.db"
                )
                    .addMigrations(MIGRATION_2_3)
                    // No user has real history worth migrating yet at this stage of development.
                    .fallbackToDestructiveMigration()
                    .build().also { INSTANCE = it }
            }
    }
}
