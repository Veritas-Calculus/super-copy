/*
 * Copyright 2026 SuperCopy
 *
 * Licensed under the MIT License.
 */

package ac.plz.super_copy.data.local.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Entity representing a clipboard history entry.
 *
 * @property id Unique identifier for the entry, auto-generated.
 * @property content The text content that was recognized and copied.
 * @property timestamp The time when the entry was created, in milliseconds since epoch.
 */
@Entity(tableName = "clipboard_entries")
data class ClipboardEntry(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val content: String,
    val timestamp: Long = System.currentTimeMillis()
)
