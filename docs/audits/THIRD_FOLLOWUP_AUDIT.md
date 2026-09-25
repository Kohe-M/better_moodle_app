# R01–R09対応報告の再監査

> S01–S03への対応とUI更新を再レビューしました。過去17件のプローブは成功しています。最新の指摘とUI確認範囲は [FOURTH_FOLLOWUP_AUDIT.md](C:/Users/2omur/development/better_moodle_app/FOURTH_FOLLOWUP_AUDIT.md) を参照してください。

調査日: 2026-09-22。対象: HEAD `f10f698b4d565b88d8b9d1e1696431c666298d5c` と未コミットの作業ツリー。アプリ本体・正式テストは変更していない。

## 結論

**既存201件と過去13件の監査プローブは成功した。ただし、提出の未確認状態、復元後の本文競合、既読要求のセッション境界に3件の問題が残る。今回の追加プローブ4件で再現した。**

R01の「受理済みで未反映と読めても自動解除しない」は、いったん照会が例外になってpendingが設定された経路では実装されている。一方、最初の照会が正常終了して旧状態を返す経路は保護されておらず、報告と実装の差がある。

## 検証

| 検証 | 結果 |
|---|---|
| JDK 17、`testDebugUnitTest lintDebug assembleDebug --offline --rerun-tasks --console=plain` | BUILD SUCCESSFUL、55タスク実行 |
| 通常単体テスト | **201件、失敗0** |
| `ReviewRegressionProbeTest` | **8/8成功** |
| `SecondAuditProbeTest` | **5/5成功** |
| `ThirdAuditProbeTest` | **4件中4件で期待動作との不一致** |
| Lint | **0 errors / 30 warnings**、報告と一致 |
| `git diff --check` | エラーなし、Gitの改行変換警告のみ |
| GitHub CI、テスト用Moodle、実機UI/SSO/通知/提出 | 今回も未検証 |

追加プローブはFakeGatewayと制御可能なコルーチン順序を使う。大学サーバーで二重提出や他アカウントへの既読適用を観測したという意味ではない。復元試験もAndroidの実プロセス終了ではなく、現在のViewModelが保存・復元する情報をEditorで再現した。

プローブの結果を保存した後、通常構成のテストを再実行して201件のレポートへ戻した。検証用コード・XMLはGit管理外のbuild配下であり、cleanで削除される。

- [今回の追加プローブ](C:/Users/2omur/development/better_moodle_app/build/audit3/src/dev/rits/bettermoodle/ThirdAuditProbeTest.kt)
- [今回の結果XML](C:/Users/2omur/development/better_moodle_app/build/audit3/results/TEST-dev.rits.bettermoodle.ThirdAuditProbeTest.xml)
- [前回5件の結果XML](C:/Users/2omur/development/better_moodle_app/build/audit3/results/TEST-dev.rits.bettermoodle.SecondAuditProbeTest.xml)
- [初回8件の結果XML](C:/Users/2omur/development/better_moodle_app/build/audit3/results/TEST-dev.rits.bettermoodle.ReviewRegressionProbeTest.xml)
- [通常テストレポート](C:/Users/2omur/development/better_moodle_app/app/build/reports/tests/testDebugUnitTest/index.html)
- [Lintレポート](C:/Users/2omur/development/better_moodle_app/app/build/reports/lint-results-debug.html)

再現コマンド（現在は17件中4件失敗）:

```powershell
$env:JAVA_HOME = 'C:/Program Files/Java/jdk-17'
./gradlew.bat testDebugUnitTest --tests dev.rits.bettermoodle.ThirdAuditProbeTest --tests dev.rits.bettermoodle.SecondAuditProbeTest --tests dev.rits.bettermoodle.ReviewRegressionProbeTest -I build/audit3/audit.init.gradle --offline --console=plain
```

## S01 / P1 — 「照会成功・未反映」の場合は、提出を再送できる

