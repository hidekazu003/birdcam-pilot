package io.mayu.birdpilot

// NOTE: Shutter sound policy — play ONLY in flashListener. Do not add legacy wrappers.
// Do NOT add/call: registerShutterSound(...), unregisterShutterSound(...), setShutterSoundEnabled(...)

import android.Manifest
import android.app.Activity
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.PointF
import android.graphics.Rect
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.hardware.display.DisplayManager
import android.media.MediaActionSound
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.MediaStore
import android.util.Log
import android.util.Size
import android.view.GestureDetector
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.Surface
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.FocusMeteringAction
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.core.UseCase
import androidx.camera.core.ZoomState
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.datastore.preferences.core.edit
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.Observer
import io.mayu.birdpilot.detector.BirdDetector
import io.mayu.birdpilot.detector.DetectorGate
import io.mayu.birdpilot.detector.Hud
import io.mayu.birdpilot.detector.hudState
import io.mayu.birdpilot.finder.StillScan
import io.mayu.birdpilot.overlay.RoiOverlay
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executor
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

private const val TAG = "BirdPilot"
private const val PERCH_COOLDOWN_MS = 1200L
private const val MAX_ASSIST_X = 2.0f
private const val ASSIST_X = 1.6f
class MainActivity : ComponentActivity() {
    private var previewView: PreviewView? = null
    private var imageCapture: ImageCapture? = null
    private lateinit var cameraExecutor: ExecutorService
    private lateinit var mainExecutor: Executor
    private val isCapturing = AtomicBoolean(false)
    private var lastShotAt: Long = 0L
    private var isCameraScreenVisible: Boolean = false
    private lateinit var displayManager: DisplayManager
    private var flashListener: (() -> Unit)? = null
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

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (isCameraScreenVisible &&
            (keyCode == KeyEvent.KEYCODE_VOLUME_UP || keyCode == KeyEvent.KEYCODE_VOLUME_DOWN)
        ) {
            if (event.repeatCount == 0) requestCapture()
            return true
        }
        return super.onKeyDown(keyCode, event)
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean {
        if (isCameraScreenVisible &&
            (keyCode == KeyEvent.KEYCODE_VOLUME_UP || keyCode == KeyEvent.KEYCODE_VOLUME_DOWN)
        ) {
            return true
        }
        return super.onKeyUp(keyCode, event)
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

    fun registerFlashListener(listener: () -> Unit) {
        flashListener = listener
    }

    fun unregisterFlashListener(listener: () -> Unit) {
        if (flashListener === listener) {
            flashListener = null
        }
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
                            flashListener?.invoke()
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
    Gallery,
    Settings
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
    var selectedPhoto by remember { mutableStateOf<Uri?>(null) }
    var galleryReloadSignal by remember { mutableStateOf(0) }
    var pendingDeleteCallback by remember { mutableStateOf<(() -> Unit)?>(null) }
    var pendingDeleteUri by remember { mutableStateOf<Uri?>(null) }
    val scope = rememberCoroutineScope()
    val finderEnabled by context.finderEnabledFlow.collectAsState(initial = false)

    val deleteRequestLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartIntentSenderForResult()
    ) { result ->
        val callback = pendingDeleteCallback
        if (result.resultCode == Activity.RESULT_OK) {
            callback?.invoke()
        } else if (pendingDeleteUri != null) {
            Toast.makeText(context, "削除がキャンセルされました", Toast.LENGTH_SHORT).show()
        }
        pendingDeleteCallback = null
        pendingDeleteUri = null
    }

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

    val deleteWithConsent: (Uri) -> Unit = { uri ->
        val resolver = context.contentResolver
        val onSuccess = {
            selectedPhoto = null
            galleryReloadSignal++
            Toast.makeText(context, "写真を削除しました", Toast.LENGTH_SHORT).show()
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            pendingDeleteCallback = {
                onSuccess()
                pendingDeleteCallback = null
                pendingDeleteUri = null
            }
            pendingDeleteUri = uri
            runCatching {
                val request = MediaStore.createDeleteRequest(resolver, listOf(uri))
                val intentSender = IntentSenderRequest.Builder(request.intentSender).build()
                deleteRequestLauncher.launch(intentSender)
            }.onFailure {
                pendingDeleteCallback = null
                pendingDeleteUri = null
                Toast.makeText(context, "削除に失敗しました", Toast.LENGTH_SHORT).show()
            }
        } else {
            try {
                val deleted = resolver.delete(uri, null, null) > 0
                if (deleted) {
                    onSuccess()
                } else {
                    Toast.makeText(context, "削除できませんでした", Toast.LENGTH_SHORT).show()
                }
            } catch (throwable: SecurityException) {
                Toast.makeText(context, "削除に失敗しました", Toast.LENGTH_SHORT).show()
            } catch (throwable: Exception) {
                Toast.makeText(context, "削除に失敗しました", Toast.LENGTH_SHORT).show()
            }
        }
    }

    MaterialTheme {
        Surface(modifier = Modifier.fillMaxSize()) {
            if (allPermissionsGranted) {
                when {
                    selectedPhoto != null -> PhotoViewerScreen(
                        uri = selectedPhoto!!,
                        onBack = { selectedPhoto = null },
                        onDelete = { deleteWithConsent(it) }
                    )

                    currentScreen == Screen.Camera -> CameraPreview(
                        lifecycleOwner = lifecycleOwner,
                        onGalleryClick = onGalleryClick,
                        onSettingsClick = { currentScreen = Screen.Settings },
                        finderEnabled = finderEnabled,
                        onToggleFinder = { v -> scope.launch { setFinderEnabled(context, v) } }

                    )

                    currentScreen == Screen.Gallery -> GalleryScreen(
                        onBack = { currentScreen = Screen.Camera },
                        onOpen = { selectedPhoto = it },
                        onDelete = { deleteWithConsent(it) },
                        reloadSignal = galleryReloadSignal
                    )

                    currentScreen == Screen.Settings -> SettingsScreen(
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
    onGalleryClick: () -> Unit,
    onSettingsClick: () -> Unit,
    finderEnabled: Boolean,
    onToggleFinder: (Boolean) -> Unit
) {
    val context = LocalContext.current
    val roiTh by roiThresholdFlow(context).collectAsState(initial = 0.4f)
    val activity = context as? MainActivity
    val executor = remember { ContextCompat.getMainExecutor(context) }
    val density = LocalDensity.current
    val haptics = LocalHapticFeedback.current
    var focusRingStroke by remember { mutableFloatStateOf(2f) }
    val sensorManager = remember(context) {
        context.getSystemService(Context.SENSOR_SERVICE) as? SensorManager
    }
    val gyroSensor = remember(sensorManager) {
        sensorManager?.getDefaultSensor(Sensor.TYPE_GYROSCOPE)
    }
    val previewView = remember { PreviewView(context).apply { scaleType = PreviewView.ScaleType.FILL_CENTER } }

    val detectorGate = remember { DetectorGate(minIntervalMs = 500L) }
    val birdDetector = remember { BirdDetector.heuristic() }
    var roi     by remember { mutableStateOf<Rect?>(null) }
    var roiScore by remember { mutableStateOf<Float?>(null) }
    var roiJudge by remember { mutableStateOf("—") }   // "GREEN"/"YELLOW" を入れる
    var perchAssistActive by remember { mutableStateOf(false) }
    var lastHud by remember { mutableStateOf(Hud.YELLOW) }
    var lastPerchAt by remember { mutableLongStateOf(0L) }

    val perchAssistScope = rememberCoroutineScope()

    var gyroOmega by remember { mutableStateOf(0f) }      // ← これを1つだけ残す（L529の方を採用）

    val showRoiFn: (Rect, Float?) -> Unit = { r, score ->
        roi = r
        roiScore = score
        val hud = hudState(score, roiTh)
        val becameGreen = hud == Hud.GREEN && lastHud != Hud.GREEN
        lastHud = hud
        perchAssistActive = hud == Hud.GREEN
        Log.d(TAG, "ROI ${hud.name} score=${score ?: -1f}")
        roiJudge = hud.name
        Log.d(TAG, "HUD judge=$roiJudge score=${score ?: -1f}")

        if (becameGreen) {
            val now = SystemClock.uptimeMillis()
            if (now - lastPerchAt >= PERCH_COOLDOWN_MS) {
                val cam = currentCamera.value
                val zoomState: ZoomState? = cam?.cameraInfo?.zoomState?.value
                if (cam != null && zoomState != null) {
                    val currentRatio = zoomState.zoomRatio
                    val target = min(min(currentRatio * ASSIST_X, MAX_ASSIST_X), zoomState.maxZoomRatio)
                    if (target > currentRatio) {
                        lastPerchAt = now
                        Log.d(TAG, "PA fire: $currentRatio -> $target")
                        perchAssistScope.launch {
                            withContext(Dispatchers.Main) {
                                cam.cameraControl.setZoomRatio(target)
                            }
                        }
                    }
                }
            }
        }
    }

    // --- C2 gate + logger (最小) ---
    val tryEnsureBirdness: (PointF) -> Unit = { pt ->
        StillScan.ensureBirdness(pt, previewView, detectorGate, birdDetector, showRoiFn)
    }


    // ========== C1_AUTOFIRE_BEGIN (do-not-remove) ==========
// C1: stability auto-fire (armDelayMs/holdMs/cooldownMs) + EMA + Hysteresis + recentQuiet

// --- Stability auto-fire params (C1) ---
    val stableOmega = 0.18f
    val holdMs      = 600L
    val armDelayMs  = 350L
    val cooldownMs  = 1200L

// --- C1 安定判定の堅牢化（EMA + ヒステリシス） ---
    val stableOmegaEnter = 0.14f
    val stableOmegaExit  = 0.25f
    var gyroEma          by remember { mutableFloatStateOf(0f) }
    var stableLatched    by remember { mutableStateOf(false) }
    var lastOverExitAt   by remember { mutableLongStateOf(0L) }

// C1 state
    var becameStableAt by remember { mutableLongStateOf(0L) }
    var lastAutoFireAt by remember { mutableLongStateOf(0L) }
    var autoArmed      by remember { mutableStateOf(false) }
    var lastTapAt      by remember { mutableLongStateOf(0L) }
    var lastTap        by remember { mutableStateOf(PointF(0.5f, 0.5f)) }

// タップ後の安定を監視して 1回だけ発火
    LaunchedEffect(Unit) {
        while (true) {
            val now = SystemClock.uptimeMillis()

            // EMA更新・ヒステリシス
            gyroEma = 0.2f * gyroOmega + 0.8f * gyroEma
            if (stableLatched) {
                if (gyroEma > stableOmegaExit) stableLatched = false
            } else {
                if (gyroEma < stableOmegaEnter) stableLatched = true
            }
            if (gyroOmega > stableOmegaExit) lastOverExitAt = now
            val recentQuiet = (now - lastOverExitAt) >= 800L

            val armedAndWaited = autoArmed && (now - lastTapAt) >= armDelayMs
            val stable = stableLatched

            if (!armedAndWaited || !stable || !recentQuiet) {
                becameStableAt = 0L
            } else {
                if (becameStableAt == 0L) becameStableAt = now
                val stableFor = now - becameStableAt
                if (stableFor >= holdMs && (now - lastAutoFireAt) >= cooldownMs) {
                    lastAutoFireAt = now
                    autoArmed = false
                    Log.d(TAG, "C1 fire")
                    tryEnsureBirdness(lastTap)       // C2ゲート経由
                }
            }
            kotlinx.coroutines.delay(50)             // 20Hz
        }
    }
// ========== C1_AUTOFIRE_END ==========


    LaunchedEffect(roi) {
        if (roi != null) {
            kotlinx.coroutines.delay(1200)
            roi = null
            roiScore = null
            perchAssistActive = false
        }
    }

    val previewUseCase = remember {
        Preview.Builder().build()
    }
    val imageCapture = remember {
        ImageCapture.Builder()
            .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
            .build()
    }
    var camera by remember { mutableStateOf<Camera?>(null) }
    var focusRingPosition by remember { mutableStateOf<Offset?>(null) }
    var linearZoom by remember { mutableStateOf(0f) }
    var zoomRatio by remember { mutableStateOf(1f) }
    val zoomOverlayAlpha = remember { Animatable(0f) }
    val flashAlpha = remember { Animatable(0f) }
    var fadeZoomJob by remember { mutableStateOf<Job?>(null) }
    var flashJob by remember { mutableStateOf<Job?>(null) }
    val scope = rememberCoroutineScope()
    val dataStore = remember(context) { context.cameraPreferenceDataStore }
    val showGridFlow = remember(dataStore) {
        dataStore.data.map { preferences -> preferences[GRID_ENABLED_KEY] ?: false }
    }
    val showGrid by showGridFlow.collectAsState(initial = false)
    val shutterSoundFlow = remember(dataStore) {
        dataStore.data.map { preferences -> preferences[SHUTTER_SOUND_KEY] ?: false }
    }
    val shutterSoundEnabled by shutterSoundFlow.collectAsState(initial = false)
    val finderProfileFlow = remember(dataStore) {
        dataStore.data.map { preferences ->
            FinderProfile.fromPreference(preferences[FINDER_PROFILE_KEY])
        }
    }
    val finderProfile by finderProfileFlow.collectAsState(initial = FinderProfile.OUTDOOR)
    val mediaActionSound = remember {
        MediaActionSound().apply {
            load(MediaActionSound.SHUTTER_CLICK)
        }
    }
    val cameraProviderFuture = remember { ProcessCameraProvider.getInstance(context) }
    var cameraProvider by remember { mutableStateOf<ProcessCameraProvider?>(null) }

    LaunchedEffect(cameraProviderFuture) {
        val provider = withContext(Dispatchers.IO) {
            cameraProviderFuture.get()
        }
        cameraProvider = provider
    }

    var finderResult by remember { mutableStateOf<FinderResult?>(null) }
    val finderAlpha = remember { Animatable(0f) }
    val finderExecutor = remember {
        Executors.newSingleThreadExecutor()
    }
    val finderAnalyzer = remember(previewView, executor) {
        FinderAnalyzer(
            previewView = previewView,
            mainExecutor = executor
        ) { result ->
            finderResult = result
        }
    }
    LaunchedEffect(finderAnalyzer, finderProfile) {
        val changed = finderAnalyzer.setProfile(finderProfile)
        if (changed) {
            finderAnalyzer.reset()
        }
    }

    LaunchedEffect(finderAnalyzer, gyroOmega) {
        finderAnalyzer.setGyroOmega(gyroOmega)
    }
    DisposableEffect(Unit) {
        onDispose {
            finderExecutor.shutdown()
        }
    }
    val imageAnalysis = remember {
        ImageAnalysis.Builder()
            .setTargetResolution(Size(320, 240))
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_YUV_420_888)
            .build()
    }

    LaunchedEffect(focusRingPosition) {
        val current = focusRingPosition ?: return@LaunchedEffect
        delay(3_000)
        if (focusRingPosition == current) {
            focusRingPosition = null
        }
    }

    LaunchedEffect(finderEnabled) {
        if (!finderEnabled) {
            finderAlpha.snapTo(0f)
        }
    }

    LaunchedEffect(finderResult?.timestamp, finderEnabled) {
        if (!finderEnabled || finderResult == null) {
            finderAlpha.animateTo(0f, tween(durationMillis = 180))
        } else {
            finderAlpha.animateTo(1f, tween(durationMillis = 120))
            val stamp = finderResult?.timestamp
            delay(1_000)
            if (finderResult?.timestamp == stamp && finderEnabled) {
                finderAlpha.animateTo(0.35f, tween(durationMillis = 250))
            }
        }
    }

    DisposableEffect(activity, previewView, imageCapture) {
        activity?.registerCameraComponents(previewView, imageCapture)
        onDispose {
            activity?.unregisterCameraComponents(previewView)
        }
    }

    val shutterSoundOn by rememberUpdatedState(shutterSoundEnabled)

    DisposableEffect(activity, scope) {
        if (activity == null) {
            onDispose {
                flashJob?.cancel()
                flashJob = null
            }
        } else {
            val listener: () -> Unit = {
                scope.launch {
                    flashJob?.cancel()
                    flashJob = launch {
                        try {
                            flashAlpha.snapTo(0f)

                            if (shutterSoundOn) runCatching {
                                mediaActionSound.play(MediaActionSound.SHUTTER_CLICK)
                            }

                            flashAlpha.animateTo(1f, tween(120))
                            flashAlpha.animateTo(0f, tween(180))
                        } finally {
                            flashJob = null
                        }
                    }
                }
            }
            activity.registerFlashListener(listener)
            onDispose {
                flashJob?.cancel()
                flashJob = null
                activity.unregisterFlashListener(listener)
            }
        }
    }

    LaunchedEffect(cameraProvider, finderEnabled, previewView, imageCapture) {
        val provider = cameraProvider ?: return@LaunchedEffect
        previewUseCase.setSurfaceProvider(previewView.surfaceProvider)
        val selector = CameraSelector.DEFAULT_BACK_CAMERA
        imageCapture.targetRotation = previewView.display?.rotation ?: Surface.ROTATION_0
        imageAnalysis.targetRotation = previewView.display?.rotation ?: Surface.ROTATION_0

        val useCases = mutableListOf<UseCase>(previewUseCase, imageCapture)
        if (finderEnabled) {
            finderAnalyzer.reset()
            imageAnalysis.setAnalyzer(finderExecutor, finderAnalyzer)
            useCases.add(imageAnalysis)
        } else {
            imageAnalysis.clearAnalyzer()
            finderAnalyzer.reset()
            finderResult = null
        }

        provider.unbindAll()
        val boundCamera = provider.bindToLifecycle(lifecycleOwner, selector, *useCases.toTypedArray())
        camera = boundCamera
    }

    DisposableEffect(cameraProvider) {
        onDispose {
            imageAnalysis.clearAnalyzer()
            finderAnalyzer.reset()
            cameraProvider?.unbindAll()
            camera = null
        }
    }

    DisposableEffect(camera, lifecycleOwner) {
        val boundCamera = camera ?: return@DisposableEffect onDispose {}
        val observer = Observer<ZoomState> { zoomState ->
            linearZoom = zoomState.linearZoom.coerceIn(0f, 1f)
            zoomRatio = zoomState.zoomRatio
        }
        boundCamera.cameraInfo.zoomState.observe(lifecycleOwner, observer)
        onDispose {
            boundCamera.cameraInfo.zoomState.removeObserver(observer)
        }
    }

    val currentCamera = rememberUpdatedState(camera)
    val currentLinearZoom = rememberUpdatedState(linearZoom)
    val currentFinderEnabled = rememberUpdatedState(finderEnabled)
    val currentFinderResult = rememberUpdatedState(finderResult)

    DisposableEffect(mediaActionSound) {
        onDispose {
            mediaActionSound.release()
        }
    }




    DisposableEffect(previewView) {
        val tapGestureDetector = GestureDetector(
            context,
            object : GestureDetector.SimpleOnGestureListener() {
                override fun onSingleTapUp(e: MotionEvent): Boolean {
                    val w = previewView.width.takeIf { it > 0 } ?: return true
                    val h = previewView.height.takeIf { it > 0 } ?: return true
                    lastTap = PointF(
                        (e.x / w.toFloat()).coerceIn(0f, 1f),
                        (e.y / h.toFloat()).coerceIn(0f, 1f)
                    )
                    autoArmed = true
                    lastTapAt = SystemClock.uptimeMillis()
                    becameStableAt = 0L     // ← “古い安定時間”を捨てて、タップ後に安定を測り直す

                    val cam = currentCamera.value ?: return false
                    if (previewView.width == 0 || previewView.height == 0) return false
                    val factory = previewView.meteringPointFactory
                    val finder = currentFinderResult.value
                    if (currentFinderEnabled.value && finder != null) {
                        val radiusPx = min(previewView.width, previewView.height) * 0.05f
                        val dx = e.x - finder.viewOffset.x
                        val dy = e.y - finder.viewOffset.y
                        if (hypot(dx.toDouble(), dy.toDouble()) <= radiusPx) {
                            val focusPoint = factory.createPoint(finder.viewOffset.x, finder.viewOffset.y)
                            val action = FocusMeteringAction.Builder(
                                focusPoint,
                                FocusMeteringAction.FLAG_AF or FocusMeteringAction.FLAG_AE
                            )
                                .setAutoCancelDuration(3, TimeUnit.SECONDS)
                                .build()

                            // 追加：タップ直後の軽い手応え（成功/失敗に関わらず）
                            haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                            scope.launch {
                                focusRingStroke = 3f; delay(120); focusRingStroke = 2f
                            }


                            val future = cam.cameraControl.startFocusAndMetering(action)
                            future.addListener({
                                val ok = runCatching { future.get().isFocusSuccessful }.getOrDefault(false)
                                if (ok) {
                                    haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                                    scope.launch {
                                        focusRingStroke = 4f
                                        delay(180)
                                        focusRingStroke = 2f
                                    }
                                }
                            }, executor)

                            focusRingPosition = finder.viewOffset
                            return true

                        }
                    }
                    val point = factory.createPoint(e.x, e.y)
                    val action = FocusMeteringAction.Builder(
                        point,
                        FocusMeteringAction.FLAG_AF or FocusMeteringAction.FLAG_AE
                    )
                        .setAutoCancelDuration(3, TimeUnit.SECONDS)
                        .build()

                    // 追加：タップ直後の軽い手応え（成功/失敗に関わらず）
                    haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                    scope.launch {
                        focusRingStroke = 3f; delay(120); focusRingStroke = 2f
                    }

                    val future = cam.cameraControl.startFocusAndMetering(action)
                    future.addListener({
                        val ok = runCatching { future.get().isFocusSuccessful }.getOrDefault(false)
                        if (ok) {
                            haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                            scope.launch {
                                focusRingStroke = 4f
                                delay(180)
                                focusRingStroke = 2f
                            }
                        }
                    }, executor)

                    focusRingPosition = Offset(e.x, e.y)
                    return true

                }

                override fun onLongPress(e: MotionEvent) {
                    autoArmed = false
                    val w = previewView.width
                    val h = previewView.height
                    if (w == 0 || h == 0) return
                    val xNorm = (e.x / w.toFloat()).coerceIn(0f, 1f)
                    val yNorm = (e.y / h.toFloat()).coerceIn(0f, 1f)

// 追加：長押しトリガーのログ
                    Log.d(TAG, "LP fire x=$xNorm, y=$yNorm")

// C2ゲート経由で呼ぶ（skip/call ログも内部で出る）
                    tryEnsureBirdness(PointF(xNorm, yNorm))

                }

                override fun onDoubleTap(e: MotionEvent): Boolean {
                    val cam = currentCamera.value ?: return false
                    cam.cameraControl.setLinearZoom(0f)
                    return true
                }
            }
        )

        val scaleGestureDetector = ScaleGestureDetector(
            context,
            object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
                override fun onScale(detector: ScaleGestureDetector): Boolean {
                    val cam = currentCamera.value ?: return false
                    val scaleFactor = detector.scaleFactor
                    if (scaleFactor == 1f) {
                        return false
                    }
                    val currentZoom = currentLinearZoom.value
                    val newZoom = (currentZoom + (scaleFactor - 1f)).coerceIn(0f, 1f)
                    cam.cameraControl.setLinearZoom(newZoom)
                    return true
                }

                override fun onScaleBegin(detector: ScaleGestureDetector): Boolean {
                    if (currentCamera.value != null) {
                        scope.launch {
                            fadeZoomJob?.cancel()
                            fadeZoomJob = null
                            zoomOverlayAlpha.animateTo(1f, tween(durationMillis = 150))
                        }
                        return true
                    }
                    return false
                }

                override fun onScaleEnd(detector: ScaleGestureDetector) {
                    fadeZoomJob?.cancel()
                    fadeZoomJob = scope.launch {
                        delay(1_000)
                        zoomOverlayAlpha.animateTo(0f, tween(durationMillis = 300))
                        fadeZoomJob = null
                    }
                }
            }
        )

        previewView.setOnTouchListener { view, event ->
            val handledScale = scaleGestureDetector.onTouchEvent(event)
            val handledTap   = tapGestureDetector.onTouchEvent(event)
            if (event.action == MotionEvent.ACTION_UP && handledTap && !handledScale) {
                view.performClick()
            }
            handledScale || handledTap
        }

        onDispose {
            previewView.setOnTouchListener(null)
        }
    }

    DisposableEffect(Unit) {
        val sm = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
        val gyro = sm.getDefaultSensor(Sensor.TYPE_GYROSCOPE)
        val listener = object : SensorEventListener {
            override fun onSensorChanged(e: SensorEvent) {
                val wx = e.values[0]; val wy = e.values[1]; val wz = e.values[2]
                gyroOmega = sqrt(wx*wx + wy*wy + wz*wz)  // 角速度の大きさ [rad/s]
            }
            override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
        }
        sm.registerListener(listener, gyro, SensorManager.SENSOR_DELAY_GAME)
        onDispose { sm.unregisterListener(listener) }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { previewView }
        )

        RoiOverlay(
            modifier = Modifier.fillMaxSize(),
            roi = roi,
            score = roiScore
        )

        if (perchAssistActive) {
            Box(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 96.dp)
                    .clip(RoundedCornerShape(24.dp))
                    .background(Color.Black.copy(alpha = 0.6f))
                    .padding(horizontal = 16.dp, vertical = 8.dp)
            ) {
                Text(
                    text = "Perch Assist ×1.6–×2.0",
                    color = Color(0xFF7CB342),
                    fontSize = 16.sp,
                    textAlign = TextAlign.Center
                )
            }
        }


        if (showGrid) {
            Canvas(modifier = Modifier.fillMaxSize()) {
                val lineColor = Color.White.copy(alpha = 0.4f)
                val strokeWidth = 2.dp.toPx()
                val thirdWidth = size.width / 3f
                val thirdHeight = size.height / 3f

                drawLine(
                    color = lineColor,
                    start = Offset(thirdWidth, 0f),
                    end = Offset(thirdWidth, size.height),
                    strokeWidth = strokeWidth
                )
                drawLine(
                    color = lineColor,
                    start = Offset(2f * thirdWidth, 0f),
                    end = Offset(2f * thirdWidth, size.height),
                    strokeWidth = strokeWidth
                )
                drawLine(
                    color = lineColor,
                    start = Offset(0f, thirdHeight),
                    end = Offset(size.width, thirdHeight),
                    strokeWidth = strokeWidth
                )
                drawLine(
                    color = lineColor,
                    start = Offset(0f, 2f * thirdHeight),
                    end = Offset(size.width, 2f * thirdHeight),
                    strokeWidth = strokeWidth
                )
            }
        }

        val markerAlpha = finderAlpha.value
        val activeFinder = finderResult
        if (finderEnabled && activeFinder != null && markerAlpha > 0.01f) {
            Canvas(modifier = Modifier.fillMaxSize()) {
                val radius = min(size.width, size.height) * 0.05f
                drawCircle(
                    color = Color.Yellow.copy(alpha = markerAlpha),
                    radius = radius,
                    center = activeFinder.viewOffset
                )
            }
        }

        focusRingPosition?.let { position ->
            val focusSize = 72.dp
            val offset = with(density) {
                val half = focusSize.toPx() / 2f
                IntOffset((position.x - half).roundToInt(), (position.y - half).roundToInt())
            }
            Box(
                modifier = Modifier
                    .offset { offset }
                    .size(focusSize)
                    .clip(CircleShape)
                    .border(width = focusRingStroke.dp, color = Color.White, shape = CircleShape)

            )
        }

        val overlayAlpha = zoomOverlayAlpha.value

        Column(
            modifier = Modifier
                .align(Alignment.TopEnd)
                .statusBarsPadding()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            horizontalAlignment = Alignment.End
        ) {
            SettingsButton(onClick = onSettingsClick)

            ZoomIndicator(
                zoomRatio = zoomRatio,
                alpha = overlayAlpha
            )

            // HUD: ROI の判定と score を右上に表示
            Text(
                text = "ROI $roiJudge  score=${String.format(Locale.US, "%.2f", roiScore ?: -1f)}",
                color = Color.White,
                fontSize = 14.sp,
                modifier = Modifier
                    .clip(RoundedCornerShape(6.dp))
                    .background(Color(0x88000000))
                    .padding(horizontal = 8.dp, vertical = 4.dp)
            )

            FinderToggleButton(
                isEnabled = finderEnabled,
                onToggle = {
                    val newValue = !finderEnabled
                    onToggleFinder(newValue)          // ← 親へ反映
                    if (!newValue) {                  // ← OFFにしたときだけローカルをリセット
                        finderAnalyzer.reset()
                        finderResult = null
                    }
                }
            )


            GridToggleButton(
                isEnabled = showGrid,
                onToggle = {
                    scope.launch {
                        dataStore.edit { preferences ->
                            preferences[GRID_ENABLED_KEY] = !showGrid
                        }
                    }
                }
            )

            GalleryButton(
                onClick = onGalleryClick
            )
        }

        ShutterButton(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 32.dp)
        ) {
            activity?.requestCapture()
        }

        Box(
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer(alpha = flashAlpha.value)
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.White)
            )
        }
    }
}

