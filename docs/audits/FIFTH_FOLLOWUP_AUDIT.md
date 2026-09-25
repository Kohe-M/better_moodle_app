# V01–V04対応の再レビュー

調査日: 2026-09-22。対象: HEAD `f10f698b4d565b88d8b9d1e1696431c666298d5c` と現在の未コミット作業ツリー。アプリ実装・正式テストは変更していない。

## 結論

**V01–V03の前回の再現条件は解消し、既存217件と過去20件の監査プローブは成功した。残る指摘は、保存形式の移行と保留草稿の扱いに集中している。**

今回の追加テストで、旧版のBoolean競合フラグを読み込むとClassCastExceptionになることを確認した。保留草稿の消失と本人未確認の本文表示は、ViewModelとUIのコード経路で確認した指摘であり、端末での再生成・別アカウント操作は未実施。

## 検証結果

| 検証 | 結果 |
|---|---|
| JDK 17、`testDebugUnitTest lintDebug assembleDebug --offline --rerun-tasks --no-build-cache --console=plain` | **BUILD SUCCESSFUL、55タスク実行** |
| 通常単体テスト | **217件、失敗0** |
| 過去4スイートの監査プローブ | **20/20成功** |
| 新規FifthAuditProbeTest | **1件失敗、保存形式の移行エラーを再現** |
| Lint | **0 errors / 30 warnings** |
| Git diff check | エラーなし、改行変換警告のみ |
| 実機描画・TalkBack・文字拡大・実プロセス終了・GitHub CI・テスト用Moodle | 未実施 |

最初のビルドはKotlinクラスパススナップショットのキャッシュ格納時にファイルモード取得エラーで失敗した。同じ検証をビルドキャッシュ無効で再実行し成功した。アプリのコンパイルエラーとしては扱っていない。

追加プローブの結果を保存後、通常構成のテストを再実行し、217件のレポートへ戻した。

- [追加プローブ](C:/Users/2omur/development/better_moodle_app/build/audit5/src/dev/rits/bettermoodle/FifthAuditProbeTest.kt)
- [追加プローブ結果XML](C:/Users/2omur/development/better_moodle_app/build/audit5/results/TEST-dev.rits.bettermoodle.FifthAuditProbeTest.xml)
- [直前3件の結果XML](C:/Users/2omur/development/better_moodle_app/build/audit5/results/TEST-dev.rits.bettermoodle.FourthAuditProbeTest.xml)
- [通常テストレポート](C:/Users/2omur/development/better_moodle_app/app/build/reports/tests/testDebugUnitTest/index.html)
- [Lintレポート](C:/Users/2omur/development/better_moodle_app/app/build/reports/lint-results-debug.html)

プローブとXMLはGit管理外のbuild配下にあるためcleanで削除される。

```powershell
$env:JAVA_HOME = 'C:/Program Files/Java/jdk-17'
./gradlew.bat testDebugUnitTest --tests dev.rits.bettermoodle.FifthAuditProbeTest --tests dev.rits.bettermoodle.FourthAuditProbeTest --tests dev.rits.bettermoodle.ThirdAuditProbeTest --tests dev.rits.bettermoodle.SecondAuditProbeTest --tests dev.rits.bettermoodle.ReviewRegressionProbeTest -I build/audit5/audit.init.gradle --offline --no-build-cache --console=plain
```

現在は21件中1件失敗となる。

## W01 / P1 — 旧版の競合フラグを復元するとClassCastExceptionになる

根拠: [AssignmentViewModel.kt:189](C:/Users/2omur/development/better_moodle_app/app/src/main/java/dev/rits/bettermoodle/ui/AssignmentViewModel.kt:189)、[AssignmentDraftPersistence.kt:44](C:/Users/2omur/development/better_moodle_app/app/src/main/java/dev/rits/bettermoodle/data/AssignmentDraftPersistence.kt:44)。

直前のViewModelは `assignment_draft_conflict` にBooleanを保存していた。新版は同じキーをStringとして読み、`"1"` と比較する。保存形式のバージョン識別・型の移行がない。

**実行再現:** `previousBooleanConflictMustLoadThroughActualSavedStateAdapter`。SavedStateHandleに旧形式の草稿・基準値・アカウント・Booleanのtrueを設定し、実装のprivateなSavedStateDraftStoreをリフレクションで生成してloadを実行した。

