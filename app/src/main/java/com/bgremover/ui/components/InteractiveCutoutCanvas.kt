package com.bgremover.ui.components

import android.graphics.Bitmap
import android.graphics.Path
import android.graphics.PointF
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.bgremover.model.EditorTool
import com.bgremover.util.BitmapUtils
import kotlin.math.min

@Composable
fun InteractiveCutoutCanvas(
    currentBitmap: Bitmap,
    originalBitmap: Bitmap,
    isShowingOriginal: Boolean,
    activeTool: EditorTool,
    brushSizeDp: Dp,
    modifier: Modifier = Modifier,
    isOffsetCursorEnabled: Boolean = true,
    onStrokeStart: () -> Unit = {},
    onBitmapEdited: () -> Unit = {}
) {
    val density = LocalDensity.current
    val brushPx = with(density) { brushSizeDp.toPx() }

    var userScale by remember { mutableFloatStateOf(1.0f) }
    var userPan by remember { mutableStateOf(Offset.Zero) }

    // Touch tracking for drawing and cursor preview
    var activeTouchPosition by remember { mutableStateOf<Offset?>(null) }
    var fingerTouchPosition by remember { mutableStateOf<Offset?>(null) }

    // Revision counter to trigger canvas repaint
    var editRevision by remember { mutableStateOf(0) }

    Box(modifier = modifier.fillMaxSize()) {
        Canvas(
            modifier = Modifier
                .fillMaxSize()
                .pointerInput(activeTool, brushPx, currentBitmap, originalBitmap, isOffsetCursorEnabled) {
                    awaitPointerEventScope {
                        var isDrawing = false
                        var prevPointBm: PointF? = null

                        while (true) {
                            val event = awaitPointerEvent(PointerEventPass.Main)
                            val pointers = event.changes

                            // 1. Two-finger gesture (Pinch-Zoom & Pan) works in ALL tools!
                            if (pointers.size >= 2) {
                                if (isDrawing) {
                                    isDrawing = false
                                    activeTouchPosition = null
                                    fingerTouchPosition = null
                                    prevPointBm = null
                                    onBitmapEdited()
                                }

                                val p1 = pointers[0]
                                val p2 = pointers[1]

                                val prevDistance = (p1.previousPosition - p2.previousPosition).getDistance()
                                val currDistance = (p1.position - p2.position).getDistance()
                                if (prevDistance > 0f) {
                                    val zoomFactor = currDistance / prevDistance
                                    userScale = (userScale * zoomFactor).coerceIn(0.5f, 8.0f)
                                }

                                val prevCentroid = (p1.previousPosition + p2.previousPosition) / 2f
                                val currCentroid = (p1.position + p2.position) / 2f
                                userPan += (currCentroid - prevCentroid)

                                pointers.forEach { it.consume() }
                                continue
                            }

                            // 2. Single-finger touch handler
                            if (pointers.isEmpty()) {
                                if (isDrawing) {
                                    isDrawing = false
                                    activeTouchPosition = null
                                    fingerTouchPosition = null
                                    prevPointBm = null
                                    onBitmapEdited()
                                }
                                continue
                            }

                            val pointer = pointers.first()
                            val canvasWidth = size.width.toFloat()
                            val canvasHeight = size.height.toFloat()
                            val bmWidth = currentBitmap.width.toFloat()
                            val bmHeight = currentBitmap.height.toFloat()

                            val baseScale = min(canvasWidth / bmWidth, canvasHeight / bmHeight)
                            val totalScale = baseScale * userScale
                            val displayedW = bmWidth * totalScale
                            val displayedH = bmHeight * totalScale
                            val imgLeft = (canvasWidth / 2f) + userPan.x - (displayedW / 2f)
                            val imgTop = (canvasHeight / 2f) + userPan.y - (displayedH / 2f)

                            if (activeTool == EditorTool.INSPECT || activeTool == EditorTool.BACKGROUND || activeTool == EditorTool.AUTO) {
                                // Pan mode for non-brush tools
                                if (pointer.pressed) {
                                    val pan = pointer.position - pointer.previousPosition
                                    userPan += pan
                                    pointer.consume()
                                }
                            } else if (activeTool == EditorTool.ERASE || activeTool == EditorTool.RESTORE) {
                                // Brush tools
                                val cursorOffsetY = if (isOffsetCursorEnabled) with(density) { -50.dp.toPx() } else 0f
                                val fingerPos = pointer.position
                                val effectivePos = fingerPos + Offset(0f, cursorOffsetY)

                                if (pointer.pressed) {
                                    activeTouchPosition = effectivePos
                                    fingerTouchPosition = if (isOffsetCursorEnabled) fingerPos else null

                                    val bmX = (effectivePos.x - imgLeft) / totalScale
                                    val bmY = (effectivePos.y - imgTop) / totalScale
                                    val strokeWidthBm = brushPx / totalScale

                                    if (!isDrawing) {
                                        isDrawing = true
                                        // CRITICAL: Push undo snapshot BEFORE any pixel mutation!
                                        onStrokeStart()
                                        prevPointBm = PointF(bmX, bmY)

                                        // Apply initial dot for single tap
                                        val dotPath = Path().apply {
                                            addCircle(bmX, bmY, strokeWidthBm / 2f, Path.Direction.CW)
                                        }
                                        if (activeTool == EditorTool.ERASE) {
                                            BitmapUtils.applyEraseStroke(currentBitmap, dotPath, strokeWidthBm)
                                        } else {
                                            BitmapUtils.applyRestoreStroke(currentBitmap, originalBitmap, dotPath, strokeWidthBm)
                                        }
                                    } else {
                                        val prevBm = prevPointBm ?: PointF(bmX, bmY)
                                        val segmentPath = Path().apply {
                                            moveTo(prevBm.x, prevBm.y)
                                            lineTo(bmX, bmY)
                                        }
                                        if (activeTool == EditorTool.ERASE) {
                                            BitmapUtils.applyEraseStroke(currentBitmap, segmentPath, strokeWidthBm)
                                        } else {
                                            BitmapUtils.applyRestoreStroke(currentBitmap, originalBitmap, segmentPath, strokeWidthBm)
                                        }
                                        prevPointBm = PointF(bmX, bmY)
                                    }

                                    editRevision++
                                    pointer.consume()
                                } else {
                                    // Pointer released
                                    if (isDrawing) {
                                        isDrawing = false
                                        activeTouchPosition = null
                                        fingerTouchPosition = null
                                        prevPointBm = null
                                        onBitmapEdited()
                                    }
                                }
                            }
                        }
                    }
                }
        ) {
            // Read editRevision to trigger re-draw on canvas mutations
            val _rev = editRevision

            val canvasWidth = size.width
            val canvasHeight = size.height
            val bitmapToDraw = if (isShowingOriginal) originalBitmap else currentBitmap
            val bmWidth = bitmapToDraw.width.toFloat()
            val bmHeight = bitmapToDraw.height.toFloat()

            val baseScale = min(canvasWidth / bmWidth, canvasHeight / bmHeight)
            val totalScale = baseScale * userScale
            val displayedW = (bmWidth * totalScale).toInt()
            val displayedH = (bmHeight * totalScale).toInt()

            val imgLeft = ((canvasWidth / 2f) + userPan.x - (displayedW / 2f)).toInt()
            val imgTop = ((canvasHeight / 2f) + userPan.y - (displayedH / 2f)).toInt()

            // Draw bitmap safely (prevent IllegalArgumentException if layout dimensions are zero or uninitialized)
            if (!bitmapToDraw.isRecycled && displayedW > 0 && displayedH > 0 && bitmapToDraw.width > 0 && bitmapToDraw.height > 0) {
                drawImage(
                    image = bitmapToDraw.asImageBitmap(),
                    srcOffset = IntOffset(0, 0),
                    srcSize = IntSize(bitmapToDraw.width, bitmapToDraw.height),
                    dstOffset = IntOffset(imgLeft, imgTop),
                    dstSize = IntSize(displayedW, displayedH)
                )
            }

            // Draw dashed connector line if offset cursor is active
            fingerTouchPosition?.let { fPos ->
                activeTouchPosition?.let { cPos ->
                    drawLine(
                        color = Color.White.copy(alpha = 0.5f),
                        start = fPos,
                        end = cPos,
                        strokeWidth = 2f,
                        pathEffect = PathEffect.dashPathEffect(floatArrayOf(10f, 10f), 0f)
                    )
                    drawCircle(
                        color = Color.White.copy(alpha = 0.7f),
                        radius = 6f,
                        center = fPos
                    )
                }
            }

            // Draw brush cursor preview
            activeTouchPosition?.let { pos ->
                if (activeTool == EditorTool.ERASE || activeTool == EditorTool.RESTORE) {
                    val radius = brushPx / 2f
                    // Outer white ring
                    drawCircle(
                        color = Color.White,
                        radius = radius,
                        center = pos,
                        style = Stroke(width = 3f)
                    )
                    // Inner colored ring (Red for Erase, Green for Restore)
                    val accent = if (activeTool == EditorTool.ERASE) Color(0xFFEF4444) else Color(0xFF10B981)
                    drawCircle(
                        color = accent,
                        radius = radius - 1.5f,
                        center = pos,
                        style = Stroke(width = 2f)
                    )
                }
            }
        }

        // Floating Zoom indicator & Reset button
        if (userScale > 1.05f || userScale < 0.95f) {
            Box(
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(end = 16.dp, bottom = 16.dp)
                    .clip(RoundedCornerShape(14.dp))
                    .background(Color.Black.copy(alpha = 0.7f))
                    .border(1.dp, Color.White.copy(alpha = 0.2f), RoundedCornerShape(14.dp))
                    .clickable {
                        userScale = 1.0f
                        userPan = Offset.Zero
                    }
                    .padding(horizontal = 10.dp, vertical = 6.dp)
            ) {
                Text(
                    text = "${(userScale * 100).toInt()}% • Đặt lại",
                    color = Color.White,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Medium
                )
            }
        }
    }
}
