package io.mayu.birdpilot

import android.Manifest
import android.content.ContentValues
import android.content.pm.PackageManager
import android.hardware.display.DisplayManager
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.MediaStore
import android.util.Log
import android.view.KeyEvent
import android.view.Surface
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executor
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

class MainActivity : ComponentActivity() {
    private var previewView: PreviewView? = null
    private var imageCapture: ImageCapture? = null
    private lateinit var cameraExecutor: ExecutorService
    private lateinit var mainExecutor: Executor
    private val isCapturing = AtomicBoolean(false)
    private var lastShotAt: Long = 0L
    private var isCameraScreenVisible: Boolean = false
    private lateinit var displayManager: DisplayManager
    private val displayListener = object : DisplayManager.DisplayListener {
        override fun onDisplayAdded(displayId: Int) = Unit

        override fun onDisplayRemoved(displayId: Int) = Unit

        override fun onDisplayChanged(displayId: Int) {
            val view = previewView ?: return
            val display = view.display ?: return
            if (display.displayId == displayId) {
                updateTargetRotation()
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        mainExecutor = ContextCompat.getMainExecutor(this)
        cameraExecutor = Executors.newSingleThreadExecutor()
        displayManager = getSystemService(Context.DISPLAY_SERVICE) as DisplayManager
        displayManager.registerDisplayListener(displayListener, Handler(Looper.getMainLooper()))
        setContent {
            BirdPilotApp()
        }
    }

    override fun onDestroy() {
        displayManager.unregisterDisplayListener(displayListener)
        cameraExecutor.shutdown()
        super.onDestroy()
    }

    override fun dispatchKeyEvent(ev: KeyEvent): Boolean {
        if (isCameraScreenVisible &&
            (ev.keyCode == KeyEvent.KEYCODE_VOLUME_UP || ev.keyCode == KeyEvent.KEYCODE_VOLUME_DOWN)
        ) {
            return when (ev.action) {
                KeyEvent.ACTION_DOWN -> {
                    if (ev.repeatCount == 0) {
                        requestCapture()
                    }
                    true
                }

                KeyEvent.ACTION_UP -> true
                else -> true
            }
        }
        return super.dispatchKeyEvent(ev)
    }

    fun registerCameraComponents(preview: PreviewView, capture: ImageCapture) {
        previewView = preview
        imageCapture = capture
        updateTargetRotation()
    }

    fun unregisterCameraComponents(preview: PreviewView) {
        if (previewView === preview) {
            previewView = null
            imageCapture = null
        }
    }

    fun setCameraScreenVisible(visible: Boolean) {
        isCameraScreenVisible = visible
    }

    fun requestCapture() {
        triggerCapture()
    }

    private fun triggerCapture(): Boolean {
        val capture = imageCapture ?: return false
        val preview = previewView ?: return false
        val now = SystemClock.uptimeMillis()
        if (now - lastShotAt < 500L) {
            return false
        }
        if (!isCapturing.compareAndSet(false, true)) {
            return false
        }
        lastShotAt = now

        val contentResolver = contentResolver
        cameraExecutor.execute {
            try {
                val rotation = preview.display?.rotation ?: Surface.ROTATION_0
                capture.targetRotation = rotation
                val displayName = SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.getDefault()).format(Date())
                val contentValues = ContentValues().apply {
                    put(MediaStore.MediaColumns.DISPLAY_NAME, displayName)
                    put(MediaStore.MediaColumns.MIME_TYPE, "image/jpeg")
                    put(
                        MediaStore.Images.Media.RELATIVE_PATH,
                        Environment.DIRECTORY_DCIM + "/BirdCam"
                    )
                }
                val outputOptions = ImageCapture.OutputFileOptions.Builder(
                    contentResolver,
                    MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                    contentValues
                ).build()

                capture.takePicture(
                    outputOptions,
                    mainExecutor,
                    object : ImageCapture.OnImageSavedCallback {
                        override fun onImageSaved(outputFileResults: ImageCapture.OutputFileResults) {
                            Toast.makeText(this@MainActivity, "Saved", Toast.LENGTH_SHORT).show()
                            isCapturing.set(false)
                        }

                        override fun onError(exception: ImageCaptureException) {
                            Toast.makeText(
                                this@MainActivity,
                                "Failed: ${'$'}{exception.message ?: exception.javaClass.simpleName}",
                                Toast.LENGTH_SHORT
                            ).show()
                            Log.e("MainActivity", "Failed to save image", exception)
                            isCapturing.set(false)
                        }
                    }
                )
            } catch (throwable: Throwable) {
                mainExecutor.execute {
                    Toast.makeText(
                        this@MainActivity,
                        "Failed: ${'$'}{throwable.message ?: throwable.javaClass.simpleName}",
                        Toast.LENGTH_SHORT
                    ).show()
                    Log.e("MainActivity", "Failed to capture image", throwable)
                    isCapturing.set(false)
                }
            }
        }
        return true
    }

    private fun updateTargetRotation() {
        val capture = imageCapture ?: return
        val rotation = previewView?.display?.rotation ?: Surface.ROTATION_0
        capture.targetRotation = rotation
    }
}

private enum class Screen {
    Camera,
    Gallery
}

@Composable
fun BirdPilotApp() {
    val context = LocalContext.current
    val activity = context as? MainActivity
    val lifecycleOwner = LocalLifecycleOwner.current
    val cameraPermission = Manifest.permission.CAMERA

    var allPermissionsGranted by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, cameraPermission) == PackageManager.PERMISSION_GRANTED
        )
    }

    val cameraPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted ->
        allPermissionsGranted = granted ||
            ContextCompat.checkSelfPermission(context, cameraPermission) == PackageManager.PERMISSION_GRANTED
    }

    LaunchedEffect(Unit) {
        if (!allPermissionsGranted) {
            cameraPermissionLauncher.launch(cameraPermission)
        }
    }

    var currentScreen by remember { mutableStateOf(Screen.Camera) }

    LaunchedEffect(allPermissionsGranted) {
        if (!allPermissionsGranted) {
            currentScreen = Screen.Camera
        }
    }

    LaunchedEffect(currentScreen, activity) {
        activity?.setCameraScreenVisible(currentScreen == Screen.Camera)
    }

    val galleryPermission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        Manifest.permission.READ_MEDIA_IMAGES
    } else {
        Manifest.permission.READ_EXTERNAL_STORAGE
    }

    val galleryPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted || ContextCompat.checkSelfPermission(context, galleryPermission) == PackageManager.PERMISSION_GRANTED) {
            currentScreen = Screen.Gallery
        } else {
            Toast.makeText(context, "写真へのアクセス許可が必要です", Toast.LENGTH_SHORT).show()
        }
    }

    val onGalleryClick = {
        if (ContextCompat.checkSelfPermission(context, galleryPermission) == PackageManager.PERMISSION_GRANTED) {
            currentScreen = Screen.Gallery
        } else {
            galleryPermissionLauncher.launch(galleryPermission)
        }
    }

    MaterialTheme {
        Surface(modifier = Modifier.fillMaxSize()) {
            if (allPermissionsGranted) {
                when (currentScreen) {
                    Screen.Camera -> CameraPreview(
                        lifecycleOwner = lifecycleOwner,
                        onGalleryClick = onGalleryClick
                    )

                    Screen.Gallery -> GalleryScreen(
                        onBack = { currentScreen = Screen.Camera }
                    )
                }
            } else {
                PermissionRequestView(onRequestPermission = {
                    cameraPermissionLauncher.launch(cameraPermission)
                })
            }
        }
    }
}