```text
java.lang.ClassCastException: java.lang.Boolean cannot be cast to java.lang.String
  AssignmentViewModel$SavedStateDraftStore.get(AssignmentViewModel.kt:189)
  AssignmentDraftPersistence.load(AssignmentDraftPersistence.kt:44)
```

ViewModelはinitでloadを呼ぶため、この旧保存状態が渡されると初期化できない。Map<String, String>だけを使う新しいテストでは、旧版の実際の型を表せず検出できない。

**修正:** 保存形式をバージョン化するか、アダプター境界でAny?として型を確認し、旧Booleanと新Stringを明示的に移行する。未知の型を黙ってfalseへ変換して競合保護を消さない。

**完了条件:** Boolean true/false、新Stringの1/0、キーなし、未知型、pending付き、アカウント不一致を実SavedStateHandleアダプターでテストする。旧形式の競合を保持したまま画面を開けること。

## W02 / P1 — 保留草稿は選択前に保存先から消され、再生成で失われる

根拠: [AssignmentViewModel.kt:83](C:/Users/2omur/development/better_moodle_app/app/src/main/java/dev/rits/bettermoodle/ui/AssignmentViewModel.kt:83)、同ファイルのorphanedDraft・adoptOrphanedDraft。

**コード経路、Android再生成は未実施:** 旧形式または別アカウントと判定すると、本文をメモリ内のMutableStateFlowへ移した直後にpersistence.clearを呼ぶ。orphanedDraftをSavedStateHandle等へ保存する経路はない。

利用者が「復元／破棄」をまだ選んでいない時点でプロセスが再生成されると、保存元も保留データもなくなる。同じ画面を閉じてViewModelが破棄され、再度開いた場合も保留草稿を取り戻せない。「黙って消さない」というV04の条件は満たしていない。

**修正:** 現在アカウントのEditor保存領域とは別に、所有者情報を含む保留領域を用意する。現在のEditorのcheckpointで上書きされない構造にし、復元先の保存完了または明示的破棄まで保留元を残す。adoptでもメモリ値を最初に消すのではなく、復元・保存の完了後に消す。

**完了条件:** 保留表示中、採用処理中、通信失敗、画面離脱、再生成を経ても選択前の文章が残ること。採用成功・明示破棄でのみ削除する。回転による同じViewModelの継続だけでなく、新しいViewModelを生成する試験を含める。

## W03 / P2 — 本人確認前に別アカウント由来の本文を表示する

根拠: [AssignmentScreen.kt:180](C:/Users/2omur/development/better_moodle_app/app/src/main/java/dev/rits/bettermoodle/ui/AssignmentScreen.kt:180)。保留バナーの直後で `draft.take(120)` を表示し、「この文章を復元」を提供している。

**コード経路、別アカウント端末試験は未実施:** ViewModelが保存アカウントと現在アカウントの不一致を判定しても、同じorphanedDraft経路に入り、復元選択前に先頭120文字を表示する。編集欄へ自動入力しないだけでは本文の開示を防げない。現在の利用者が復元ボタンを押すことも、旧草稿の所有者である証明にはならない。

**修正:** アカウント不明・既知のアカウント不一致・同一利用者の認証更新を区別する。所有者を確認できるまでは本文プレビューを表示せず、所有者のログイン等を通じて復元可能にする。トークンハッシュだけでは同一利用者のトークン更新か別利用者かを判断できないことを前提に、所有者識別を設計する。

**完了条件:** A由来の草稿がBの画面・アクセシビリティツリーへ出ないこと。A本人の復帰後はW02の保留領域から復元できること。UI選択と所有者確認を別条件として検証する。

## V01–V04の判定

| 項目 | 今回確認したこと | 判定 |
|---|---|---|
| V01 送信前所有・保存 | performWriteの送信前setPending→同期onCheckpoint、受理・拒否・確定時の保存。保存境界テスト追加 | 前回の再現条件は解消。AndroidライフサイクルによるSavedState取得・復元の保証は未検証 |
| V02 解除後の取得失敗 | fetch成功時のみsetPending(null)。失敗時は保持 | 前回の再現条件は解消 |
| V03 画像のみの説明 | htmlToPlainTextを描画契約へ統一、htmlHasRenderableContentの使用、画像のみのプローブ成功 | 対象の条件は解消。Composeの描画とプレビュー遷移は未検証 |
| V04 旧形式・別アカウント草稿 | 保存形式の判別、orphanedDraft UIを追加 | **未完了。W01–W03** |

