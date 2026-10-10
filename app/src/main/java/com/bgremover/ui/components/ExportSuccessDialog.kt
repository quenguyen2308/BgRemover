package com.bgremover.ui.components

import android.graphics.Bitmap
import android.net.Uri
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ColorLens
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.bgremover.model.PreviewBgType
import com.bgremover.ui.theme.EmeraldSuccess
import com.bgremover.ui.theme.PrimaryCyan
import com.bgremover.ui.theme.PrimaryIndigo
import com.bgremover.ui.theme.PrimaryPurple
import com.bgremover.ui.theme.StudioBorder
import com.bgremover.ui.theme.StudioCardBg
import com.bgremover.ui.theme.StudioCardBgElevated
import com.bgremover.ui.theme.TextPrimary
import com.bgremover.ui.theme.TextSecondary
import com.bgremover.ui.theme.TextTertiary

@Composable
fun ExportSuccessDialog(
    bitmap: Bitmap,
    savedUri: Uri?,
    selectedBg: PreviewBgType? = null,
    onDismiss: () -> Unit,
    onShare: () -> Unit,
    onSaveWithBackground: (() -> Unit)? = null,
    onPickAnother: () -> Unit
) {
    Dialog(onDismissRequest = onDismiss) {
        Surface(
            shape = RoundedCornerShape(26.dp),
            color = StudioCardBg,
            tonalElevation = 8.dp,
            border = androidx.compose.foundation.BorderStroke(1.dp, StudioBorder),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(
                modifier = Modifier.padding(22.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                // Success Badge
                Box(
                    modifier = Modifier
                        .size(52.dp)
                        .clip(CircleShape)
                        .background(EmeraldSuccess.copy(alpha = 0.15f)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.CheckCircle,
                        contentDescription = null,
                        tint = EmeraldSuccess,
                        modifier = Modifier.size(30.dp)
                    )
                }

                Spacer(modifier = Modifier.height(10.dp))

                Text(
                    text = "Đã lưu ảnh PNG thành công!",
                    fontSize = 17.sp,
                    fontWeight = FontWeight.Bold,
                    color = TextPrimary
                )

                Text(
                    text = "Định dạng PNG trong suốt • ${bitmap.width} × ${bitmap.height} px",
                    fontSize = 12.sp,
                    color = TextSecondary,
                    modifier = Modifier.padding(top = 4.dp, bottom = 14.dp)
                )

                // Image preview with checkerboard background
                Box(
                    modifier = Modifier
                        .size(190.dp)
                        .clip(RoundedCornerShape(18.dp))
                        .border(1.dp, StudioBorder, RoundedCornerShape(18.dp))
                ) {
                    CheckerboardBackground(
                        modifier = Modifier.size(190.dp),
                        squareSize = 8.dp
                    ) {
                        Image(
                            bitmap = bitmap.asImageBitmap(),
                            contentDescription = "Kết quả tách nền",
                            contentScale = ContentScale.Fit,
                            modifier = Modifier.size(190.dp)
                        )
                    }
                }

                Text(
                    text = "Lưu tại: Bộ nhớ trong > Pictures > BgRemover",
                    fontSize = 11.sp,
                    color = TextTertiary,
                    modifier = Modifier.padding(top = 10.dp, bottom = 14.dp)
                )

                // Save with background button if color background is chosen
                if (selectedBg != null && selectedBg != PreviewBgType.CHECKERBOARD && onSaveWithBackground != null) {
                    OutlinedButton(
                        onClick = onSaveWithBackground,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(44.dp)
                            .padding(bottom = 8.dp),
                        shape = RoundedCornerShape(14.dp),
                        border = androidx.compose.foundation.BorderStroke(1.dp, PrimaryCyan.copy(alpha = 0.6f)),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = PrimaryCyan)
                    ) {
                        Icon(imageVector = Icons.Default.ColorLens, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = "Lưu thêm ảnh kèm nền ${selectedBg.displayName}",
                            fontSize = 12.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                }

                // Glowing Share Action Button
                Button(
                    onClick = onShare,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(46.dp)
                        .clip(RoundedCornerShape(14.dp))
                        .background(
                            Brush.horizontalGradient(
                                listOf(PrimaryPurple, PrimaryIndigo)
                            )
                        ),
                    colors = ButtonDefaults.buttonColors(containerColor = Color.Transparent),
                    shape = RoundedCornerShape(14.dp)
                ) {
                    Icon(imageVector = Icons.Default.Share, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Chia sẻ ảnh ngay", fontWeight = FontWeight.Bold, color = Color.White)
                }

                Spacer(modifier = Modifier.height(10.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedButton(
                        onClick = onDismiss,
                        modifier = Modifier
                            .weight(1f)
                            .height(42.dp),
                        shape = RoundedCornerShape(12.dp),
                        border = androidx.compose.foundation.BorderStroke(1.dp, StudioBorder),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = TextPrimary)
                    ) {
                        Text("Tiếp tục sửa", fontSize = 12.sp)
                    }

                    OutlinedButton(
                        onClick = onPickAnother,
                        modifier = Modifier
                            .weight(1f)
                            .height(42.dp),
                        shape = RoundedCornerShape(12.dp),
                        border = androidx.compose.foundation.BorderStroke(1.dp, StudioBorder),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = PrimaryCyan)
                    ) {
                        Text("Ảnh khác", fontSize = 12.sp)
                    }
                }
            }
        }
    }
}
