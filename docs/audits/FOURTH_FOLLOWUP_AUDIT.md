# S01–S03およびUI更新のレビュー

> V01–V04への対応を再レビューしました。過去20件の監査プローブは成功しています。最新の判定と保存形式・保留草稿の残件は [FIFTH_FOLLOWUP_AUDIT.md](C:/Users/2omur/development/better_moodle_app/FIFTH_FOLLOWUP_AUDIT.md) を参照してください。

調査日: 2026-09-22。対象は未コミットの現在の作業ツリー。実装・正式テストは変更していない。

## 結論

**前回までの17件の監査プローブはすべて成功した。ただし、送信中のPendingWrite保持・保存タイミング、解除失敗時の操作制限、画像だけの説明表示、旧形式草稿の復元に問題が残る。**

今回の3件の追加プローブで3つの不一致を再現した。旧形式草稿の問題はViewModelの分岐を確認した静的指摘であり、Androidの復元実行試験は未実施。UIはテーマ・共通部品・一覧・課題・小テスト・コース画面等のソースを確認した。実端末の描画、文字サイズ変更、TalkBack、画面遷移の実行確認とは区別する。

## 検証結果

| 対象 | 今回の結果 |
|---|---|
| JDK 17、`testDebugUnitTest lintDebug assembleDebug --offline --rerun-tasks --console=plain` | **BUILD SUCCESSFUL、55タスク実行** |
| 既存単体テスト | **210件、失敗0** |
| 過去の監査プローブ | **17件成功**（8＋5＋4） |
| 今回のFourthAuditProbeTest | **3件失敗**、下記V01–V03の再現 |
| Lint | **0 errors / 30 warnings** |
| Git diff check | エラーなし、改行変換の警告のみ |
| GitHub CI・テスト用Moodle・実機受入 | 未実施 |

通常テストの結果は報告と一致。プローブはFakeGatewayと純粋関数による検証で、大学サーバーへの試験提出や実際のプロセス終了は行っていない。プローブ実行後に通常構成のテストを実行し、210件のレポートへ戻した。

- [追加プローブのソース](C:/Users/2omur/development/better_moodle_app/build/audit4/src/dev/rits/bettermoodle/FourthAuditProbeTest.kt)
- [追加プローブ結果XML](C:/Users/2omur/development/better_moodle_app/build/audit4/results/TEST-dev.rits.bettermoodle.FourthAuditProbeTest.xml)
- [直前4件の結果XML](C:/Users/2omur/development/better_moodle_app/build/audit4/results/TEST-dev.rits.bettermoodle.ThirdAuditProbeTest.xml)
- [既存テストレポート](C:/Users/2omur/development/better_moodle_app/app/build/reports/tests/testDebugUnitTest/index.html)
- [Lintレポート](C:/Users/2omur/development/better_moodle_app/app/build/reports/lint-results-debug.html)

build配下の検証用ソース・結果はGit管理外で、cleanで消える。

```powershell
$env:JAVA_HOME = 'C:/Program Files/Java/jdk-17'
./gradlew.bat testDebugUnitTest --tests dev.rits.bettermoodle.FourthAuditProbeTest --tests dev.rits.bettermoodle.ThirdAuditProbeTest --tests dev.rits.bettermoodle.SecondAuditProbeTest --tests dev.rits.bettermoodle.ReviewRegressionProbeTest -I build/audit4/audit.init.gradle --offline --console=plain
```

現在は20件中3件失敗となる。

## V01 / P1 — 送信中はPendingWriteが存在せず、保存も操作完了後まで行われない

根拠: [AssignmentEditor.kt:472](C:/Users/2omur/development/better_moodle_app/app/src/main/java/dev/rits/bettermoodle/data/AssignmentEditor.kt:472)、[AssignmentViewModel.kt:113](C:/Users/2omur/development/better_moodle_app/app/src/main/java/dev/rits/bettermoodle/ui/AssignmentViewModel.kt:113)、同ファイルpersist。

