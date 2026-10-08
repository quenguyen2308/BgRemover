package com.bgremover.model

import android.net.Uri

data class SavedCutout(
    val uri: Uri,
    val name: String,
    val sizeBytes: Long,
    val dateModified: Long,
    val width: Int = 0,
    val height: Int = 0
)
