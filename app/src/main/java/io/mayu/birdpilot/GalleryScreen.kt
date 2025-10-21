package io.mayu.birdpilot

import android.content.ContentUris
import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.provider.MediaStore
import android.util.Size
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import android.os.Build
import android.graphics.BitmapFactory
import android.media.ThumbnailUtils


@Composable
fun GalleryScreen(
    onBack: () -> Unit,
    onOpen: (Uri) -> Unit,
    onDelete: (Uri) -> Unit,
    reloadSignal: Int
) {
    BackHandler(onBack = onBack)

    val context = LocalContext.current
    var imageUris by remember { mutableStateOf<List<Uri>>(emptyList()) }
    var isLoading by remember { mutableStateOf(false) }
    var pendingDeleteUri by remember { mutableStateOf<Uri?>(null) }
    val scope = rememberCoroutineScope()

    fun loadImages() {
        scope.launch {
            isLoading = true
            val result = withContext(Dispatchers.IO) {
                runCatching { loadGalleryImages(context) }
            }
            result.onSuccess { uris ->
                imageUris = uris
            }.onFailure { throwable ->
                if (throwable is SecurityException) {
                    Toast.makeText(context, "写真へのアクセス許可が必要です", Toast.LENGTH_SHORT).show()
                    onBack()
                } else {
                    Toast.makeText(context, "ギャラリーの読み込みに失敗しました", Toast.LENGTH_SHORT).show()
                }
            }
            isLoading = false
        }
    }

    LaunchedEffect(reloadSignal) {
        loadImages()
    }

    Surface(color = Color.Black, modifier = Modifier.fillMaxSize()) {
        Column(modifier = Modifier.fillMaxSize()) {
            GalleryTopBar(
                onBack = onBack,
                onRefresh = { loadImages() },
                isLoading = isLoading
            )
            if (imageUris.isEmpty() && !isLoading) {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "BirdCamの写真がありません",
                        color = Color.White.copy(alpha = 0.8f),
                        textAlign = TextAlign.Center
                    )
                }
            } else {
                LazyVerticalGrid(
                    columns = GridCells.Adaptive(minSize = 120.dp),
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(imageUris, key = { it.toString() }) { uri ->
                        GalleryThumbnail(
                            uri = uri,
                            onClick = { onOpen(uri) },
                            onLongClick = { pendingDeleteUri = uri }
                        )
                    }
                }
            }
        }
    }

    val targetDelete = pendingDeleteUri
    if (targetDelete != null) {
        AlertDialog(
            onDismissRequest = { pendingDeleteUri = null },
            title = { Text(text = "写真を削除") },
            text = { Text(text = "この写真を削除しますか？") },
            confirmButton = {
                TextButton(
                    onClick = {
                        pendingDeleteUri = null
                        onDelete(targetDelete)
                    }
                ) {
                    Text(text = "削除")
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingDeleteUri = null }) {
                    Text(text = "キャンセル")
                }
            }
        )
    }
}

@Composable
private fun GalleryTopBar(
    onBack: () -> Unit,
    onRefresh: () -> Unit,
    isLoading: Boolean
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .background(Color.Black)
            .padding(horizontal = 16.dp, vertical = 12.dp)
    ) {
        TextButton(
            onClick = onBack,
            modifier = Modifier.align(Alignment.CenterStart)
        ) {
            Text(text = "戻る", color = Color.White)
        }
        Text(
            text = "ギャラリー",
            color = Color.White,
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.align(Alignment.Center)
        )
        Row(
            modifier = Modifier.align(Alignment.CenterEnd),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (isLoading) {
                CircularProgressIndicator(
                    modifier = Modifier.padding(end = 8.dp),
                    color = Color.White,
                    strokeWidth = 2.dp
                )
            }
            TextButton(onClick = onRefresh) {
                Text(text = "再読み込み", color = Color.White)
            }
        }
    }
}

@Composable
private fun GalleryThumbnail(
    uri: Uri,
    onClick: () -> Unit,
    onLongClick: () -> Unit
) {
    val context = LocalContext.current
    var bitmap by remember(uri) { mutableStateOf<Bitmap?>(null) }

    LaunchedEffect(uri) {
        bitmap = withContext(Dispatchers.IO) {
            runCatching {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    context.contentResolver.loadThumbnail(uri, Size(200, 200), null)
                } else {
                    context.contentResolver.openInputStream(uri)?.use { ins ->
                        BitmapFactory.decodeStream(ins)?.let { bm ->
                            ThumbnailUtils.extractThumbnail(
                                bm,
                                200, 200,
                                ThumbnailUtils.OPTIONS_RECYCLE_INPUT
                            )
                        }
                    }
                }

            }.getOrNull()
        }
    }

    Box(
        modifier = Modifier
            .aspectRatio(1f)
            .clip(MaterialTheme.shapes.small)
            .background(Color.DarkGray)
            .pointerInput(Unit) {
                detectTapGestures(
                    onTap = { onClick() },
                    onLongPress = { onLongClick() }
                )
            },
        contentAlignment = Alignment.Center
    ) {
        val thumb = bitmap
        if (thumb != null) {
            Image(
                bitmap = thumb.asImageBitmap(),
                contentDescription = null,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop
            )
        } else {
            CircularProgressIndicator(color = Color.White, strokeWidth = 2.dp)
        }
    }
}

private fun loadGalleryImages(context: Context): List<Uri> {
    val projection = arrayOf(MediaStore.Images.Media._ID)
    val selection = "${MediaStore.Images.Media.RELATIVE_PATH} LIKE ?"
    val selectionArgs = arrayOf("DCIM/BirdCam%")
    val sortOrder = "${MediaStore.Images.Media.DATE_TAKEN} DESC"

    val uris = mutableListOf<Uri>()
    context.contentResolver.query(
        MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
        projection,
        selection,
        selectionArgs,
        sortOrder
    )?.use { cursor ->
        val idColumn = cursor.getColumnIndexOrThrow(MediaStore.Images.Media._ID)
        while (cursor.moveToNext()) {
            val id = cursor.getLong(idColumn)
            val contentUri = ContentUris.withAppendedId(
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                id
            )
            uris.add(contentUri)
        }
    }
    return uris
}