報告とコメントは「送信開始からPendingWriteを所有」としているが、performWriteは最初にblockを実行し、返ってきた後の492行で初めてpendingWriteを設定する。

**再現済み:** `inFlightSubmissionMustAlreadyOwnPendingWrite`。Gatewayの提出呼出しに入ったところで待機させ、Editorの状態を確認するとpendingWriteはnullだった。

さらにViewModelのsave/start/submit/removeは、Editorの操作が返った後でpersistを呼ぶ。StateFlowの途中状態を保存する購読や、送信前保存のコールバックはない。したがって、Editor側でpendingを早く設定するだけでも修正は不十分。送信中・照会中にプロセスが終了すると、復元するJSON自体がSavedStateHandleへ書かれていない経路が残る。

**影響:** サーバーでは処理が進んでいても、復元後のアプリは未確認の送信を知らず、照会が旧状態なら再送し得る。JSONのencode/decodeテストだけではこの問題は検証できない。

**修正方針:** 未確認操作の登録・保存を送信より前に完了する経路にする。以降のacknowledgedや確定も一貫して保存する。SavedStateHandleが保証する復元範囲と、より強い永続化が必要な範囲を明確にする。

**完了条件:** 送信開始前、送信待ち、応答後、照会待ちの各地点で状態を保存・再生成し、pendingが復元されるテスト。現在のViewModelまたは抽出した保存境界まで通し、EditorとJSON単体だけで完了としない。

## V02 / P2 — 明示解除後の最新状態取得に失敗しても、旧状態で書き込める

根拠: [AssignmentEditor.kt:202](C:/Users/2omur/development/better_moodle_app/app/src/main/java/dev/rits/bettermoodle/data/AssignmentEditor.kt:202)。dismissPendingWriteはpendingを先に消してからrefreshを呼ぶ。refresh失敗時は取得済みのdataを保持するため、最新状態を取得できていなくても書き込みを受け付ける。

**再現済み:** `releaseMustNotEnableWritesWhenLatestStateCannotBeLoaded`。未確認状態→照会を失敗させる→明示解除→最終提出で、提出呼出しが1回から**2回**になった。

明示解除を許す設計自体への指摘ではない。「解除後は最新状態を取り直し、古い画面のまま操作させない」という実装コメントの保証を満たしていない点が問題。

**修正方針:** 最新状態を取得するまでpendingまたは再読込必須状態を保持する。取得に失敗した場合のUIは、再読込とWeb確認へ限定する。

**完了条件:** 解除→再読込成功／失敗／キャンセルの各経路。失敗直後に保存・追加・削除・開始・提出を押しても、旧状態で送信されないこと。

## V03 / P2 — UI更新で画像だけの説明が非表示になる

根拠: [AssignmentScreen.kt:213](C:/Users/2omur/development/better_moodle_app/app/src/main/java/dev/rits/bettermoodle/ui/AssignmentScreen.kt:213)、[QuizScreen.kt:189](C:/Users/2omur/development/better_moodle_app/app/src/main/java/dev/rits/bettermoodle/ui/QuizScreen.kt:189)。HtmlTextを呼ぶ前に `htmlToPlainText(intro).isNotBlank()` で説明の表示を判定する。

[HtmlText.kt:30](C:/Users/2omur/development/better_moodle_app/app/src/main/java/dev/rits/bettermoodle/ui/HtmlText.kt:30) のplain変換はimgのalt/srcを文章として返さない。一方、HtmlTextが使うannotated変換には画像リンクを生成する処理がある。

**再現済み:** `imageOnlyIntroMustReachHtmlRenderer`。imgだけのHTMLについて、annotated変換には表示可能な画像リンクがあるが、画面の表示条件はfalseとなった。純粋関数と実際の表示条件の不一致を検証したもので、Composeの描画試験ではない。

**影響:** 教員が画像だけで提示した課題の説明・小テストの説明が、アプリでは見えなくなる。常時Webボタンは残るが、説明が欠落していること自体が分かりにくい。

