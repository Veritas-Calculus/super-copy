/*
 * Copyright 2026 SuperCopy
 *
 * Licensed under the MIT License.
 */

package ac.plz.super_copy.data.repository

import ac.plz.super_copy.data.local.dao.ClipboardDao
import ac.plz.super_copy.data.local.entity.ClipboardEntry
import kotlinx.coroutines.flow.Flow

/**
 * Repository for managing clipboard history data.
 * Acts as a single source of truth for clipboard entries.
 *
 * @property clipboardDao The DAO for clipboard operations.
 */
class ClipboardRepository(private val clipboardDao: ClipboardDao) {

    /**
     * Gets all clipboard entries as a Flow.
     */
    val allEntries: Flow<List<ClipboardEntry>> = clipboardDao.getAllEntries()

    /**
     * Gets recent clipboard entries with a specified limit.
     *
     * @param limit The maximum number of entries to retrieve.
     * @return A Flow emitting the list of recent entries.
     */
    fun getRecentEntries(limit: Int = DEFAULT_HISTORY_LIMIT): Flow<List<ClipboardEntry>> {
        return clipboardDao.getRecentEntries(limit)
    }

    /**
     * Saves a new clipboard entry.
     *
     * @param content The text content to save.
     * @return The ID of the inserted entry.
     */
    suspend fun saveEntry(content: String): Long {
        val entry = ClipboardEntry(content = content)
        return clipboardDao.insert(entry)
    }

    /**
     * Deletes a clipboard entry.
     *
     * @param entry The entry to delete.
     */
    suspend fun deleteEntry(entry: ClipboardEntry) {
        clipboardDao.delete(entry)
    }

    /**
     * Clears all clipboard history.
     */
    suspend fun clearAll() {
        clipboardDao.deleteAll()
    }

    /**
     * Cleans up old entries beyond the retention period.
     *
     * @param retentionDays The number of days to retain entries.
     */
    suspend fun cleanupOldEntries(retentionDays: Int = DEFAULT_RETENTION_DAYS) {
        val cutoffTimestamp = System.currentTimeMillis() - (retentionDays * DAY_IN_MILLIS)
        clipboardDao.deleteOlderThan(cutoffTimestamp)
    }

    companion object {
        private const val DEFAULT_HISTORY_LIMIT = 100
        private const val DEFAULT_RETENTION_DAYS = 30
        private const val DAY_IN_MILLIS = 24 * 60 * 60 * 1000L
    }
}
