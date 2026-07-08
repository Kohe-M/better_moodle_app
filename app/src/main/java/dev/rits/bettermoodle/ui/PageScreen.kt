package dev.rits.bettermoodle.ui

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import dev.rits.bettermoodle.AppContainer
import dev.rits.bettermoodle.BuildConfig
import dev.rits.bettermoodle.data.MoodlePage
import dev.rits.bettermoodle.data.MoodleWsException
import dev.rits.bettermoodle.data.PageBlock
import dev.rits.bettermoodle.data.PageLoadErrorKind
import dev.rits.bettermoodle.data.PageTarget
import dev.rits.bettermoodle.data.PreviewKind
import dev.rits.bettermoodle.data.UrlPolicy
import dev.rits.bettermoodle.data.canFallbackToWebView
import dev.rits.bettermoodle.data.classifyPageLoadError
import dev.rits.bettermoodle.data.diagnosticCode
import dev.rits.bettermoodle.data.pageHttpStatus
import dev.rits.bettermoodle.data.pageLoadErrorMessage
import dev.rits.bettermoodle.data.pageMoodleErrorCode
import dev.rits.bettermoodle.data.pluginFileNameFromUrl
import dev.rits.bettermoodle.data.previewKindFor
import dev.rits.bettermoodle.data.splitHtmlToBlocks
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private data class PageDiagnostic(
    val kind: PageLoadErrorKind,
    val moodleErrorCode: String?,
    val httpStatus: Int?,
)

private data class PageLinkFile(
    val fileUrl: String,
    val filename: String,
)

