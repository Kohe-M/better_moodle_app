# DESIGN.md の実装メモ (2026-09-27)

旧「コマ」モチーフ版の設計根拠は DESIGN.md (Campus) の全面採用で置き換えた。
トークンは `ui/theme/Theme.kt` (`AppTheme.colors` / `AppTheme.coursePalette` / `Spacing` / `Radius` / `Sizes` / `Motion`) に写してある。
値を変えるときは DESIGN.md を先に直すこと。

## DESIGN.md → 実装

| DESIGN.md | 実装 |
|---|---|
| §2 タブ (今日/時間割/課題/コース) | `MainActivity.MainTabs`。通知は今日タブ右上、設定はコースタブ右上、シラバス・ポータルはコースタブ内の行から push |
| §3 授業カラー (ハッシュ mod 10・隣接ずらし・利用者変更) | `data/CourseColors.kt` (純ロジック)、`data/LocalPreferences.kt` の `CourseColorStore`、`ui/Motif.kt` の `courseColor()`。変更はコース詳細の色スクエアから |
| §7 スケルトン (300ms 未満は何も出さない) | `SkeletonList` / `LoadingBox` |
| §8.1 Now Card | `ui/TodayScreen.kt` + `data/TimetableFocus.kt` の `nowCardState` |
| §8.2 時間割グリッド | `ui/TimetableScreen.kt` (曜日ごとの列で連続時限を結合、600dp 以上は2ペイン、840dp 以上は今日の締切、フォント倍率 1.6 以上はリスト) |
| §8.3 課題リスト | `ui/DeadlinesScreen.kt` (セクション・完了チェック・右スワイプで完了・完了の折りたたみ・コース別フィルタ) |
| §8.4 コース一覧・詳細 | `ui/CourseListScreen.kt` (`CoursesScreen`)、`ui/CourseScreen.kt` (現在週のセクションを自動展開: `currentSectionIndex`) |
| §8.4.1 詳細画面 | `ui/Common.kt` の `DetailTitle`・`StatusHeroCard`・`DetailCard`・`InfoRow`。課題・小テスト・フォーラム・ページの各画面で使う |
| components.grouped-list | `ui/Common.kt` の `Modifier.groupedItem` と `GroupPosition` |
| §8.5 同期・オフライン | `StaleDataBanner` (オフライン/更新失敗を出し分け)、`SyncFooter` |
| §8.7 ボタン | `PrimaryButton` / `SecondaryButton` / `TertiaryButton` / `ConfirmSheet` |
| §10 文言 | `ui/Formats.kt` (`9/28(月)`・`13:00`・`明日 23:59`・`2日前に期限切れ`) |

## データが無いため未実装のもの

- 学期・クォーター切替 (rutime_table は今学期分しか返さない)
- 休講・教室変更バッジ、セルの未読ドット、出欠カウンター、教員名、授業メモ
- 空きコマタップでの「授業を追加」(時間割は Moodle から読むだけ)
- 課題の「Moodle 上で提出済み」表示 (action events は提出済みを返さない)。完了はアプリ内の完了扱いのみ
- 左スワイプのリマインダー、ファイルのダウンロード状態 (オフライン保存がない)
- 時間帯トーン (任意項目)、ウィジェット、常駐通知
