package com.bgremover.engine

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.util.Log
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.segmentation.Segmentation
import com.google.mlkit.vision.segmentation.Segmenter
import com.google.mlkit.vision.segmentation.selfie.SelfieSegmenterOptions
import com.google.mlkit.vision.segmentation.subject.SubjectSegmentation
import com.google.mlkit.vision.segmentation.subject.SubjectSegmenter
import com.google.mlkit.vision.segmentation.subject.SubjectSegmenterOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.nio.FloatBuffer
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

enum class CutoutMode {
    SMART_AUTO,     // Tự động nhận diện: nếu nền studio/đơn sắc -> tách giữ nguyên đồ vật, cầu thang, lò sưởi, cửa sổ; nếu ảnh đời thực -> AI chân dung
    SMART_OBJECT,   // Tách vật thể & chi tiết: Giữ nguyên đạo cụ, nội thất (cầu thang, cửa sổ, lò sưởi) với viền sắc nét 100%
    AI_PORTRAIT     // AI Chân dung: Chỉ giữ người (ML Kit)
}

class BgRemoverEngine(private val context: Context) {

    private val TAG = "BgRemoverEngine"

    // 1. Play Services Subject Segmenter (Handles general objects if module ready)
    private val subjectOptions: SubjectSegmenterOptions = SubjectSegmenterOptions.Builder()
        .enableForegroundBitmap()
        .enableForegroundConfidenceMask()
        .build()

    private val subjectSegmenter: SubjectSegmenter = SubjectSegmentation.getClient(subjectOptions)

    // 2. Bundled 100% On-Device Local Selfie Segmenter
    private val selfieOptions: SelfieSegmenterOptions = SelfieSegmenterOptions.Builder()
        .setDetectorMode(SelfieSegmenterOptions.SINGLE_IMAGE_MODE)
        .build()

    private val selfieSegmenter: Segmenter = Segmentation.getClient(selfieOptions)

    suspend fun removeBackground(
        inputBitmap: Bitmap,
        mode: CutoutMode = CutoutMode.SMART_AUTO
    ): Result<Bitmap> = withContext(Dispatchers.Default) {

        // Check if Smart Object engine should be used
        val shouldTrySmartEngine = mode == CutoutMode.SMART_OBJECT ||
                (mode == CutoutMode.SMART_AUTO && SmartCutoutEngine.isUniformBackground(inputBitmap))

        if (shouldTrySmartEngine) {
            try {
                Log.d(TAG, "Running high-precision SmartCutoutEngine (preserving props & sharp edges)...")
                val smartResult = SmartCutoutEngine.removeBackground(inputBitmap)
                return@withContext Result.success(smartResult)
            } catch (e: Exception) {
                Log.w(TAG, "SmartCutoutEngine fallback: ${e.message}")
            }
        }

        // Fallback or explicit AI Portrait: ML Kit processing
        val inputImage = InputImage.fromBitmap(inputBitmap, 0)

        // Try Subject Segmentation if not in portrait-only mode
        if (mode != CutoutMode.AI_PORTRAIT) {
            val subjectResult: com.google.mlkit.vision.segmentation.subject.SubjectSegmentationResult? = try {
                suspendCancellableCoroutine { continuation ->
                    subjectSegmenter.process(inputImage)
                        .addOnSuccessListener { segResult ->
                            if (continuation.isActive) continuation.resume(segResult)
                        }
                        .addOnFailureListener { exception ->
                            if (continuation.isActive) continuation.resumeWithException(exception)
                        }
                }
            } catch (e: Exception) {
                Log.w(TAG, "Subject segmenter unavailable: ${e.message}")
                null
            }

            if (subjectResult != null) {
                val foregroundBitmap = subjectResult.foregroundBitmap
                val confidenceMask = subjectResult.foregroundConfidenceMask

                if (confidenceMask != null) {
                    return@withContext Result.success(
                        applyMaskToOriginal(
                            original = inputBitmap,
                            maskBuffer = confidenceMask,
                            maskWidth = inputBitmap.width,
                            maskHeight = inputBitmap.height
                        )
                    )
                } else if (foregroundBitmap != null) {
                    val finalBitmap = if (foregroundBitmap.width == inputBitmap.width && foregroundBitmap.height == inputBitmap.height) {
                        foregroundBitmap.copy(Bitmap.Config.ARGB_8888, true)
                    } else {
                        Bitmap.createScaledBitmap(foregroundBitmap, inputBitmap.width, inputBitmap.height, true).copy(Bitmap.Config.ARGB_8888, true)
                    }
                    return@withContext Result.success(finalBitmap)
                }
            }
        }

        // Bundled Local Selfie Engine
        try {
            val mask: com.google.mlkit.vision.segmentation.SegmentationMask = suspendCancellableCoroutine { continuation ->
                selfieSegmenter.process(inputImage)
                    .addOnSuccessListener { segMask ->
                        if (continuation.isActive) continuation.resume(segMask)
                    }
                    .addOnFailureListener { exception ->
                        if (continuation.isActive) continuation.resumeWithException(exception)
                    }
            }

            val finalTransparentBitmap = applyMaskToOriginal(
                original = inputBitmap,
                maskBuffer = mask.buffer.asFloatBuffer(),
                maskWidth = mask.width,
                maskHeight = mask.height
            )
            Result.success(finalTransparentBitmap)
        } catch (e: Exception) {
            Log.e(TAG, "All segmentation engines failed", e)
            Result.failure(e)
        }
    }

    private fun applyMaskToOriginal(
        original: Bitmap,
        maskBuffer: FloatBuffer,
        maskWidth: Int,
        maskHeight: Int
    ): Bitmap {
        val totalPixels = maskWidth * maskHeight
        val maskPixels = IntArray(totalPixels)
        maskBuffer.rewind()

        for (i in 0 until totalPixels) {
            val confidence = if (maskBuffer.hasRemaining()) maskBuffer.get() else 0f
            // Crisp, clean thresholding to prevent dirty blotches ("lem nhem")
            val alpha = when {
                confidence >= 0.65f -> 255
                confidence <= 0.45f -> 0
                else -> (((confidence - 0.45f) / 0.20f) * 255f).toInt().coerceIn(0, 255)
            }
            maskPixels[i] = (alpha shl 24) or 0x00FFFFFF
        }

        val maskBitmap = Bitmap.createBitmap(maskPixels, maskWidth, maskHeight, Bitmap.Config.ARGB_8888)

        val scaledMask = if (maskWidth != original.width || maskHeight != original.height) {
            val scaled = Bitmap.createScaledBitmap(maskBitmap, original.width, original.height, true)
            maskBitmap.recycle()
            scaled
        } else {
            maskBitmap
        }

        val resultBitmap = Bitmap.createBitmap(original.width, original.height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(resultBitmap)
        canvas.drawBitmap(original, 0f, 0f, null)

        val maskPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG).apply {
            xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_IN)
        }
        canvas.drawBitmap(scaledMask, 0f, 0f, maskPaint)
        scaledMask.recycle()

        return resultBitmap
    }

    fun close() {
        try {
            subjectSegmenter.close()
        } catch (_: Exception) {}
        try {
            selfieSegmenter.close()
        } catch (_: Exception) {}
    }
}
