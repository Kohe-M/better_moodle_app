package dev.rits.bettermoodle.ui

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.Uri
import androidx.browser.customtabs.CustomTabsIntent
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.TopAppBarColors
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import dev.rits.bettermoodle.data.UrlPolicy
import dev.rits.bettermoodle.ui.theme.AppTheme

/** トップバー: 背景は canvas。スクロール後は surface に切り替えて操作層を浮かせる */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun appTopBarColors(): TopAppBarColors = TopAppBarDefaults.topAppBarColors(
    containerColor = AppTheme.colors.canvas,
    scrolledContainerColor = AppTheme.colors.surface,
    titleContentColor = AppTheme.colors.ink,
    navigationIconContentColor = AppTheme.colors.ink,
    actionIconContentColor = AppTheme.colors.inkSecondary,
)

/** [appTopBarColors] の CenterAlignedTopAppBar 版。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun appCenterTopBarColors(): TopAppBarColors = TopAppBarDefaults.centerAlignedTopAppBarColors(
    containerColor = AppTheme.colors.canvas,
    scrolledContainerColor = AppTheme.colors.surface,
    titleContentColor = AppTheme.colors.ink,
    navigationIconContentColor = AppTheme.colors.ink,
    actionIconContentColor = AppTheme.colors.inkSecondary,
)

/** 端末がネットワークにつながっているか。変化を購読する */
@Composable
fun rememberIsOnline(): Boolean {
    val context = LocalContext.current
    val cm = remember { context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager }
    fun current(): Boolean = runCatching {
        cm?.getNetworkCapabilities(cm.activeNetwork)?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true
    }.getOrDefault(true)
    var online by remember { mutableStateOf(current()) }
    DisposableEffect(cm) {
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) { online = true }
            override fun onLost(network: Network) { online = current() }
        }
        val registered = runCatching { cm?.registerDefaultNetworkCallback(callback) }.isSuccess && cm != null
        onDispose { if (registered) runCatching { cm?.unregisterNetworkCallback(callback) } }
    }
    return online
}

/** Chrome Custom Tab でURLを開く。ブラウザが無い等で起動できなければ false を返し、利用者に知らせる。 */
fun openInCustomTab(context: Context, url: String): Boolean {
    if (!UrlPolicy.canOpenExternally(url)) return false
    val launched = runCatching {
        CustomTabsIntent.Builder().build().launchUrl(context, Uri.parse(url))
    }.isSuccess
    if (!launched) {
        android.widget.Toast.makeText(context, "ブラウザを起動できませんでした", android.widget.Toast.LENGTH_SHORT).show()
    }
    return launched
}
