/*
 * Copyright 2026 SuperCopy
 *
 * Licensed under the MIT License.
 */

package ac.plz.super_copy.data.local.database

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import ac.plz.super_copy.data.local.dao.ClipboardDao
import ac.plz.super_copy.data.local.entity.ClipboardEntry

/**
 * Room database for the SuperCopy application.
 * Stores clipboard history entries locally.
 */
@Database(
    entities = [ClipboardEntry::class],
    version = 1,
    exportSchema = false
)
abstract class AppDatabase : RoomDatabase() {

    abstract fun clipboardDao(): ClipboardDao

    companion object {
        private const val DATABASE_NAME = "supercopy_database"

        @Volatile
        private var instance: AppDatabase? = null

        /**
         * Gets the singleton instance of the database.
         *
         * @param context The application context.
         * @return The database instance.
         */
        fun getInstance(context: Context): AppDatabase {
            return instance ?: synchronized(this) {
                instance ?: buildDatabase(context).also { instance = it }
            }
        }

        private fun buildDatabase(context: Context): AppDatabase {
            return Room.databaseBuilder(
                context.applicationContext,
                AppDatabase::class.java,
                DATABASE_NAME
            ).build()
        }
    }
}
