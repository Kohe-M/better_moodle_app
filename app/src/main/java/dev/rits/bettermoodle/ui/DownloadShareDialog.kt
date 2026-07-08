package dev.rits.bettermoodle.ui

import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.webkit.MimeTypeMap
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import dev.rits.bettermoodle.AppContainer
import dev.rits.bettermoodle.data.UrlPolicy
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.IOException
import java.util.Locale

private sealed interface DownloadShareState {
    data class Downloading(val downloadedBytes: Long, val totalBytes: Long?) : DownloadShareState
    data class Ready(val file: File, val mimeType: String) : DownloadShareState
    data class Failed(val message: String) : DownloadShareState
}

@Composable
fun DownloadShareDialog(
    container: AppContainer,
    fileUrl: String,
    filename: String,
    mimeType: String?,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    var retryTick by remember(fileUrl) { mutableIntStateOf(0) }
    var state by remember(fileUrl, retryTick) {
        mutableStateOf<DownloadShareState>(DownloadShareState.Downloading(0L, null))
    }
    var openedPath by remember(fileUrl, retryTick) { mutableStateOf<String?>(null) }

    LaunchedEffect(fileUrl, filename, mimeType, retryTick) {
        state = DownloadShareState.Downloading(0L, null)
        state = withContext(Dispatchers.IO) {
            runCatching {
                downloadSharedFile(
                    container = container,
                    cacheDir = context.cacheDir,
                    fileUrl = fileUrl,
                    filename = filename,
                    mimeType = mimeType,
                    onProgress = { downloaded, total ->
                        withContext(Dispatchers.Main.immediate) {
                            state = DownloadShareState.Downloading(downloaded, total)
                        }
                    },
                )
            }.getOrElse { error ->
                DownloadShareState.Failed(error.message ?: "ファイルをダウンロードできませんでした。")
            }
        }
    }

    LaunchedEffect(state) {
        val ready = state as? DownloadShareState.Ready ?: return@LaunchedEffect
        if (openedPath == ready.file.absolutePath) return@LaunchedEffect
        openedPath = ready.file.absolutePath
        val opened = openOrShareDownloadedFile(context, ready.file, ready.mimeType, filename)
        if (opened) {
            onDismiss()
        } else {
            state = DownloadShareState.Failed("このファイルを開けるアプリが見つかりませんでした。")
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(filename.ifBlank { "ファイル" }) },
        text = {
            when (val current = state) {
                is DownloadShareState.Downloading -> DownloadProgress(current)
                is DownloadShareState.Failed -> Text(current.message)
                is DownloadShareState.Ready -> Text("ファイルを開いています...")
            }
        },
        confirmButton = {
            if (state is DownloadShareState.Failed) {
                TextButton(onClick = { retryTick++ }) {
                    Text("リトライ")
                }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("キャンセル")
            }
        },
    )
}

@Composable
private fun DownloadProgress(state: DownloadShareState.Downloading) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        val total = state.totalBytes
        if (total != null && total > 0L) {
            LinearProgressIndicator(
                progress = { (state.downloadedBytes.toFloat() / total.toFloat()).coerceIn(0f, 1f) },
                modifier = Modifier.fillMaxWidth(),
            )
            Text("${formatDownloadBytes(state.downloadedBytes)} / ${formatDownloadBytes(total)}")
        } else {
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            Text("${formatDownloadBytes(state.downloadedBytes)} ダウンロード済み")
        }
    }
}

private val downloadShareHttp = OkHttpClient()
private const val MAX_SHARED_FILE_BYTES = 50L * 1024L * 1024L
private const val DOWNLOAD_CACHE_MAX_AGE_MILLIS = 24L * 60L * 60L * 1000L

