# 修正報告の再レビューと次のアクション

> 2026-09-22再監査: 下記の実装報告と現在の作業ツリーを再確認しました。T01–T08は成功しましたが、新たに5件の不一致を再現しています。最新の判定は [SECOND_FOLLOWUP_AUDIT.md](C:/Users/2omur/development/better_moodle_app/SECOND_FOLLOWUP_AUDIT.md) を参照してください。

調査日: 2026-09-21。対象: HEAD `f10f698b4d565b88d8b9d1e1696431c666298d5c` に対する未コミットの作業ツリー（新規ファイルを含む）。

## 結論

**初回レビューから改善していますが、F01–F16を「一通り完了」とするには不足があります。次はKMP移行や機能追加ではなく、提出物保護と認証処理の仕上げを行います。**

API引数修正、通常課題の編集開始、warningの扱い、ViewModelによる草稿管理、追加読込、Webへの導線、CI定義の追加は確認できました。一方で、提出ファイルの識別、書き込み結果不明時の扱い、書式判定などに問題が残っています。「全HTTPがキャンセル対応済み」という報告は実装と一致しません。

今回、アプリ本体・既存テスト・CI設定は変更していません。既存の未コミット変更を保持し、本書と前回文書への参照追記だけを成果物としています。検証用コード・結果はGit管理外の `build/review-probes/` に置きました。

## 再検証結果

| 検証 | 結果 | 意味・制限 |
|---|---|---|
| `testDebugUnitTest lintDebug assembleDebug --offline --rerun-tasks --console=plain` | BUILD SUCCESSFUL、53タスク実行 | キャッシュ結果だけではなく再実行。Android Studio同梱JBRを使用 |
| 既存単体テストXML集計 | **147件、失敗0、エラー0、スキップ0** | 報告と一致 |
| Lint | **0 errors / 36 warnings** | 報告と一致。実機での動作保証とは別 |
| レビュー用追加プローブ | **8件中8件で期待動作との不一致を再現** | 既存147件とは別。下記T01–T08。意図的に期待動作をassertしており、不一致がテスト失敗として現れる |
| `git diff --check` | 問題なし | 実装の正しさまでは保証しない |
| `adb devices -l` | 接続端末0台 | 実機、学内SSO、大学APIへの実提出、通知配送は未検証 |
| GitHub Actions | 定義を確認 | ローカル実行のみ。GitHub上でのCI成功実績は未確認 |

追加プローブの後は通常のGradle構成で既存147テストのレポートを復元しています。次のリンクはビルド成果物のため、cleanで削除されます。

- [既存147テストのレポート](C:/Users/2omur/development/better_moodle_app/app/build/reports/tests/testDebugUnitTest/index.html)
- [Lintレポート](C:/Users/2omur/development/better_moodle_app/app/build/reports/lint-results-debug.html)
- [追加プローブのソース](C:/Users/2omur/development/better_moodle_app/build/review-probes/src/dev/rits/bettermoodle/ReviewRegressionProbeTest.kt)
- [追加プローブの結果XML](C:/Users/2omur/development/better_moodle_app/build/review-probes/results/TEST-dev.rits.bettermoodle.ReviewRegressionProbeTest.xml)

ネットワークの再現テストはローカルMockWebServer、提出結果の再現テストはFakeGatewayを使いました。大学サーバーでファイル消失や重複提出を観測したという意味ではありません。認証競合やCompose画面の問題は、コードで確認できる経路と、今後の実行テストが必要な部分を区別しています。

## 報告と実装の照合

「実装確認」は対象の変更が存在し、該当するコード・ローカルテストを確認した意味です。実機受入完了を意味しません。

