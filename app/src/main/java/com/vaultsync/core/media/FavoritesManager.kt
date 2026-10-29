package com.vaultsync.core.media

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class FavoritesManager private constructor(context: Context) {
    private val prefs: SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private val _favoritesFlow = MutableStateFlow<Set<String>>(loadFavorites())
    val favoritesFlow: StateFlow<Set<String>> = _favoritesFlow.asStateFlow()

    private fun loadFavorites(): Set<String> {
        return prefs.getStringSet(KEY_FAVORITES, emptySet())?.toSet() ?: emptySet()
    }

    fun isFavorite(mediaId: String): Boolean {
        return _favoritesFlow.value.contains(mediaId)
    }

    fun toggleFavorite(mediaId: String): Boolean {
        val current = _favoritesFlow.value.toMutableSet()
        val newState = if (current.contains(mediaId)) {
            current.remove(mediaId)
            false
        } else {
            current.add(mediaId)
            true
        }
        prefs.edit().putStringSet(KEY_FAVORITES, current).apply()
        _favoritesFlow.value = current
        return newState
    }

    fun setFavorite(mediaId: String, isFavorite: Boolean) {
        val current = _favoritesFlow.value.toMutableSet()
        if (isFavorite) {
            current.add(mediaId)
        } else {
            current.remove(mediaId)
        }
        prefs.edit().putStringSet(KEY_FAVORITES, current).apply()
        _favoritesFlow.value = current
    }

    companion object {
        private const val PREFS_NAME = "vaultsync_favorites_prefs"
        private const val KEY_FAVORITES = "starred_media_ids"

        @Volatile
        private var instance: FavoritesManager? = null

        fun get(context: Context): FavoritesManager {
            return instance ?: synchronized(this) {
                instance ?: FavoritesManager(context.applicationContext).also { instance = it }
            }
        }
    }
}
