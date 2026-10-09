package com.bgremover.engine

import android.graphics.Bitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.BitSet
import kotlin.math.abs

object SmartCutoutEngine {

    /**
     * Checks if the image has a predominantly uniform background
     * (e.g. solid white, studio backdrop, green screen, solid black).
     * Samples multiple points across all 4 borders to avoid false positives.
     */
    fun isUniformBackground(bitmap: Bitmap, maxTolerance: Int = 22): Boolean {
        val w = bitmap.width
        val h = bitmap.height
        if (w < 20 || h < 20) return false

        val samplePoints = mutableListOf<Int>()
        val stepX = maxOf(1, w / 20)
        val stepY = maxOf(1, h / 20)

        for (x in 0 until w step stepX) {
            samplePoints.add(bitmap.getPixel(x, 0))
            samplePoints.add(bitmap.getPixel(x, h - 1))
        }
        for (y in 0 until h step stepY) {
            samplePoints.add(bitmap.getPixel(0, y))
            samplePoints.add(bitmap.getPixel(w - 1, y))
        }

        if (samplePoints.isEmpty()) return false

        var sumR = 0L
        var sumG = 0L
        var sumB = 0L
        for (p in samplePoints) {
            sumR += (p shr 16) and 0xFF
            sumG += (p shr 8) and 0xFF
            sumB += p and 0xFF
        }
        val avgR = (sumR / samplePoints.size).toInt()
        val avgG = (sumG / samplePoints.size).toInt()
        val avgB = (sumB / samplePoints.size).toInt()

        var devCount = 0
        for (p in samplePoints) {
            val r = (p shr 16) and 0xFF
            val g = (p shr 8) and 0xFF
            val b = p and 0xFF
            val maxDiff = maxOf(abs(r - avgR), abs(g - avgG), abs(b - avgB))
            if (maxDiff > maxTolerance) {
                devCount++
            }
        }

        val uniformRatio = 1.0f - (devCount.toFloat() / samplePoints.size)
        return uniformRatio >= 0.85f
    }