**修正方針:** HtmlText自身の表示判断に任せるか、同じレンダリング契約で「表示できる内容があるか」を判定する。画像を無視するplainTextを共通の空判定に使わない。コースsummary、module.description、提出済み文章、通知本文など同じ条件を使う箇所も確認する。

**完了条件:** 画像のみ（altあり／なし）、画像＋文章、空タグのみ、リンクのみのHTMLについて、課題・小テスト画面で期待した表示になるComposeテスト。画像リンクからのプレビュー遷移も確認する。

## V04 / P2 — 旧形式の草稿はViewModelで復元されない

根拠: [AssignmentViewModel.kt:57](C:/Users/2omur/development/better_moodle_app/app/src/main/java/dev/rits/bettermoodle/ui/AssignmentViewModel.kt:57)。復元はsavedAccountが非nullかつ現在のaccountIdと一致する場合に限る。

**静的確認、実復元未実施:** 以前の形式はKEY_DRAFTだけを保存し、KEY_ACCOUNTを持たない。その状態ではEditor.restoreDraftへ到達しない。最初のrefreshでサーバー本文を取り込んだ後、persistはdirtyなしとして旧草稿キーを削除する。Editorの「基準値がない旧形式を保護する」分岐は、実ViewModelの旧SavedStateからは呼ばれない。

**修正方針:** アカウント不明の草稿を無条件に現在の編集欄へ復元せず、旧形式を検出して保持・復元可否の確認・破棄の扱いを決める。「旧形式対応済み」とは分けて記載する。トークンハッシュはMoodleユーザーIDではなく認証情報の識別であるため、同じ利用者のトークン更新をどう扱うかも明示する。

**完了条件:** KEY_DRAFTのみ、account一致、不一致、同一利用者のトークン更新、未確認操作ありのSavedStateテスト。本人確認できない草稿を他アカウントへ表示せず、本人の未保存草稿も黙って消さない。

## UI変更の確認範囲

| 変更 | ソース上の確認 | 必要な追加検証 |
|---|---|---|
| Themeのライト／ダーク色、surface段階、Typography、角丸 | 定義と参照を確認。コンパイル・Lint成功 | 両テーマの実描画、拡大文字、コントラスト |
| AppCard、LeadingIconBox、StatusPill、SectionHeading、EmptyBox | 共通部品化と一覧での使用を確認 | タッチ領域、TalkBack、長い講義名・通知件名 |
| 課題・小テストの状態カード、説明の条件表示 | 状態・期限の表示とWeb導線を確認 | **V03の画像説明欠落**、期限なし、提出済み、採点済み |
| 未確認操作・競合のバナーと操作ボタン | backendへの接続を確認 | **V02の失敗経路**。3ボタン横並びを320dp幅・フォント拡大で操作できるか |
| コース／フォーラム／通知／シラバス／時間割の見た目 | カード・見出し・空表示・色の変更を確認 | 0件stale、長文、画面回転、横画面、スクロールと更新 |

横幅・文字拡大時のボタン欠けやコントラスト不良を実測したわけではないため、これらは不具合件数に含めていない。今回のUI指摘で再現したのはV03の表示条件。

## S01–S03の判定と報告の補正

- **S01:** performWrite/settlePendingの統一と、旧状態を読んだ際の再送防止は前回プローブで確認。送信開始時の所有と永続化はV01、明示解除はV02が残る。
- **S02:** Editorの基準値・競合復元、PendingWriteの直列化は改善。実ViewModelの保存タイミングと旧形式移行はV01/V04が残る。
- **S03:** トークンスナップショット、送信前チェック、Jobキャンセル、応答所有権を確認。前回の予約後セッション切替プローブも成功。
- 報告の「旧トークンはサーバー側で拒否される」は、今回のローカル検証では保証していない。ローカルログアウトだけでサーバーのtoken失効を行う実装はない。またAuthSession.onAuthErrorは世代ではなくfailedTokenとcurrentTokenの一致を比較する。報告はこの実装どおりに修正すること。

