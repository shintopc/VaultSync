package com.vaultsync.core.media

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.ExifInterface
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.util.UUID

data class ExifDetails(
    val cameraModel: String? = null,
    val cameraMake: String? = null,
    val aperture: String? = null,
    val focalLength: String? = null,
    val exposureTime: String? = null,
    val iso: String? = null,
    val width: Int = 0,
    val height: Int = 0,
    val dateTaken: String? = null,
    val latitude: Double? = null,
    val longitude: Double? = null,
    val hasLocation: Boolean = false,
)

object ExifMetadataInspector {

    suspend fun extractExif(context: Context, uri: Uri): ExifDetails = withContext(Dispatchers.IO) {
        try {
            context.contentResolver.openInputStream(uri)?.use { inputStream ->
                readExifFromStream(inputStream)
            } ?: ExifDetails()
        } catch (_: Exception) {
            ExifDetails()
        }
    }

    suspend fun extractExifFromFile(file: File): ExifDetails = withContext(Dispatchers.IO) {
        try {
            if (!file.exists()) return@withContext ExifDetails()
            val exif = ExifInterface(file.absolutePath)
            buildExifDetails(exif)
        } catch (_: Exception) {
            ExifDetails()
        }
    }

    private fun readExifFromStream(inputStream: InputStream): ExifDetails {
        return try {
            val exif = ExifInterface(inputStream)
            buildExifDetails(exif)
        } catch (_: Exception) {
            ExifDetails()
        }
    }

    private fun buildExifDetails(exif: ExifInterface): ExifDetails {
        val make = exif.getAttribute(ExifInterface.TAG_MAKE)?.trim()
        val model = exif.getAttribute(ExifInterface.TAG_MODEL)?.trim()
        val aperture = exif.getAttribute(ExifInterface.TAG_F_NUMBER)
        val focalLength = exif.getAttribute(ExifInterface.TAG_FOCAL_LENGTH)
        val exposure = exif.getAttribute(ExifInterface.TAG_EXPOSURE_TIME)
        val iso = exif.getAttribute(ExifInterface.TAG_ISO_SPEED_RATINGS) ?: exif.getAttribute("PhotographicSensitivity")
        val date = exif.getAttribute(ExifInterface.TAG_DATETIME_ORIGINAL)
            ?: exif.getAttribute(ExifInterface.TAG_DATETIME)

        val width = exif.getAttributeInt(ExifInterface.TAG_IMAGE_WIDTH, 0)
        val height = exif.getAttributeInt(ExifInterface.TAG_IMAGE_LENGTH, 0)

        val latLong = FloatArray(2)
        val hasGps = exif.getLatLong(latLong)

        return ExifDetails(
            cameraMake = make,
            cameraModel = model,
            aperture = aperture?.let { "f/$it" },
            focalLength = focalLength?.let { "${it}mm" },
            exposureTime = exposure?.let { formatExposure(it) },
            iso = iso?.let { "ISO $it" },
            width = width,
            height = height,
            dateTaken = date,
            latitude = if (hasGps) latLong[0].toDouble() else null,
            longitude = if (hasGps) latLong[1].toDouble() else null,
            hasLocation = hasGps,
        )
    }

    private fun formatExposure(exposureStr: String): String {
        return try {
            val value = exposureStr.toDouble()
            if (value > 0 && value < 1.0) {
                val denom = (1.0 / value).toInt()
                "1/${denom}s"
            } else {
                "${value}s"
            }
        } catch (_: Exception) {
            exposureStr
        }
    }

    suspend fun stripExifAndCreateCleanCopy(context: Context, sourceUri: Uri): File? = withContext(Dispatchers.IO) {
        try {
            context.contentResolver.openInputStream(sourceUri)?.use { input ->
                val bitmap = BitmapFactory.decodeStream(input) ?: return@withContext null
                val cleanFile = File(context.cacheDir, "shared_clean_${UUID.randomUUID()}.jpg")
                FileOutputStream(cleanFile).use { out ->
                    bitmap.compress(Bitmap.CompressFormat.JPEG, 95, out)
                }
                bitmap.recycle()
                cleanFile
            }
        } catch (_: Exception) {
            null
        }
    }
}
