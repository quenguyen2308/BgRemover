package com.bgremover.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.bgremover.ui.theme.PrimaryCyan
import com.bgremover.ui.theme.PrimaryIndigo
import com.bgremover.ui.theme.StudioBorder
import com.bgremover.ui.theme.StudioCardBgElevated

@Composable
fun CompareButton(
    isPressed: Boolean,
    onPressChanged: (Boolean) -> Unit,
    modifier: Modifier = Modifier
) {
    val bgModifier = if (isPressed) {
        Modifier
            .background(
                Brush.horizontalGradient(
                    listOf(PrimaryIndigo, PrimaryCyan)
                )
            )
            .border(1.dp, PrimaryCyan, RoundedCornerShape(20.dp))
    } else {
        Modifier
            .background(StudioCardBgElevated)
            .border(1.dp, StudioBorder, RoundedCornerShape(20.dp))
    }

    Box(
        modifier = modifier
            .clip(RoundedCornerShape(20.dp))
            .then(bgModifier)
            .pointerInput(Unit) {
                detectTapGestures(
                    onPress = {
                        onPressChanged(true)
                        tryAwaitRelease()
                        onPressChanged(false)
                    }
                )
            }
            .padding(horizontal = 12.dp, vertical = 7.dp),
        contentAlignment = Alignment.Center
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                imageVector = Icons.Default.Visibility,
                contentDescription = "Xem ảnh gốc",
                tint = if (isPressed) Color.White else PrimaryCyan,
                modifier = Modifier.size(15.dp)
            )
            Spacer(modifier = Modifier.width(5.dp))
            Text(
                text = if (isPressed) "Ảnh gốc" else "Xem gốc",
                fontSize = 11.sp,
                fontWeight = if (isPressed) FontWeight.Bold else FontWeight.Medium,
                color = if (isPressed) Color.White else Color(0xFFCBD5E1)
            )
        }
    }
}
