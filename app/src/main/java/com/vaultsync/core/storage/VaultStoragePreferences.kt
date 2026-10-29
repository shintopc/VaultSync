package com.vaultsync.core.storage

import android.content.Context
import android.content.SharedPreferences
import android.os.Environment
import java.io.File

class VaultStoragePreferences(context: Context) {
    private val prefs: SharedPreferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    var isSharedStorageEnabled: Boolean
        get() = prefs.getBoolean(KEY_IS_SHARED_ENABLED, false)
        set(value) = prefs.edit().putBoolean(KEY_IS_SHARED_ENABLED, value).apply()

    var sharedFolderPath: String?
        get() = prefs.getString(KEY_SHARED_FOLDER_PATH, null)
        set(value) = prefs.edit().putString(KEY_SHARED_FOLDER_PATH, value).apply()

    var sharedFolderTreeUri: String?
        get() = prefs.getString(KEY_SHARED_FOLDER_TREE_URI, null)
        set(value) = prefs.edit().putString(KEY_SHARED_FOLDER_TREE_URI, value).apply()

    var isMigrationCompleted: Boolean
        get() = prefs.getBoolean(KEY_IS_MIGRATION_COMPLETED, false)
        set(value) = prefs.edit().putBoolean(KEY_IS_MIGRATION_COMPLETED, value).apply()

    fun getSharedStorageDirectory(): File {
        val configured = sharedFolderPath
        if (!configured.isNullOrBlank()) {
            return File(configured)
        }
        val docsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS)
        return File(docsDir, DEFAULT_FOLDER_NAME)
    }

    fun configureSharedStorage(path: String, treeUri: String? = null) {
        prefs.edit()
            .putBoolean(KEY_IS_SHARED_ENABLED, true)
            .putString(KEY_SHARED_FOLDER_PATH, path)
            .putString(KEY_SHARED_FOLDER_TREE_URI, treeUri)
            .putBoolean(KEY_IS_MIGRATION_COMPLETED, true)
            .apply()
    }

    companion object {
        private const val PREFS_NAME = "vault_storage_preferences"
        private const val KEY_IS_SHARED_ENABLED = "is_shared_storage_enabled"
        private const val KEY_SHARED_FOLDER_PATH = "shared_folder_path"
        private const val KEY_SHARED_FOLDER_TREE_URI = "shared_folder_tree_uri"
        private const val KEY_IS_MIGRATION_COMPLETED = "is_migration_completed"
        const val DEFAULT_FOLDER_NAME = "VaultSync"
    }
}
