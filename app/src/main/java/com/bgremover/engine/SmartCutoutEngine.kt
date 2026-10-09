package com.bgremover.engine

import android.graphics.Bitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.BitSet
import kotlin.math.abs

object SmartCutoutEngine {

    data class BackgroundAnalysis(
        val isUniform: Boolean,
        val bgR: Int,
        val bgG: Int,
        val bgB: Int
    )

    /**
     * Accurately analyzes background uniformity by clustering dominant colors
     * along the top corners, top border, and upper half of side borders
     * (where background is cleanest in portraits, avoiding subjects at the bottom).
     */
    fun analyzeBackground(bitmap: Bitmap): BackgroundAnalysis {
        val w = bitmap.width
        val h = bitmap.height
        if (w < 20 || h < 20) return BackgroundAnalysis(false, 255, 255, 255)

        val samples = mutableListOf<Int>()
        val stepX = maxOf(1, w / 40)
        val stepY = maxOf(1, h / 40)

        // Top corners
        samples.add(bitmap.getPixel(0, 0))
        samples.add(bitmap.getPixel(w - 1, 0))

        // Top border
        for (x in 0 until w step stepX) {
            samples.add(bitmap.getPixel(x, 0))
        }

        // Top half of left & right borders
        for (y in 0 until (h / 2) step stepY) {
            samples.add(bitmap.getPixel(0, y))
            samples.add(bitmap.getPixel(w - 1, y))
        }

        if (samples.isEmpty()) return BackgroundAnalysis(false, 255, 255, 255)

        // Quantize colors into 16-step bins (step of 16)
        val binCounts = mutableMapOf<Int, Int>()
        for (p in samples) {
            val r = ((p shr 16) and 0xFF) / 16
            val g = ((p shr 8) and 0xFF) / 16
            val b = (p and 0xFF) / 16
            val key = (r shl 16) or (g shl 8) or b
            binCounts[key] = (binCounts[key] ?: 0) + 1
        }

        val dominantEntry = binCounts.maxByOrNull { it.value } ?: return BackgroundAnalysis(false, 255, 255, 255)
        val dominantBin = dominantEntry.key
        val dominantCount = dominantEntry.value
        val ratio = dominantCount.toFloat() / samples.size

        // Calculate precise average of samples inside dominant cluster
        var sumR = 0L
        var sumG = 0L
        var sumB = 0L
        var matchCount = 0
        for (p in samples) {
            val r = ((p shr 16) and 0xFF) / 16
            val g = ((p shr 8) and 0xFF) / 16
            val b = (p and 0xFF) / 16
            val key = (r shl 16) or (g shl 8) or b
            if (key == dominantBin) {
                sumR += (p shr 16) and 0xFF
                sumG += (p shr 8) and 0xFF
                sumB += p and 0xFF
                matchCount++
            }
        }

        val avgR = if (matchCount > 0) (sumR / matchCount).toInt() else 255
        val avgG = if (matchCount > 0) (sumG / matchCount).toInt() else 255
        val avgB = if (matchCount > 0) (sumB / matchCount).toInt() else 255

        val isUniform = ratio >= 0.50f
        return BackgroundAnalysis(isUniform, avgR, avgG, avgB)
    }

    fun isUniformBackground(bitmap: Bitmap): Boolean = analyzeBackground(bitmap).isUniform