/**
 * mod_page (先生が作る「概要」「教材」等のMoodleページ) のネイティブ表示。
 * 本文はテキストブロックとインライン画像に分割して描画する。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PageScreen(
    container: AppContainer,
    target: PageTarget,
    title: String,
    onBack: () -> Unit,
    onFallbackWeb: () -> Unit,
    onOpenPdf: (url: String, title: String) -> Unit,
    onOpenFilePreview: (url: String, title: String, kind: PreviewKind) -> Unit,
    onOpenUrl: (url: String, title: String) -> Unit,
) {
    var refreshTick by remember { mutableIntStateOf(0) }
    var state by remember { mutableStateOf<UiState<MoodlePage>>(UiState.Loading) }
    var diagnostic by remember { mutableStateOf<PageDiagnostic?>(null) }
    var downloadToShare by remember { mutableStateOf<PageLinkFile?>(null) }

    LaunchedEffect(target, refreshTick) {
        if (!target.isValid) {
            diagnostic = PageDiagnostic(PageLoadErrorKind.InvalidPageTarget, null, null)
            state = UiState.Error(pageLoadErrorMessage(PageLoadErrorKind.InvalidPageTarget))
            return@LaunchedEffect
        }
        state = UiState.Loading
        diagnostic = null
        state = try {
            val page = container.moodleRepository.page(target.courseId, target.courseModuleId)
                ?: throw MoodleWsException("invalidrecord", "Page not found")
            UiState.Success(page)
        } catch (error: Exception) {
            val kind = classifyPageLoadError(error)
            diagnostic = PageDiagnostic(
                kind = kind,
                moodleErrorCode = pageMoodleErrorCode(error),
                httpStatus = pageHttpStatus(error),
            )
            UiState.Error(pageLoadErrorMessage(kind))
        }
    }

    // 本文中のリンクの振り分け:
    // pluginfile → 種別に応じて既存プレビュー/ダウンロード共有、それ以外は openUrl (Moodle→WebView / 外部→Custom Tabs)
    fun handleLink(url: String) {
        if (UrlPolicy.isSafeMoodlePluginFileSourceUrl(url)) {
            val filename = pluginFileNameFromUrl(url)
            when (val kind = previewKindFor(filename, null)) {
                PreviewKind.Pdf -> onOpenPdf(url, filename)
                PreviewKind.Image,
                PreviewKind.Text,
                -> onOpenFilePreview(url, filename, kind)
                PreviewKind.Html -> onOpenUrl(url, filename)
                PreviewKind.Unsupported -> downloadToShare = PageLinkFile(url, filename)
            }
        } else {
            onOpenUrl(url, title)
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(title, maxLines = 1) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "戻る")
                    }
                },
                actions = {
                    IconButton(onClick = { refreshTick++ }) {
                        Icon(Icons.Filled.Refresh, contentDescription = "再読み込み")
                    }
                },
            )
        },
    ) { padding ->
        when (val s = state) {
            is UiState.Loading -> LoadingBox()
            is UiState.Error -> PageErrorContent(
                padding = padding,
                message = s.message,
                diagnostic = diagnostic,
                onRetry = { refreshTick++ },
                onFallbackWeb = onFallbackWeb,
            )
            is UiState.Success -> PageContentBody(
                padding = padding,
                container = container,
                page = s.data,
                onOpenLink = ::handleLink,
            )
        }
    }

    downloadToShare?.let { file ->
        DownloadShareDialog(
            container = container,
            fileUrl = file.fileUrl,
            filename = file.filename,
            mimeType = null,
            onDismiss = { downloadToShare = null },
        )
    }
}

@Composable
private fun PageContentBody(
    padding: PaddingValues,
    container: AppContainer,
    page: MoodlePage,
    onOpenLink: (String) -> Unit,
) {
    val introBlocks = remember(page.intro) { splitHtmlToBlocks(page.intro) }
    val blocks = remember(page.content) { splitHtmlToBlocks(page.content) }
    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(padding),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        item(key = "page-name") {
            Text(page.name, style = MaterialTheme.typography.headlineSmall)
        }
        introBlocks.forEachIndexed { index, block ->
            item(key = "intro-$index") {
                PageBlockItem(container, block, onOpenLink)
            }
        }
        blocks.forEachIndexed { index, block ->
            item(key = "content-$index") {
                PageBlockItem(container, block, onOpenLink)
            }
        }
        if (introBlocks.isEmpty() && blocks.isEmpty()) {
            item(key = "empty") {
                Text(
                    "このページには表示できる内容がありません。",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun PageBlockItem(
    container: AppContainer,
    block: PageBlock,
    onOpenLink: (String) -> Unit,
) {
    when (block) {
        is PageBlock.Html -> HtmlText(
            html = block.html,
            onOpenUrl = onOpenLink,
            style = MaterialTheme.typography.bodyMedium,
        )
        is PageBlock.Image -> PageInlineImage(container, block.src)
    }
}

private sealed interface InlineImageState {
    data object Loading : InlineImageState
    data class Ready(val bitmap: Bitmap) : InlineImageState
    data object Failed : InlineImageState
}

private const val MAX_INLINE_IMAGE_BYTES = 10L * 1024L * 1024L

@Composable
private fun PageInlineImage(container: AppContainer, src: String) {
    var state by remember(src) { mutableStateOf<InlineImageState>(InlineImageState.Loading) }
    LaunchedEffect(src) {
        state = withContext(Dispatchers.IO) {
            runCatching {
                // downloadPreviewBytes が pluginfile 許可リスト検証とサイズ上限を担う
                val bytes = downloadPreviewBytes(container, src, MAX_INLINE_IMAGE_BYTES, rejectHtml = true)
                InlineImageState.Ready(decodeInlineImage(bytes))
            }.getOrElse { InlineImageState.Failed }
        }
    }

    when (val s = state) {
        is InlineImageState.Loading -> Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 8.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            CircularProgressIndicator()
        }
        is InlineImageState.Failed -> Text(
            "[画像]",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.bodySmall,
        )
        is InlineImageState.Ready -> {
            DisposableEffect(s.bitmap) {
                onDispose { s.bitmap.recycle() }
            }
            Image(
                bitmap = s.bitmap.asImageBitmap(),
                contentDescription = "ページ内の画像",
                modifier = Modifier.fillMaxWidth(),
                contentScale = ContentScale.FillWidth,
            )
        }
    }
}

private fun decodeInlineImage(bytes: ByteArray): Bitmap {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
    if (bounds.outWidth <= 0 || bounds.outHeight <= 0) throw java.io.IOException("画像として読み込めませんでした。")
    val sample = calculateSampleSize(bounds.outWidth, bounds.outHeight)
    return BitmapFactory.decodeByteArray(
        bytes,
        0,
        bytes.size,
        BitmapFactory.Options().apply { inSampleSize = sample },
    ) ?: throw java.io.IOException("画像として読み込めませんでした。")
}

@Composable
private fun PageErrorContent(
    padding: PaddingValues,
    message: String,
    diagnostic: PageDiagnostic?,
    onRetry: () -> Unit,
    onFallbackWeb: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(padding)
            .padding(24.dp),
        verticalArrangement = Arrangement.Center,
    ) {
        Text(message, style = MaterialTheme.typography.bodyMedium)
        Spacer(Modifier.height(12.dp))
        Button(onClick = onRetry, modifier = Modifier.fillMaxWidth()) {
            Text("再読み込み")
        }
        if (diagnostic?.kind?.canFallbackToWebView() == true) {
            Button(onClick = onFallbackWeb, modifier = Modifier.fillMaxWidth()) {
                Text("Moodle画面で開く")
            }
        }
        if (BuildConfig.DEBUG && diagnostic != null) {
            Text(
                "errorcode: ${diagnostic.safeMoodleErrorCode() ?: diagnostic.kind.diagnosticCode()}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

private val SAFE_PAGE_ERROR_CODE = Regex("[A-Za-z0-9_-]{1,64}")

private fun PageDiagnostic.safeMoodleErrorCode(): String? =
    moodleErrorCode?.takeIf { SAFE_PAGE_ERROR_CODE.matches(it) }