@Composable
private fun PermissionRequestView(onRequestPermission: () -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp),
            modifier = Modifier.padding(24.dp)
        ) {
            Text(
                text = "カメラ撮影とギャラリー表示には権限が必要です",
                color = Color.White
            )
            Button(onClick = onRequestPermission) {
                Text(text = "権限を許可")
            }
        }
    }
}

@Composable
private fun CameraPreview(
    lifecycleOwner: LifecycleOwner,
    onGalleryClick: () -> Unit
) {
    val context = LocalContext.current
    val previewView = remember {
        PreviewView(context).apply {
            scaleType = PreviewView.ScaleType.FILL_CENTER
        }
    }
    val imageCapture = remember {
        ImageCapture.Builder()
            .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
            .build()
    }

    DisposableEffect(activity, previewView, imageCapture) {
        activity?.registerCameraComponents(previewView, imageCapture)
        onDispose {
            activity?.unregisterCameraComponents(previewView)
        }
    }

    DisposableEffect(lifecycleOwner) {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(context)
        var cameraProvider: ProcessCameraProvider? = null

        val listener = Runnable {
            cameraProvider = cameraProviderFuture.get()
            val preview = Preview.Builder().build().also {
                it.setSurfaceProvider(previewView.surfaceProvider)
            }
            val selector = CameraSelector.DEFAULT_BACK_CAMERA

            imageCapture.targetRotation = previewView.display?.rotation ?: Surface.ROTATION_0

            cameraProvider?.unbindAll()
            cameraProvider?.bindToLifecycle(lifecycleOwner, selector, preview, imageCapture)
        }

        cameraProviderFuture.addListener(listener, executor)

        onDispose {
            cameraProvider?.unbindAll()
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { previewView }
        )

        GalleryButton(
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(24.dp),
            onClick = onGalleryClick
        )

        ShutterButton(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 32.dp)
        ) {
            activity?.requestCapture()
        }
    }
}

@Composable
private fun GalleryButton(
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    Box(
        modifier = modifier
            .size(48.dp)
            .clip(CircleShape)
            .background(Color.Black.copy(alpha = 0.4f))
            .border(width = 1.dp, color = Color.White, shape = CircleShape)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = "🖼",
            color = Color.White,
            fontSize = 20.sp,
            textAlign = TextAlign.Center
        )
    }
}

@Composable
private fun ShutterButton(
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    Box(
        modifier = modifier,
        contentAlignment = Alignment.Center
    ) {
        Box(
            modifier = Modifier
                .size(80.dp)
                .clip(CircleShape)
                .background(Color.Transparent)
                .border(width = 4.dp, color = Color.White, shape = CircleShape)
                .clickable(onClick = onClick),
            contentAlignment = Alignment.Center
        ) {
            Box(
                modifier = Modifier
                    .size(60.dp)
                    .clip(CircleShape)
                    .background(Color.White)
            )
        }
    }
}