| 項目 | 判定 | 確認できた改善 | 残り／次アクション |
|---|---|---|---|
| F01 API引数 | 実装確認 | 保存・最終提出はassignmentid、取得・開始はassignid。HTTP本文テストあり | テスト用Moodleで結合確認。旧引数バグは繰り返し指摘しない |
| F02 通常課題の開始 | 実装確認 | caneditで直接編集、timelimitによるStart分岐、グループ提出のWeb誘導 | 時間制限・受付前・開始済みの実応答とUI確認（N11） |
| F03 ファイル保持 | **部分** | 同一draftへ既存＋追加分を入れる処理 | パスを失う、同名置換の内容を検証しない（N01） |
| F04 warning・成功判定 | **部分** | 正常形のwarningを拒否、書き込み後の再取得 | 応答消失後の再送、不正warningの黙殺（N02、N08） |
| F05 草稿保護 | **部分** | dirty、離脱確認、自動保存、SavedStateHandle | コピー中はbusyでない、結果不明状態を保持しない。ViewModel/画面復元は未テスト（N02、N11） |
| F06 HTML契約 | **部分** | エスケープ、改行変換、確認文表示 | style属性等の書式を見落とす（N03） |
| F07 stale表示 | **部分** | staleErrorと取得日時、複数画面へのバナー | 0件表示の分岐、フォーラム投稿表示では失敗が隠れる（N09） |
| F08 セッション | **部分** | AuthState、tokenキャッシュ更新順、Cookie完了待ち | ログアウトの所有scope、失敗tokenの判定と削除の競合、Worker通知直前の競合（N04） |
| F09 ページング | **部分** | 追加読込、offset、コース期間、Workerの窓取得 | カーソル異常を完了扱い、Worker上限後の処理、更新時の検索条件（N06） |
| F10 通知 | **部分** | Intent宛先、onNewIntent、既読API、ID＋期限のキー | 既読要求の寿命、プロセス再生成時の宛先保持、端末試験（N10） |
| F11 外部起動 | 実装確認 | ACTION_VIEWを直接試行、例外時の共有、Custom Tab失敗通知 | 対応アプリあり／なしで実機確認（N11） |
| F12 原文への導線 | **部分** | 課題・フォーラム一覧・ページのWeb導線、introattachments、画像リンク | 拡張子だけの画像判定とフォーラム個別投稿への直接遷移（N07） |
| F13 シラバス | **部分** | 結果型、UIエラー区別、DataStore edit内更新 | 不正応答の一部はNetworkError／NoResultsになる。既存3テストはresolveと同時更新を検証しない（N08、N11） |
| F14 キャンセル | **部分・報告との差あり** | Call.awaitの未受領Response解放、いくつかの呼出元で採用 | executeが3箇所残る。await採用済みでもbody読込中に中断できない（N05） |
| F15 WebView | 報告どおり部分 | onRelease、復旧UI、SSO戻る／タイムアウト | SSOホスト方針・MFA・タブ往復などの受入試験（N11） |
| F16 テスト／CI | **部分** | 147テストとGitHub Actions定義 | 画面、認証競合、実draft構築、結合テストが不足。CI実行証拠も未（N11） |

## 再現できた8つの問題

| ID | 再現条件／追加プローブ名 | 現状の結果 | 期待結果 | 対応 |
|---|---|---|---|---|
| T01 | `preserveSameFilenameInDifferentDirectories`: `/chapter1/report.pdf` と `/chapter2/report.pdf` | plan.keepは1件 | 2件を別ファイルとして保持、または変更操作を拒否してWebへ | N01 |
| T02 | `sameNameReplacementMustVerifyMoreThanFilename`: 新しいサイズのreport.pdfを保存したがFakeは古いファイルを返す | addFileがtrueで成功通知 | 不一致を検出し、成功と断定しない | N01 |
| T03 | `lostFinalSubmitResponseMustBeReconciledBeforeRetry`: Fakeのサーバー状態はsubmittedへ遷移し、応答だけIOExceptionになる。その後再度提出 | 書き込み呼出し2回 | 結果照会を挟み、適用済みなら再送しない | N02 |
| T04 | `styledSpanMustNotBeEditableAsPlainText`: `<span style="color:red;font-weight:bold">` | isRichHtml=false | 書式ありと判定し、プレーン編集で消さない | N03 |
| T05 | `cancellationMustInterruptResponseBodyRead`: ヘッダ送信後bodyを1.5秒遅らせ、150msでコルーチンをタイムアウト | 戻るまで**1550ms** | body待ちもキャンセルで終了する | N05 |
| T06 | `stalledFullPageMustNotBeReportedComplete`: 50件の満杯ページ、lastid=0 | deadlinesWithin.complete=true | 不完全／プロトコル異常として扱う | N06 |
| T07 | `imageLinkWithoutMimeTypeRoutesToImage`: previewKindFor("figure.png", null) | Unsupported | Image | N07 |
| T08 | `malformedWarningsMustNotBecomeSuccess`: `{"warnings":["invalid warning structure"]}` | Repositoryの保存が例外なしで終了 | 応答形式エラー | N08 |

