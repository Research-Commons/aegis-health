package com.aegis.health.db.history

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(entities = [HistoryEntity::class], version = 1, exportSchema = false)
abstract class HistoryDatabase : RoomDatabase() {
    abstract fun history(): HistoryDao

    companion object {
        fun build(context: Context): HistoryDatabase =
            Room.databaseBuilder(
                context.applicationContext,
                HistoryDatabase::class.java,
                "aegis_history.db",
            ).build()
    }
}
