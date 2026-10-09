package com.bgremover.util

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PointF
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.Shader
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.core.content.FileProvider
import androidx.exifinterface.media.ExifInterface
import com.bgremover.model.SavedCutout
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object BitmapUtils {

    suspend fun loadBitmapFromUri(context: Context, uri: Uri, maxDimension: Int = 2048): Bitmap? =
        withContext(Dispatchers.IO) {
            try {
                // 1. Check dimensions first
                val options = BitmapFactory.Options().apply {
                    inJustDecodeBounds = true
                }
                context.contentResolver.openInputStream(uri)?.use { stream ->
                    BitmapFactory.decodeStream(stream, null, options)
                }

                if (options.outWidth <= 0 || options.outHeight <= 0) return@withContext null

                // 2. Calculate sample size
                var sampleSize = 1
                var width = options.outWidth
                var height = options.outHeight
                while (width > maxDimension || height > maxDimension) {
                    sampleSize *= 2
                    width /= 2
                    height /= 2
                }

                val decodeOptions = BitmapFactory.Options().apply {
                    inSampleSize = sampleSize
                    inPreferredConfig = Bitmap.Config.ARGB_8888
                    inMutable = true
                }

                val decodedBitmap = context.contentResolver.openInputStream(uri)?.use { stream ->
                    BitmapFactory.decodeStream(stream, null, decodeOptions)
                } ?: return@withContext null

                // 3. Fix EXIF orientation
                val orientation = getExifOrientation(context, uri)
                if (orientation != 0) {
                    val matrix = Matrix().apply { postRotate(orientation.toFloat()) }
                    val rotated = Bitmap.createBitmap(
                        decodedBitmap, 0, 0,
                        decodedBitmap.width, decodedBitmap.height,
                        matrix, true
                    )
                    decodedBitmap.recycle()
                    rotated
                } else {
                    decodedBitmap
                }
            } catch (e: Exception) {
                e.printStackTrace()
                null
            }
        }

    private fun getExifOrientation(context: Context, uri: Uri): Int {
        return try {
            context.contentResolver.openInputStream(uri)?.use { stream ->
                val exif = ExifInterface(stream)
                when (exif.getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)) {
                    ExifInterface.ORIENTATION_ROTATE_90 -> 90
                    ExifInterface.ORIENTATION_ROTATE_180 -> 180
                    ExifInterface.ORIENTATION_ROTATE_270 -> 270
                    else -> 0
                }
            } ?: 0
        } catch (e: Exception) {
            0
        }
    }

    suspend fun saveTransparentPngToGallery(
        context: Context,
        bitmap: Bitmap,
        titlePrefix: String = "Cutout"
    ): Result<Uri> = withContext(Dispatchers.IO) {
        val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
        val fileName = "${titlePrefix}_${timestamp}.png"

        try {
            val resolver = context.contentResolver
            val contentValues = ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME, fileName)
                put(MediaStore.Images.Media.MIME_TYPE, "image/png")
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/BgRemover")
                    put(MediaStore.Images.Media.IS_PENDING, 1)
                }
            }

            val imageUri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, contentValues)
                ?: return@withContext Result.failure(Exception("Cannot create MediaStore entry"))

            resolver.openOutputStream(imageUri)?.use { outStream ->
                // PNG compression is lossless and preserves alpha transparency!
                val success = bitmap.compress(Bitmap.CompressFormat.PNG, 100, outStream)
                if (!success) {
                    return@withContext Result.failure(Exception("Bitmap PNG compression failed"))
                }
            } ?: return@withContext Result.failure(Exception("Cannot open OutputStream"))

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                contentValues.clear()
                contentValues.put(MediaStore.Images.Media.IS_PENDING, 0)
                resolver.update(imageUri, contentValues, null, null)
            }

            Result.success(imageUri)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun saveToCacheForSharing(context: Context, bitmap: Bitmap): Uri? =
        withContext(Dispatchers.IO) {
            try {
                val cacheDir = File(context.cacheDir, "shared_images").apply { mkdirs() }
                val tempFile = File(cacheDir, "cutout_share.png")
                FileOutputStream(tempFile).use { out ->
                    bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
                }
                FileProvider.getUriForFile(
                    context,
                    "${context.packageName}.fileprovider",
                    tempFile
                )
            } catch (e: Exception) {
                e.printStackTrace()
                null
            }
        }

    suspend fun querySavedCutouts(context: Context): List<SavedCutout> =
        withContext(Dispatchers.IO) {
            val list = mutableListOf<SavedCutout>()
            try {
                val projection = arrayOf(
                    MediaStore.Images.Media._ID,
                    MediaStore.Images.Media.DISPLAY_NAME,
                    MediaStore.Images.Media.SIZE,
                    MediaStore.Images.Media.DATE_MODIFIED,
                    MediaStore.Images.Media.WIDTH,
                    MediaStore.Images.Media.HEIGHT
                )

                val selection = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    "${MediaStore.Images.Media.MIME_TYPE} = ? AND ${MediaStore.Images.Media.RELATIVE_PATH} LIKE ?"
                } else {
                    "${MediaStore.Images.Media.MIME_TYPE} = ? AND ${MediaStore.Images.Media.DISPLAY_NAME} LIKE ?"
                }

                val selectionArgs = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    arrayOf("image/png", "%BgRemover%")
                } else {
                    arrayOf("image/png", "Cutout_%")
                }

                val sortOrder = "${MediaStore.Images.Media.DATE_MODIFIED} DESC"

                context.contentResolver.query(
                    MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                    projection,
                    selection,
                    selectionArgs,
                    sortOrder
                )?.use { cursor ->
                    val idCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media._ID)
                    val nameCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DISPLAY_NAME)
                    val sizeCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.SIZE)
                    val dateCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DATE_MODIFIED)
                    val widthCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.WIDTH)
                    val heightCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.HEIGHT)

                    while (cursor.moveToNext()) {
                        val id = cursor.getLong(idCol)
                        val name = cursor.getString(nameCol) ?: "Cutout"
                        val size = cursor.getLong(sizeCol)
                        val date = cursor.getLong(dateCol) * 1000L
                        val width = cursor.getInt(widthCol)
                        val height = cursor.getInt(heightCol)

                        val contentUri = Uri.withAppendedPath(
                            MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                            id.toString()
                        )
                        list.add(
                            SavedCutout(
                                uri = contentUri,
                                name = name,
                                sizeBytes = size,
                                dateModified = date,
                                width = width,
                                height = height
                            )
                        )
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
            list
        }

    fun applyEraseStroke(targetBitmap: Bitmap, strokePath: Path, strokeWidthPx: Float) {
        val paint = Paint().apply {
            isAntiAlias = true
            isDither = true
            xfermode = PorterDuffXfermode(PorterDuff.Mode.CLEAR)
            style = Paint.Style.STROKE
            strokeJoin = Paint.Join.ROUND
            strokeCap = Paint.Cap.ROUND
            strokeWidth = strokeWidthPx
        }
        val canvas = Canvas(targetBitmap)
        canvas.drawPath(strokePath, paint)
    }

    fun applyRestoreStroke(
        targetBitmap: Bitmap,
        originalBitmap: Bitmap,
        strokePath: Path,
        strokeWidthPx: Float
    ) {
        val shader = BitmapShader(originalBitmap, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP)
        val paint = Paint().apply {
            isAntiAlias = true
            isDither = true
            this.shader = shader
            style = Paint.Style.STROKE
            strokeJoin = Paint.Join.ROUND
            strokeCap = Paint.Cap.ROUND
            strokeWidth = strokeWidthPx
        }
        val canvas = Canvas(targetBitmap)
        canvas.drawPath(strokePath, paint)
    }

    suspend fun deleteSavedCutout(context: Context, uri: Uri): Boolean = withContext(Dispatchers.IO) {
        try {
            context.contentResolver.delete(uri, null, null) > 0
        } catch (e: Exception) {
            e.printStackTrace()
            false
        }
    }

    suspend fun saveCompositeImageToGallery(
        context: Context,
        cutoutBitmap: Bitmap,
        backgroundColor: androidx.compose.ui.graphics.Color,
        titlePrefix: String = "Cutout_Bg"
    ): Result<Uri> = withContext(Dispatchers.IO) {
        try {
            val compositeBitmap = Bitmap.createBitmap(cutoutBitmap.width, cutoutBitmap.height, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(compositeBitmap)
            val argb = android.graphics.Color.argb(
                (backgroundColor.alpha * 255).toInt(),
                (backgroundColor.red * 255).toInt(),
                (backgroundColor.green * 255).toInt(),
                (backgroundColor.blue * 255).toInt()
            )
            canvas.drawColor(argb)
            canvas.drawBitmap(cutoutBitmap, 0f, 0f, null)
            val res = saveTransparentPngToGallery(context, compositeBitmap, titlePrefix)
            compositeBitmap.recycle()
            res
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun smoothCutoutEdges(source: Bitmap): Bitmap = withContext(Dispatchers.Default) {
        val w = source.width
        val h = source.height
        val total = w * h
        val pixels = IntArray(total)
        source.getPixels(pixels, 0, w, 0, 0, w, h)
        val output = IntArray(total)

        for (y in 0 until h) {
            val yOffset = y * w
            for (x in 0 until w) {
                val idx = yOffset + x
                val curr = pixels[idx]
                val alpha = (curr shr 24) and 0xFF

                if (alpha in 1..254) {
                    var neighborAlphaSum = 0
                    var count = 0
                    if (x > 0) { neighborAlphaSum += (pixels[idx - 1] shr 24) and 0xFF; count++ }
                    if (x < w - 1) { neighborAlphaSum += (pixels[idx + 1] shr 24) and 0xFF; count++ }
                    if (y > 0) { neighborAlphaSum += (pixels[idx - w] shr 24) and 0xFF; count++ }
                    if (y < h - 1) { neighborAlphaSum += (pixels[idx + w] shr 24) and 0xFF; count++ }

                    val smoothedAlpha = if (count > 0) ((alpha * 2 + neighborAlphaSum / count) / 3).coerceIn(0, 255) else alpha
                    output[idx] = (smoothedAlpha shl 24) or (curr and 0x00FFFFFF)
                } else {
                    output[idx] = curr
                }
            }
        }
        Bitmap.createBitmap(output, w, h, Bitmap.Config.ARGB_8888)
    }

    fun copyBitmap(source: Bitmap): Bitmap {
        return source.copy(Bitmap.Config.ARGB_8888, true)
    }
}