    /**
     * High-precision, full-resolution edge-aware connected background extractor.
     * Preserves the primary subject AND all interacting furniture/props
     * (e.g. stairs, windows, fireplaces, chairs) while removing uniform background.
     * Uses memory-efficient BitSets and reuses queues to prevent OutOfMemoryError.
     */
    suspend fun removeBackground(
        bitmap: Bitmap,
        tolerance: Int = 13,
        minComponentRatio: Float = 0.008f,
        keepFloatingStickers: Boolean = false
    ): Bitmap = withContext(Dispatchers.Default) {
        val width = bitmap.width
        val height = bitmap.height
        val totalPixels = width * height

        val pixels = IntArray(totalPixels)
        bitmap.getPixels(pixels, 0, width, 0, 0, width, height)

        // 1. Calculate average border background color
        var sumR = 0L
        var sumG = 0L
        var sumB = 0L
        var sampleCount = 0

        val stepX = maxOf(1, width / 100)
        val stepY = maxOf(1, height / 100)

        for (x in 0 until width step stepX) {
            val topP = pixels[x]
            val botP = pixels[(height - 1) * width + x]
            sumR += ((topP shr 16) and 0xFF) + ((botP shr 16) and 0xFF)
            sumG += ((topP shr 8) and 0xFF) + ((botP shr 8) and 0xFF)
            sumB += (topP and 0xFF) + (botP and 0xFF)
            sampleCount += 2
        }

        for (y in 0 until height step stepY) {
            val leftP = pixels[y * width]
            val rightP = pixels[y * width + (width - 1)]
            sumR += ((leftP shr 16) and 0xFF) + ((rightP shr 16) and 0xFF)
            sumG += ((leftP shr 8) and 0xFF) + ((rightP shr 8) and 0xFF)
            sumB += (leftP and 0xFF) + (rightP and 0xFF)
            sampleCount += 2
        }

        val bgR = (sumR / sampleCount).toInt()
        val bgG = (sumG / sampleCount).toInt()
        val bgB = (sumB / sampleCount).toInt()

        // 2. Fast flood-fill BFS using BitSet (1 bit per pixel vs 1 byte in BooleanArray, saving ~12MB)
        val isBackground = BitSet(totalPixels)
        val queue = IntArray(totalPixels)
        var head = 0
        var tail = 0

        fun isBgColor(pixel: Int): Boolean {
            val r = (pixel shr 16) and 0xFF
            val g = (pixel shr 8) and 0xFF
            val b = pixel and 0xFF
            return abs(r - bgR) <= tolerance &&
                   abs(g - bgG) <= tolerance &&
                   abs(b - bgB) <= tolerance
        }

        // Add matching border pixels as initial seeds
        for (x in 0 until width) {
            val topIdx = x
            val botIdx = (height - 1) * width + x
            if (!isBackground.get(topIdx) && isBgColor(pixels[topIdx])) {
                isBackground.set(topIdx)
                queue[tail++] = topIdx
            }
            if (!isBackground.get(botIdx) && isBgColor(pixels[botIdx])) {
                isBackground.set(botIdx)
                queue[tail++] = botIdx
            }
        }

        for (y in 0 until height) {
            val leftIdx = y * width
            val rightIdx = y * width + (width - 1)
            if (!isBackground.get(leftIdx) && isBgColor(pixels[leftIdx])) {
                isBackground.set(leftIdx)
                queue[tail++] = leftIdx
            }
            if (!isBackground.get(rightIdx) && isBgColor(pixels[rightIdx])) {
                isBackground.set(rightIdx)
                queue[tail++] = rightIdx
            }
        }

        // Expand flood fill to all connected background pixels
        while (head < tail) {
            val currentIdx = queue[head++]
            val cx = currentIdx % width
            val cy = currentIdx / width

            // 4-neighborhood
            if (cx > 0) {
                val nIdx = currentIdx - 1
                if (!isBackground.get(nIdx) && isBgColor(pixels[nIdx])) {
                    isBackground.set(nIdx)
                    queue[tail++] = nIdx
                }
            }
            if (cx < width - 1) {
                val nIdx = currentIdx + 1
                if (!isBackground.get(nIdx) && isBgColor(pixels[nIdx])) {
                    isBackground.set(nIdx)
                    queue[tail++] = nIdx
                }
            }
            if (cy > 0) {
                val nIdx = currentIdx - width
                if (!isBackground.get(nIdx) && isBgColor(pixels[nIdx])) {
                    isBackground.set(nIdx)
                    queue[tail++] = nIdx
                }
            }
            if (cy < height - 1) {
                val nIdx = currentIdx + width
                if (!isBackground.get(nIdx) && isBgColor(pixels[nIdx])) {
                    isBackground.set(nIdx)
                    queue[tail++] = nIdx
                }
            }
        }

        // 3. Connected Components Analysis for foreground using BitSets & reused queue
        val isForegroundKept = BitSet(totalPixels)

        if (keepFloatingStickers) {
            for (i in 0 until totalPixels) {
                if (!isBackground.get(i)) isForegroundKept.set(i)
            }
        } else {
            val visitedFg = BitSet(totalPixels)
            val minComponentPixels = (totalPixels * minComponentRatio).toInt()

            for (i in 0 until totalPixels) {
                if (!isBackground.get(i) && !visitedFg.get(i)) {
                    var cHead = 0
                    var cTail = 0

                    visitedFg.set(i)
                    queue[cTail++] = i

                    while (cHead < cTail) {
                        val curr = queue[cHead++]
                        val cx = curr % width
                        val cy = curr / width

                        if (cx > 0) {
                            val nIdx = curr - 1
                            if (!isBackground.get(nIdx) && !visitedFg.get(nIdx)) {
                                visitedFg.set(nIdx)
                                queue[cTail++] = nIdx
                            }
                        }
                        if (cx < width - 1) {
                            val nIdx = curr + 1
                            if (!isBackground.get(nIdx) && !visitedFg.get(nIdx)) {
                                visitedFg.set(nIdx)
                                queue[cTail++] = nIdx
                            }
                        }
                        if (cy > 0) {
                            val nIdx = curr - width
                            if (!isBackground.get(nIdx) && !visitedFg.get(nIdx)) {
                                visitedFg.set(nIdx)
                                queue[cTail++] = nIdx
                            }
                        }
                        if (cy < height - 1) {
                            val nIdx = curr + width
                            if (!isBackground.get(nIdx) && !visitedFg.get(nIdx)) {
                                visitedFg.set(nIdx)
                                queue[cTail++] = nIdx
                            }
                        }
                    }

                    val compSize = cTail
                    if (compSize >= minComponentPixels) {
                        for (k in 0 until compSize) {
                            isForegroundKept.set(queue[k])
                        }
                    }
                }
            }
        }

        // Safety check: verify foreground was reasonably segmented
        val fgCount = isForegroundKept.cardinality()
        val fgRatio = fgCount.toFloat() / totalPixels
        if (fgRatio < 0.005f || fgRatio > 0.99f) {
            throw IllegalStateException("SmartCutout failed to isolate subject (fgRatio=$fgRatio)")
        }

        // 4. Generate final transparent output bitmap with subtle edge anti-aliasing
        val outputPixels = IntArray(totalPixels)

        for (y in 0 until height) {
            val yOffset = y * width
            for (x in 0 until width) {
                val idx = yOffset + x
                if (isForegroundKept.get(idx)) {
                    val isEdge = (x > 0 && !isForegroundKept.get(idx - 1)) ||
                                 (x < width - 1 && !isForegroundKept.get(idx + 1)) ||
                                 (y > 0 && !isForegroundKept.get(idx - width)) ||
                                 (y < height - 1 && !isForegroundKept.get(idx + width))

                    val orig = pixels[idx]
                    outputPixels[idx] = if (isEdge) {
                        (0xEE shl 24) or (orig and 0x00FFFFFF)
                    } else {
                        (0xFF shl 24) or (orig and 0x00FFFFFF)
                    }
                } else {
                    outputPixels[idx] = 0
                }
            }
        }

        Bitmap.createBitmap(outputPixels, width, height, Bitmap.Config.ARGB_8888)
    }
}