プローブの再実行（clean前のみ、既存テストへ正式に移植するまでは検証用）:

```powershell
$env:JAVA_HOME = 'C:/Program Files/Android/Android Studio/jbr'
./gradlew.bat testDebugUnitTest --tests dev.rits.bettermoodle.ReviewRegressionProbeTest -I build/review-probes/audit.init.gradle --offline --console=plain
```

この実行は通常のテストレポートを上書きします。結果保存後にinit scriptなしで通常テストを実行すれば既存構成へ戻ります。次の実装ではT01–T08を正式な回帰テストとして追加し、成功させてください。

## 次のアクション（優先順）

### N01 / P1 — ファイルのパスと内容を含めて提出物を保護する

**根拠:** [AssignmentFileDraft.kt:52](C:/Users/2omur/development/better_moodle_app/app/src/main/java/dev/rits/bettermoodle/data/AssignmentFileDraft.kt:52) はfilenameだけで削除・置換・distinctを行い、[MoodleClient.kt:111](C:/Users/2omur/development/better_moodle_app/app/src/main/java/dev/rits/bettermoodle/data/MoodleClient.kt:111) はアップロード先filepathを常に `/` にします。[AssignmentEditor.kt:225](C:/Users/2omur/development/better_moodle_app/app/src/main/java/dev/rits/bettermoodle/data/AssignmentEditor.kt:225) の検証もfilename集合の一致だけです。T01/T02で再現済み。

**作業:** ファイル識別を `(filepath, filename)` に統一し、draft再構築時にパスを維持。対応しないならサブディレクトリのある提出を検出してWeb編集に限定する。同名置換には確認を設け、保存後はパス・サイズ等を確認する。サイズだけで同内容とは断定できないため、必要な内容確認方法も定義する。再アップロード対象のダウンロードがHTMLエラーなどではないこと、サイズが想定と一致することも検証する。

**追加条件:** 課題画面を開いた後にWeb側でファイルが変わるケースを考慮し、古いスナップショットで新しい提出物を削除しないよう変更前の再照会と競合検出を行う。

**完了条件:** T01/T02、A＋B保持、フォルダ別同名、最後の1件削除、同名・同サイズで内容だけ違う置換、途中ダウンロード失敗を検証。Fakeのplan確認だけでなく、`AssignmentFileDraft.build` を経由するHTTPテストで全multipart・draft ID・パスを確認する。

### N02 / P1 — 書き込み結果不明とファイル準備中を状態として保持する

**根拠:** [AssignmentEditor.kt:264](C:/Users/2omur/development/better_moodle_app/app/src/main/java/dev/rits/bettermoodle/data/AssignmentEditor.kt:264) は最終提出のIOExceptionを通常エラーとして扱い、状態照会せずbusyを解除。T03では2回目の操作で再送しました。再取得に失敗した場合も文言を出すだけで、次の書き込みを禁止する状態はありません。

また[AssignmentViewModel.kt:75](C:/Users/2omur/development/better_moodle_app/app/src/main/java/dev/rits/bettermoodle/ui/AssignmentViewModel.kt:75) はContentProviderからのコピーが終わるまでEditorのbusyを開始しません。遅いファイルのコピー中に最終提出を押せる経路です。戻る操作もbusyを判定せず、通信中にViewModelが破棄される可能性があります（画面での再現は未実施）。

