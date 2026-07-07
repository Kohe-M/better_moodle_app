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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import dev.rits.bettermoodle.AppContainer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File

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

    val state by produceState<PdfState>(PdfState.Loading, fileUrl) {
        value = withContext(Dispatchers.IO) {
            runCatching {
                val authed = container.moodleRepository.authedFileUrl(fileUrl) ?: fileUrl
                val file = downloadToCache(context.cacheDir, authed)
                inspectPdf(file)
            }.getOrElse { PdfState.Failed(it.message ?: "PDFを表示できませんでした") }
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

private fun downloadToCache(cacheDir: File, url: String): File {
    val out = File(cacheDir, "preview_${url.hashCode()}.pdf")
    downloadHttp.newCall(Request.Builder().url(url).build()).execute().use { resp ->
        if (!resp.isSuccessful) throw java.io.IOException("HTTP ${resp.code}")
        val body = resp.body ?: throw java.io.IOException("空のレスポンス")
        out.outputStream().use { body.byteStream().copyTo(it) }
    }
    return out
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
    val pageState by produceState<PdfPageState>(PdfPageState.Loading, file, pageIndex) {
        value = withContext(Dispatchers.IO) {
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
                val height = (targetWidth.toFloat() * page.height / page.width).toInt().coerceAtLeast(1)
                val bmp = Bitmap.createBitmap(targetWidth, height, Bitmap.Config.ARGB_8888)
                bmp.eraseColor(Color.WHITE)
                page.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                return bmp
            }
        }
    }
}
