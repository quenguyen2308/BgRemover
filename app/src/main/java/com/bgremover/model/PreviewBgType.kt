package com.bgremover.model

import androidx.compose.ui.graphics.Color

enum class PreviewBgType(val displayName: String, val color: Color?) {
    CHECKERBOARD("Caro trong suốt", null),
    WHITE("Trắng", Color.White),
    BLACK("Đen", Color.Black),
    GREEN("Xanh lá", Color(0xFF10B981)),
    RED("Đỏ", Color(0xFFEF4444)),
    BLUE("Xanh dương", Color(0xFF3B82F6))
}