**作業:** `PreparingFile / Saving / Submitting / OutcomeUnknown / Ready` 等の状態を定義する。応答切断・タイムアウト後は再照会が済むまで同じ書き込みを受け付けない。ファイル準備開始時から一連の操作を排他化し、コピー途中の上限検査・キャンセル・一時ファイル所有権を統一。画面離脱時の扱いを明示する。

**完了条件:** T03に加え、保存／最終提出がサーバーで適用された直後の切断、照会失敗、再起動、ファイルコピー中の最終提出・戻る・連打を検証。「再読込して確認」という文言だけで完了としない。

### N03 / P1 — プレーン編集できるHTMLを保守的に判定する

**根拠:** [OnlineTextFormat.kt:19](C:/Users/2omur/development/better_moodle_app/app/src/main/java/dev/rits/bettermoodle/data/OnlineTextFormat.kt:19) は許可タグ名だけを見ており、style/class/align等の書式を無視します。T04でfalseを確認。このまま保存すると文字色・太字等が失われます。

**作業:** 属性も含め、往復変換で意味・書式を保持できるHTMLだけ編集可能にする。不明な書式はWebへ。連続空白・タブ・連続改行の扱いを明文化し、比較時にすべての空白を畳むことで意味のある差まで隠さない。正規化後もdirtyが残る場合にSavedStateを消さない。

**完了条件:** T04、span/style、p/align、class、空白・改行・特殊文字、既存リッチテキストで上書きを防ぐテスト。文章保存→再取得→再生成で表示と草稿状態が一致する。

### N04 / P1 — ログアウトと認証エラー処理をセッション単位で完結させる

**根拠（競合経路、端末再現未実施）:** [MainActivity.kt:738](C:/Users/2omur/development/better_moodle_app/app/src/main/java/dev/rits/bettermoodle/MainActivity.kt:738) はMainTabsのCompose scopeからlogoutを起動。[App.kt:143](C:/Users/2omur/development/better_moodle_app/app/src/main/java/dev/rits/bettermoodle/App.kt:143) は最初にLoggedOutを発行するので、その呼出元画面・scopeが破棄されます。後続のDataStore削除やCookie削除がキャンセルされる実行順序が残っています。

[App.kt:132](C:/Users/2omur/development/better_moodle_app/app/src/main/java/dev/rits/bettermoodle/App.kt:132) のfailedToken比較も `appScope.launch` の前です。比較後・削除実行前に新しいtokenが保存されると、その新しい認証を消す余地があります。Workerは[DeadlineWorker.kt:51](C:/Users/2omur/development/better_moodle_app/app/src/main/java/dev/rits/bettermoodle/work/DeadlineWorker.kt:51) で世代確認後にDataStoreを読み、通知・通知済み保存を行うため、その間のログアウトは防げません。

**作業:** ログアウトを画面scopeに所有させず、セッション管理側で永続状態・メモリ状態・Cookie・通知の後片付けまで完了させる。ログイン・token同期・条件付き削除を同じ排他制御下で実施し、失敗したtoken／世代を破壊操作の実行時にも確認する。Workerの通知・通知済み更新も現在セッションとの整合性を守る。

**完了条件:** DataStoreの処理を遅延させたログアウト、ログアウト直後の再起動、旧invalidtokenの処理待ち中に新ログイン、Worker通知直前のログアウト、A→B切替を再現テスト。新しい認証やBの保存状態にAの結果が混ざらない。

### N05 / P2 — HTTP本文読み取りまでキャンセルを伝播する

**報告との差:** 以下は現在もブロッキングexecuteです。

- [SsoLogin.kt:62](C:/Users/2omur/development/better_moodle_app/app/src/main/java/dev/rits/bettermoodle/auth/SsoLogin.kt:62)
- [FilePreviewScreen.kt:190](C:/Users/2omur/development/better_moodle_app/app/src/main/java/dev/rits/bettermoodle/ui/FilePreviewScreen.kt:190)
- [PdfPreviewScreen.kt:204](C:/Users/2omur/development/better_moodle_app/app/src/main/java/dev/rits/bettermoodle/ui/PdfPreviewScreen.kt:204)

