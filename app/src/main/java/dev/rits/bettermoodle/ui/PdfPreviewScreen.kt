package dev.rits.bettermoodle.ui

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.OpenInBrowser
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import dev.rits.bettermoodle.AppContainer
import dev.rits.bettermoodle.data.UrlPolicy
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import kotlin.math.sqrt

private sealed interface PdfState {
    data object Loading : PdfState
    data class Ready(val file: File, val pageCount: Int, val shownPages: Int) : PdfState
    data class Failed(val message: String) : PdfState
}

private sealed interface PdfPageState {
    data object Loading : PdfPageState
    data class Ready(val bitmap: Bitmap) : PdfPageState
    data class Failed(val message: String) : PdfPageState
}

/**
 * 認証付きファイルURLからPDFをダウンロードし、PdfRendererで
 * 各ページをビットマップ化してアプリ内表示する。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PdfPreviewScreen(
    container: AppContainer,
    fileUrl: String,
    title: String,
    onBack: () -> Unit,
    onOpenExternally: () -> Unit,
) {
    val context = LocalContext.current

    var state by remember(fileUrl) { mutableStateOf<PdfState>(PdfState.Loading) }
    val latestState by rememberUpdatedState(state)
    LaunchedEffect(fileUrl) {
        state = PdfState.Loading
        state = withContext(Dispatchers.IO) {
            runCatching {
                if (!UrlPolicy.isSafeMoodlePluginFileSourceUrl(fileUrl)) {
                    throw java.io.IOException("許可されていないPDF URLです")
                }
                val authed = container.moodleRepository.authedFileUrl(fileUrl)
                    ?: throw java.io.IOException("PDFの認証URLを作成できませんでした")
                if (!UrlPolicy.isAllowedMoodlePluginFileUrl(authed)) {
                    throw java.io.IOException("許可されていないPDF URLです")
                }
                val file = downloadToCache(context.cacheDir, authed)
                runCatching { inspectPdf(file) }
                    .getOrElse {
                        file.delete()
                        throw it
                    }
            }.getOrElse { PdfState.Failed(it.message ?: "PDFを表示できませんでした") }
        }
    }
    DisposableEffect(fileUrl) {
        onDispose {
            (latestState as? PdfState.Ready)?.file?.delete()
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
                    IconButton(onClick = onOpenExternally) {
                        Icon(Icons.Filled.OpenInBrowser, contentDescription = "ブラウザで開く")
                    }
                },
            )
        },
    ) { padding ->
        when (val s = state) {
            is PdfState.Loading -> LoadingBox()
            is PdfState.Failed -> ErrorBox("${s.message}\n\n右上のボタンからブラウザで開けます")
            is PdfState.Ready -> LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .background(androidx.compose.ui.graphics.Color(0xFF2A2A2A)),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(s.shownPages, key = { it }) { index ->
                    PdfPage(file = s.file, pageIndex = index, totalPages = s.pageCount)
                }
                if (s.shownPages < s.pageCount) {
                    item {
                        Text(
                            "最初の ${s.shownPages} / ${s.pageCount} ページを表示しています",
                            color = androidx.compose.ui.graphics.Color.LightGray,
                            textAlign = TextAlign.Center,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(12.dp),
                        )
                    }
                }
            }
        }
    }
}

private val downloadHttp = OkHttpClient()
private const val MAX_PDF_BYTES = 30L * 1024L * 1024L
private const val MAX_BITMAP_PIXELS = 4_000_000
private const val PREVIEW_CACHE_MAX_AGE_MILLIS = 24L * 60L * 60L * 1000L

private fun downloadToCache(cacheDir: File, url: String): File {
    cleanupOldPreviewPdfs(cacheDir)
    val tmp = File.createTempFile("preview_", ".tmp", cacheDir)
    val out = File.createTempFile("preview_", ".pdf", cacheDir).also { it.delete() }
    return runCatching {
        downloadHttp.newCall(Request.Builder().url(url).build()).execute().use { resp ->
            if (!resp.isSuccessful) throw java.io.IOException("HTTP ${resp.code}")
            val body = resp.body ?: throw java.io.IOException("空のレスポンス")
            val length = body.contentLength()
            if (length > MAX_PDF_BYTES) throw java.io.IOException("PDFが大きすぎます")

            var copied = 0L
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            body.byteStream().use { input ->
                tmp.outputStream().use { output ->
                    while (true) {
                        val read = input.read(buffer)
                        if (read < 0) break
                        copied += read
                        if (copied > MAX_PDF_BYTES) throw java.io.IOException("PDFが大きすぎます")
                        output.write(buffer, 0, read)
                    }
                }
            }
        }
        if (!looksLikePdf(tmp)) throw java.io.IOException("PDFではないレスポンスです")
        if (!tmp.renameTo(out)) {
            tmp.copyTo(out, overwrite = true)
            tmp.delete()
        }
        out
    }.getOrElse { error ->
        tmp.delete()
        out.delete()
        throw error
    }
}

private fun cleanupOldPreviewPdfs(
    cacheDir: File,
    nowMillis: Long = System.currentTimeMillis(),
) {
    cacheDir.listFiles { file ->
        file.isFile && file.name.startsWith("preview_") && file.name.endsWith(".pdf")
    }.orEmpty()
        .filter { nowMillis - it.lastModified() > PREVIEW_CACHE_MAX_AGE_MILLIS }
        .forEach { runCatching { it.delete() } }
}

private fun looksLikePdf(file: File): Boolean {
    val prefix = ByteArray(8)
    val read = file.inputStream().use { it.read(prefix) }
    if (read < 5) return false
    return prefix.decodeToString(0, read).trimStart().startsWith("%PDF-")
}

private fun inspectPdf(file: File, maxPages: Int = 40): PdfState {
    ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY).use { pfd ->
        PdfRenderer(pfd).use { renderer ->
            val pageCount = renderer.pageCount
            if (pageCount <= 0) throw java.io.IOException("PDFにページがありません")
            return PdfState.Ready(
                file = file,
                pageCount = pageCount,
                shownPages = minOf(pageCount, maxPages),
            )
        }
    }
}

@Composable
private fun PdfPage(file: File, pageIndex: Int, totalPages: Int) {
    var pageState by remember(file, pageIndex) { mutableStateOf<PdfPageState>(PdfPageState.Loading) }
    LaunchedEffect(file, pageIndex) {
        pageState = PdfPageState.Loading
        pageState = withContext(Dispatchers.IO) {
            runCatching {
                PdfPageState.Ready(renderPdfPage(file, pageIndex))
            }.getOrElse { PdfPageState.Failed(it.message ?: "ページを表示できませんでした") }
        }
    }

    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        when (val state = pageState) {
            is PdfPageState.Loading -> LoadingBox()
            is PdfPageState.Failed -> Text(
                state.message,
                color = androidx.compose.ui.graphics.Color.LightGray,
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(24.dp),
            )
            is PdfPageState.Ready -> {
                DisposableEffect(state.bitmap) {
                    onDispose { state.bitmap.recycle() }
                }
                Image(
                    bitmap = state.bitmap.asImageBitmap(),
                    contentDescription = "ページ ${pageIndex + 1}",
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(androidx.compose.ui.graphics.Color.White),
                    contentScale = ContentScale.FillWidth,
                )
            }
        }
        Text(
            "${pageIndex + 1} / $totalPages",
            color = androidx.compose.ui.graphics.Color.LightGray,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(4.dp),
        )
    }
}

private fun renderPdfPage(file: File, pageIndex: Int, targetWidth: Int = 1240): Bitmap {
    ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY).use { pfd ->
        PdfRenderer(pfd).use { renderer ->
            require(pageIndex in 0 until renderer.pageCount) { "ページ番号が範囲外です" }
            renderer.openPage(pageIndex).use { page ->
                val scaleForWidth = targetWidth.toDouble() / page.width.toDouble()
                val scaleForPixels = sqrt(MAX_BITMAP_PIXELS.toDouble() / (page.width.toDouble() * page.height.toDouble()))
                val scale = minOf(scaleForWidth, scaleForPixels, 1.0).coerceAtLeast(0.1)
                val width = (page.width * scale).toInt().coerceAtLeast(1)
                val height = (page.height * scale).toInt().coerceAtLeast(1)
                val bmp = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
                bmp.eraseColor(Color.WHITE)
                page.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                return bmp
            }
        }
    }
}
