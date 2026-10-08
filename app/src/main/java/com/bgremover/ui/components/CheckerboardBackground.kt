package com.bgremover.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

@Composable
fun CheckerboardBackground(
    modifier: Modifier = Modifier,
    squareSize: Dp = 12.dp,
    lightColor: Color = Color(0xFFF1F5F9),
    darkColor: Color = Color(0xFFCBD5E1),
    content: @Composable () -> Unit = {}
) {
    Box(modifier = modifier) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val squarePx = squareSize.toPx()
            val numCols = (size.width / squarePx).toInt() + 1
            val numRows = (size.height / squarePx).toInt() + 1

            for (row in 0 until numRows) {
                for (col in 0 until numCols) {
                    val isEven = (row + col) % 2 == 0
                    val color = if (isEven) lightColor else darkColor
                    drawRect(
                        color = color,
                        topLeft = Offset(col * squarePx, row * squarePx),
                        size = Size(squarePx, squarePx)
                    )
                }
            }
        }
        content()
    }
}
