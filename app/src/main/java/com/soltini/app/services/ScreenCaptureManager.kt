package com.soltini.app.services

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.Image
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Handler
import android.os.Looper
import android.util.Base64
import android.util.DisplayMetrics
import android.util.Log
import android.view.WindowManager
import java.io.ByteArrayOutputStream
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull

/**
 * ScreenCaptureManager
 *
 * Manages MediaProjection, VirtualDisplay, and ImageReader to capture screen frames
 * for Gemini Live multimodal vision perception.
 */
object ScreenCaptureManager {

    private const val TAG = "ScreenCaptureManager"

    private var projectionManager: MediaProjectionManager? = null
    private var mediaProjection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null
    private var screenDensity: Int = DisplayMetrics.DENSITY_DEFAULT
    private var screenWidth: Int = 720
    private var screenHeight: Int = 1280

    private var permissionDeferred: CompletableDeferred<Boolean>? = null
    private var permissionResultCode: Int = 0
    private var permissionData: Intent? = null

    fun initialize(context: Context) {
        val wm = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val metrics = DisplayMetrics()
        @Suppress("DEPRECATION")
        wm.defaultDisplay.getRealMetrics(metrics)
        screenWidth = metrics.widthPixels
        screenHeight = metrics.heightPixels
        screenDensity = metrics.densityDpi
        projectionManager = context.getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
    }

    fun hasPermission(): Boolean = mediaProjection != null || (permissionResultCode != 0 && permissionData != null)

    suspend fun requestCapturePermission(context: Context): Boolean {
        if (hasPermission()) return true

        permissionDeferred = CompletableDeferred()
        com.soltini.app.ui.ScreenCapturePermissionActivity.requestPermission(context)

        val granted = withTimeoutOrNull(15000L) {
            permissionDeferred?.await()
        } ?: false

        return granted
    }

    fun onPermissionGranted(resultCode: Int, data: Intent) {
        permissionResultCode = resultCode
        permissionData = data
        permissionDeferred?.complete(true)
        Log.i(TAG, "MediaProjection permission granted")
    }

    fun onPermissionDenied() {
        permissionDeferred?.complete(false)
        Log.w(TAG, "MediaProjection permission denied by user")
    }

    @SuppressLint("WrongConstant")
    private fun ensureProjection(context: Context): Boolean {
        if (mediaProjection != null) return true
        if (permissionResultCode == 0 || permissionData == null) return false

        try {
            if (projectionManager == null) {
                initialize(context)
            }
            mediaProjection = projectionManager?.getMediaProjection(permissionResultCode, permissionData!!)
            return mediaProjection != null
        } catch (e: Exception) {
            Log.e(TAG, "Failed to create MediaProjection: ${e.message}", e)
            return false
        }
    }

    /**
     * Captures current screen frame as JPEG compressed byte array.
     */
    suspend fun captureScreenshotJpeg(context: Context, quality: Int = 75, maxDimension: Int = 1024): ByteArray? {
        val bitmap = captureScreenshotBitmap(context, maxDimension) ?: return null
        return try {
            val stream = ByteArrayOutputStream()
            bitmap.compress(Bitmap.CompressFormat.JPEG, quality, stream)
            bitmap.recycle()
            stream.toByteArray()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to compress bitmap to JPEG: ${e.message}")
            null
        }
    }

    /**
     * Captures current screen frame as Base64 JPEG string for AI vision prompts.
     */
    suspend fun captureScreenshotBase64(context: Context, quality: Int = 70): String? {
        val bytes = captureScreenshotJpeg(context, quality) ?: return null
        return Base64.encodeToString(bytes, Base64.NO_WRAP)
    }

    @SuppressLint("WrongConstant")
    suspend fun captureScreenshotBitmap(context: Context, maxDimension: Int = 1024): Bitmap? {
        if (!ensureProjection(context)) {
            val granted = requestCapturePermission(context)
            if (!granted || !ensureProjection(context)) {
                Log.w(TAG, "Cannot capture screen: MediaProjection permission missing")
                return null
            }
        }

        return try {
            val width = screenWidth
            val height = screenHeight

            val reader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 2)
            val captureDeferred = CompletableDeferred<Bitmap?>()

            val vDisplay = mediaProjection?.createVirtualDisplay(
                "SoltiniScreenVision",
                width,
                height,
                screenDensity,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                reader.surface,
                null,
                Handler(Looper.getMainLooper())
            )

            reader.setOnImageAvailableListener({ r ->
                var image: Image? = null
                try {
                    image = r.acquireLatestImage()
                    if (image != null) {
                        val planes = image.planes
                        val buffer = planes[0].buffer
                        val pixelStride = planes[0].pixelStride
                        val rowStride = planes[0].rowStride
                        val rowPadding = rowStride - pixelStride * width

                        val rawBitmap = Bitmap.createBitmap(
                            width + rowPadding / pixelStride,
                            height,
                            Bitmap.Config.ARGB_8888
                        )
                        rawBitmap.copyPixelsFromBuffer(buffer)

                        // Crop if rowPadding exists
                        val croppedBitmap = if (rowPadding > 0) {
                            Bitmap.createBitmap(rawBitmap, 0, 0, width, height)
                        } else {
                            rawBitmap
                        }

                        // Resize if exceeding maxDimension
                        val scale = if (width > maxDimension || height > maxDimension) {
                            val max = Math.max(width, height)
                            maxDimension.toFloat() / max.toFloat()
                        } else {
                            1.0f
                        }

                        val finalBitmap = if (scale < 1.0f) {
                            val scaledW = (width * scale).toInt()
                            val scaledH = (height * scale).toInt()
                            Bitmap.createScaledBitmap(croppedBitmap, scaledW, scaledH, true)
                        } else {
                            croppedBitmap
                        }

                        captureDeferred.complete(finalBitmap)
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Error processing screen frame: ${e.message}")
                    captureDeferred.complete(null)
                } finally {
                    image?.close()
                }
            }, Handler(Looper.getMainLooper()))

            val resultBitmap = withTimeoutOrNull(3000L) {
                captureDeferred.await()
            }

            vDisplay?.release()
            reader.close()
            resultBitmap
        } catch (e: Exception) {
            Log.e(TAG, "Error during screen capture: ${e.message}", e)
            null
        }
    }

    fun release() {
        virtualDisplay?.release()
        virtualDisplay = null
        imageReader?.close()
        imageReader = null
        mediaProjection?.stop()
        mediaProjection = null
    }
}
