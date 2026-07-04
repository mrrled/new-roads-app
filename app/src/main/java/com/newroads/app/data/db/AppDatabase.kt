package com.newroads.app.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import com.newroads.app.data.db.dao.VisitedSegmentDao
import com.newroads.app.data.db.dao.WalkRecordDao
import com.newroads.app.data.db.entity.VisitedSegmentEntity
import com.newroads.app.data.db.entity.WalkRecordEntity

@Database(
    entities = [VisitedSegmentEntity::class, WalkRecordEntity::class],
    version = 1,
    exportSchema = false
)
abstract class AppDatabase : RoomDatabase() {

    abstract fun visitedSegmentDao(): VisitedSegmentDao
    abstract fun walkRecordDao(): WalkRecordDao

    companion object {
        @Volatile
        private var INSTANCE: AppDatabase? = null

        fun get(context: Context): AppDatabase =
            INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "newroads.db"
                ).build().also { INSTANCE = it }
            }
    }
}
