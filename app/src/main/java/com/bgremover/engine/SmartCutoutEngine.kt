package com.bgremover.engine

import android.graphics.Bitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.ArrayDeque
import kotlin.math.abs

object SmartCutoutEngine {

    /**
     * Checks if the image has a predominantly uniform background
     * (e.g. solid white, studio backdrop, green screen, solid black).
     */
    fun isUniformBackground(bitmap: Bitmap, maxTolerance: Int = 20): Boolean {
        val w = bitmap.width
        val h = bitmap.height
        if (w < 10 || h < 10) return false

        val corner1 = bitmap.getPixel(0, 0)
        val corner2 = bitmap.getPixel(w - 1, 0)
        val corner3 = bitmap.getPixel(0, h - 1)
        val corner4 = bitmap.getPixel(w - 1, h - 1)

        fun maxChannelDiff(c1: Int, c2: Int): Int {
            val r1 = (c1 shr 16) and 0xFF
            val g1 = (c1 shr 8) and 0xFF
            val b1 = c1 and 0xFF
            val r2 = (c2 shr 16) and 0xFF
            val g2 = (c2 shr 8) and 0xFF
            val b2 = c2 and 0xFF
            return maxOf(abs(r1 - r2), abs(g1 - g2), abs(b1 - b2))
        }

        return maxChannelDiff(corner1, corner2) <= maxTolerance &&
               maxChannelDiff(corner1, corner3) <= maxTolerance &&
               maxChannelDiff(corner1, corner4) <= maxTolerance
    }

    /**
     * High-precision, full-resolution edge-aware connected background extractor.
     * Preserves the primary subject AND all interacting furniture/props
     * (e.g. stairs, windows, fireplaces, chairs) while removing uniform background
     * and filtering out small floating background sticker clutter.
     */
    suspend fun removeBackground(
        bitmap: Bitmap,
        tolerance: Int = 13,
        minComponentRatio: Float = 0.015f,
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

        // 2. Fast flood-fill BFS from all 4 borders using a circular IntArray queue
        val isBackground = BooleanArray(totalPixels)
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
            if (!isBackground[topIdx] && isBgColor(pixels[topIdx])) {
                isBackground[topIdx] = true
                queue[tail++] = topIdx
            }
            if (!isBackground[botIdx] && isBgColor(pixels[botIdx])) {
                isBackground[botIdx] = true
                queue[tail++] = botIdx
            }
        }

        for (y in 0 until height) {
            val leftIdx = y * width
            val rightIdx = y * width + (width - 1)
            if (!isBackground[leftIdx] && isBgColor(pixels[leftIdx])) {
                isBackground[leftIdx] = true
                queue[tail++] = leftIdx
            }
            if (!isBackground[rightIdx] && isBgColor(pixels[rightIdx])) {
                isBackground[rightIdx] = true
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
                if (!isBackground[nIdx] && isBgColor(pixels[nIdx])) {
                    isBackground[nIdx] = true
                    queue[tail++] = nIdx
                }
            }
            if (cx < width - 1) {
                val nIdx = currentIdx + 1
                if (!isBackground[nIdx] && isBgColor(pixels[nIdx])) {
                    isBackground[nIdx] = true
                    queue[tail++] = nIdx
                }
            }
            if (cy > 0) {
                val nIdx = currentIdx - width
                if (!isBackground[nIdx] && isBgColor(pixels[nIdx])) {
                    isBackground[nIdx] = true
                    queue[tail++] = nIdx
                }
            }
            if (cy < height - 1) {
                val nIdx = currentIdx + width
                if (!isBackground[nIdx] && isBgColor(pixels[nIdx])) {
                    isBackground[nIdx] = true
                    queue[tail++] = nIdx
                }
            }
        }

        // 3. Connected Components Analysis for foreground
        val isForegroundKept = BooleanArray(totalPixels)

        if (keepFloatingStickers) {
            // Keep everything that wasn't flooded by background
            for (i in 0 until totalPixels) {
                if (!isBackground[i]) isForegroundKept[i] = true
            }
        } else {
            // Find connected components to retain the main subject and its props
            // while discarding disconnected small sticker clutter
            val visitedFg = BooleanArray(totalPixels)
            val minComponentPixels = (totalPixels * minComponentRatio).toInt()

            val compQueue = IntArray(totalPixels)

            for (i in 0 until totalPixels) {
                if (!isBackground[i] && !visitedFg[i]) {
                    var cHead = 0
                    var cTail = 0

                    visitedFg[i] = true
                    compQueue[cTail++] = i

                    while (cHead < cTail) {
                        val curr = compQueue[cHead++]
                        val cx = curr % width
                        val cy = curr / width

                        if (cx > 0) {
                            val nIdx = curr - 1
                            if (!isBackground[nIdx] && !visitedFg[nIdx]) {
                                visitedFg[nIdx] = true
                                compQueue[cTail++] = nIdx
                            }
                        }
                        if (cx < width - 1) {
                            val nIdx = curr + 1
                            if (!isBackground[nIdx] && !visitedFg[nIdx]) {
                                visitedFg[nIdx] = true
                                compQueue[cTail++] = nIdx
                            }
                        }
                        if (cy > 0) {
                            val nIdx = curr - width
                            if (!isBackground[nIdx] && !visitedFg[nIdx]) {
                                visitedFg[nIdx] = true
                                compQueue[cTail++] = nIdx
                            }
                        }
                        if (cy < height - 1) {
                            val nIdx = curr + width
                            if (!isBackground[nIdx] && !visitedFg[nIdx]) {
                                visitedFg[nIdx] = true
                                compQueue[cTail++] = nIdx
                            }
                        }
                    }

                    val compSize = cTail
                    if (compSize >= minComponentPixels) {
                        for (k in 0 until compSize) {
                            isForegroundKept[compQueue[k]] = true
                        }
                    }
                }
            }
        }

        // 4. Generate final transparent output bitmap with subtle edge anti-aliasing
        val outputPixels = IntArray(totalPixels)

        for (y in 0 until height) {
            val yOffset = y * width
            for (x in 0 until width) {
                val idx = yOffset + x
                if (isForegroundKept[idx]) {
                    // Check if on edge for 1px anti-aliasing
                    val isEdge = (x > 0 && !isForegroundKept[idx - 1]) ||
                                 (x < width - 1 && !isForegroundKept[idx + 1]) ||
                                 (y > 0 && !isForegroundKept[idx - width]) ||
                                 (y < height - 1 && !isForegroundKept[idx + width])

                    val orig = pixels[idx]
                    outputPixels[idx] = if (isEdge) {
                        // Smooth edge transition
                        (0xEE shl 24) or (orig and 0x00FFFFFF)
                    } else {
                        (0xFF shl 24) or (orig and 0x00FFFFFF)
                    }
                } else {
                    outputPixels[idx] = 0 // Full transparent
                }
            }
        }

        Bitmap.createBitmap(outputPixels, width, height, Bitmap.Config.ARGB_8888)
    }
}
