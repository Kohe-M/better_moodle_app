package dev.rits.bettermoodle.ui

import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import dev.rits.bettermoodle.AppContainer
import dev.rits.bettermoodle.data.PreviewKind
import dev.rits.bettermoodle.data.UrlPolicy
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.ByteArrayOutputStream

private sealed interface FilePreviewState {
    data object Loading : FilePreviewState
    data class ImageReady(val bitmap: android.graphics.Bitmap) : FilePreviewState
    data class TextReady(val text: String) : FilePreviewState
    data object Unsupported : FilePreviewState
    data class Failed(val message: String) : FilePreviewState
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FilePreviewScreen(
    container: AppContainer,
    fileUrl: String,
    title: String,
    kind: PreviewKind,
    onBack: () -> Unit,
) {
    var state by remember(fileUrl, kind) { mutableStateOf<FilePreviewState>(FilePreviewState.Loading) }
    var showDownloadShareDialog by remember(fileUrl) { mutableStateOf(false) }

    LaunchedEffect(fileUrl, kind) {
        state = FilePreviewState.Loading
        state = withContext(Dispatchers.IO) {
            runCatching {
                when (kind) {
                    PreviewKind.Image -> loadImagePreview(container, fileUrl)
                    PreviewKind.Text -> loadTextPreview(container, fileUrl)
                    PreviewKind.Pdf -> FilePreviewState.Unsupported
                    PreviewKind.Unsupported -> FilePreviewState.Unsupported
                }
            }.getOrElse {
                FilePreviewState.Failed("ファイルをプレビューできませんでした。")
            }
        }
    }

    Scaffold(
        topBar = {
            CenterAlignedTopAppBar(
                title = { Text(title, maxLines = 1) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "戻る")
                    }
                },
                actions = {
                    IconButton(onClick = { showDownloadShareDialog = true }) {
                        Icon(Icons.Filled.Share, contentDescription = "他のアプリで開く")
                    }
                },
            )
        },
    ) { padding ->
        when (val s = state) {
            is FilePreviewState.Loading -> LoadingBox()
            is FilePreviewState.Failed -> ErrorBox(s.message)
            is FilePreviewState.Unsupported -> ErrorBox("この形式はアプリ内プレビューに未対応です。")
            is FilePreviewState.TextReady -> Text(
                s.text,
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .verticalScroll(rememberScrollState())
                    .padding(16.dp),
                style = MaterialTheme.typography.bodyMedium,
            )
            is FilePreviewState.ImageReady -> {
                DisposableEffect(s.bitmap) {
                    onDispose { s.bitmap.recycle() }
                }
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(padding)
                        .background(MaterialTheme.colorScheme.surface),
                    verticalArrangement = Arrangement.Center,
                ) {
                    Image(
                        bitmap = s.bitmap.asImageBitmap(),
                        contentDescription = title,
                        modifier = Modifier.fillMaxWidth(),
                        contentScale = ContentScale.Fit,
                    )
                }
            }
        }
    }

    if (showDownloadShareDialog) {
        DownloadShareDialog(
            container = container,
            fileUrl = fileUrl,
            filename = title,
            mimeType = previewShareMimeType(kind),
            onDismiss = { showDownloadShareDialog = false },
        )
    }
}

private fun previewShareMimeType(kind: PreviewKind): String? =
    when (kind) {
        PreviewKind.Text -> "text/plain"
        else -> null
    }

private val filePreviewHttp = OkHttpClient()
private const val MAX_IMAGE_BYTES = 10L * 1024L * 1024L
private const val MAX_TEXT_BYTES = 1024L * 1024L
private const val MAX_IMAGE_PIXELS = 4_000_000

private fun loadImagePreview(container: AppContainer, fileUrl: String): FilePreviewState {
    val bytes = downloadPreviewBytes(container, fileUrl, MAX_IMAGE_BYTES, rejectHtml = true)
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
    if (bounds.outWidth <= 0 || bounds.outHeight <= 0) throw java.io.IOException("画像として読み込めませんでした。")
    val sample = calculateSampleSize(bounds.outWidth, bounds.outHeight)
    val bitmap = BitmapFactory.decodeByteArray(
        bytes,
        0,
        bytes.size,
        BitmapFactory.Options().apply { inSampleSize = sample },
    ) ?: throw java.io.IOException("画像として読み込めませんでした。")
    return FilePreviewState.ImageReady(bitmap)
}

private fun loadTextPreview(container: AppContainer, fileUrl: String): FilePreviewState {
    val bytes = downloadPreviewBytes(container, fileUrl, MAX_TEXT_BYTES, rejectHtml = true)
    val text = bytes.toString(Charsets.UTF_8)
    if (looksLikeHtml(text)) throw java.io.IOException("HTMLレスポンスはテキスト提出物として表示できません。")
    return FilePreviewState.TextReady(text)
}

private fun downloadPreviewBytes(
    container: AppContainer,
    fileUrl: String,
    maxBytes: Long,
    rejectHtml: Boolean,
): ByteArray {
    if (!UrlPolicy.isSafeMoodlePluginFileSourceUrl(fileUrl)) {
        throw java.io.IOException("許可されていないファイルURLです。")
    }
    val authed = container.moodleRepository.authedFileUrl(fileUrl)
        ?: throw java.io.IOException("ファイルの認証URLを作成できませんでした。")
    if (!UrlPolicy.isAllowedMoodlePluginFileUrl(authed)) {
        throw java.io.IOException("許可されていないファイルURLです。")
    }
    filePreviewHttp.newCall(Request.Builder().url(authed).build()).execute().use { resp ->
        if (!resp.isSuccessful) throw java.io.IOException("HTTP ${resp.code}")
        val contentType = resp.header("Content-Type").orEmpty().lowercase()
        if (rejectHtml && contentType.contains("text/html")) {
            throw java.io.IOException("HTMLレスポンスはプレビューできません。")
        }
        val body = resp.body ?: throw java.io.IOException("空のレスポンスです。")
        val length = body.contentLength()
        if (length > maxBytes) throw java.io.IOException("ファイルが大きすぎます。")
        val out = ByteArrayOutputStream()
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        var copied = 0L
        body.byteStream().use { input ->
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                copied += read
                if (copied > maxBytes) throw java.io.IOException("ファイルが大きすぎます。")
                out.write(buffer, 0, read)
            }
        }
        return out.toByteArray()
    }
}

private fun calculateSampleSize(width: Int, height: Int): Int {
    var sample = 1
    while ((width / sample).toLong() * (height / sample).toLong() > MAX_IMAGE_PIXELS) {
        sample *= 2
    }
    return sample
}

private fun looksLikeHtml(text: String): Boolean {
    val trimmed = text.trimStart().lowercase()
    return trimmed.startsWith("<!doctype html") || trimmed.startsWith("<html")
}