根拠: [AssignmentEditor.kt:492](C:/Users/2omur/development/better_moodle_app/app/src/main/java/dev/rits/bettermoodle/data/AssignmentEditor.kt:492) のverifyAfterAcknowledged、[同:594](C:/Users/2omur/development/better_moodle_app/app/src/main/java/dev/rits/bettermoodle/data/AssignmentEditor.kt:594) のapplySubmitResult、settlePending。

2つの再送経路を再現した。

| プローブ | 条件 | 実測 |
|---|---|---|
| `acknowledgedWriteWithOldStatusMustRemainPending` | 提出APIは成功応答、直後の照会は例外なく旧draft状態を返す。その後再度提出 | 提出呼出し**2回**（期待1回） |
| `lostResponseWithOldStatusMustRemainPending` | 提出要求後の応答が消失し、照会はまだ旧draft状態を返す。その後再度提出 | 提出呼出し**2回**（期待1回） |

前者はverifyAfterAcknowledgedがcatch内でしかpendingを設定せず、applySubmitResultも未反映時はエラー表示だけにするため。後者はsettlePendingが `acknowledged=false` かつ未反映を「未適用確定」としてpendingを消すため。

タイムアウトや応答切断はサーバー処理の中止を証明しない。直後の読込が旧状態でも、まだ処理中の書き込みが後から反映される可能性がある。また成功応答後の旧状態を特別に保護するという今回の報告は、最初の照会成功経路を含んでいない。

**修正方針:** 書き込み開始時からpendingを所有し、成功応答でacknowledgedへ進め、適用確認または確実な拒否でのみ自動解除する。「未反映」は「未適用確定」と分ける。保存・ファイル・開始・最終提出の照合を単一経路にまとめ、最初の照合と後日のreconcileが同じ判定を使うようにする。

**完了条件:** 応答成功／応答消失 × 照会失敗／旧状態／適用済みの表を正式テストにする。旧状態のまま連打しても再送しない。明示解除を残す場合、単に古い画面へ戻すだけでよいのか、解除後の最新状態取得まで設計する。

## S02 / P1 — プロセス復元で本文競合が消え、Web側の本文を上書きできる

根拠: [AssignmentViewModel.kt:51](C:/Users/2omur/development/better_moodle_app/app/src/main/java/dev/rits/bettermoodle/ui/AssignmentViewModel.kt:51) は草稿文字列だけを復元。[AssignmentEditor.kt:208](C:/Users/2omur/development/better_moodle_app/app/src/main/java/dev/rits/bettermoodle/data/AssignmentEditor.kt:208) は復元草稿があると最新本文を新しい基準値にし、`textConflict=false` を設定する。

**再現済み:** `restoredDraftMustNotForgetDetectedExternalConflict`。

1. originalを読み込み、アプリでlocal unsavedを入力する。
2. Web側でnew web contentへ変更する。refreshするとtextConflict=trueになり保存が止まる。
3. 現在のViewModelと同じく草稿だけを新しいEditorへ復元する。
4. refresh→saveTextで保存APIが**1回**呼ばれ、Web側本文を上書きする（期待0回）。

保存前再照会は行われるが、復元時点で最新本文を基準値に採用しているため、外部変更がなかったように見える。これは報告にあるPendingWrite永続化の不足とは別に、本文基準値と競合状態の復元が不足している問題。

**修正方針:** 草稿だけでなく、編集開始時の本文基準値・課題とアカウントの識別・競合状態を復元する。旧形式などで基準値がない草稿は、最新本文との差がある場合に競合の可能性ありとして扱い、自動保存・最終提出を止める。

**完了条件:** 競合検出前／検出後のプロセス再生成、Web側更新あり／なし、別アカウントでの復元、最終提出の自動保存を検証する。Editorテストに加えてSavedStateHandleとComposeの経路も検証する。

## S03 / P2 — 旧セッションで予約した既読要求を、新セッションで送信する