@Composable
private fun GridToggleButton(
    isEnabled: Boolean,
    onToggle: () -> Unit
) {
    Box(
        modifier = Modifier
            .size(48.dp)
            .clip(CircleShape)
            .background(Color.Black.copy(alpha = 0.4f))
            .border(width = 1.dp, color = Color.White, shape = CircleShape)
            .clickable(onClick = onToggle),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = if (isEnabled) "▦" else "▢",
            color = Color.White,
            fontSize = 20.sp,
            textAlign = TextAlign.Center
        )
    }
}

@Composable
private fun FinderToggleButton(
    isEnabled: Boolean,
    onToggle: () -> Unit
) {
    Box(
        modifier = Modifier
            .size(48.dp)
            .clip(CircleShape)
            .background(
                if (isEnabled) {
                    Color.Yellow.copy(alpha = 0.5f)
                } else {
                    Color.Black.copy(alpha = 0.4f)
                }
            )
            .border(
                width = 1.dp,
                color = if (isEnabled) Color.Yellow else Color.White,
                shape = CircleShape
            )
            .clickable(onClick = onToggle),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = "Finder",
            color = if (isEnabled) Color.Black else Color.White,
            fontSize = 12.sp,
            textAlign = TextAlign.Center
        )
    }
}

@Composable
private fun ZoomIndicator(
    zoomRatio: Float,
    alpha: Float
) {
    if (alpha <= 0.001f) {
        return
    }
    Box(
        modifier = Modifier
            .graphicsLayer { this.alpha = alpha }
            .clip(CircleShape)
            .background(Color.Black.copy(alpha = 0.6f))
            .padding(horizontal = 12.dp, vertical = 6.dp)
    ) {
        Text(
            text = "x${String.format(Locale.US, "%.1f", zoomRatio)}",
            color = Color.White,
            fontSize = 16.sp
        )
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
private fun SettingsButton(
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
            text = "⚙",
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

private data class FinderResult(
    val viewOffset: Offset,
    val normalizedX: Float,
    val normalizedY: Float,
    val timestamp: Long
)

private class FinderAnalyzer(
    private val previewView: PreviewView,
    private val mainExecutor: Executor,
    private val onResult: (FinderResult?) -> Unit
) : ImageAnalysis.Analyzer {
    private val STABILITY_RADIUS_F = 0.05f
    @Volatile
    private var profileThresholds: FinderThresholds = FinderProfile.OUTDOOR.thresholds()
    @Volatile
    private var currentProfile: FinderProfile = FinderProfile.OUTDOOR
    private var previousFrame: ByteArray? = null
    private var emaX: Float? = null
    private var emaY: Float? = null
    private var lastAnalysisTime: Long = 0L
    private var lastDispatchTime: Long = 0L
    @Volatile
    private var hasLastResult: Boolean = false
    private var lastAcceptedX: Float? = null
    private var lastAcceptedY: Float? = null
    private var stableCount: Int = 0
    @Volatile
    private var gyroOmega: Float = 0f

    fun setProfile(profile: FinderProfile): Boolean {
        if (currentProfile == profile) {
            return false
        }
        currentProfile = profile
        profileThresholds = profile.thresholds()
        return true
    }

    fun reset() {
        previousFrame = null
        emaX = null
        emaY = null
        lastAnalysisTime = 0L
        lastAcceptedX = null
        lastAcceptedY = null
        stableCount = 0
        gyroOmega = 0f
        if (hasLastResult) {
            hasLastResult = false
            mainExecutor.execute { onResult(null) }
        }
    }

    fun setGyroOmega(omega: Float) {
        gyroOmega = omega
    }

    override fun analyze(image: ImageProxy) {
        try {
            val now = SystemClock.elapsedRealtime()
            if (now - lastAnalysisTime < 100L) {
                return
            }
            lastAnalysisTime = now

            val omega = gyroOmega
            val thresholds = profileThresholds
            if (omega >= 1.2f) {
                dispatchNull()
                lastAnalysisTime = now + 100L
                return
            }
            val activeRatioMax = thresholds.activeRatioMax
            val stabilityRequired = thresholds.stabilityRequired

            val width = image.width
            val height = image.height
            val yData = extractYPlane(image) ?: return
            val previous = previousFrame
            previousFrame = yData.copyOf()
            if (previous == null || previous.size != yData.size) {
                return
            }

            val diffValues = IntArray(yData.size)
            val histogram = IntArray(256)
            var sum = 0L
            var sumSq = 0L
            for (i in yData.indices) {
                val current = yData[i].toInt() and 0xFF
                val prev = previous[i].toInt() and 0xFF
                val delta = abs(current - prev)
                diffValues[i] = delta
                histogram[delta]++
                sum += delta
                sumSq += delta.toLong() * delta
            }

            val totalPixels = diffValues.size
            if (totalPixels == 0) {
                reset()
                return
            }

            val mean = sum.toDouble() / totalPixels
            val variance = sumSq.toDouble() / totalPixels - mean * mean
            val threshold = if (variance < 20.0) {
                16
            } else {
                computeOtsuThreshold(histogram, totalPixels)
            }

            val mask = BooleanArray(totalPixels)
            var onCount = 0
            for (i in diffValues.indices) {
                if (diffValues[i] >= threshold) {
                    mask[i] = true
                    onCount++
                }
            }

            val activeRatio = onCount.toFloat() / totalPixels
            if (activeRatio > activeRatioMax) {
                emaX = null
                emaY = null
                lastAcceptedX = null
                lastAcceptedY = null
                stableCount = 0
                dispatchNull()
                return
            }

            val blob = findLargestBlob(mask, width, height, thresholds.minAreaRange)
            if (blob == null) {
                emaX = null
                emaY = null
                lastAcceptedX = null
                lastAcceptedY = null
                stableCount = 0
                dispatchNull()
                return
            }

            val centroidX = blob.sumX.toFloat() / blob.area
            val centroidY = blob.sumY.toFloat() / blob.area
            var normalizedX = (centroidX / width.toFloat()).coerceIn(0f, 1f)
            var normalizedY = (centroidY / height.toFloat()).coerceIn(0f, 1f)


            val currentX = emaX
            val currentY = emaY
            val smoothedX = if (currentX == null) normalizedX else currentX + (normalizedX - currentX) * 0.2f
            val smoothedY = if (currentY == null) normalizedY else currentY + (normalizedY - currentY) * 0.2f
            normalizedX = smoothedX.coerceIn(0f, 1f)
            normalizedY = smoothedY.coerceIn(0f, 1f)
            emaX = normalizedX
            emaY = normalizedY

            val previousAcceptedX = lastAcceptedX
            val previousAcceptedY = lastAcceptedY
            val shortSide = min(width, height).toFloat().coerceAtLeast(1f)
            stableCount = if (previousAcceptedX == null || previousAcceptedY == null) {
                1
            } else {
                val dx = (normalizedX - previousAcceptedX) * width.toFloat()
                val dy = (normalizedY - previousAcceptedY) * height.toFloat()
                val distance = hypot(dx.toDouble(), dy.toDouble()).toFloat() / shortSide
                if (distance <= STABILITY_RADIUS_F) {
                    stableCount + 1
                } else {
                    1
                }
            }
            if (stableCount > stabilityRequired) {
                stableCount = stabilityRequired
            }
            lastAcceptedX = normalizedX
            lastAcceptedY = normalizedY

            if (stableCount < stabilityRequired) {
                dispatchNull()
                return
            }

            dispatchResult(normalizedX, normalizedY, now)
        } finally {
            image.close()
        }
    }

    private fun dispatchNull() {
        if (!hasLastResult) {
            return
        }
        hasLastResult = false
        mainExecutor.execute { onResult(null) }
    }

    private fun dispatchResult(normalizedX: Float, normalizedY: Float, timestamp: Long) {
        if (timestamp - lastDispatchTime < 16L && hasLastResult) {
            return
        }
        lastDispatchTime = timestamp
        mainExecutor.execute {
            val viewOffset = projectToPreview(normalizedX, normalizedY)
            if (viewOffset != null) {
                hasLastResult = true
                onResult(
                    FinderResult(
                        viewOffset = viewOffset,
                        normalizedX = normalizedX,
                        normalizedY = normalizedY,
                        timestamp = timestamp
                    )
                )
            } else {
                hasLastResult = false
                onResult(null)
            }
        }

    }

    private fun projectToPreview(normalizedX: Float, normalizedY: Float): Offset? {
        val w = previewView?.width ?: return null
        val h = previewView?.height ?: return null
        if (w == 0 || h == 0) return null
        return Offset(
            normalizedX.coerceIn(0f, 1f) * w,
            normalizedY.coerceIn(0f, 1f) * h
        )
    }


    private fun extractYPlane(image: ImageProxy): ByteArray? {
        if (image.planes.isEmpty()) {
            return null
        }
        val plane = image.planes[0]
        val buffer = plane.buffer.duplicate()
        val rowStride = plane.rowStride
        val pixelStride = plane.pixelStride
        val width = image.width
        val height = image.height
        val data = ByteArray(width * height)
        buffer.rewind()
        if (pixelStride == 1) {
            for (row in 0 until height) {
                val rowStart = row * rowStride
                if (rowStart + width > buffer.capacity()) {
                    break
                }
                buffer.position(rowStart)
                buffer.get(data, row * width, width)
            }
            return data
        }
        val rowBuffer = ByteArray(rowStride)
        for (row in 0 until height) {
            val rowStart = row * rowStride
            val available = buffer.capacity() - rowStart
            if (available <= 0) {
                break
            }
            buffer.position(rowStart)
            buffer.get(rowBuffer, 0, min(rowStride, available))
            val dstOffset = row * width
            var srcIndex = 0
            var column = 0
            while (column < width && srcIndex < rowStride) {
                data[dstOffset + column] = rowBuffer[srcIndex]
                srcIndex += pixelStride
                column++
            }
        }
        return data
    }

    private fun findLargestBlob(
        mask: BooleanArray,
        width: Int,
        height: Int,
        areaRange: ClosedFloatingPointRange<Float>
    ): Blob? {
        val visited = ByteArray(mask.size)
        val queue = IntArray(mask.size)
        var head: Int
        var tail: Int
        var bestArea = 0
        var bestSumX = 0L
        var bestSumY = 0L
        val totalPixels = width * height
        val neighborsX = intArrayOf(-1, 0, 1, -1, 1, -1, 0, 1)
        val neighborsY = intArrayOf(-1, -1, -1, 0, 0, 1, 1, 1)
        for (index in mask.indices) {
            if (!mask[index] || visited[index].toInt() != 0) {
                continue
            }
            head = 0
            tail = 0
            queue[tail++] = index
            visited[index] = 1
            var area = 0
            var sumX = 0L
            var sumY = 0L
            while (head < tail) {
                val current = queue[head++]
                val y = current / width
                val x = current % width
                area++
                sumX += x
                sumY += y
                for (i in neighborsX.indices) {
                    val nx = x + neighborsX[i]
                    val ny = y + neighborsY[i]
                    if (nx < 0 || ny < 0 || nx >= width || ny >= height) {
                        continue
                    }
                    val neighborIndex = ny * width + nx
                    if (!mask[neighborIndex] || visited[neighborIndex].toInt() != 0) {
                        continue
                    }
                    visited[neighborIndex] = 1
                    queue[tail++] = neighborIndex
                }
            }
            val areaFrac = if (totalPixels == 0) 0f else area.toFloat() / totalPixels.toFloat()
            if (
                areaFrac >= areaRange.start &&
                areaFrac <= areaRange.endInclusive &&
                area > bestArea
            ) {
                bestArea = area
                bestSumX = sumX
                bestSumY = sumY
            }
        }
        if (bestArea == 0) {
            return null
        }
        return Blob(bestArea, bestSumX, bestSumY)
    }

    private fun computeOtsuThreshold(histogram: IntArray, total: Int): Int {
        var sum = 0L
        for (i in histogram.indices) {
            sum += i.toLong() * histogram[i]
        }
        var sumB = 0L
        var wB = 0L
        var maxVar = 0.0
        var threshold = 16
        for (i in histogram.indices) {
            wB += histogram[i].toLong()
            if (wB == 0L) continue
            val wF = total - wB
            if (wF == 0L) break
            sumB += i.toLong() * histogram[i]
            val mB = sumB.toDouble() / wB
            val mF = (sum - sumB).toDouble() / wF
            val between = wB * wF * (mB - mF) * (mB - mF)
            if (between > maxVar) {
                maxVar = between
                threshold = i
            }
        }
        return threshold
    }

    private data class Blob(
        val area: Int,
        val sumX: Long,
        val sumY: Long
    )
}