package com.bgremover.ui.screens

import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Redo
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.AutoFixHigh
import androidx.compose.material.icons.filled.Brush
import androidx.compose.material.icons.filled.CleaningServices
import androidx.compose.material.icons.filled.ColorLens
import androidx.compose.material.icons.filled.FileDownload
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.bgremover.engine.BgRemoverEngine
import com.bgremover.engine.CutoutMode
import com.bgremover.model.EditorTool
import com.bgremover.model.PreviewBgType
import com.bgremover.ui.components.BackgroundSelector
import com.bgremover.ui.components.BrushSlider
import com.bgremover.ui.components.CheckerboardBackground
import com.bgremover.ui.components.CompareButton
import com.bgremover.ui.components.ExportSuccessDialog
import com.bgremover.ui.components.InteractiveCutoutCanvas
import com.bgremover.ui.theme.EmeraldSuccess
import com.bgremover.ui.theme.PrimaryCyan
import com.bgremover.ui.theme.PrimaryIndigo
import com.bgremover.ui.theme.PrimaryPurple
import com.bgremover.ui.theme.StudioBg
import com.bgremover.ui.theme.StudioBorder
import com.bgremover.ui.theme.StudioCardBg
import com.bgremover.ui.theme.StudioCardBgElevated
import com.bgremover.ui.theme.TextPrimary
import com.bgremover.ui.theme.TextSecondary
import com.bgremover.util.BitmapUtils
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EditorScreen(
    initialBitmap: Bitmap,
    onBack: () -> Unit,
    onPickAnother: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val haptic = LocalHapticFeedback.current
    val engine = remember { BgRemoverEngine(context) }

    DisposableEffect(Unit) {
        onDispose {
            engine.close()
        }
    }

    val originalBitmap = remember { initialBitmap }
    var currentBitmap by remember { mutableStateOf(initialBitmap) }

    val undoStack = remember { ArrayDeque<Bitmap>() }
    val redoStack = remember { ArrayDeque<Bitmap>() }

    var activeTool by remember { mutableStateOf(EditorTool.AUTO) }
    var previewBgType by remember { mutableStateOf(PreviewBgType.BLACK) }
    var brushSizeDp by remember { mutableStateOf(26.dp) }
    var isOffsetCursorEnabled by remember { mutableStateOf(true) }
    var isShowingOriginal by remember { mutableStateOf(false) }

    var isProcessing by remember { mutableStateOf(false) }
    var processingMessage by remember { mutableStateOf("Đang phân tích và tách nền bằng AI...") }

    var showExportDialog by remember { mutableStateOf(false) }
    var savedUri by remember { mutableStateOf<Uri?>(null) }

    // Helper: Push state to undo stack before modifying
    fun pushUndoState(oldBitmap: Bitmap) {
        try {
            if (undoStack.size >= 4) {
                undoStack.removeFirst()
            }
            val copy = BitmapUtils.copyBitmap(oldBitmap)
            if (copy != oldBitmap) {
                undoStack.addLast(copy)
            }
            redoStack.clear()
        } catch (_: Throwable) {
            // Ignore if out of memory for undo
        }
    }

    var currentCutoutMode by remember { mutableStateOf(CutoutMode.SMART_AUTO) }
    val modeCache = remember { mutableMapOf<CutoutMode, Bitmap>() }
    var isFirstLaunch by remember { mutableStateOf(true) }
    var cutoutJob by remember { mutableStateOf<Job?>(null) }

    // AI Cutout operation with instant 0ms caching & smooth UX
    fun performAiCutout(mode: CutoutMode = currentCutoutMode) {
        val cached = modeCache[mode]
        if (cached != null) {
            cutoutJob?.cancel()
            isProcessing = false
            currentCutoutMode = mode
            currentBitmap = cached
            haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
            return
        }

        cutoutJob?.cancel()
        cutoutJob = scope.launch {
            try {
                isProcessing = true
                currentCutoutMode = mode
                processingMessage = when (mode) {
                    CutoutMode.SMART_OBJECT -> "Đang giữ trọn chi tiết, đồ vật & phụ kiện..."
                    CutoutMode.SMART_AUTO -> "Đang tự động nhận diện chủ thể tối ưu..."
                }

                val result = engine.removeBackground(originalBitmap, mode)
                isProcessing = false

                result.onSuccess { transparentResult ->
                    modeCache[mode] = transparentResult
                    currentBitmap = transparentResult
                    if (isFirstLaunch) {
                        activeTool = EditorTool.AUTO
                        isFirstLaunch = false
                    }
                    haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                }.onFailure { err ->
                    Toast.makeText(context, "Lỗi: ${err.message}", Toast.LENGTH_LONG).show()
                }
            } catch (_: CancellationException) {
                // Ignore cancellation when switching modes quickly
            } catch (t: Throwable) {
                isProcessing = false
                android.util.Log.e("EditorScreen", "Error during performAiCutout", t)
                Toast.makeText(context, "Không thể xử lý: ${t.message}", Toast.LENGTH_LONG).show()
            }
        }
    }

    // Smooth cutout edges
    fun performSmoothEdges() {
        scope.launch {
            pushUndoState(currentBitmap)
            isProcessing = true
            processingMessage = "Đang làm mịn viền cắt..."
            val smoothed = BitmapUtils.smoothCutoutEdges(currentBitmap)
            currentBitmap = smoothed
            modeCache[currentCutoutMode] = smoothed
            isProcessing = false
            haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
            Toast.makeText(context, "Đã làm mịn viền ảnh!", Toast.LENGTH_SHORT).show()
        }
    }

    // Save cutout with the selected background color
    fun saveWithCurrentBackground() {
        val color = previewBgType.color ?: return
        scope.launch {
            isProcessing = true
            processingMessage = "Đang lưu ảnh kèm phông nền..."
            val res = BitmapUtils.saveCompositeImageToGallery(context, currentBitmap, color)
            isProcessing = false
            res.onSuccess {
                haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                Toast.makeText(context, "Đã lưu ảnh nền ${previewBgType.displayName} vào thư viện!", Toast.LENGTH_LONG).show()
            }.onFailure { e ->
                Toast.makeText(context, "Lỗi: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        }
    }

    // Auto-run AI Cutout when Editor opens for the first time
    LaunchedEffect(Unit) {
        performAiCutout(CutoutMode.SMART_AUTO)
    }

    BackHandler {
        onBack()
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(StudioBg)
    ) {
        Column(modifier = Modifier.fillMaxSize()) {

            // Floating Header Bar
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .statusBarsPadding()
                    .padding(horizontal = 10.dp, vertical = 8.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    // Back circular button
                    Box(
                        modifier = Modifier
                            .size(40.dp)
                            .clip(CircleShape)
                            .background(StudioCardBgElevated)
                            .border(1.dp, StudioBorder, CircleShape)
                            .clickable { onBack() },
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Quay lại",
                            tint = TextPrimary,
                            modifier = Modifier.size(20.dp)
                        )
                    }

                    // Center Control Capsule (Undo, Redo, Compare)
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        // Undo & Redo Capsule
                        Row(
                            modifier = Modifier
                                .clip(RoundedCornerShape(20.dp))
                                .background(StudioCardBgElevated)
                                .border(1.dp, StudioBorder, RoundedCornerShape(20.dp))
                                .padding(horizontal = 4.dp, vertical = 2.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            IconButton(
                                onClick = {
                                    if (undoStack.isNotEmpty()) {
                                        val previous = undoStack.removeLast()
                                        redoStack.addLast(BitmapUtils.copyBitmap(currentBitmap))
                                        currentBitmap = previous
                                        haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                    }
                                },
                                enabled = undoStack.isNotEmpty(),
                                modifier = Modifier.size(32.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.AutoMirrored.Filled.Undo,
                                    contentDescription = "Hoàn tác",
                                    tint = if (undoStack.isNotEmpty()) TextPrimary else Color(0xFF475569),
                                    modifier = Modifier.size(17.dp)
                                )
                            }

                            Box(
                                modifier = Modifier
                                    .width(1.dp)
                                    .height(16.dp)
                                    .background(StudioBorder)
                            )

                            IconButton(
                                onClick = {
                                    if (redoStack.isNotEmpty()) {
                                        val next = redoStack.removeLast()
                                        undoStack.addLast(BitmapUtils.copyBitmap(currentBitmap))
                                        currentBitmap = next
                                        haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                    }
                                },
                                enabled = redoStack.isNotEmpty(),
                                modifier = Modifier.size(32.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.AutoMirrored.Filled.Redo,
                                    contentDescription = "Làm lại",
                                    tint = if (redoStack.isNotEmpty()) TextPrimary else Color(0xFF475569),
                                    modifier = Modifier.size(17.dp)
                                )
                            }
                        }

                        // Compare Button
                        CompareButton(
                            isPressed = isShowingOriginal,
                            onPressChanged = { 
                                isShowingOriginal = it
                                haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                            }
                        )
                    }

                    // Actions: Share & Save PNG
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        // Share icon button
                        Box(
                            modifier = Modifier
                                .size(40.dp)
                                .clip(CircleShape)
                                .background(StudioCardBgElevated)
                                .border(1.dp, StudioBorder, CircleShape)
                            .clickable {
                                scope.launch {
                                    val shareUri = BitmapUtils.saveToCacheForSharing(context, currentBitmap)
                                    if (shareUri != null) {
                                        val shareIntent = Intent(Intent.ACTION_SEND).apply {
                                            type = "image/png"
                                            putExtra(Intent.EXTRA_STREAM, shareUri)
                                            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                        }
                                        context.startActivity(Intent.createChooser(shareIntent, "Chia sẻ ảnh PNG"))
                                    } else {
                                        Toast.makeText(context, "Không thể chia sẻ ảnh", Toast.LENGTH_SHORT).show()
                                    }
                                }
                            },
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.Default.Share,
                                contentDescription = "Chia sẻ",
                                tint = TextPrimary,
                                modifier = Modifier.size(18.dp)
                            )
                        }

                        // Save PNG Gradient Button
                        Box(
                            modifier = Modifier
                                .height(40.dp)
                                .clip(RoundedCornerShape(20.dp))
                                .background(
                                    Brush.horizontalGradient(
                                        listOf(PrimaryPurple, PrimaryIndigo)
                                    )
                                )
                                .clickable {
                                    scope.launch {
                                        val saveResult = BitmapUtils.saveTransparentPngToGallery(context, currentBitmap)
                                        saveResult.onSuccess { uri ->
                                            savedUri = uri
                                            showExportDialog = true
                                            haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                        }.onFailure { e ->
                                            Toast.makeText(
                                                context,
                                                "Lưu thất bại: ${e.message}",
                                                Toast.LENGTH_LONG
                                            ).show()
                                        }
                                    }
                                }
                                .padding(horizontal = 10.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(
                                    imageVector = Icons.Default.FileDownload,
                                    contentDescription = null,
                                    tint = Color.White,
                                    modifier = Modifier.size(16.dp)
                                )
                                Spacer(modifier = Modifier.width(4.dp))
                                Text(
                                    text = "Lưu PNG",
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = Color.White,
                                    maxLines = 1,
                                    softWrap = false
                                )
                            }
                        }
                    }
                }
            }

            // Main Canvas Area (Viewport Frame)
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 4.dp)
                    .clip(RoundedCornerShape(22.dp))
                    .border(1.dp, StudioBorder, RoundedCornerShape(22.dp)),
                contentAlignment = Alignment.Center
            ) {
                // Background Viewport (Checkerboard or solid preview colors)
                if (previewBgType == PreviewBgType.CHECKERBOARD) {
                    CheckerboardBackground(
                        modifier = Modifier.fillMaxSize(),
                        squareSize = 10.dp
                    )
                } else {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(previewBgType.color ?: Color.Transparent)
                    )
                }

                // Interactive Canvas with Drawing and Gesture controls
                InteractiveCutoutCanvas(
                    currentBitmap = currentBitmap,
                    originalBitmap = originalBitmap,
                    isShowingOriginal = isShowingOriginal,
                    activeTool = activeTool,
                    brushSizeDp = brushSizeDp,
                    isOffsetCursorEnabled = isOffsetCursorEnabled,
                    modifier = Modifier.fillMaxSize(),
                    onStrokeStart = {
                        pushUndoState(currentBitmap)
                    },
                    onBitmapEdited = {
                        modeCache[currentCutoutMode] = currentBitmap
                    }
                )

                // Tool Hint Overlay
                if (activeTool == EditorTool.ERASE || activeTool == EditorTool.RESTORE) {
                    Box(
                        modifier = Modifier
                            .align(Alignment.TopCenter)
                            .padding(top = 14.dp)
                            .clip(RoundedCornerShape(16.dp))
                            .background(Color.Black.copy(alpha = 0.75f))
                            .border(1.dp, Color.White.copy(alpha = 0.15f), RoundedCornerShape(16.dp))
                            .padding(horizontal = 14.dp, vertical = 6.dp)
                    ) {
                        Text(
                            text = if (activeTool == EditorTool.ERASE)
                                "🧹 Tô 1 ngón tay để tẩy • 2 ngón tay thu phóng/di chuyển"
                            else
                                "🖌️ Tô 1 ngón tay để phục hồi • 2 ngón tay thu phóng/di chuyển",
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Medium,
                            color = Color.White
                        )
                    }
                }
            }

            // Bottom Tool Dock & Sub-settings Panel
            Surface(
                color = StudioCardBg,
                shape = RoundedCornerShape(topStart = 26.dp, topEnd = 26.dp),
                border = androidx.compose.foundation.BorderStroke(1.dp, StudioBorder),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier.padding(top = 8.dp)
                ) {
                    // Sub-tool specific settings
                    when (activeTool) {
                        EditorTool.ERASE -> {
                            BrushSlider(
                                brushSizeDp = brushSizeDp,
                                onBrushSizeChange = { brushSizeDp = it },
                                accentColor = Color(0xFFEF4444),
                                isOffsetCursorEnabled = isOffsetCursorEnabled,
                                onToggleOffsetCursor = { isOffsetCursorEnabled = !isOffsetCursorEnabled },
                                onSmoothEdges = { performSmoothEdges() }
                            )
                        }
                        EditorTool.RESTORE -> {
                            BrushSlider(
                                brushSizeDp = brushSizeDp,
                                onBrushSizeChange = { brushSizeDp = it },
                                accentColor = EmeraldSuccess,
                                isOffsetCursorEnabled = isOffsetCursorEnabled,
                                onToggleOffsetCursor = { isOffsetCursorEnabled = !isOffsetCursorEnabled },
                                onSmoothEdges = { performSmoothEdges() }
                            )
                        }
                        EditorTool.BACKGROUND -> {
                            BackgroundSelector(
                                selectedBg = previewBgType,
                                onSelectBg = { previewBgType = it }
                            )
                        }
                        EditorTool.AUTO -> {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 12.dp, vertical = 6.dp),
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                val isAutoActive = currentCutoutMode == CutoutMode.SMART_AUTO
                                Box(
                                    modifier = Modifier
                                        .weight(1f)
                                        .clip(RoundedCornerShape(14.dp))
                                        .background(if (isAutoActive) PrimaryIndigo.copy(alpha = 0.25f) else StudioCardBgElevated)
                                        .border(1.dp, if (isAutoActive) PrimaryCyan else StudioBorder, RoundedCornerShape(14.dp))
                                        .clickable { performAiCutout(CutoutMode.SMART_AUTO) }
                                        .padding(horizontal = 8.dp, vertical = 8.dp),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                        Text(
                                            text = "✨ Tự động AI",
                                            fontSize = 12.sp,
                                            fontWeight = FontWeight.Bold,
                                            color = if (isAutoActive) PrimaryCyan else TextPrimary
                                        )
                                        Text(
                                            text = "Cân bằng tối ưu",
                                            fontSize = 10.sp,
                                            color = TextSecondary
                                        )
                                    }
                                }

                                val isSmartActive = currentCutoutMode == CutoutMode.SMART_OBJECT
                                Box(
                                    modifier = Modifier
                                        .weight(1f)
                                        .clip(RoundedCornerShape(14.dp))
                                        .background(if (isSmartActive) PrimaryIndigo.copy(alpha = 0.25f) else StudioCardBgElevated)
                                        .border(1.dp, if (isSmartActive) PrimaryCyan else StudioBorder, RoundedCornerShape(14.dp))
                                        .clickable { performAiCutout(CutoutMode.SMART_OBJECT) }
                                        .padding(horizontal = 8.dp, vertical = 8.dp),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                        Text(
                                            text = "🎯 Giữ chi tiết",
                                            fontSize = 12.sp,
                                            fontWeight = FontWeight.Bold,
                                            color = if (isSmartActive) PrimaryCyan else TextPrimary
                                        )
                                        Text(
                                            text = "Đồ vật & phụ kiện",
                                            fontSize = 10.sp,
                                            color = TextSecondary
                                        )
                                    }
                                }
                            }
                        }
                        EditorTool.INSPECT -> {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 16.dp, vertical = 6.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = "Dùng 2 ngón tay thu phóng để kiểm tra viền",
                                    fontSize = 11.sp,
                                    color = TextSecondary
                                )

                                Box(
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(12.dp))
                                        .background(StudioCardBgElevated)
                                        .border(1.dp, StudioBorder, RoundedCornerShape(12.dp))
                                        .clickable { performSmoothEdges() }
                                        .padding(horizontal = 10.dp, vertical = 5.dp),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Icon(
                                            imageVector = Icons.Default.AutoFixHigh,
                                            contentDescription = null,
                                            tint = PrimaryCyan,
                                            modifier = Modifier.size(13.dp)
                                        )
                                        Spacer(modifier = Modifier.width(4.dp))
                                        Text(
                                            text = "Làm mịn viền",
                                            fontSize = 10.sp,
                                            fontWeight = FontWeight.Bold,
                                            color = PrimaryCyan
                                        )
                                    }
                                }
                            }
                        }
                    }

                    // Main Segmented Tool Navigation Bar
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .navigationBarsPadding()
                            .padding(horizontal = 12.dp, vertical = 6.dp),
                        horizontalArrangement = Arrangement.SpaceAround,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        StudioBottomTab(
                            title = "AI Tự động",
                            icon = Icons.Default.AutoAwesome,
                            isSelected = activeTool == EditorTool.AUTO,
                            accentColor = PrimaryCyan,
                            onClick = { activeTool = EditorTool.AUTO }
                        )

                        StudioBottomTab(
                            title = "Cọ tẩy",
                            icon = Icons.Default.CleaningServices,
                            isSelected = activeTool == EditorTool.ERASE,
                            accentColor = Color(0xFFEF4444),
                            onClick = { activeTool = EditorTool.ERASE }
                        )

                        StudioBottomTab(
                            title = "Phục hồi",
                            icon = Icons.Default.Brush,
                            isSelected = activeTool == EditorTool.RESTORE,
                            accentColor = EmeraldSuccess,
                            onClick = { activeTool = EditorTool.RESTORE }
                        )

                        StudioBottomTab(
                            title = "Đổi phông",
                            icon = Icons.Default.ColorLens,
                            isSelected = activeTool == EditorTool.BACKGROUND,
                            accentColor = PrimaryPurple,
                            onClick = { activeTool = EditorTool.BACKGROUND }
                        )
                    }
                }
            }
        }

        // Modern Pulsing AI Processing Modal Overlay
        if (isProcessing) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = if (isFirstLaunch) 0.78f else 0.40f)),
                contentAlignment = Alignment.Center
            ) {
                Surface(
                    shape = RoundedCornerShape(24.dp),
                    color = StudioCardBg,
                    border = androidx.compose.foundation.BorderStroke(1.dp, PrimaryPurple.copy(alpha = 0.5f)),
                    modifier = Modifier.padding(32.dp)
                ) {
                    Column(
                        modifier = Modifier.padding(28.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        CircularProgressIndicator(
                            color = PrimaryCyan,
                            trackColor = PrimaryPurple.copy(alpha = 0.3f),
                            strokeWidth = 4.dp,
                            modifier = Modifier.size(52.dp)
                        )
                        Spacer(modifier = Modifier.height(18.dp))
                        Text(
                            text = processingMessage,
                            fontSize = 14.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = TextPrimary
                        )
                        Text(
                            text = "Mô hình On-Device đang xử lý ngoại tuyến...",
                            fontSize = 11.sp,
                            color = TextSecondary,
                            modifier = Modifier.padding(top = 4.dp)
                        )
                    }
                }
            }
        }

        // Export Success Dialog
        if (showExportDialog) {
            ExportSuccessDialog(
                bitmap = currentBitmap,
                savedUri = savedUri,
                selectedBg = previewBgType,
                onDismiss = { showExportDialog = false },
                onShare = {
                    scope.launch {
                        val shareUri = BitmapUtils.saveToCacheForSharing(context, currentBitmap)
                        if (shareUri != null) {
                            val shareIntent = Intent(Intent.ACTION_SEND).apply {
                                type = "image/png"
                                putExtra(Intent.EXTRA_STREAM, shareUri)
                                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                            }
                            context.startActivity(Intent.createChooser(shareIntent, "Chia sẻ ảnh PNG"))
                        }
                    }
                },
                onSaveWithBackground = if (previewBgType != PreviewBgType.CHECKERBOARD) {
                    { saveWithCurrentBackground() }
                } else null,
                onPickAnother = {
                    showExportDialog = false
                    onPickAnother()
                }
            )
        }
    }
}

@Composable
private fun StudioBottomTab(
    title: String,
    icon: ImageVector,
    isSelected: Boolean,
    accentColor: Color,
    onClick: () -> Unit
) {
    Column(
        modifier = Modifier
            .clip(RoundedCornerShape(14.dp))
            .clickable { onClick() }
            .padding(horizontal = 14.dp, vertical = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Box(
            modifier = Modifier
                .size(38.dp)
                .clip(CircleShape)
                .background(
                    if (isSelected) accentColor.copy(alpha = 0.2f)
                    else Color.Transparent
                )
                .border(
                    width = if (isSelected) 1.5.dp else 0.dp,
                    color = if (isSelected) accentColor else Color.Transparent,
                    shape = CircleShape
                ),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = icon,
                contentDescription = title,
                tint = if (isSelected) accentColor else Color(0xFF64748B),
                modifier = Modifier.size(20.dp)
            )
        }
        Text(
            text = title,
            fontSize = 11.sp,
            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
            color = if (isSelected) TextPrimary else TextSecondary,
            modifier = Modifier.padding(top = 4.dp)
        )
    }
}