さらにawaitはヘッダ受領までを待つだけで、返った後の[body.string()](C:/Users/2omur/development/better_moodle_app/app/src/main/java/dev/rits/bettermoodle/data/MoodleClient.kt:163) やストリームread中のJobキャンセルとCall.cancelの結びつきが維持されません。T05はawait採用済みのMoodleClientで再現しています。readの前にensureActiveを置くだけでは、すでにブロック中のreadを解除できません。

**作業:** HTTP応答の取得から本文消費・closeまでを一つのキャンセル対応処理にする。既存3経路も移行し、コール中断・Response解放・一時ファイル削除を一貫して管理する。

**完了条件:** T05、接続前キャンセル、ヘッダ直後キャンセル、body低速／停止、アップロード途中、PDF画面離脱で中断と資源解放を確認。未受領Responseの解放と本文readの中断を別テストで守る。

### N06 / P2 — ページ終端と取得失敗を区別する

**根拠:** [MoodleRepository.kt:54](C:/Users/2omur/development/better_moodle_app/app/src/main/java/dev/rits/bettermoodle/data/MoodleRepository.kt:54) はカーソルが進まないとhasMore=falseにし、[同:95](C:/Users/2omur/development/better_moodle_app/app/src/main/java/dev/rits/bettermoodle/data/MoodleRepository.kt:95) はそれをcomplete=trueへ変換。T06で再現。既存RepositoryPagingTestは「ループしない」は確認しますが、不完全を終端と誤認しない条件はありません。

**作業:** `End / More / Incomplete(reason)` を区別。満杯ページのカーソル不在・未進行・循環を異常として扱う。Workerは20ページ上限で不完全でも現在はResult.successになるため、続きを取得する方法・再試行・利用者への状態表示を決める。「24時間分を取り切る」と無条件に記述しない。

**画面側:** [DeadlinesScreen.kt:52](C:/Users/2omur/development/better_moodle_app/app/src/main/java/dev/rits/bettermoodle/ui/DeadlinesScreen.kt:52) は検索開始時刻をrememberで固定。明示的な更新時には時刻とページ世代を更新し、追加取得中は同じスナップショットを使う。`remember(firstPage)` は内容が等しい先頭ページの再取得で追加ページを破棄しないため、更新世代を別に管理する。

**完了条件:** T06、1000件超、カーソル循環、全ページが対象外イベント、先頭ページが変わらず後続だけ変更、長時間表示後の更新。打ち切り・異常を全件成功として表示しない。

### N07 / P2 — 画像リンクと直接遷移した投稿の閲覧導線を完成させる

**根拠:** [MainActivity.kt:526](C:/Users/2omur/development/better_moodle_app/app/src/main/java/dev/rits/bettermoodle/MainActivity.kt:526) は画像リンクを `previewKindFor(filename, null)` で分類しますが、[AssignmentUiModels.ktのpreviewKindFor](C:/Users/2omur/development/better_moodle_app/app/src/main/java/dev/rits/bettermoodle/data/AssignmentUiModels.kt:186) は画像MIMEしか認識しません。T07でpngがUnsupportedとなり、WebViewへ落ちます。WebViewのdownload listenerはダウンロードを拒否するため、画像リンクが確実に見られるとはいえません。

また[ForumDiscussionScreen](C:/Users/2omur/development/better_moodle_app/app/src/main/java/dev/rits/bettermoodle/ui/ForumScreen.kt:185) の直接遷移画面では、成功時の常時Webボタンがありません。追加されたのはForumScreen側です。

**作業・完了条件:** png/jpg/jpeg/webp等を適切に分類し、HTTPの種類検証も維持。画像リンク→正しいプレビューを画面まで通して確認。通知から直接フォーラム投稿を開いた場合も、添付・返信等へWebで到達できることを確認する。

### N08 / P2 — 不正応答を「成功」「0件」「通信失敗」に丸めない

