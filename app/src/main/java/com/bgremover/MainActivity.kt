package com.bgremover

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import com.bgremover.ui.screens.EditorScreen
import com.bgremover.ui.screens.HomeScreen
import com.bgremover.ui.theme.BgRemoverTheme
import com.bgremover.ui.theme.PrimaryIndigo
import com.bgremover.util.BitmapUtils
import kotlinx.coroutines.launch
import java.io.File

class MainActivity : ComponentActivity() {

    private val incomingImageUriState = mutableStateOf<Uri?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        parseIntent(intent)
        initMlKitModules()

        setContent {
            BgRemoverTheme {
                MainContent(
                    incomingUri = incomingImageUriState.value,
                    onResetUri = { incomingImageUriState.value = null }
                )
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        parseIntent(intent)
    }

    private fun parseIntent(intent: Intent?) {
        if (intent == null) return
        if (intent.action == Intent.ACTION_SEND && intent.type?.startsWith("image/") == true) {
            val uri: Uri? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
            } else {
                @Suppress("DEPRECATION")
                intent.getParcelableExtra(Intent.EXTRA_STREAM)
            }
            incomingImageUriState.value = uri
        } else if (intent.action == Intent.ACTION_VIEW && intent.data != null) {
            incomingImageUriState.value = intent.data
        } else if (intent.hasExtra("image_path")) {
            val rawPath = intent.getStringExtra("image_path")?.trim('\'', '"')
            if (!rawPath.isNullOrBlank()) {
                val file = File(rawPath)
                android.util.Log.d("MainActivity", "Processing image_path=$rawPath, exists=${file.exists()}")
                incomingImageUriState.value = Uri.fromFile(file)
            }
        }
    }

    private fun initMlKitModules() {
        try {
            val moduleInstallClient = com.google.android.gms.common.moduleinstall.ModuleInstall.getClient(this)
            val options = com.google.mlkit.vision.segmentation.subject.SubjectSegmenterOptions.Builder().build()
            val segmenter = com.google.mlkit.vision.segmentation.subject.SubjectSegmentation.getClient(options)
            val request = com.google.android.gms.common.moduleinstall.ModuleInstallRequest.newBuilder()
                .addApi(segmenter)
                .build()
            moduleInstallClient.installModules(request)
        } catch (_: Throwable) {
            // Safely ignore if GMS or ModuleInstall is unavailable
        }
    }
}

@Composable
fun MainContent(
    incomingUri: Uri?,
    onResetUri: () -> Unit = {}
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var activeBitmap by remember { mutableStateOf<Bitmap?>(null) }
    var isLoadingImage by remember { mutableStateOf(false) }
    var cameraTempUri by remember { mutableStateOf<Uri?>(null) }

    fun loadBitmapFromUri(uri: Uri) {
        scope.launch {
            try {
                isLoadingImage = true
                val bitmap = BitmapUtils.loadBitmapFromUri(context, uri)
                isLoadingImage = false
                if (bitmap != null) {
                    activeBitmap = bitmap
                } else {
                    Toast.makeText(context, "Không thể đọc định dạng ảnh này", Toast.LENGTH_SHORT).show()
                }
            } catch (t: Throwable) {
                isLoadingImage = false
                android.util.Log.e("MainActivity", "Failed to load image from $uri", t)
                Toast.makeText(context, "Lỗi đọc ảnh: ${t.message}", Toast.LENGTH_SHORT).show()
            }
        }
    }

    // Photo Picker launcher
    val photoPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickVisualMedia()
    ) { uri ->
        uri?.let { loadBitmapFromUri(it) }
    }

    // Camera capture launcher
    val takePictureLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.TakePicture()
    ) { success ->
        if (success) {
            cameraTempUri?.let { loadBitmapFromUri(it) }
        }
    }

    // Camera permission request launcher
    val cameraPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (isGranted) {
            launchCamera(context, { cameraTempUri = it }, takePictureLauncher::launch)
        } else {
            Toast.makeText(context, "Cần cấp quyền camera để chụp ảnh", Toast.LENGTH_SHORT).show()
        }
    }

    fun startCamera() {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            launchCamera(context, { cameraTempUri = it }, takePictureLauncher::launch)
        } else {
            cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
        }
    }

    // Handle incoming image if app was launched via Share sheet
    LaunchedEffect(incomingUri) {
        incomingUri?.let { loadBitmapFromUri(it) }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        val currentBitmap = activeBitmap
        if (currentBitmap != null) {
            EditorScreen(
                initialBitmap = currentBitmap,
                onBack = { 
                    activeBitmap = null 
                    onResetUri()
                },
                onPickAnother = {
                    activeBitmap = null
                    onResetUri()
                    photoPickerLauncher.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                }
            )
        } else {
            HomeScreen(
                onPickImageFromGallery = {
                    photoPickerLauncher.launch(
                        PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                    )
                },
                onTakePhotoFromCamera = {
                    startCamera()
                },
                onSelectRecentCutout = { uri ->
                    loadBitmapFromUri(uri)
                }
            )
        }

        // Loading Overlay when decoding image
        if (isLoadingImage) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.5f)),
                contentAlignment = Alignment.Center
            ) {
                Surface(
                    shape = RoundedCornerShape(16.dp),
                    color = MaterialTheme.colorScheme.surface,
                    modifier = Modifier.padding(32.dp)
                ) {
                    Column(
                        modifier = Modifier.padding(24.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        CircularProgressIndicator(color = PrimaryIndigo, modifier = Modifier.size(40.dp))
                        Spacer(modifier = Modifier.height(16.dp))
                        Text(
                            text = "Đang tải ảnh...",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                    }
                }
            }
        }
    }
}

private fun launchCamera(
    context: android.content.Context,
    onUriReady: (Uri) -> Unit,
    launchAction: (Uri) -> Unit
) {
    try {
        val cameraDir = File(context.cacheDir, "camera_photos").apply { mkdirs() }
        val tempFile = File.createTempFile("photo_", ".jpg", cameraDir)
        val uri = FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            tempFile
        )
        onUriReady(uri)
        launchAction(uri)
    } catch (e: Exception) {
        Toast.makeText(context, "Lỗi khởi động camera: ${e.message}", Toast.LENGTH_SHORT).show()
    }
}
