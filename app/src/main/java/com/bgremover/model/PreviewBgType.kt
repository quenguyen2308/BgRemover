package com.bgremover.model

import androidx.compose.ui.graphics.Color

enum class PreviewBgType(val displayName: String, val color: Color?) {
    BLACK("Đen", Color.Black),
    WHITE("Trắng", Color.White),
    CHECKERBOARD("Caro trong suốt", null),
    GREEN("Xanh lá", Color(0xFF10B981)),
    RED("Đỏ", Color(0xFFEF4444)),
    BLUE("Xanh dương", Color(0xFF3B82F6))
}