## 次のアクション

1. V01を優先し、送信より前の状態登録・保存を一体化する。ViewModel/SavedState経路のテストを追加する。
2. V02/V04の解除・移行経路を修正し、復元や通信失敗でも操作制限と草稿保護が残ることを確認する。
3. V03を修正し、画像だけの説明を含むUI受入を行う。
4. 既知の同名置換確認、R04/R09の端末・結合条件、GitHub CI、テスト用Moodle、実機受入を消化する。

210件と過去17件の成功は維持すべき成果。ただし、今回も「Editor内の状態を単体検証すること」と「ViewModelがその状態を正しい時点で保存し、UIが正しく表示すること」に差が残っている。次はそこを通したテストを完了条件にする。

## 対応状況 (2026-09-22 実装後)

監査プローブは `FourthAuditProbeTest` 3 件を含む全 20 件が新実装で成功
(`./gradlew.bat testDebugUnitTest --tests ... -I build/audit4/audit.init.gradle --offline`、JDK 17)。
通常構成の単体テストは **217 件、失敗 0**。今回の 3 プローブは正式テストへ移植済み。実端末の描画・TalkBack・文字拡大、GitHub CI 実行、テスト用 Moodle 結合は未実施。

| ID | 状態 | 対応内容 | 正式テスト | 残り |
|---|---|---|---|---|
| V01 | 実装・単体済 | `performWrite` は送信前に `PendingWrite` (acknowledged=false) を所有し、`AssignmentEditor(onCheckpoint)` の保存フックを同期的に呼んでから送信する。成功応答 (acknowledged=true)、確定・拒否・解除でも同じ経路で保存。保存境界を `AssignmentDraftPersistence` + `DraftStateStore` として抽出し、ViewModel は SavedStateHandle 実装を渡すだけにした。SavedStateHandle が保証するのはシステム主導の再生成までで、タスク終了・端末再起動では復元されないことを KDoc に明記 | `in-flight submission must already own pending write and be persisted before send`、`pending persisted before send is restored and blocks resend after process death`、`checkpoints cover before send, after acknowledge and after settle` | Android 実プロセス終了での復元試験 |
| V02 | 実装・単体済 | `dismissPendingWrite` は最新状態の取得に成功したときだけ解除し、失敗時は未確認のまま保持してエラー表示。旧状態での書き込みは拒否される | `release must not enable writes when latest state cannot be loaded` (失敗→連打→復帰→解除) | 解除中のキャンセル経路の画面テスト |
| V03 | 実装・単体済 | `htmlToPlainText` を `HtmlText` と同じ描画契約 (`htmlToAnnotatedString`) に載せ替え、画像を "[画像: alt]" として扱う。表示分岐は新設の `htmlHasRenderableContent` に統一 (課題・小テストの説明、コースのセクション概要・モジュール説明、通知本文) | `image only intro must reach html renderer` (alt あり/なし、画像+文章、空タグ、リンクのみ) | Compose での描画・画像リンクからのプレビュー遷移 |
| V04 | 実装・単体済 | 旧形式 (アカウント不明) と別アカウント (トークン更新後の同一利用者を含む) の草稿は編集欄へ入れず `orphanedDraft` として保留し、画面で「復元 / 破棄」を選ばせる。復元は基準値なしの保護経路 (最新本文と差があれば競合)。別アカウントの未確認操作は復元しない。トークンのハッシュは Moodle ユーザーIDではないことを KDoc に明記 | `persistence distinguishes legacy, foreign and own drafts`、`legacy draft adopted through the protective path becomes a conflict when web text differs` | SavedStateHandle 実体・Compose の復元試験 |

前回報告の「旧トークンはサーバー側で拒否される」「`onAuthError` は世代不一致で無視される」は監査の指摘どおり実装に合わせて THIRD_FOLLOWUP_AUDIT.md の記述を修正した (ローカルログアウトはサーバーのトークンを失効させない。`onAuthError` は failedToken と現在トークンの一致で判定する)。