**根拠:** [MoodleClient.kt:91](C:/Users/2omur/development/better_moodle_app/app/src/main/java/dev/rits/bettermoodle/data/MoodleClient.kt:91) はwarningのデコード失敗をmapNotNullで捨て、想定外の形も空warningへ変換します。T08でRepository保存が成功扱いになります。後段照会がある操作でも、この契約の緩さは残ります。

シラバスのparseRecordsは一部のjsonObject/jsonArrayキャストが外側のcatchへ落ちてNetworkErrorになり、全レコードのデコード失敗はNoResultsになります。F13の結果型の追加だけでは、仕様変更の区別は完成していません。

**作業:** WS関数ごとに許容する成功応答を定義。null/空本文の受理を全操作共通にしない。不正warningと不正構造を専用の応答形式エラーへ。既読APIの成功判定も、警告とIDの契約を厳密に確認する。

**完了条件:** T08、warning配列／オブジェクトの正しい例、wrong type、空本文、不正JSON、シラバスactions=null／文字列、全レコード不正と本当の0件を検証。

### N09 / P2 — 「0件」のときも更新失敗を表示する

**根拠:** [DeadlinesScreen.kt:92](C:/Users/2omur/development/better_moodle_app/app/src/main/java/dev/rits/bettermoodle/ui/DeadlinesScreen.kt:92) は空データで先にreturnするため、staleErrorがあってもバナーに到達せず「締切はありません」と表示。NotificationsScreen、CourseListScreen、SyllabusScreenにも同種の分岐があります。ForumScreenのDiscussionPostsはrememberLoadableを使いますがstaleバナーを表示しません。

**作業・完了条件:** 0件表示より外側に更新状態を置く。空一覧取得→オフライン更新で「以前は0件、現在の取得は失敗」を区別。フィルターで0件になる通知、フォーラムの更新失敗もComposeテストに含める。

### N10 / P2 — 既読同期と通知宛先を画面・プロセス寿命から切り離す

**根拠（実行テスト未実施）:** [NotificationsScreen.kt:81](C:/Users/2omur/development/better_moodle_app/app/src/main/java/dev/rits/bettermoodle/ui/NotificationsScreen.kt:81) の既読要求はrememberCoroutineScopeで起動し、同時に別画面へ遷移します。遅い通信では元画面の破棄で要求もキャンセルされる経路があります。失敗はUIに通知されません。

pendingDeepLinkはAppContainerのメモリ内のみで、[MainActivity.kt:102](C:/Users/2omur/development/better_moodle_app/app/src/main/java/dev/rits/bettermoodle/MainActivity.kt:102) はsavedInstanceStateがあるとIntentを読みません。通知を受けた後、ログイン途中でプロセスが再生成されると宛先を失う可能性があります。

**作業・完了条件:** 既読要求をセッションに紐づくRepository等に所有させ、成功・失敗・再試行を管理。遷移待ち宛先を安全に復元し、成功消費後の二重遷移を防止。cold start、onNewIntent、ログイン待ちの再生成、遅い既読通信、通知権限／チャンネル無効で検証。

### N11 / P2 — 完了判定に必要なテストと実行証拠を揃える

**現状のテストの限界:** AssignmentEditorTestのFakeGatewayはbuildFileDraft時点でサーバーファイルを書き換え、saveFilesではdraft IDを記録するだけです。実際のダウンロード→再アップロード→保存は試していません。SavedStateHandleのテストではなくEditor.restoreDraftのテストであり、ViewModel・画面再生成・busy中操作・破棄確認を保証しません。SyllabusRepositoryTestは3つの純粋関数相当の確認で、resolve結果型やDataStore同時更新は未検証です。

**作業:** T01–T08を正式テストへ移植し、N01–N10の回帰テストを追加。Fakeのサーバー状態はsave適用時点で更新し、通信前後の失敗を注入できるようにする。SessionStore、Clock、HTTP、通知発行を差し替え可能にして競合を検証。重要なCompose操作とSavedState復元を追加。

