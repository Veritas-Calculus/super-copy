/*
 * Copyright 2026 SuperCopy
 *
 * Licensed under the MIT License.
 */

package ac.plz.super_copy.data.local.dao

import ac.plz.super_copy.data.local.entity.ClipboardEntry
import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

/**
 * Data Access Object for clipboard entries.
 * Provides methods to interact with the clipboard_entries table.
 */
@Dao
interface ClipboardDao {

    /**
     * Retrieves all clipboard entries ordered by timestamp in descending order.
     *
     * @return A Flow emitting the list of clipboard entries.
     */
    @Query("SELECT * FROM clipboard_entries ORDER BY timestamp DESC")
    fun getAllEntries(): Flow<List<ClipboardEntry>>

    /**
     * Retrieves a limited number of clipboard entries ordered by timestamp.
     *
     * @param limit The maximum number of entries to retrieve.
     * @return A Flow emitting the list of clipboard entries.
     */
    @Query("SELECT * FROM clipboard_entries ORDER BY timestamp DESC LIMIT :limit")
    fun getRecentEntries(limit: Int): Flow<List<ClipboardEntry>>

    /**
     * Inserts a new clipboard entry.
     *
     * @param entry The entry to insert.
     * @return The row ID of the inserted entry.
     */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(entry: ClipboardEntry): Long

    /**
     * Deletes a clipboard entry.
     *
     * @param entry The entry to delete.
     */
    @Delete
    suspend fun delete(entry: ClipboardEntry)

    /**
     * Deletes all clipboard entries.
     */
    @Query("DELETE FROM clipboard_entries")
    suspend fun deleteAll()

    /**
     * Deletes entries older than the specified timestamp.
     *
     * @param timestamp The cutoff timestamp in milliseconds.
     */
    @Query("DELETE FROM clipboard_entries WHERE timestamp < :timestamp")
    suspend fun deleteOlderThan(timestamp: Long)
}
