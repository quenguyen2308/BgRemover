package com.bgremover.ui.components

import android.graphics.Bitmap
import android.graphics.Path
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.unit.sp
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
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
    onBitmapEdited: () -> Unit = {}
) {
    val density = LocalDensity.current
    val brushPx = with(density) { brushSizeDp.toPx() }

    var userScale by remember { mutableFloatStateOf(1.0f) }
    var userPan by remember { mutableStateOf(Offset.Zero) }

    // Track active touch position for drawing brush circle cursor
    var activeTouchPosition by remember { mutableStateOf<Offset?>(null) }
    var isDrawingStroke by remember { mutableStateOf(false) }

    // Version counter to trigger Compose canvas re-draw when bitmap is modified in-place
    var editRevision by remember { mutableStateOf(0) }

    Box(modifier = modifier.fillMaxSize()) {
        Canvas(
            modifier = Modifier
                .fillMaxSize()
                // 1. Gesture detection for Pinch-Zoom & Pan (when 2 fingers or in inspect mode)
                .pointerInput(activeTool) {
                    detectTransformGestures { _, pan, zoom, _ ->
                        if (activeTool == EditorTool.INSPECT || activeTool == EditorTool.BACKGROUND || activeTool == EditorTool.AUTO) {
                            userScale = (userScale * zoom).coerceIn(0.5f, 8.0f)
                            userPan += pan
                        }
                    }
                }
                // 2. Custom touch handler for drawing or 2-finger pan/zoom when in brush mode
                .pointerInput(activeTool, brushPx, currentBitmap, originalBitmap, userScale, userPan) {
                    awaitPointerEventScope {
                        var currentPath: Path? = null

                        while (true) {
                            val event = awaitPointerEvent(PointerEventPass.Main)
                            val pointers = event.changes

                            if (pointers.size > 1) {
                                // Multi-touch: allow zoom/pan even during brush tools
                                activeTouchPosition = null
                                isDrawingStroke = false
                                currentPath = null
                                continue
                            }

                            if (pointers.isEmpty()) {
                                activeTouchPosition = null
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

                            val touchPos = pointer.position
                            val bmX = (touchPos.x - imgLeft) / totalScale
                            val bmY = (touchPos.y - imgTop) / totalScale
                            val strokeWidthBm = brushPx / totalScale

                            if (activeTool == EditorTool.ERASE || activeTool == EditorTool.RESTORE) {
                                if (pointer.pressed) {
                                    activeTouchPosition = touchPos
                                    isDrawingStroke = true

                                    if (currentPath == null) {
                                        currentPath = Path().apply { moveTo(bmX, bmY) }
                                    } else {
                                        currentPath.lineTo(bmX, bmY)
                                    }

                                    // Apply stroke incrementally
                                    val segmentPath = Path().apply {
                                        val prev = pointer.previousPosition
                                        val prevBmX = (prev.x - imgLeft) / totalScale
                                        val prevBmY = (prev.y - imgTop) / totalScale
                                        moveTo(prevBmX, prevBmY)
                                        lineTo(bmX, bmY)
                                    }

                                    if (activeTool == EditorTool.ERASE) {
                                        BitmapUtils.applyEraseStroke(currentBitmap, segmentPath, strokeWidthBm)
                                    } else {
                                        BitmapUtils.applyRestoreStroke(
                                            currentBitmap,
                                            originalBitmap,
                                            segmentPath,
                                            strokeWidthBm
                                        )
                                    }

                                    editRevision++
                                    pointer.consume()
                                } else {
                                    // Pointer released
                                    if (isDrawingStroke) {
                                        isDrawingStroke = false
                                        currentPath = null
                                        activeTouchPosition = null
                                        onBitmapEdited()
                                    }
                                }
                            }
                        }
                    }
                }
        ) {
            // Read editRevision to trigger re-composition on canvas edit
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

            // Draw image
            drawImage(
                image = bitmapToDraw.asImageBitmap(),
                srcOffset = IntOffset(0, 0),
                srcSize = IntSize(bitmapToDraw.width, bitmapToDraw.height),
                dstOffset = IntOffset(imgLeft, imgTop),
                dstSize = IntSize(displayedW, displayedH)
            )

            // Draw brush hover / position circle when drawing
            activeTouchPosition?.let { pos ->
                if (activeTool == EditorTool.ERASE || activeTool == EditorTool.RESTORE) {
                    val radius = brushPx / 2f
                    // Outer circle (white)
                    drawCircle(
                        color = Color.White,
                        radius = radius,
                        center = pos,
                        style = Stroke(width = 3f)
                    )
                    // Inner circle (accent color: Red for Erase, Green for Restore)
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
                    .align(androidx.compose.ui.Alignment.BottomEnd)
                    .padding(end = 16.dp, bottom = 16.dp)
                    .clip(androidx.compose.foundation.shape.RoundedCornerShape(14.dp))
                    .background(Color.Black.copy(alpha = 0.7f))
                    .border(1.dp, Color.White.copy(alpha = 0.2f), androidx.compose.foundation.shape.RoundedCornerShape(14.dp))
                    .clickable {
                        userScale = 1.0f
                        userPan = Offset.Zero
                    }
                    .padding(horizontal = 10.dp, vertical = 6.dp)
            ) {
                androidx.compose.material3.Text(
                    text = "${(userScale * 100).toInt()}% • Đặt lại",
                    color = Color.White,
                    fontSize = 11.sp,
                    fontWeight = androidx.compose.ui.text.font.FontWeight.Medium
                )
            }
        }
    }
}