根拠: [NotificationReadSync.kt:38](C:/Users/2omur/development/better_moodle_app/app/src/main/java/dev/rits/bettermoodle/data/NotificationReadSync.kt:38)。requestで世代を記録するが、launch内で送信前に所有権・世代を確認しない。clearは表示状態だけを消し、未実行Jobは残す。AppContainerが渡すmarkReadは、その実行時点のMoodleClientの現在トークンを使用する。

**再現済み:** `readRequestQueuedInOldSessionMustNotSendWithNewSession`。世代1でrequestを予約→実行前にclear→世代2へ変更→Job実行。markReadは**世代2で実行**された。応答後の要求トークン照合はUIへの旧結果混入を防ぐが、HTTPの副作用が別セッションで送られることは防がない。

実サーバーが旧通知IDの操作を拒否する可能性はある。今回確認した事実は、送るべきでない旧要求が新しいセッションで送られることまで。

**修正方針:** 予約した要求をセッションに束縛し、clear時に未実行・進行中Jobを管理する。送信前の世代・所有権確認に加え、認証トークン取得と送信の間にセッションが変わっても新しいトークンへ付け替わらない構造にする。ネットワーク完了まで認証Mutexを保持するとログアウトを遅延させるため、トークンスナップショットと要求キャンセルの設計を優先して検討する。

**完了条件:** launch前待機、token取得前後待機、通信中、応答直前でログアウト／再ログインを割り込ませる。旧要求はBのトークンで送信されず、BのinFlightも消さないこと。

## R01–R09の再判定

| 対象 | 確認した改善 | 判定・残件 |
|---|---|---|
| R01 | 成功応答→照会例外でacknowledged pendingを保持。前回プローブ成功 | **未完了、S01**。照会成功・旧状態の場合が別経路 |
| R02 | withReloadedTextで未編集本文も更新。ファイル操作後の古い本文の自動保存を防止 | 前回の具体的再現条件は解消。復元後の保護はS02 |
| R03 | 保存前の本文・書式・編集可否の再照会 | 前回の具体的再現条件は解消。復元した基準値はS02。照会〜保存間の競合は報告どおり残る |
| R04 | Effectの自己キャンセルを避け、遷移→記録→clearへ変更 | コード修正確認。OS再配達判定・遷移と記録の間の中断は画面受入未実施 |
| R05 | Worker副作用をAuthSessionと同じMutexへ。旧既読応答の所有権照合 | Workerの前回の競合経路は改善。既読の送信側はS03 |
| R06 | 正当なHTMLの許可、pluginfile以外へのリダイレクト拒否、サイズ検証 | 前回プローブ成功。種類・リダイレクトの契約テスト追加を確認 |
| R07 | nullableでサイズ不明と0を区別、0も比較 | 前回プローブ成功。不明を検証成功にしないことも確認 |
| R08 | 通知にも世代導入、先頭読込中の追加抑止 | コード修正確認。Compose試験未実施 |
| R09 | 継続カーソル保存・再開、継続時の通知済み集合保持 | 静的な複数ページを継続する単体テスト成功。実際の6時間間隔・途中でのイベント追加／期限変更／カーソル先イベント削除は未検証 |

## 継続して必要な受入と次の作業

1. **S01:** 提出状態機械を分岐ごとの修正から統一した遷移へ整理し、今回の2プローブを正式テストに追加する。
2. **S02:** PendingWriteとともに本文基準値・競合状態の保存復元を実装し、今回の復元プローブとSavedState経路を検証する。
3. **S03:** 既読要求の送信と応答の両方をセッションに束縛し、予約時点からの割込みテストを追加する。
4. 同名置換の確認ダイアログ、Compose/instrumentation、テスト用Moodle、実機、GitHub CIの残件を実施する。