private suspend fun downloadSharedFile(
    container: AppContainer,
    cacheDir: File,
    fileUrl: String,
    filename: String,
    mimeType: String?,
    onProgress: suspend (downloadedBytes: Long, totalBytes: Long?) -> Unit,
): DownloadShareState.Ready {
    if (!UrlPolicy.isSafeMoodlePluginFileSourceUrl(fileUrl)) {
        throw IOException("許可されていないファイルURLです。")
    }
    val authed = container.moodleRepository.authedFileUrl(fileUrl)
        ?: throw IOException("ファイルの認証URLを作成できませんでした。")
    if (!UrlPolicy.isAllowedMoodlePluginFileUrl(authed)) {
        throw IOException("許可されていないファイルURLです。")
    }

    val downloadsDir = File(cacheDir, "downloads").also { it.mkdirs() }
    cleanupOldDownloads(downloadsDir)
    val safeName = safeDownloadFilename(filename, mimeType)
    val tmp = File.createTempFile("download_", ".tmp", downloadsDir)
    val out = uniqueDownloadFile(downloadsDir, safeName)

    return runCatching {
        downloadShareHttp.newCall(Request.Builder().url(authed).build()).execute().use { resp ->
            if (!resp.isSuccessful) throw IOException("HTTP ${resp.code}")
            val body = resp.body ?: throw IOException("空のレスポンスです。")
            val length = body.contentLength().takeIf { it >= 0L }
            if (length != null && length > MAX_SHARED_FILE_BYTES) {
                throw IOException("ファイルサイズが50MBを超えています。")
            }
            val resolvedMime = mimeType
                ?.takeIf { it.isNotBlank() }
                ?: resp.header("Content-Type")?.substringBefore(';')?.trim()?.takeIf { it.isNotBlank() }
                ?: inferMimeType(safeName)

            var copied = 0L
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            body.byteStream().use { input ->
                tmp.outputStream().use { output ->
                    while (true) {
                        val read = input.read(buffer)
                        if (read < 0) break
                        copied += read
                        if (copied > MAX_SHARED_FILE_BYTES) {
                            throw IOException("ファイルサイズが50MBを超えています。")
                        }
                        output.write(buffer, 0, read)
                        onProgress(copied, length)
                    }
                }
            }
            if (!tmp.renameTo(out)) {
                tmp.copyTo(out, overwrite = true)
                tmp.delete()
            }
            DownloadShareState.Ready(out, resolvedMime)
        }
    }.getOrElse { error ->
        tmp.delete()
        out.delete()
        throw error
    }
}

fun openOrShareDownloadedFile(
    context: Context,
    file: File,
    mimeType: String,
    title: String,
): Boolean {
    val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
    val viewIntent = Intent(Intent.ACTION_VIEW).apply {
        setDataAndType(uri, mimeType)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        clipData = ClipData.newUri(context.contentResolver, title, uri)
    }
    if (context.packageManager.queryIntentActivities(viewIntent, PackageManager.MATCH_DEFAULT_ONLY).isNotEmpty()) {
        return runCatching {
            context.startActivity(viewIntent)
            true
        }.getOrDefault(false)
    }

    val sendIntent = Intent(Intent.ACTION_SEND).apply {
        type = mimeType
        putExtra(Intent.EXTRA_STREAM, uri)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        clipData = ClipData.newUri(context.contentResolver, title, uri)
    }
    return try {
        context.startActivity(Intent.createChooser(sendIntent, title.ifBlank { "ファイルを共有" }))
        true
    } catch (_: ActivityNotFoundException) {
        false
    }
}

fun copyFileToDownloadShareCache(
    cacheDir: File,
    source: File,
    filename: String,
    mimeType: String?,
): File {
    val downloadsDir = File(cacheDir, "downloads").also { it.mkdirs() }
    cleanupOldDownloads(downloadsDir)
    val out = uniqueDownloadFile(downloadsDir, safeDownloadFilename(filename, mimeType))
    source.copyTo(out, overwrite = false)
    return out
}

private fun cleanupOldDownloads(
    downloadsDir: File,
    nowMillis: Long = System.currentTimeMillis(),
) {
    downloadsDir.listFiles().orEmpty()
        .filter { it.isFile && nowMillis - it.lastModified() > DOWNLOAD_CACHE_MAX_AGE_MILLIS }
        .forEach { runCatching { it.delete() } }
}

private fun uniqueDownloadFile(downloadsDir: File, filename: String): File {
    var candidate = File(downloadsDir, filename)
    if (!candidate.exists()) return candidate
    val base = filename.substringBeforeLast('.', filename)
    val ext = filename.substringAfterLast('.', missingDelimiterValue = "")
    var index = 1
    while (candidate.exists()) {
        val suffix = if (ext.isBlank()) " ($index)" else " ($index).$ext"
        candidate = File(downloadsDir, "$base$suffix")
        index++
    }
    return candidate
}

private fun safeDownloadFilename(filename: String, mimeType: String?): String {
    val cleaned = filename
        .substringAfterLast('/')
        .substringAfterLast('\\')
        .replace(Regex("[\\r\\n\\t]"), " ")
        .replace(Regex("[\\\\/:*?\"<>|]"), "_")
        .trim()
        .take(120)
        .ifBlank { "download" }
    if ('.' in cleaned) return cleaned
    val extension = MimeTypeMap.getSingleton().getExtensionFromMimeType(mimeType.orEmpty())
    return if (extension.isNullOrBlank()) cleaned else "$cleaned.$extension"
}

private fun inferMimeType(filename: String): String {
    val extension = filename.substringAfterLast('.', missingDelimiterValue = "").lowercase(Locale.US)
    return MimeTypeMap.getSingleton().getMimeTypeFromExtension(extension) ?: "application/octet-stream"
}

private fun formatDownloadBytes(bytes: Long): String =
    when {
        bytes < 1024L -> "${bytes}B"
        bytes < 1024L * 1024L -> String.format(Locale.US, "%.1fKB", bytes / 1024.0)
        else -> String.format(Locale.US, "%.1fMB", bytes / 1024.0 / 1024.0)
    }
