package com.vaultsync.core.security

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.ImageFormat
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureRequest
import android.media.ImageReader
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import androidx.core.content.ContextCompat
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.util.UUID

data class IntruderLogEntry(
    val id: String,
    val timestampMillis: Long,
    val photoPath: String?,
    val failureCount: Int,
) {
    val photoFile: File?
        get() = photoPath?.let { File(it) }?.takeIf { it.exists() }
}

object IntruderCaptureManager {
    private const val TAG = "IntruderCaptureManager"
    private const val LOGS_DIR = "intruder_logs"
    private const val INDEX_FILE = "intruder_index.json"

    fun getLogsDir(context: Context): File {
        val dir = File(context.filesDir, LOGS_DIR)
        if (!dir.exists()) dir.mkdirs()
        return dir
    }

    @Synchronized
    fun getLogs(context: Context): List<IntruderLogEntry> {
        val indexFile = File(getLogsDir(context), INDEX_FILE)
        if (!indexFile.exists()) return emptyList()
        return try {
            val json = indexFile.readText()
            val array = JSONArray(json)
            val list = mutableListOf<IntruderLogEntry>()
            for (i in 0 until array.length()) {
                val obj = array.getJSONObject(i)
                list.add(
                    IntruderLogEntry(
                        id = obj.getString("id"),
                        timestampMillis = obj.getLong("timestamp"),
                        photoPath = if (obj.has("photoPath") && !obj.isNull("photoPath")) obj.getString("photoPath") else null,
                        failureCount = obj.optInt("failureCount", 3),
                    )
                )
            }
            list.sortedByDescending { it.timestampMillis }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to read intruder logs: ${e.message}")
            emptyList()
        }
    }

    @Synchronized
    fun addLog(context: Context, entry: IntruderLogEntry) {
        val current = getLogs(context).toMutableList()
        current.add(0, entry)
        // Keep max 50 entries
        val trimmed = current.take(50)
        saveIndex(context, trimmed)
    }

    @Synchronized
    fun clearLogs(context: Context) {
        val dir = getLogsDir(context)
        dir.listFiles()?.forEach { file ->
            try { file.delete() } catch (_: Exception) {}
        }
    }

    private fun saveIndex(context: Context, entries: List<IntruderLogEntry>) {
        val array = JSONArray()
        for (e in entries) {
            val obj = JSONObject()
            obj.put("id", e.id)
            obj.put("timestamp", e.timestampMillis)
            obj.put("photoPath", e.photoPath)
            obj.put("failureCount", e.failureCount)
            array.put(obj)
        }
        val indexFile = File(getLogsDir(context), INDEX_FILE)
        try {
            indexFile.writeText(array.toString(2))
        } catch (e: Exception) {
            Log.e(TAG, "Failed to write intruder index: ${e.message}")
        }
    }

    /**
     * Records a break-in attempt. If CAMERA permission is granted and front camera is present,
     * takes a silent background snapshot without any camera preview or flash.
     */
    fun recordAttempt(context: Context, failedCount: Int) {
        val timestamp = System.currentTimeMillis()
        val entryId = UUID.randomUUID().toString()

        val hasCameraPerm = ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
        if (!hasCameraPerm) {
            // Record event without photo
            addLog(context, IntruderLogEntry(entryId, timestamp, null, failedCount))
            return
        }

        try {
            captureFrontCameraPhoto(context) { photoFile ->
                addLog(context, IntruderLogEntry(entryId, timestamp, photoFile?.absolutePath, failedCount))
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error initiating front camera capture: ${e.message}")
            addLog(context, IntruderLogEntry(entryId, timestamp, null, failedCount))
        }
    }

    @SuppressLint("MissingPermission")
    private fun captureFrontCameraPhoto(context: Context, onCaptured: (File?) -> Unit) {
        val cameraManager = context.getSystemService(Context.CAMERA_SERVICE) as? CameraManager
        if (cameraManager == null) {
            onCaptured(null)
            return
        }

        val frontCameraId = cameraManager.cameraIdList.firstOrNull { id ->
            val chars = cameraManager.getCameraCharacteristics(id)
            chars.get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_FRONT
        } ?: cameraManager.cameraIdList.firstOrNull()

        if (frontCameraId == null) {
            onCaptured(null)
            return
        }

        val thread = HandlerThread("IntruderCaptureThread").apply { start() }
        val handler = Handler(thread.looper)

        val imageReader = ImageReader.newInstance(640, 480, ImageFormat.JPEG, 2)
        val photoFile = File(getLogsDir(context), "intruder_${System.currentTimeMillis()}.jpg")
        var activeCamera: CameraDevice? = null

        imageReader.setOnImageAvailableListener({ reader ->
            val image = reader.acquireLatestImage() ?: return@setOnImageAvailableListener
            try {
                val buffer = image.planes[0].buffer
                val bytes = ByteArray(buffer.remaining())
                buffer.get(bytes)
                FileOutputStream(photoFile).use { it.write(bytes) }
                onCaptured(photoFile)
            } catch (e: Exception) {
                Log.e(TAG, "Failed saving intruder photo: ${e.message}")
                onCaptured(null)
            } finally {
                image.close()
                reader.close()
                try { activeCamera?.close() } catch (_: Exception) {}
                thread.quitSafely()
            }
        }, handler)

        try {
            cameraManager.openCamera(frontCameraId, object : CameraDevice.StateCallback() {
                override fun onOpened(camera: CameraDevice) {
                    activeCamera = camera
                    try {
                        val captureBuilder = camera.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE).apply {
                            addTarget(imageReader.surface)
                            set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE)
                        }

                        @Suppress("DEPRECATION")
                        camera.createCaptureSession(
                            listOf(imageReader.surface),
                            object : CameraCaptureSession.StateCallback() {
                                override fun onConfigured(session: CameraCaptureSession) {
                                    try {
                                        session.capture(captureBuilder.build(), null, handler)
                                    } catch (e: Exception) {
                                        Log.e(TAG, "Failed session.capture: ${e.message}")
                                        camera.close()
                                        thread.quitSafely()
                                        onCaptured(null)
                                    }
                                }

                                override fun onConfigureFailed(session: CameraCaptureSession) {
                                    camera.close()
                                    thread.quitSafely()
                                    onCaptured(null)
                                }
                            },
                            handler
                        )
                    } catch (e: Exception) {
                        Log.e(TAG, "Capture config error: ${e.message}")
                        camera.close()
                        thread.quitSafely()
                        onCaptured(null)
                    }
                }

                override fun onDisconnected(camera: CameraDevice) {
                    camera.close()
                    thread.quitSafely()
                }

                override fun onError(camera: CameraDevice, error: Int) {
                    camera.close()
                    thread.quitSafely()
                    onCaptured(null)
                }
            }, handler)
        } catch (e: Exception) {
            Log.e(TAG, "Failed opening camera: ${e.message}")
            thread.quitSafely()
            onCaptured(null)
        }
    }
}

