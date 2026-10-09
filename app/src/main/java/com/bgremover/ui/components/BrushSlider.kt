package com.bgremover.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoFixHigh
import androidx.compose.material.icons.filled.PanTool
import androidx.compose.material.icons.filled.TouchApp
import androidx.compose.material3.Icon
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.bgremover.ui.theme.PrimaryCyan
import com.bgremover.ui.theme.PrimaryIndigo
import com.bgremover.ui.theme.StudioBorder
import com.bgremover.ui.theme.StudioCardBgElevated
import com.bgremover.ui.theme.TextPrimary
import com.bgremover.ui.theme.TextSecondary

@Composable
fun BrushSlider(
    brushSizeDp: Dp,
    onBrushSizeChange: (Dp) -> Unit,
    modifier: Modifier = Modifier,
    accentColor: Color = PrimaryIndigo,
    isOffsetCursorEnabled: Boolean = true,
    onToggleOffsetCursor: () -> Unit = {},
    onSmoothEdges: (() -> Unit)? = null
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = "Cỡ cọ:",
                    fontSize = 12.sp,
                    color = TextSecondary
                )
                Spacer(modifier = Modifier.width(4.dp))
                Text(
                    text = "${brushSizeDp.value.toInt()} px",
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    color = TextPrimary
                )
            }

            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                // Offset cursor toggle button
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(12.dp))
                        .background(if (isOffsetCursorEnabled) PrimaryCyan.copy(alpha = 0.2f) else StudioCardBgElevated)
                        .border(1.dp, if (isOffsetCursorEnabled) PrimaryCyan else StudioBorder, RoundedCornerShape(12.dp))
                        .clickable { onToggleOffsetCursor() }
                        .padding(horizontal = 8.dp, vertical = 4.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Default.TouchApp,
                            contentDescription = null,
                            tint = if (isOffsetCursorEnabled) PrimaryCyan else TextSecondary,
                            modifier = Modifier.size(13.dp)
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(
                            text = if (isOffsetCursorEnabled) "Bù cọ: Bật" else "Bù cọ: Tắt",
                            fontSize = 10.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = if (isOffsetCursorEnabled) PrimaryCyan else TextSecondary
                        )
                    }
                }

                // Smooth edges button if callback provided
                if (onSmoothEdges != null) {
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(12.dp))
                            .background(StudioCardBgElevated)
                            .border(1.dp, StudioBorder, RoundedCornerShape(12.dp))
                            .clickable { onSmoothEdges() }
                            .padding(horizontal = 8.dp, vertical = 4.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                imageVector = Icons.Default.AutoFixHigh,
                                contentDescription = null,
                                tint = PrimaryIndigo,
                                modifier = Modifier.size(13.dp)
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(
                                text = "Mịn viền",
                                fontSize = 10.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = TextPrimary
                            )
                        }
                    }
                }

                // Live circle diameter preview with ring
                Box(
                    modifier = Modifier
                        .size(32.dp)
                        .clip(CircleShape)
                        .border(1.dp, StudioBorder, CircleShape),
                    contentAlignment = Alignment.Center
                ) {
                    Box(
                        modifier = Modifier
                            .size(brushSizeDp.coerceIn(4.dp, 26.dp))
                            .clip(CircleShape)
                            .background(accentColor)
                    )
                }
            }
        }

        Slider(
            value = brushSizeDp.value,
            onValueChange = { onBrushSizeChange(it.dp) },
            valueRange = 8f..80f,
            colors = SliderDefaults.colors(
                thumbColor = accentColor,
                activeTrackColor = accentColor,
                inactiveTrackColor = StudioBorder
            ),
            modifier = Modifier.fillMaxWidth()
        )
    }
}
