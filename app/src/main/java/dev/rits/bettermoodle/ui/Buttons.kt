package dev.rits.bettermoodle.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.rits.bettermoodle.ui.theme.AppTheme
import dev.rits.bettermoodle.ui.theme.Sizes
import dev.rits.bettermoodle.ui.theme.Spacing

// ---------------------------------------------------------------------------
// ボタン (DESIGN.md §8.7)。Primary は1画面1つまで。高さ 48・ピル
// ---------------------------------------------------------------------------

private val ButtonPadding = PaddingValues(horizontal = 20.dp)

@Composable
fun PrimaryButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    leading: (@Composable () -> Unit)? = null,
) {
    Button(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier.heightIn(min = Sizes.hitMin),
        contentPadding = ButtonPadding,
        colors = ButtonDefaults.buttonColors(
            containerColor = AppTheme.colors.accent,
            contentColor = AppTheme.colors.onAccent,
        ),
    ) {
        leading?.let { it(); Spacer(Modifier.width(Spacing.sm)) }
        Text(text, style = MaterialTheme.typography.labelLarge)
    }
}

@Composable
fun SecondaryButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    leading: (@Composable () -> Unit)? = null,
) {
    FilledTonalButton(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier.heightIn(min = Sizes.hitMin),
        contentPadding = ButtonPadding,
        colors = ButtonDefaults.filledTonalButtonColors(
            containerColor = AppTheme.colors.accentSoft,
            contentColor = AppTheme.colors.accent,
        ),
    ) {
        leading?.let { it(); Spacer(Modifier.width(Spacing.sm)) }
        Text(text, style = MaterialTheme.typography.labelLarge)
    }
}

@Composable
fun TertiaryButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    destructive: Boolean = false,
) {
    TextButton(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier.heightIn(min = Sizes.hitMin),
        colors = ButtonDefaults.textButtonColors(
            contentColor = if (destructive) AppTheme.colors.danger else AppTheme.colors.accent,
        ),
    ) {
        Text(text, style = MaterialTheme.typography.labelLarge)
    }
}

/**
 * 確認シート (DESIGN.md §8.7: 破壊的操作は danger 文字の tertiary + 確認シート)。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConfirmSheet(
    title: String,
    message: String,
    confirmLabel: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    destructive: Boolean = true,
) {
    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = AppTheme.colors.surface) {
        Column(Modifier.padding(horizontal = ScreenGutter).padding(bottom = Spacing.xl)) {
            Text(title, style = MaterialTheme.typography.titleMedium, color = AppTheme.colors.ink)
            Spacer(Modifier.height(Spacing.sm))
            Text(message, style = MaterialTheme.typography.bodyMedium, color = AppTheme.colors.inkSecondary)
            Spacer(Modifier.height(Spacing.xl))
            Row(horizontalArrangement = Arrangement.spacedBy(Spacing.sm), modifier = Modifier.fillMaxWidth()) {
                SecondaryButton("キャンセル", onClick = onDismiss, modifier = Modifier.weight(1f))
                TextButton(
                    onClick = onConfirm,
                    modifier = Modifier.weight(1f).heightIn(min = Sizes.hitMin),
                    colors = ButtonDefaults.textButtonColors(
                        contentColor = if (destructive) AppTheme.colors.danger else AppTheme.colors.accent,
                    ),
                ) { Text(confirmLabel, style = MaterialTheme.typography.labelLarge) }
            }
        }
    }
}