**実機受入:** 通常・時間制限付き・グループ・延長あり課題、実draft、SSO/MFA、WebView復旧、通知配送とタップ、外部ビューアを確認。ネイティブで未対応の条件はWebに誘導する。大学の実履修課題へ無断で試験提出しない。

**CI・文書:** GitHub上でワークフローを実行し、147件＋正式追加テストの結果とLint・ビルド成果物を確認。PROJECT_REVIEWの「済」は実装・単体・結合・実機の各状態へ分けて更新する。README/ROADMAPも「全HTTP」「24時間分を取り切る」「全ファイル保持」などの断定を、検証済み範囲に揃える。

## 実装を依頼する順序と完了ゲート

| 順序 | 作業単位 | 対象 | 完了ゲート |
|---|---|---|---|
| 1 | 提出データ保護 | N01、N02、N03 | T01–T04が成功。パス・内容・草稿が保護され、結果不明から無条件に再送しない |
| 2 | セッションの整合性 | N04 | ログアウトが画面破棄で中断せず、旧結果が新認証へ干渉しないことを遅延テストで確認 |
| 3 | 通信・応答・ページング | N05、N06、N08 | T05、T06、T08成功。不完全を成功と見せず、body待ちも中断する |
| 4 | 閲覧・通知・更新表示 | N07、N09、N10 | T07成功。0件stale、画像リンク、直接投稿、既読遷移、プロセス復元の画面テスト成功 |
| 5 | 結合・実機・報告整備 | N11 | テスト用Moodleと端末受入、GitHub CI成功、項目別の検証根拠を文書化 |

**少なくとも順序1・2の完了前にネイティブ提出を「安定化完了」として配布しないことを推奨します。** ファイルのサブディレクトリや保持できない書式をWeb専用にするのは、データ保護を満たす暫定修正として認めます。機能をネイティブ化しきることより、提出内容を壊さないことを完了基準にします。

PDF巨大ページ対策、閲覧完了API、フォーラムのBack動作、URL正規化、依存更新等の初回「追加改善候補」は引き続き別バックログです。今回の8件の再現結果と上記の未完了条件を先に解消し、KMP/iOS移行はその後に再評価します。

## 対応状況 (2026-09-21 実装後)

T01–T08 は正式な回帰テストへ移植し、`build/review-probes` のプローブ 8 件も新実装に対して全件成功することを確認した
(`./gradlew.bat testDebugUnitTest --tests dev.rits.bettermoodle.ReviewRegressionProbeTest -I build/review-probes/audit.init.gradle --offline`。
結果は AGP の既定位置 `app/build/test-results/testDebugUnitTest/` に出力される)。
通常構成の単体テストは **185 件、失敗 0**。Lint 0 errors / 36 warnings。GitHub 上での CI 実行、テスト用 Moodle との結合、実機受入は未実施。

| ID | 正式テスト |
|---|---|
| T01 | `OnlineTextFormatTest.plan preserves same filename in different directories`, `AssignmentEditorTest.adding a file keeps existing files with their paths in the draft` |
| T02 | `AssignmentEditorTest.same name replacement must verify more than filename` |
| T03 | `AssignmentEditorTest.lost final submit response is reconciled and not resent`, `...blocks further writes until reconciled` |
| T04 | `OnlineTextFormatTest.rich html including styled attributes is detected...` |
| T05 | `HttpCancellationTest.cancellation interrupts response body read (T05)` ほか 3 件 |
| T06 | `RepositoryPagingTest.stalled full page must not be reported complete by deadlinesWithin` |
| T07 | `ResponseContractTest.image link without mime type routes to image (T07)` |
| T08 | `ResponseContractTest.malformed warnings must not become success (T08)` |