HTML変換は画像ラベルだけでなく、段落・リスト等もAnnotatedStringの規則を通すようになっている。既存テストは成功しており、今回この変更から別の確定不具合は見つからなかった。通知プレビューや提出済み本文を含む実画面での確認は残る。

## 次の修正順序

1. **W01:** 旧SavedStateの型移行を実装し、今回の再現テストを正式テストへ追加する。
2. **W02/W03:** 保留草稿を独立した保存領域と所有者確認にまとめ直す。「表示するか」「保存を残すか」「現在の編集欄へ採用するか」を別々に制御する。
3. SavedStateHandle実体と新規ViewModel生成、Composeの保留表示・採用・破棄を通すテストを追加する。現行の保存形式判別だけのテストでV04完了としない。
4. 既知の同名置換確認、R04/R09の端末・結合条件、文字拡大・TalkBack・画像遷移、GitHub CIとテスト用Moodle受入を実施する。

前回までの20件の成功は確認できた。今回は提出API経路を再び広く書き換えるより、保存形式移行と保留草稿の所有・寿命を仕上げることが優先。

## 対応状況 (2026-09-22 実装後)

監査プローブは `FifthAuditProbeTest` 1 件を含む全 21 件が新実装で成功
(`./gradlew.bat testDebugUnitTest --tests ... -I build/audit5/audit.init.gradle --offline --no-build-cache`、JDK 17)。
通常構成の単体テストは **225 件、失敗 0**。今回のプローブは正式テストへ移植済み。
実端末での再生成・別アカウント操作・TalkBack・GitHub CI・テスト用 Moodle は未実施。

| ID | 状態 | 対応内容 | 正式テスト | 残り |
|---|---|---|---|---|
| W01 | 実装・単体済 | `DraftStateStore.get` を `Any?` にし、アダプター境界で型を落とさない。`AssignmentDraftPersistence` が競合フラグを移行 (新 "1"/"0"、旧 Boolean、未知の型は保護側へ倒して競合扱い、キーなしのみ false)。保存形式に `KEY_FORMAT`=2 のバージョンを持たせた | `FifthAuditRegressionTest`: 実 `SavedStateHandle` + 実アダプターで旧 Boolean を復元、型の網羅 (Boolean/文字列/欠落/未知)、旧形式の pending 読み取り、アカウント一致・不一致・未ログイン、新形式の往復 | — |
| W02 | 実装・単体済 | 保留草稿を編集領域とは別の保存領域 (`KEY_ORPHAN_*`) へ退避。`moveToOrphan` は保留を書いてから編集領域を消すので途中終了でも失われない。編集領域の `save`/`clear` では消えず、採用は「編集領域へ保存できたことを確認してから」保留を消す。破棄は利用者の明示操作のみ。ViewModel は保留領域から読むので、新しい ViewModel・再生成後も保留が見える | `orphaned draft survives a new persistence instance until adopted or discarded`、`orphan keeps the owner account so the original account can recover it`、`blank draft is not kept as an orphan` | Android 実プロセス終了・Compose での採用/破棄 |
| W03 | 実装・単体済 | 画面へ渡すのは本文を含まない `OrphanedDraftInfo` (文字数・保存時刻・所有者不一致フラグ) だけにし、バナーから本文プレビューを削除。所有者が分かっていて不一致なら取り込みボタンを出さず「そのアカウントでログインすると復元できます」と案内する (保留は残す)。所有者不明の旧形式のみ明示操作で取り込める。保存時と同じアカウントに戻れば通常どおり取り込める | `orphan info exposed to the ui carries no text and blocks adoption on mismatch`、`orphan keeps the owner account...` | 別アカウント端末試験・アクセシビリティツリーの確認 |

**設計上の限界 (文書化)**: トークンのハッシュは認証情報の識別であり Moodle ユーザーIDではないため、
同じ利用者がログインし直した場合も「所有者不一致」になる。その場合は取り込みを許可せず保留として残す
(黙って消さない) 方針とした。所有者不明の旧形式は、本人確認ができないまま利用者の明示操作で取り込めるが、
本文プレビューは表示しない。
