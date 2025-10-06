package io.mayu.birdpilot

import android.content.Context
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
fun PhotoViewerScreen(
    uri: Uri,
    onBack: () -> Unit,
    onDelete: (Uri) -> Unit
) {
    BackHandler(onBack = onBack)

    val context = LocalContext.current
    var bitmap by remember(uri) { mutableStateOf<Bitmap?>(null) }
    var isLoading by remember { mutableStateOf(true) }
    var showDeleteDialog by remember { mutableStateOf(false) }
    var scale by remember(uri) { mutableStateOf(1f) }
    var offset by remember(uri) { mutableStateOf(Offset.Zero) }

    LaunchedEffect(uri) {
        val result = withContext(Dispatchers.IO) {
            runCatching { loadBitmap(context, uri) }
        }
        result.onSuccess { bmp ->
            bitmap = bmp
        }.onFailure {
            Toast.makeText(context, "画像を読み込めませんでした", Toast.LENGTH_SHORT).show()
            onBack()
        }
        isLoading = false
    }

    Surface(color = Color.Black, modifier = Modifier.fillMaxSize()) {
        Box(modifier = Modifier.fillMaxSize()) {
            val imageBitmap = bitmap
            if (imageBitmap != null) {
                Image(
                    bitmap = imageBitmap.asImageBitmap(),
                    contentDescription = null,
                    modifier = Modifier
                        .fillMaxSize()
                        .pointerInput(uri) {
                            detectTransformGestures { _, pan, zoom, _ ->
                                val newScale = (scale * zoom).coerceIn(1f, 5f)
                                val adjustedPan = if (newScale > 1f) pan else Offset.Zero
                                scale = newScale
                                offset = if (newScale <= 1f) {
                                    Offset.Zero
                                } else {
                                    offset + adjustedPan
                                }
                            }
                        }
                        .pointerInput(uri) {
                            detectTapGestures(
                                onDoubleTap = {
                                    scale = 1f
                                    offset = Offset.Zero
                                }
                            )
                        }
                        .graphicsLayer(
                            scaleX = scale,
                            scaleY = scale,
                            translationX = offset.x,
                            translationY = offset.y
                        ),
                    contentScale = ContentScale.Fit
                )
            } else if (isLoading) {
                CircularProgressIndicator(
                    color = Color.White,
                    modifier = Modifier.align(Alignment.Center)
                )
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .align(Alignment.TopCenter)
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                TextButton(onClick = onBack) {
                    Text(
                        text = "戻る",
                        color = Color.White,
                        style = MaterialTheme.typography.titleMedium
                    )
                }
                TextButton(onClick = { showDeleteDialog = true }) {
                    Text(
                        text = "削除",
                        color = Color.White,
                        style = MaterialTheme.typography.titleMedium
                    )
                }
            }
        }
    }

    if (showDeleteDialog) {
        AlertDialog(
            onDismissRequest = { showDeleteDialog = false },
            title = { Text(text = "写真を削除") },
            text = { Text(text = "この写真を削除しますか？") },
            confirmButton = {
                TextButton(
                    onClick = {
                        showDeleteDialog = false
                        onDelete(uri)
                    }
                ) {
                    Text(text = "削除")
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteDialog = false }) {
                    Text(text = "キャンセル")
                }
            }
        )
    }
}

private fun loadBitmap(context: Context, uri: Uri): Bitmap {
    return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
        val source = ImageDecoder.createSource(context.contentResolver, uri)
        ImageDecoder.decodeBitmap(source)
    } else {
        @Suppress("DEPRECATION")
        MediaStore.Images.Media.getBitmap(context.contentResolver, uri)
    }
}
