package dev.rits.bettermoodle.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Badge
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.rits.bettermoodle.AppContainer
import dev.rits.bettermoodle.data.PopupNotification
import org.jsoup.Jsoup
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private enum class NotificationFilter(val label: String) {
    ALL("すべて"),
    ASSIGN("課題関連"),
    OTHER("その他"),
}

/** Moodleの通知一覧。課題関連とそれ以外をフィルタで分けられる。 */
@Composable
fun NotificationsScreen(
    container: AppContainer,
    onOpenUrl: (url: String, title: String) -> Unit = { _, _ -> },
) {
    val (state, refresh) = rememberLoadable { container.moodleRepository.notifications() }
    var filter by remember { mutableStateOf(NotificationFilter.ALL) }

    Column(modifier = Modifier.fillMaxSize()) {
        Row(modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp)) {
            NotificationFilter.entries.forEach { f ->
                FilterChip(
                    selected = filter == f,
                    onClick = { filter = f },
                    label = { Text(f.label) },
                    modifier = Modifier.padding(end = 8.dp),
                )
            }
        }
        when (val s = state) {
            is UiState.Loading -> LoadingBox()
            is UiState.Error -> ErrorBox(s.message, onRetry = refresh)
            is UiState.Success -> {
                val filtered = s.data.notifications.filter {
                    when (filter) {
                        NotificationFilter.ALL -> true
                        NotificationFilter.ASSIGN -> it.isAssignRelated()
                        NotificationFilter.OTHER -> !it.isAssignRelated()
                    }
                }
                if (filtered.isEmpty()) {
                    ErrorBox("通知はありません", onRetry = refresh)
                    return
                }
                LazyColumn(contentPadding = androidx.compose.foundation.layout.PaddingValues(12.dp)) {
                    items(filtered, key = { it.id }) { n ->
                        NotificationCard(n) {
                            n.contexturl?.takeIf { it.isNotBlank() }
                                ?.let { onOpenUrl(it, n.contexturlname ?: n.subject) }
                        }
                    }
                }
            }
        }
    }
}

private fun PopupNotification.isAssignRelated(): Boolean =
    component == "mod_assign" || component == "mod_quiz" ||
        eventtype?.contains("assign") == true ||
        subject.contains("課題") || subject.contains("提出")

@Composable
private fun NotificationCard(n: PopupNotification, onClick: () -> Unit) {
    val time = Instant.ofEpochSecond(n.timecreated)
        .atZone(ZoneId.systemDefault())
        .format(DateTimeFormatter.ofPattern("M/d HH:mm"))
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
            .clickable(onClick = onClick),
        colors = CardDefaults.cardColors(
            containerColor = if (n.read) MaterialTheme.colorScheme.surfaceVariant
            else MaterialTheme.colorScheme.secondaryContainer,
        ),
    ) {
        Column(Modifier.padding(12.dp)) {
            Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                if (!n.read) {
                    Badge(modifier = Modifier.padding(end = 8.dp)) { Text("未読") }
                }
                Text(
                    n.subject,
                    style = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier.weight(1f),
                    maxLines = 2,
                )
            }
            n.smallmessage?.takeIf { it.isNotBlank() }?.let { msg ->
                Text(
                    Jsoup.parse(msg).text(),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 3,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
            Text(
                time,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.outline,
                modifier = Modifier.padding(top = 4.dp),
            )
        }
    }
}
