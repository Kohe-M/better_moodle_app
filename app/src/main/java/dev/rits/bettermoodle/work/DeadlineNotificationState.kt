package dev.rits.bettermoodle.work

import dev.rits.bettermoodle.data.ActionEvent

/**
 * 通知済みイベントの識別キー。イベントIDだけでなく締切時刻も含めるので、
 * 同じイベントの締切が変更されたら別キーになり再通知できる。
 */
fun deadlineNotificationKey(eventId: Long, timesort: Long): String = "$eventId@$timesort"

fun ActionEvent.deadlineNotificationKey(): String = deadlineNotificationKey(id, timesort)

fun retainedNotifiedEventIds(
    existingNotifiedIds: Set<String>,
    currentDeadlineIds: Set<String>,
    newlyNotifiedIds: Set<String>,
): Set<String> = (existingNotifiedIds intersect currentDeadlineIds) + newlyNotifiedIds

/** Worker が取得上限で打ち切ったときの継続位置 (次回はここから続ける) */
data class DeadlineCursor(val fromEpochSec: Long, val afterEventId: Long)

/**
 * 次回実行の継続位置。ページ上限で打ち切られ、続きの位置が分かるときだけ保存する。
 * 完了・カーソル異常のときは null (次回は先頭から)。
 */
fun nextDeadlineCursor(fromEpochSec: Long, window: dev.rits.bettermoodle.data.DeadlineWindow): DeadlineCursor? {
    if (window.complete) return null
    val next = window.nextAfterEventId ?: return null
    return DeadlineCursor(fromEpochSec, next)
}

/**
 * 通知済みキーを「現在の締切集合」に絞ってよいか。
 * 先頭から取り切った実行だけが全件を知っている。継続実行や不完全な実行は先頭側を見ていないので
 * 絞り込まない (絞ると先頭側の締切を再通知してしまう)。
 */
fun shouldPruneNotifiedKeys(window: dev.rits.bettermoodle.data.DeadlineWindow, continuedFromCursor: Boolean): Boolean =
    window.complete && !continuedFromCursor
