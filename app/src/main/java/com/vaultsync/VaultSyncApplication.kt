package com.vaultsync

import android.app.Application
import coil3.ImageLoader
import coil3.PlatformContext
import coil3.SingletonImageLoader
import coil3.memory.MemoryCache
import coil3.request.crossfade
import coil3.video.VideoFrameDecoder
import com.vaultsync.core.media.VideoThumbnailFetcher
import com.vaultsync.core.trash.TrashManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

import okio.Path.Companion.toPath

class VaultSyncApplication : Application(), SingletonImageLoader.Factory {
    override fun onCreate() {
        super.onCreate()
        // Automatically cleanup any trash items older than 15 days on app startup
        CoroutineScope(Dispatchers.IO).launch {
            try {
                TrashManager.get(this@VaultSyncApplication).cleanupExpired()
            } catch (_: Exception) {
            }
        }
    }

    override fun newImageLoader(context: PlatformContext): ImageLoader {
        return ImageLoader.Builder(context)
            .crossfade(true)
            .memoryCache {
                MemoryCache.Builder()
                    .maxSizePercent(context, 0.25)
                    .build()
            }
            .diskCache {
                coil3.disk.DiskCache.Builder()
                    .directory(context.cacheDir.resolve("image_cache").absolutePath.toPath())
                    .maxSizeBytes(250L * 1024 * 1024)
                    .build()
            }
            .components {
                add(VideoThumbnailFetcher.Factory(context))
                add(VideoFrameDecoder.Factory())
            }
            .build()
    }
}
