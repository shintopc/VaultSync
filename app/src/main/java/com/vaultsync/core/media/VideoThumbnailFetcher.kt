package com.vaultsync.core.media

import android.content.Context
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.os.Build
import android.util.Size
import coil3.ImageLoader
import coil3.asImage
import coil3.decode.DataSource
import coil3.fetch.FetchResult
import coil3.fetch.Fetcher
import coil3.fetch.ImageFetchResult
import coil3.request.Options
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * High-performance Video Thumbnail Fetcher for Coil 3.
 *
 * Automatically intercepts video URIs (both MediaStore content:// and file://)
 * and retrieves the thumbnail:
 * 1. On Android 10+ (API 29+), uses ContentResolver.loadThumbnail for instant,
 *    hardware-accelerated, OS-cached video thumbnails.
 * 2. On older Android versions or fallback, uses MediaMetadataRetriever to extract
 *    a clean representative frame (at 1.0s to avoid solid black initial frames).
 */
class VideoThumbnailFetcher(
    private val context: Context,
    private val data: coil3.Uri,
    private val options: Options,
) : Fetcher {

    override suspend fun fetch(): FetchResult? = withContext(Dispatchers.IO) {
        val androidUri = try {
            android.net.Uri.parse(data.toString())
        } catch (_: Exception) {
            return@withContext null
        }

        val bitmap: Bitmap? = try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                // High-performance OS-cached thumbnail from Android MediaStore
                context.contentResolver.loadThumbnail(androidUri, Size(512, 512), null)
            } else {
                null
            }
        } catch (_: Exception) {
            null
        } ?: try {
            // Robust fallback via MediaMetadataRetriever
            val retriever = MediaMetadataRetriever()
            try {
                if (androidUri.scheme == "content" || androidUri.scheme == "android.resource") {
                    retriever.setDataSource(context, androidUri)
                } else {
                    retriever.setDataSource(androidUri.path ?: androidUri.toString())
                }

                // Prefer frame at 1s mark (1,000,000 microseconds) to avoid initial black intro frames
                retriever.getFrameAtTime(1_000_000L, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                    ?: retriever.frameAtTime
            } finally {
                try {
                    retriever.release()
                } catch (_: Exception) {}
            }
        } catch (_: Exception) {
            null
        }

        if (bitmap != null) {
            ImageFetchResult(
                image = bitmap.asImage(),
                isSampled = true,
                dataSource = DataSource.DISK,
            )
        } else {
            null
        }
    }

    class Factory(private val context: Context) : Fetcher.Factory<coil3.Uri> {
        override fun create(
            data: coil3.Uri,
            options: Options,
            imageLoader: ImageLoader,
        ): Fetcher? {
            val scheme = data.scheme
            if (scheme != "content" && scheme != "file") return null

            val uriStr = data.toString().lowercase()
            val path = data.path.orEmpty().lowercase()

            val isVideo = uriStr.contains("/video/") ||
                path.endsWith(".mp4") ||
                path.endsWith(".mkv") ||
                path.endsWith(".mov") ||
                path.endsWith(".3gp") ||
                path.endsWith(".webm") ||
                path.endsWith(".avi") ||
                path.endsWith(".flv") ||
                path.endsWith(".ts")

            if (isVideo) {
                return VideoThumbnailFetcher(context, data, options)
            }

            // Fallback: check content type via ContentResolver for content Uris
            if (scheme == "content") {
                val mime = try {
                    val androidUri = android.net.Uri.parse(data.toString())
                    context.contentResolver.getType(androidUri)
                } catch (_: Exception) {
                    null
                }
                if (mime?.startsWith("video/") == true) {
                    return VideoThumbnailFetcher(context, data, options)
                }
            }

            return null
        }
    }
}
