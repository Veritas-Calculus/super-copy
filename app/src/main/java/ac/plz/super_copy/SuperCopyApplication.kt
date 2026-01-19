/*
 * Copyright 2026 SuperCopy
 *
 * Licensed under the MIT License.
 */

package ac.plz.super_copy

import android.app.Application
import ac.plz.super_copy.data.local.database.AppDatabase
import ac.plz.super_copy.data.repository.ClipboardRepository

/**
 * Application class for SuperCopy.
 * Provides singleton instances of database and repository.
 */
class SuperCopyApplication : Application() {

    val database: AppDatabase by lazy {
        AppDatabase.getInstance(this)
    }

    val clipboardRepository: ClipboardRepository by lazy {
        ClipboardRepository(database.clipboardDao())
    }

    override fun onCreate() {
        super.onCreate()
        instance = this
    }

    companion object {
        @Volatile
        private var instance: SuperCopyApplication? = null

        fun getInstance(): SuperCopyApplication {
            return instance ?: throw IllegalStateException(
                "SuperCopyApplication not initialized"
            )
        }
    }
}