| 項目 | 状態 | 対応内容 | 残り |
|---|---|---|---|
| N01 | 実装・単体済 | `SubmissionFileKey(filepath, filename)` で識別。`AssignmentFileDraft.build` は既存ファイルを取得時にサイズ一致・HTML でないことを確認し同じパスへ再アップロード。upload 応答の itemid/filename/filepath を要求と照合。保存後は `verifySaved` で集合とサイズを照合。変更前に再照会して画面表示時の集合と違えば中断 (競合検出)。`AssignmentFileDraftHttpTest` で multipart・draft ID・パスを HTTP レベルで確認 | 同名・同サイズで内容だけ違う置換はサイズでは検出できない (Moodle の API にハッシュが無いため)。同名置換の確認ダイアログは未 (現在は置換後に一覧で分かる)。テスト用 Moodle での結合 |
| N02 | 実装・単体済 | `EditorPhase` と `PendingWrite` を状態化。応答消失・形式不明は照会→適用済みなら成功/未適用なら再送可/照会不能なら結果不明を保持し「結果を確認」まで書き込み拒否。ファイルコピーは Editor の排他区間内。書き込み+照会は NonCancellable。離脱は busy 中に確認ダイアログ | 画面テスト (Compose)、再起動時の PendingWrite 復元 (現在はプロセス再生成で消える。再生成後は通常の再読込でサーバー状態が正) |
| N03 | 実装・単体済 | 属性付き要素も書式ありと判定。空白規則 (行末空白・3連続以上の空行を落とす、行内空白は保持) を明文化し、比較は可逆変換の厳密一致。保存確認後は草稿も正規化後に揃え、dirty が残るときは SavedState を消さない | 画面再生成の実行テスト |
| N04 | 実装・単体済 | `AuthSession` (Mutex、アプリ scope、NonCancellable)。ログアウトは永続化→後片付け→LoggedOut の順。`onAuthError` はロック内で現在トークンと比較。collector はロック内で `store.token()` を読み直す。Worker は取得前・通知直前・通知済み保存前に `isCurrent(generation)` を確認 | 端末での再現テスト |
| N05 | 実装・単体済 | `Call.useResponse`: 番人コルーチンが親のキャンセルで `Call.cancel()` し、ブロック中の read を解除。SsoLogin/FilePreview/PdfPreview の execute() も移行。DownloadShareDialog・Syllabus も移行 | アップロード途中の中断テスト (MockWebServer で multipart 途中停止を再現しづらいため未) |
| N06 | 実装・単体済 | `ActionEventPage.incompleteReason` / `DeadlineWindow.incompleteReason`。満杯ページのカーソル不在・既知IDの循環は不完全。Worker は不完全時に通知済みを削らず success (方針を KDoc に明記)。締切画面は更新世代で開始時刻を取り直し、古い世代の追加ページを捨て、不完全をバナー表示 | 1000 件超の実データ |
| N07 | 実装・単体済 | 拡張子による画像判定 (png/jpg/jpeg/gif/webp/bmp/heic)。`ForumDiscussionScreen` にも常時「Moodleで開く」 | 画面まで通した確認 |
| N08 | 実装・単体済 | save/submit は warning 配列のみ受理、要素不正・null 本文・オブジェクトは `MoodleResponseParseException`。型付き読み取りは `decodeStrict`。既読 API は ID 一致と warnings 配列を検証。シラバスは構造不正・全レコード不正を InvalidResponse、真の 0 件だけ NoResults | — |
| N09 | 実装済 | 締切・通知・コース一覧・シラバス・フォーラム投稿で `StaleDataBanner` を 0 件分岐の外側へ | Compose テスト |
| N10 | 実装・単体済 | `NotificationReadSync` (アプリ scope、世代チェック、失敗理由表示)。通知宛先は `EXTRA_OPEN_KEY` と DataStore の消費済みキーで二重遷移を防ぎ、savedInstanceState の有無に関わらず Intent を読む | 端末試験 (cold start / onNewIntent / 権限なし) |
| N11 | 部分 | T01–T08 移植、Fake は save 適用時に更新、HTTP・遅延を注入できるテスト (AuthSessionTest, HttpCancellationTest, AssignmentFileDraftHttpTest) | Compose/instrumentation、テスト用 Moodle 結合、GitHub CI 実行実績、実機受入 |