    /**
     * High-precision, full-resolution edge-aware connected background extractor.
     * Preserves the primary subject AND all interacting furniture/props
     * (e.g. stairs, windows, fireplaces, chairs) while removing uniform background.
     * Uses memory-efficient BitSets and reuses queues to prevent OutOfMemoryError.
     */
    suspend fun removeBackground(
        bitmap: Bitmap,
        tolerance: Int = 16,
        minComponentRatio: Float = 0.008f,
        keepFloatingStickers: Boolean = false,
        aiProtectedMask: BitSet? = null
    ): Bitmap = withContext(Dispatchers.Default) {
        val width = bitmap.width
        val height = bitmap.height
        val totalPixels = width * height

        val pixels = IntArray(totalPixels)
        bitmap.getPixels(pixels, 0, width, 0, 0, width, height)

        // 1. Accurately detect dominant background color
        val bgAnalysis = analyzeBackground(bitmap)
        val bgR = bgAnalysis.bgR
        val bgG = bgAnalysis.bgG
        val bgB = bgAnalysis.bgB

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

        // Add matching border pixels as initial seeds (NEVER seed AI protected subject/dress pixels!)
        for (x in 0 until width) {
            val topIdx = x
            val botIdx = (height - 1) * width + x
            if ((aiProtectedMask == null || !aiProtectedMask.get(topIdx)) && !isBackground.get(topIdx) && isBgColor(pixels[topIdx])) {
                isBackground.set(topIdx)
                queue[tail++] = topIdx
            }
            if ((aiProtectedMask == null || !aiProtectedMask.get(botIdx)) && !isBackground.get(botIdx) && isBgColor(pixels[botIdx])) {
                isBackground.set(botIdx)
                queue[tail++] = botIdx
            }
        }

        for (y in 0 until height) {
            val leftIdx = y * width
            val rightIdx = y * width + (width - 1)
            if ((aiProtectedMask == null || !aiProtectedMask.get(leftIdx)) && !isBackground.get(leftIdx) && isBgColor(pixels[leftIdx])) {
                isBackground.set(leftIdx)
                queue[tail++] = leftIdx
            }
            if ((aiProtectedMask == null || !aiProtectedMask.get(rightIdx)) && !isBackground.get(rightIdx) && isBgColor(pixels[rightIdx])) {
                isBackground.set(rightIdx)
                queue[tail++] = rightIdx
            }
        }

        // Expand flood fill to all connected background pixels (cannot penetrate AI protected subject/dress)
        while (head < tail) {
            val currentIdx = queue[head++]
            val cx = currentIdx % width
            val cy = currentIdx / width

            // 4-neighborhood
            if (cx > 0) {
                val nIdx = currentIdx - 1
                if ((aiProtectedMask == null || !aiProtectedMask.get(nIdx)) && !isBackground.get(nIdx) && isBgColor(pixels[nIdx])) {
                    isBackground.set(nIdx)
                    queue[tail++] = nIdx
                }
            }
            if (cx < width - 1) {
                val nIdx = currentIdx + 1
                if ((aiProtectedMask == null || !aiProtectedMask.get(nIdx)) && !isBackground.get(nIdx) && isBgColor(pixels[nIdx])) {
                    isBackground.set(nIdx)
                    queue[tail++] = nIdx
                }
            }
            if (cy > 0) {
                val nIdx = currentIdx - width
                if ((aiProtectedMask == null || !aiProtectedMask.get(nIdx)) && !isBackground.get(nIdx) && isBgColor(pixels[nIdx])) {
                    isBackground.set(nIdx)
                    queue[tail++] = nIdx
                }
            }
            if (cy < height - 1) {
                val nIdx = currentIdx + width
                if ((aiProtectedMask == null || !aiProtectedMask.get(nIdx)) && !isBackground.get(nIdx) && isBgColor(pixels[nIdx])) {
                    isBackground.set(nIdx)
                    queue[tail++] = nIdx
                }
            }
        }

        // 2b. Identify Main Foreground Silhouette Components (subject + interacting furniture/props)
        // This allows us to distinguish genuine cavities trapped between the subject and props
        // from disconnected floating stickers (like flowers/hearts) or outer elements.
        val fgVisited = BitSet(totalPixels)
        val isMainForeground = BitSet(totalPixels)
        val minMainSilhouettePixels = (totalPixels * 0.03f).toInt() // At least 3% of the image

        for (i in 0 until totalPixels) {
            if (!isBackground.get(i) && !fgVisited.get(i)) {
                var cHead = 0
                var cTail = 0
                fgVisited.set(i)
                queue[cTail++] = i

                while (cHead < cTail) {
                    val curr = queue[cHead++]
                    val cx = curr % width
                    val cy = curr / width

                    if (cx > 0) {
                        val nIdx = curr - 1
                        if (!isBackground.get(nIdx) && !fgVisited.get(nIdx)) {
                            fgVisited.set(nIdx)
                            queue[cTail++] = nIdx
                        }
                    }
                    if (cx < width - 1) {
                        val nIdx = curr + 1
                        if (!isBackground.get(nIdx) && !fgVisited.get(nIdx)) {
                            fgVisited.set(nIdx)
                            queue[cTail++] = nIdx
                        }
                    }
                    if (cy > 0) {
                        val nIdx = curr - width
                        if (!isBackground.get(nIdx) && !fgVisited.get(nIdx)) {
                            fgVisited.set(nIdx)
                            queue[cTail++] = nIdx
                        }
                    }
                    if (cy < height - 1) {
                        val nIdx = curr + width
                        if (!isBackground.get(nIdx) && !fgVisited.get(nIdx)) {
                            fgVisited.set(nIdx)
                            queue[cTail++] = nIdx
                        }
                    }
                }

                if (cTail >= minMainSilhouettePixels) {
                    for (k in 0 until cTail) {
                        isMainForeground.set(queue[k])
                    }
                }
            }
        }

        // 2c. Enclosed Cavity Detection & Removal
        // Accurately extracts cavities trapped between the subject and props (e.g. gap under armpit,
        // gap between dress ruffle and fireplace column) without eroding the subject's clothing.
        // A true background cavity has a mean color virtually identical to the studio background
        // (dist <= 5.5 or high pureBgRatio) and borders the main foreground silhouette.
        val cavityVisited = BitSet(totalPixels)
        for (i in 0 until totalPixels) {
            if (!isBackground.get(i) && !cavityVisited.get(i) && isBgColor(pixels[i])) {
                var cHead = 0
                var cTail = 0
                cavityVisited.set(i)
                queue[cTail++] = i

                var sumR = 0L
                var sumG = 0L
                var sumB = 0L
                var pureBgCount = 0
                var bordersMainFg = false
                var aiOverlapCount = 0

                while (cHead < cTail) {
                    val curr = queue[cHead++]
                    val cx = curr % width
                    val cy = curr / width

                    val p = pixels[curr]
                    val r = (p shr 16) and 0xFF
                    val g = (p shr 8) and 0xFF
                    val b = p and 0xFF
                    sumR += r
                    sumG += g
                    sumB += b

                    if (abs(r - bgR) <= 3 && abs(g - bgG) <= 3 && abs(b - bgB) <= 3) {
                        pureBgCount++
                    }
                    if (aiProtectedMask != null && aiProtectedMask.get(curr)) {
                        aiOverlapCount++
                    }

                    // Check 4-neighborhood
                    if (cx > 0) {
                        val nIdx = curr - 1
                        if (!isBackground.get(nIdx)) {
                            if (isMainForeground.get(nIdx)) bordersMainFg = true
                            if (!cavityVisited.get(nIdx) && isBgColor(pixels[nIdx])) {
                                cavityVisited.set(nIdx)
                                queue[cTail++] = nIdx
                            }
                        }
                    }
                    if (cx < width - 1) {
                        val nIdx = curr + 1
                        if (!isBackground.get(nIdx)) {
                            if (isMainForeground.get(nIdx)) bordersMainFg = true
                            if (!cavityVisited.get(nIdx) && isBgColor(pixels[nIdx])) {
                                cavityVisited.set(nIdx)
                                queue[cTail++] = nIdx
                            }
                        }
                    }
                    if (cy > 0) {
                        val nIdx = curr - width
                        if (!isBackground.get(nIdx)) {
                            if (isMainForeground.get(nIdx)) bordersMainFg = true
                            if (!cavityVisited.get(nIdx) && isBgColor(pixels[nIdx])) {
                                cavityVisited.set(nIdx)
                                queue[cTail++] = nIdx
                            }
                        }
                    }
                    if (cy < height - 1) {
                        val nIdx = curr + width
                        if (!isBackground.get(nIdx)) {
                            if (isMainForeground.get(nIdx)) bordersMainFg = true
                            if (!cavityVisited.get(nIdx) && isBgColor(pixels[nIdx])) {
                                cavityVisited.set(nIdx)
                                queue[cTail++] = nIdx
                            }
                        }
                    }
                }

                val compSize = cTail
                val isAiSubject = (aiProtectedMask != null && compSize > 0 && (aiOverlapCount.toFloat() / compSize) > 0.35f)
                // Enclosed cavities must border the main subject/prop silhouette and contain at least 20 pixels
                if (!isAiSubject && bordersMainFg && compSize >= 20) {
                    val meanR = sumR.toFloat() / compSize
                    val meanG = sumG.toFloat() / compSize
                    val meanB = sumB.toFloat() / compSize
                    val dist = kotlin.math.sqrt(
                        (meanR - bgR) * (meanR - bgR) +
                        (meanG - bgG) * (meanG - bgG) +
                        (meanB - bgB) * (meanB - bgB)
                    )
                    val pureRatio = pureBgCount.toFloat() / compSize

                    // True backdrop cavities are virtually identical to backdrop (dist <= 5.0 AND pureRatio >= 0.35).
                    // Clothing highlights and folds have ivory/shadow shades with dist >= 12.0 and pureRatio <= 0.03.
                    if (dist <= 5.0f && pureRatio >= 0.35f) {
                        android.util.Log.d("SmartCutoutEngine", "REMOVING cavity of size=$compSize (dist=$dist, pureRatio=$pureRatio)")
                        for (k in 0 until compSize) {
                            isBackground.set(queue[k])
                        }
                    }
                }
            }
        }

        android.util.Log.d("SmartCutoutEngine", "After Step 2c cavity removal: bgCount=${isBackground.cardinality()}")

        // 3. Connected Components Analysis for foreground using BitSets & reused queue
        val isForegroundKept = BitSet(totalPixels)
        if (aiProtectedMask != null) {
            isForegroundKept.or(aiProtectedMask)
        }

        fun isDashedOutlineStroke(
            compSize: Int,
            bw: Int,
            bh: Int,
            meanR: Float,
            meanG: Float,
            meanB: Float
        ): Boolean {
            if (compSize !in 15..700) return false
            val density = compSize.toFloat() / (bw * bh)
            val isNarrowOrArc = (minOf(bw, bh) <= 14) || (density <= 0.40f)
            val isPastelDashColor = (meanR >= 236f && meanG >= 198f && meanB >= 192f && meanR > meanG && kotlin.math.abs(meanG - meanB) <= 18f)
            return isNarrowOrArc && isPastelDashColor
        }

        if (keepFloatingStickers) {
            val visitedFg = BitSet(totalPixels)
            val minNoisePixels = 60
            for (i in 0 until totalPixels) {
                if (!isBackground.get(i) && !visitedFg.get(i)) {
                    var cHead = 0
                    var cTail = 0
                    visitedFg.set(i)
                    queue[cTail++] = i

                    var minX = i % width
                    var maxX = minX
                    var minY = i / width
                    var maxY = minY
                    var sumR = 0L
                    var sumG = 0L
                    var sumB = 0L

                    while (cHead < cTail) {
                        val curr = queue[cHead++]
                        val cx = curr % width
                        val cy = curr / width

                        if (cx < minX) minX = cx
                        if (cx > maxX) maxX = cx
                        if (cy < minY) minY = cy
                        if (cy > maxY) maxY = cy

                        val p = pixels[curr]
                        sumR += (p shr 16) and 0xFF
                        sumG += (p shr 8) and 0xFF
                        sumB += p and 0xFF

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
                    val bw = maxX - minX + 1
                    val bh = maxY - minY + 1
                    val meanR = sumR.toFloat() / compSize
                    val meanG = sumG.toFloat() / compSize
                    val meanB = sumB.toFloat() / compSize

                    val isDash = isDashedOutlineStroke(compSize, bw, bh, meanR, meanG, meanB)
                    if (compSize >= minNoisePixels && !isDash) {
                        for (k in 0 until compSize) {
                            isForegroundKept.set(queue[k])
                        }
                    } else if (isDash && aiProtectedMask != null) {
                        for (k in 0 until compSize) {
                            isForegroundKept.clear(queue[k])
                        }
                    }
                }
            }
        } else {
            val visitedFg = BitSet(totalPixels)
            val minComponentPixels = (totalPixels * minComponentRatio).toInt()

            for (i in 0 until totalPixels) {
                if (!isBackground.get(i) && !visitedFg.get(i)) {
                    var cHead = 0
                    var cTail = 0
                    var touchesMain = false

                    visitedFg.set(i)
                    queue[cTail++] = i

                    var minX = i % width
                    var maxX = minX
                    var minY = i / width
                    var maxY = minY
                    var sumR = 0L
                    var sumG = 0L
                    var sumB = 0L

                    while (cHead < cTail) {
                        val curr = queue[cHead++]
                        val cx = curr % width
                        val cy = curr / width

                        if (cx < minX) minX = cx
                        if (cx > maxX) maxX = cx
                        if (cy < minY) minY = cy
                        if (cy > maxY) maxY = cy

                        val p = pixels[curr]
                        sumR += (p shr 16) and 0xFF
                        sumG += (p shr 8) and 0xFF
                        sumB += p and 0xFF

                        if (!touchesMain) {
                            for (dy in -2..2) {
                                val ny = cy + dy
                                if (ny in 0 until height) {
                                    for (dx in -2..2) {
                                        val nx = cx + dx
                                        if (nx in 0 until width) {
                                            if (isMainForeground.get(ny * width + nx)) {
                                                touchesMain = true
                                                break
                                            }
                                        }
                                    }
                                    if (touchesMain) break
                                }
                            }
                        }

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
                    val bw = maxX - minX + 1
                    val bh = maxY - minY + 1
                    val meanR = sumR.toFloat() / compSize
                    val meanG = sumG.toFloat() / compSize
                    val meanB = sumB.toFloat() / compSize

                    val isDash = isDashedOutlineStroke(compSize, bw, bh, meanR, meanG, meanB)
                    val keep = !isDash && (compSize >= minComponentPixels || (touchesMain && compSize >= 60))
                    if (keep) {
                        for (k in 0 until compSize) {
                            isForegroundKept.set(queue[k])
                        }
                    } else if (isDash && aiProtectedMask != null) {
                        for (k in 0 until compSize) {
                            isForegroundKept.clear(queue[k])
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
        // In-place mutation of pixels array saves 6.3MB of memory
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
                    pixels[idx] = if (isEdge) {
                        (0xEE shl 24) or (orig and 0x00FFFFFF)
                    } else {
                        (0xFF shl 24) or (orig and 0x00FFFFFF)
                    }
                } else {
                    pixels[idx] = 0
                }
            }
        }

        // Create a guaranteed MUTABLE Bitmap so subsequent manual erasing/restoring never throws IllegalStateException
        val resultBitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        resultBitmap.setPixels(pixels, 0, width, 0, 0, width, height)
        android.util.Log.d("SmartCutoutEngine", "SmartCutout finished. fgRatio=$fgRatio, totalFg=${isForegroundKept.cardinality()}")
        resultBitmap
    }
}