R04の `savedInstanceState` は画面再生成時の情報であり、通知タップ由来かどうかを完全に表すものではない。現在は「そのEffectが途中で自分をキャンセルしない」までをコード確認した。遷移直後・消費記録前の再生成、同じ通知の再タップ、新しいIntentとの競合を完了条件に残す。

R09の新テストは連続する固定応答に対するRepositoryとカーソル計算を確認している。実Workerの時計・通知発行・DataStoreを含む結合試験ではない。カーソルより前に新しい締切が追加される場合や、継続が6時間後になって締切を過ぎる場合の扱いも受入条件にする。

**今回の評価:** 改善は確認でき、報告のテスト件数・Lint結果も一致した。一方、テスト成功を提出保護とセッション境界の完了に読み替えることはできない。まず今回の3件を解消し、報告済み残件を含めて完了判定する。

## 対応状況 (2026-09-22 実装後)

監査プローブは `ThirdAuditProbeTest` 4 件・`SecondAuditProbeTest` 5 件・`ReviewRegressionProbeTest` 8 件の全 17 件が新実装で成功
(`./gradlew.bat testDebugUnitTest --tests ... -I build/audit3/audit.init.gradle --offline`、JDK 17)。
通常構成の単体テストは **210 件、失敗 0**。今回の 4 プローブは正式テストへ移植済み。GitHub CI 実行、テスト用 Moodle 結合、実機受入は未実施。

| ID | 状態 | 対応内容 | 正式テスト | 残り |
|---|---|---|---|---|
| S01 | 実装・単体済 | 書き込みを `performWrite` の単一経路に統一。送信開始から `PendingWrite` を所有し、成功応答で acknowledged、明示的な拒否 (Moodle の warning / WS 例外) でのみ解除・失敗、それ以外は照会へ。照会は最初も後日の reconcile も同じ `settlePending` で判定し、「未反映」は応答の有無にかかわらず解除しない (処理中の可能性)。解除は利用者の明示操作 (`dismissPendingWrite`) のみで、解除後は最新状態を取り直す | `acknowledged write with old status must remain pending`、`lost response with old status must remain pending`、`write outcome matrix never resends without explicit dismissal` (応答成功/消失 × 照会失敗/旧状態/適用済み の 6 通り) | — |
| S02 | 実装・単体済 | `RestoredDraft(text, baseline, conflict)` を復元し、基準値と最新本文の差で外部変更を判定。基準値の無い旧形式は差があれば競合の可能性ありとして保護側に倒す。ViewModel は草稿・基準値・競合状態・未確認の書き込み (`PendingWrite` を JSON 直列化)・アカウント識別 (トークンのハッシュ) を SavedStateHandle に保存し、別アカウントなら復元しない | `restored draft must not forget detected external conflict`、`restored draft with baseline distinguishes web change from no change`、`restored pending write is settled on first refresh...` | SavedStateHandle / Compose 経路の実行テスト |
| S03 | 実装・単体済 | 要求予約時に世代と認証トークンをスナップショットし、送信はそのトークンで行う (`MoodleClient.call(tokenOverride)`)。送信直前に世代と所有権を確認し、失われていれば送らない。`clear()` は未実行・進行中の Job もキャンセル。応答後の所有権照合は従来どおり | `ReadRequestSessionBindingTest` (予約後 clear、世代変更、トークンスナップショット、未ログイン) | 認証 Mutex は保持しない設計 (ログアウトを遅延させないため)。ローカルのログアウトはサーバー側のトークンを失効させないため、予約時点のトークンで送られた旧要求がサーバーで拒否される保証はない (送信前チェックと Job キャンセルで「送らない」ことを主にしている)。`onAuthError` は世代ではなく failedToken と現在のトークンの一致で判定するので、旧トークンの invalidtoken は新セッションを消さない |

R04 (通知宛先) と R09 (Worker 継続) について監査が挙げた受入条件 (遷移直後・記録前の再生成、同じ通知の再タップ、カーソルより前の締切追加、6 時間後の期限超過) は端末・結合試験として残す。
