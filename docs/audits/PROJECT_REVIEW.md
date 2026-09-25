# プロジェクト全体レビューと修正方針

調査日: 2026-09-21

## 結論

最優先は、課題提出フローの修復です。標準MoodleとのAPI契約に不整合があり、通常課題の編集開始、本文・ファイル保存、最終提出が正常に完了しない経路があります。引数だけ直すと、既存ファイルの消失など別の問題が表面化するため、提出フロー全体を一つの修正単位として扱います。

次に、更新失敗の可視化、認証・ログアウトの競合対策、一覧の取得漏れ、通知からの遷移、教材閲覧とWebViewの復旧手段を整備します。KMP/iOS移行や大規模な依存更新は、この安定化と回帰テスト整備の後に進める方針です。

この調査ではアプリの実装を変更していません。変更は本レビュー文書の追加のみです。

> **対応状況 (2026-09-21 追記)**: 本文書の F01–F16 に対する修正を実装した。各項目の対応内容は末尾の「対応状況」表を参照。
> 実機・大学サーバーでの検証は未実施のまま (チェックリストは未消化)。

## 調査範囲・検証結果

- 単一Android appモジュール。main配下のKotlin 45ファイル、単体テスト17ファイルを対象に、構成・主要経路・モデル・テストを調査。
- 認証、トークン保存、時間割、締切、通知、コース、課題、小テスト、フォーラム、ページ教材、PDF・ファイル共有、シラバス、ポータル、バックグラウンド処理、Manifest・Gradle・README・ROADMAPを確認。
- Android Studio同梱JBRを指定して `./gradlew.bat testDebugUnitTest lintDebug assembleDebug --console=plain` を実行。**BUILD SUCCESSFUL**。
- XMLレポートの集計: **100テスト、失敗0、エラー0、スキップ0**。
- Lint: **0 errors / 38 warnings**。内訳は依存更新通知30件、target SDK 1件、package visibility 1件、バックアップ規則1件、Compose Modifier 2件、リソース・アイコン・version catalog各1件。依存更新通知は構成別の重複を含み、38種類の障害を意味しません。
- `adb devices -l` の接続端末は0台。実機操作、学内SSO、実アカウントのAPI応答、課題の実提出、通知の端末配送は未検証。大学側への提出・既読化などの変更操作は実施していません。
- Moodleの契約は公式リポジトリの `MOODLE_405_STABLE` と照合。大学サーバーの実バージョン・カスタマイズは未確認です。

検証レポート: [単体テスト](C:/Users/2omur/development/better_moodle_app/app/build/reports/tests/testDebugUnitTest/index.html)、[Lint](C:/Users/2omur/development/better_moodle_app/app/build/reports/lint-results-debug.html)。ビルド成果物なのでcleanで削除されます。

「契約確認」はローカルコードと公式仕様の照合、「コード確認」は分岐・状態遷移から確認した問題です。いずれも、実端末で発生を観測したという意味ではありません。「要再現」は発生順序や大学環境に依存する項目です。

## 優先度の基準

- **P1**: 提出できない、提出内容を失う、失敗を成功として扱う。ネイティブ提出を日常利用する前に解消。
- **P2**: 情報の欠落・誤認、画面遷移や復旧の失敗、競合、リソース管理。安定化期間で修正。
- **P3**: 運用・保守・表示改善。上記修正と並行または後続で実施。

## 問題点と個別の修正方針

### F01 / P1 — 保存・最終提出APIの引数名が違う【契約確認】

**根拠:** [MoodleRepository.kt:138](C:/Users/2omur/development/better_moodle_app/app/src/main/java/dev/rits/bettermoodle/data/MoodleRepository.kt:138)。`saveOnlineTextSubmission`、`saveFileSubmission`、`submitAssignmentForGrading` が `assignid` を送っています。標準の保存・最終提出APIは `assignmentid` を必須とします。取得・開始APIの `assignid` まで一括置換してはいけません。[Moodle公式実装](https://github.com/moodle/moodle/blob/MOODLE_405_STABLE/mod/assign/externallib.php)

**動作への影響:** 文章保存、ファイル保存、最終提出で必須引数不足のエラーになります。読み取り画面と単体テストが動いていても、提出は成立しません。

**修正:** WS関数ごとにリクエスト・レスポンスの型または専用ビルダーを定義し、上記3経路だけ正しい名前に修正。HTTPリクエスト本文まで検証する契約テストを追加。

**完了条件:** 保存2種と最終提出に `assignmentid` が含まれ、ステータス取得・開始はそれぞれの契約を維持。テスト用Moodleで保存結果と提出状態を再取得して一致を確認。

### F02 / P1 — 通常課題を「提出開始」から編集できない【契約確認】

**根拠:** [AssignmentUiModels.kt:102](C:/Users/2omur/development/better_moodle_app/app/src/main/java/dev/rits/bettermoodle/data/AssignmentUiModels.kt:102) は未提出に `Start` だけを出し、[MoodleRepository.kt:134](C:/Users/2omur/development/better_moodle_app/app/src/main/java/dev/rits/bettermoodle/data/MoodleRepository.kt:134) は `mod_assign_start_submission` を呼びます。このAPIは制限時間付き課題の開始用で、通常課題では `timelimitnotenabled` warningを返します。[Moodle公式実装](https://github.com/moodle/moodle/blob/MOODLE_405_STABLE/mod/assign/classes/external/start_submission.php)

**再現条件:** 制限時間なし・未提出・編集可能な課題を開いて「提出を開始」。警告を無視して再読込するだけになり、Editに到達せず入力欄が出ない経路があります。

**修正:** 通常課題は `canedit` に基づいて未提出時から編集可能にする。制限時間付き課題だけ明示的な開始確認を設け、開始APIを呼ぶ。未対応の時間制限付き課題は当面WebViewへ誘導する。

**完了条件:** 通常課題は開始APIなしで入力・保存できる。時間制限付き課題の開始を画面表示だけで実行しない。

### F03 / P1 — 「ファイルを追加」が既存ファイルの置換になる【契約確認・F01修正後に顕在化】

**根拠:** [MoodleClient.kt:113](C:/Users/2omur/development/better_moodle_app/app/src/main/java/dev/rits/bettermoodle/data/MoodleClient.kt:113) は毎回 `itemid=0` で新規draftへ1ファイルだけアップロードし、[AssignmentScreen.kt:131](C:/Users/2omur/development/better_moodle_app/app/src/main/java/dev/rits/bettermoodle/ui/AssignmentScreen.kt:131) はそのdraft IDを提出の全ファイル集合として保存します。既存提出物をdraftへ引き継ぐ処理がありません。

**動作への影響:** 既存A.pdfにB.pdfを「追加」しても、Bだけのdraftで提出領域を更新します。標準Moodleはdraftにない旧ファイルを削除するため、Aが提出から消える経路です。[ファイル提出プラグイン](https://github.com/moodle/moodle/blob/MOODLE_405_STABLE/mod/assign/submission/file/locallib.php)、[draft保存実装](https://github.com/moodle/moodle/blob/MOODLE_405_STABLE/lib/filelib.php)

**修正:** 編集開始時に既存ファイルを含むdraftを準備し、同一draftへ追加・削除を適用して集合全体を保存する。必要APIが利用できなければWebViewへ誘導。「追加」を無断の「置換」として実装しない。

**完了条件:** A保存→B追加でA/Bが残る。同名ファイル・複数ファイル・削除・最大件数・サイズ上限・途中失敗を検証。F01単独でリリースしない。

### F04 / P1 — warning応答を提出成功と扱う【契約確認】

**根拠:** [MoodleRepository.kt:134](C:/Users/2omur/development/better_moodle_app/app/src/main/java/dev/rits/bettermoodle/data/MoodleRepository.kt:134) 以降の書き込み処理は `client.call` の応答を破棄しています。開始APIは `warnings` フィールド、保存・最終提出はwarning配列を返せます。HTTP成功やexception不在は提出成功を保証しません。[Moodle保存・提出実装](https://github.com/moodle/moodle/blob/MOODLE_405_STABLE/mod/assign/externallib.php)

**動作への影響:** 締切到達などで保存されなくても `runCatching.isSuccess` になり、理由を表示せず再読込します。

**修正:** 操作ごとのレスポンス型で警告・失敗を判定。入力を保持したままエラー表示し、保存後・最終提出後にサーバーの内容と状態を確認。通信切断で結果不明の場合も、無条件の再送前に状態を照会する。

**完了条件:** HTTP 200のwarning、空の成功応答、invalidparameter、保存後の応答切断を区別し、未保存内容を失わない。

### F05 / P1 — 未保存文章の消失・古い内容の最終提出【コード確認】

**根拠:** [AssignmentScreen.kt:96](C:/Users/2omur/development/better_moodle_app/app/src/main/java/dev/rits/bettermoodle/ui/AssignmentScreen.kt:96) は再読込で `onlineText` をサーバー値に置き換えます。戻る・再読込に変更確認がなく、ファイル保存後も再読込します。[同:282](C:/Users/2omur/development/better_moodle_app/app/src/main/java/dev/rits/bettermoodle/ui/AssignmentScreen.kt:282) の最終提出は編集中文字列を保存しません。

**再現条件:** 文章を編集後に再読込すると消失。ファイル追加後にも消失する経路があります。保存済み下書きを編集して保存前に最終提出すると、F01修正後には古い保存内容を提出します。

**修正:** 課題IDごとの編集状態・dirty状態・保存状態をViewModel等へ分離。未保存の離脱・再読込を確認し、最終提出は保存成功を前提にする。送信中の再読込・重複操作を抑止し、プロセス再生成時の草稿復元方針も決める。

**完了条件:** 編集→戻る／更新／ファイル追加／アプリ再生成で草稿が不意に失われず、最終提出対象を確認できる。

### F06 / P2 — プレーンテキスト入力をHTMLとして保存する【コード確認】

**根拠:** [AssignmentScreen.kt:192](C:/Users/2omur/development/better_moodle_app/app/src/main/java/dev/rits/bettermoodle/ui/AssignmentScreen.kt:192) の通常入力欄に既存HTMLをそのまま設定し、[MoodleRepository.kt:143](C:/Users/2omur/development/better_moodle_app/app/src/main/java/dev/rits/bettermoodle/data/MoodleRepository.kt:143) は `format=1` で送っています。

**影響:** 既存のタグが編集欄へ露出し、入力した改行や `<...>` が意図どおり表示されません。F01修正後のデータ品質問題です。また最終提出の確認はチェックボックスだけで、取得済み `submissionstatement` の本文を表示していません。

**修正・完了条件:** プレーンテキスト方式なら特殊文字のエスケープと改行変換を明示する。既存リッチテキストを無断に簡略化しない。書式を保持できないケースはWeb編集へ誘導。日本語・改行・`<`・`&`・既存HTMLと、確認文本文の表示を検証。

### F07 / P2 — 更新失敗が隠され、古い締切を最新と誤認する【コード確認】

**根拠:** [LoadState.kt:32](C:/Users/2omur/development/better_moodle_app/app/src/main/java/dev/rits/bettermoodle/ui/LoadState.kt:32)。キャッシュがある場合、ロード失敗はエラー情報を捨てて `UiState.Success(cached)` になります。

**再現条件:** 締切を表示→オフライン→更新。古い表示が残るだけで失敗が分かりません。キャッシュ時刻・TTL・復帰時更新もありません。

**修正・完了条件:** 最終成功日時、更新中、更新失敗、古いデータ表示中を別々に保持して表示。キャッシュは残しつつ再試行可能にする。オフラインで「更新成功」と誤認させない。

### F08 / P2 — 認証とログアウトが一貫したセッション境界になっていない【要再現】

**根拠:** [App.kt:70](C:/Users/2omur/development/better_moodle_app/app/src/main/java/dev/rits/bettermoodle/App.kt:70) のtokenキャッシュ更新と、[MainActivity.kt:116](C:/Users/2omur/development/better_moodle_app/app/src/main/java/dev/rits/bettermoodle/MainActivity.kt:116) の画面切替は別々のFlow購読です。画面が認証済みになってからクライアントのtokenが更新される順序もあり得ます。またinvalidtokenコールバックは、その失敗が現在のtokenのものか検証せず全認証を消します。

**競合シナリオ:** 起動・再ログイン直後の最初の取得がnotokenで失敗。旧リクエストの遅延エラーが新しいログインを消去。バックグラウンドWorkerはログアウト時にキャンセル・セッション再確認されず、取得済み旧アカウントの締切を後から通知する経路も残ります。

**追加根拠:** [App.kt:105](C:/Users/2omur/development/better_moodle_app/app/src/main/java/dev/rits/bettermoodle/App.kt:105) は非同期のCookie削除完了を待たず終了するため、直後の再読込との競合も検証が必要です。

**修正・完了条件:** tokenとセッション世代を単一の認証状態に統合。リクエスト開始時の世代が一致する場合だけ認証を消去。ログアウトで通信・Worker・キャッシュ・既存通知を整理し、Cookie削除完了を待つ。遅延レスポンス、A→Bアカウント切替、Worker実行中のログアウトを制御可能なテストで検証。

### F09 / P2 — 一覧の取得上限・対象範囲がUIに表れない【コード確認】

**根拠:** [MoodleRepository.kt:24](C:/Users/2omur/development/better_moodle_app/app/src/main/java/dev/rits/bettermoodle/data/MoodleRepository.kt:24) のイベント取得は最大3ページ×50件で終了。締切への絞り込みは取得後なので、対象外イベントでも枠を消費します。[同:49](C:/Users/2omur/development/better_moodle_app/app/src/main/java/dev/rits/bettermoodle/data/MoodleRepository.kt:49) の通知は常に先頭50件。追加取得UIがありません。

**影響:** 151件目以降のイベント、51件目以降の通知を見られません。取得の打ち切りを「全件」と区別できません。また「すべてのコース」は実際には `classification=inprogress` のみで、過去・未来のコースを除外しています。

**修正・完了条件:** ページ状態と `hasMore` / 打ち切り状態を返す。イベントカーソルの未進行を検出し、通知に追加読込、コースに期間フィルターを設ける。151件以上・51件以上・重複／未進行カーソル・過去コースを検証。Workerは24時間の対象範囲を取り切る方針を明示する。

### F10 / P2 — 通知から対象へ移動できず、既読も同期されない【コード確認】

**根拠:** [DeadlineWorker.kt:52](C:/Users/2omur/development/better_moodle_app/app/src/main/java/dev/rits/bettermoodle/work/DeadlineWorker.kt:52) のIntentはMainActivityを起動するだけで、イベントURLや課題IDを渡していません。MainActivityにも通知宛先の処理がありません。[NotificationsScreen.kt:85](C:/Users/2omur/development/better_moodle_app/app/src/main/java/dev/rits/bettermoodle/ui/NotificationsScreen.kt:85) は表示・遷移だけで既読APIを呼びません。

**影響:** 締切通知を押しても対象課題が開かず、アプリで読んだMoodle通知は未読のままです。通知済み判定がイベントIDだけなので、同じイベントの締切変更を再通知できません。

**修正・完了条件:** 安全に検証した宛先をIntentへ格納し、cold start・既存Activityへの `onNewIntent`・ログイン後の遷移を統一。既読APIの成功後に一覧へ反映。再通知はイベントIDと締切時刻で判定。通知権限・チャンネル無効時を含め、実端末で確認する。

### F11 / P2 — PDF等の「他のアプリで開く」が共有に誤って迂回する【Lint・仕様確認】

**根拠:** [DownloadShareDialog.kt:225](C:/Users/2omur/development/better_moodle_app/app/src/main/java/dev/rits/bettermoodle/ui/DownloadShareDialog.kt:225) の `queryIntentActivities` に対するqueries宣言がManifestにありません。Lintも `QueryPermissionsNeeded` を報告しています。

**条件と影響:** Android 11以降でビューアが照会結果から隠れると、起動可能なのにACTION_VIEWを試さずACTION_SENDへ進みます。ビューアが共有先として出るとは限りません。[Android公式説明](https://developer.android.com/training/package-visibility/use-cases)

**修正・完了条件:** ACTION_VIEWを実行し `ActivityNotFoundException` 時だけ共有へフォールバックするか、必要なqueriesを宣言。対応ビューアあり・なしの端末で確認。Custom Tab起動失敗も例外を捕捉して利用者へ知らせる。

### F12 / P2 — ネイティブ表示で欠けた教材・未対応課題の逃げ道が不足【コード確認】

**根拠:** [HtmlText.kt:106](C:/Users/2omur/development/better_moodle_app/app/src/main/java/dev/rits/bettermoodle/ui/HtmlText.kt:106) は画像を `[画像]` にします。課題・フォーラムなどの説明画像を閲覧できず、[AssignmentModels.kt:20](C:/Users/2omur/development/better_moodle_app/app/src/main/java/dev/rits/bettermoodle/data/AssignmentModels.kt:20) は課題の別添説明ファイルをモデル化していません。課題画面は取得失敗時にも再試行のみです。

**影響:** 問題文が画像だけの課題、別添指示書、Web Service未対応の課題で必要情報へ到達できません。ページ画面は別実装で画像を表示しますが、pluginfile以外の画像やiframe等は再現できません。

**修正・完了条件:** 課題・フォーラム・ページに常時使える「Moodleで開く」を用意。添付DTOと安全な画像表示を共通化し、対応外HTMLは省略を通知する。画像のみの説明、別添PDF、外部埋込、API利用不可のケースで原文へ到達できることを確認。

### F13 / P2 — シラバスの通信失敗を「見つからない」と表示する【コード確認】

**根拠:** [SyllabusRepository.kt:131](C:/Users/2omur/development/better_moodle_app/app/src/main/java/dev/rits/bettermoodle/data/SyllabusRepository.kt:131) の `resolveUrl` がNetworkError・InvalidResponse・NoResultsをすべてnullへ変換します。SyllabusScreenの授業コード検索はnullを「見つかりませんでした」とし、CourseScreenのシラバスボタンはnullなら無反応です。

**修正・完了条件:** URL解決も成功・0件・通信失敗・仕様変更を保持した結果型にする。再試行と公式検索サイトへの導線を表示。オフライン・HTTPエラー・不正JSON・0件・成功を区別する。

**関連保守:** FWUIDの固定値は将来の変更リスクであり、現在動かないとは断定しません。仕様変更時の再取得手段を検討。[SessionStore.kt:162](C:/Users/2omur/development/better_moodle_app/app/src/main/java/dev/rits/bettermoodle/data/SessionStore.kt:162) のキャッシュread-modify-writeをedit内に移し、同時解決で更新を失わないようにする。

### F14 / P2 — 通信キャンセル時のリソース解放が不完全【コード・ライブラリ契約確認】

**根拠:** [MoodleClient.kt:104](C:/Users/2omur/development/better_moodle_app/app/src/main/java/dev/rits/bettermoodle/data/MoodleClient.kt:104) は `resume(response)` が例外を投げた場合だけcloseします。継続がキャンセル済みの場合はresumeが無視されることがあり、またresume後・実処理再開前のキャンセルでもResponseが消費されないため、このclose経路では不足します。[kotlinx.coroutines公式仕様](https://github.com/Kotlin/kotlinx.coroutines/blob/1.9.0/kotlinx-coroutines-core/common/src/CancellableContinuation.kt)

**関連:** upload、シラバス、プレビューはブロッキングexecuteを使用。画面を閉じても通信中断が即座に伝わらない処理があります。課題のファイルコピーでは、コピー途中に失敗するとtmp削除用finallyへ到達しません。

**修正・完了条件:** 共通のキャンセル対応HTTP層を用意し、resumeのキャンセルハンドラで未受領Responseを閉じる。CancellationExceptionを握り潰さず再送出。一時ファイル作成から所有権・finallyを明確にする。遅延サーバー、受信直前のキャンセル、コピー失敗で接続・ファイルが残らないことを検証。

### F15 / P2 — WebViewの終了・失敗・戻る操作が揃っていない【コード確認・端末要検証】

**根拠:** [LoginScreen.kt:132](C:/Users/2omur/development/better_moodle_app/app/src/main/java/dev/rits/bettermoodle/ui/LoginScreen.kt:132)、[MoodleWebScreen.kt:109](C:/Users/2omur/development/better_moodle_app/app/src/main/java/dev/rits/bettermoodle/ui/MoodleWebScreen.kt:109)、[PortalScreen.kt:44](C:/Users/2omur/development/better_moodle_app/app/src/main/java/dev/rits/bettermoodle/ui/PortalScreen.kt:44) に明示的なWebView破棄処理がありません。ログイン画面は戻るハンドラなし、ポーリングは10分で黙って終了。Moodle/Portalはmain-frame通信エラー用の復旧UIがありません。

**影響:** SSO中に戻るとログインの入口へ戻れない、失敗後に再試行しにくい、画面切替後にもWebViewの処理が残る可能性があります。ログイン画面とMoodle/PortalではSSO許可ホストの方針も違い、大学側の経由ホスト追加への耐性が一致しません。

**修正・完了条件:** WebView所有・破棄、Back操作、進捗、HTTP/通信エラー、再試行、SSOタイムアウトを共通化。Cookie保持とView破棄を区別する。SSO成功・取消・遅延・オフライン・MFA・タブ往復を実機で確認する。

### F16 / P3 — テストが実際の提出契約・画面・端末動作を覆っていない【構成確認】

**根拠:** [app/build.gradle.kts:58](C:/Users/2omur/development/better_moodle_app/app/build.gradle.kts:58) と17テストファイル。現在のテストはパーサー、ルーティング、表示モデル等が中心で、提出HTTP契約や画面操作のテストがありません。リポジトリにはGitHub Actionsの定義もありません。

**影響:** 100テストが成功していてもF01/F02を検出できません。既存のActionEventPagingTestは最大3ページという現仕様を確認しますが、利用者に必要な全件取得を保証しません。

**修正・完了条件:** 以下の実施順に合わせ、MockWebServer等のHTTP契約テスト、草稿保護の状態テスト、重要画面のCompose/instrumentationテスト、テスト用Moodleでの結合テストを追加。CIでビルド・単体テスト・Lintを自動実行し、各P1が最低1つの回帰テストで守られる状態にする。

## 追加の改善候補・実機確認項目

以下は上記P1より後に扱います。コード上の改善余地と、実際に発生した障害を混同しないため別枠とします。

| 対象 | 観察・確認事項 | 方針 |
|---|---|---|
| 時間割 | 成功表示中の手動更新導線がない。空の時間割・ブロック不在・解析不能を区別できない。実HTMLでの検証はREADMEでも未完了 | 更新UIと状態区別、匿名化した実HTML fixture、rowspan/colspan・休講期・土日の検証 |
| URL検証 | UrlPolicyはrawQueryのパラメータ名を比較し、percent encodingを正規化しない | エンコードされた秘密パラメータ名、userinfo、ポート、相対URLのテスト。方針を統一してトークンを新たに外部へ付ける経路を作らない |
| 課題の個別設定 | グループ提出、個別期限延長、制限時間、複数提出方式のモデル化が不足 | 実応答を確認し、未対応状態をWebへ誘導。一般課題の値だけで全課題を判定しない |
| 閲覧完了 | native page/PDF取得では閲覧完了APIを呼ばない | 閲覧完了を条件とする教材・次の活動の解放をテストし、該当するmoduleのview APIを実装 |
| フォーラム | 内部の選択状態で一覧→投稿を切り替えるが、システムBackは選択解除に対応していない | 上部戻るとシステムBackの動作を一致。フォーラム添付ファイルも原文への導線を確保 |
| PDF | 40ページ上限はUIで通知済み。縮尺下限0.1は極端な寸法で4Mピクセル上限を超え得る | 外部ビューア導線を保ち、巨大ページの寸法検証・厳密なpixel上限・キャンセル時Bitmap解放を追加 |
| 保存ファイル | downloadsは24時間経過後の次回操作時に清掃。ログアウトで削除しない | アカウント切替・共有URI寿命を考慮して保持・削除方針を明示 |
| Android対応 | Lintにバックアップ規則、SDK、アイコン等の警告 | リリース条件に合わせて修正。依存は互換性を確認しながら段階更新し、「最新版へ一括更新」はしない |
| ドキュメント | READMEの機能・API一覧が現在のネイティブ画面を網羅していない。ROADMAPの「Android安定化」が小さく見積もられている | 本計画のP1完了条件をROADMAP Phase 0へ反映し、実対応・未対応・未検証を明記 |

## 推奨する実施順序

| 段階 | 作業 | 対応項目 | 完了条件 |
|---|---|---|---|
| 0. 検証基盤 | 現状100テストをCIへ載せ、HTTPクライアント・Clock・Sessionを差し替え可能にする。匿名fixtureを用意 | F16 | ビルド・テスト・LintがCIで再現し、提出契約の失敗をテストで再現できる |
| 1. 提出フローの修復 | 引数、通常/時間制限付き課題、warning、既存ファイル保持をまとめて修正。必要ならネイティブ提出をWebへ誘導する暫定経路を先行 | F01–F04、F12 | 通常課題で入力→保存→再取得→最終提出が成立。既存A＋追加Bを保持。warningを成功扱いしない |
| 2. 提出内容の保護 | 草稿・dirty・送信状態、HTML/テキスト契約、提出確認文、結果不明時の照会 | F05–F06 | 再読込・戻る・再生成・通信断で入力を失わず、表示内容と最終提出内容が一致 |
| 3. データ・セッションの整合性 | 更新失敗表示、認証世代、ログアウト処理、全件取得/追加取得、通知遷移・既読 | F07–F10 | 古いデータを明示し、旧セッションが新セッションに干渉しない。通知から正しい対象へ遷移 |
| 4. 閲覧・通信の安定化 | ファイル外部起動、欠けた教材のWeb表示、シラバスエラー区別、キャンセル・WebView管理 | F11–F15 | 未対応・失敗時にも原文へ到達し、通信中断とリソース解放が検証される |
| 5. 配布・移行準備 | Android端末で受入確認、残るLint、README/ROADMAP更新、その後KMP分割 | 追加項目 | 以下の実機チェックリストを通過し、未対応機能が明記されている |

段階1は相互依存があります。F01の引数修正だけを配布すると、F03の既存ファイル消失やF05の古い文章提出が有効になるため、まとめて完了判定します。クラスの大規模分割より、動作契約と回帰テストを先に固定します。

## 受入テストのチェックリスト

- [ ] 通常課題: 未提出から編集、文章保存、ファイル保存、最終提出、再取得で一致。
- [ ] 提出物保護: 既存A＋追加B、同名追加、文章＋ファイルの併用、warning、締切到達、送信途中切断。
- [ ] 草稿: 戻る、更新、ファイル選択の取消、画面再生成、バックグラウンド復帰。
- [ ] 制限時間付き・グループ・延長あり課題: 対応を確認するかWebへ確実に誘導。
- [ ] SSO: 初回、再起動、期限切れ、MFA、取消、タイムアウト、端末の戻る操作。
- [ ] 認証競合: 起動直後の同時取得、旧tokenの遅延invalidtoken、Worker中ログアウト、別アカウントで再ログイン。
- [ ] 一覧: 151件以上のイベント、51件以上の通知、過去・未来コース、更新失敗中のキャッシュ表示。
- [ ] 通知: 許可・拒否・チャンネル無効、アプリ終了中/起動中のタップ、締切変更、既読同期。
- [ ] 教材: 画像だけの課題説明、別添PDF、ページ画像、フォーラム添付、41ページ以上PDF、巨大ページ、外部ビューアなし。
- [ ] シラバス: コード/キーワード検索、0件、オフライン、HTTPエラー、API応答変更、年度境界。
- [ ] Android UI: minSdk側とtargetSdk以降、縦横・文字拡大・小画面、時間割の実HTML。

大学環境固有の契約確認は、匿名化した読み取り応答とテスト用課題から進めます。実際の履修課題へテスト提出することを、この計画の前提にはしません。

## 対応状況 (2026-09-22、FOLLOWUP_REVIEW.md / SECOND〜FIFTH_FOLLOWUP_AUDIT.md 反映後)

「済」を一括りにせず、実装 / 単体 (JVM) / 結合 (テスト用Moodle) / 実機 の各段階で分ける。
結合・実機は本日時点ですべて未実施。詳細な残件と再現テストの対応は [FOLLOWUP_REVIEW.md](FOLLOWUP_REVIEW.md)、[SECOND_FOLLOWUP_AUDIT.md](SECOND_FOLLOWUP_AUDIT.md)、[THIRD_FOLLOWUP_AUDIT.md](THIRD_FOLLOWUP_AUDIT.md)、[FOURTH_FOLLOWUP_AUDIT.md](FOURTH_FOLLOWUP_AUDIT.md)、[FIFTH_FOLLOWUP_AUDIT.md](FIFTH_FOLLOWUP_AUDIT.md) の末尾を参照。

| 項目 | 実装 | 単体 | 結合 | 実機 | 対応内容 / 主な回帰テスト |
|---|---|---|---|---|---|
| F01 | 済 | 済 | 未 | 未 | 保存・最終提出は `assignmentid`、取得・開始は `assignid`。`AssignmentSubmissionContractTest` (MockWebServer で本文検証) |
| F02 | 済 | 済 | 未 | 未 | 通常課題は `canedit` で未提出から編集、`Start` は `timelimit>0` かつ未開始のみ。グループ提出は Web へ。`AssignmentActionRulesTest` |
| F03 | 済 | 済 | 未 | 未 | `(filepath, filename)` で識別し、空 draft → 既存を再取得 (サイズ・HTML検証) → 同じパスへ再アップロード → 追加。保存後は集合+サイズを照合、変更前に競合検出。`AssignmentEditorTest`, `AssignmentFileDraftHttpTest` (HTTP 経由) |
| F04 | 済 | 済 | 未 | 未 | warning 配列の契約を厳密化 (`callForWarningList`)。書き込みは送信開始から `PendingWrite` を所有し、適用確認または明示的な拒否でのみ解除。応答消失・照会失敗・「照会成功だが未反映」はすべて保持して再送しない。`ResponseContractTest`, `AssignmentEditorTest` (応答×照会の 6 通り) |
| F05 | 済 | 済 | 未 | 未 | `EditorPhase` と `PendingWrite` を状態に持つ。保存前にサーバー本文を再照会し外部変更 (`textConflict`) を検出、未編集なら再読込で草稿も追随。草稿・基準値・競合状態・未確認の書き込み・アカウント識別を `AssignmentDraftPersistence` 経由で送信前に保存し復元 (保存形式はバージョン付きで旧形式から移行。本人確認できない草稿は別領域へ退避し、本文を見せずに採用/破棄を選ばせる)。ファイルコピーも排他区間。書き込みは NonCancellable。Compose の画面テストは未 |
| F06 | 済 | 済 | 未 | 未 | 属性付き (style/class/align 等) も書式ありと判定して Web 編集へ。空白規則を明文化し比較は可逆変換の厳密一致。`OnlineTextFormatTest` |
| F07 | 済 | — | 未 | 未 | `staleError`/取得時刻。バナーは 0 件表示より外側 (締切・通知・コース・シラバス・フォーラム投稿)。Compose テストは未 |
| F08 | 済 | 済 | 未 | 未 | `AuthSession` がログイン/ログアウト/認証エラー/同期を Mutex 下で直列化。ログアウトはアプリ scope (NonCancellable) が所有し後片付け後に LoggedOut。`AuthSessionTest` (遅延・競合) |
| F09 | 済 | 済 | 未 | 未 | `End / More / Incomplete(reason)` を区別。満杯ページのカーソル不在・循環は不完全。Worker は不完全時に通知済みを削らず success。更新世代で時刻・追加ページを管理。`RepositoryPagingTest` |
| F10 | 済 | 済 | 未 | 未 | 既読同期は `NotificationReadSync` (アプリ scope、世代チェック、失敗表示)。通知宛先は消費済みキーを DataStore に持ち再生成後も二重遷移しない。端末試験は未 |
| F11 | 済 | — | — | 未 | ACTION_VIEW 直接試行 → 例外時のみ共有。Custom Tab 失敗を通知 |
| F12 | 済 | 済 | 未 | 未 | 課題・フォーラム一覧・投稿・ページに常時「Moodleで開く」。拡張子で画像判定 (`ResponseContractTest` T07)。画像だけの説明も表示分岐 (`htmlHasRenderableContent`) を通る。画像リンク→プレビューの画面テストは未 |
| F13 | 済 | 済 | 未 | 未 | `SyllabusResolveResult`。不正構造・全レコード不正は InvalidResponse、真の 0 件だけ NoResults。`SyllabusResponseContractTest` |
| F14 | 済 | 済 | 未 | 未 | `Call.useResponse` でヘッダ待ち〜本文消費〜close を一つのキャンセル区間に。`MoodleClient`・Syllabus・共有ダウンロード・SSOプローブ・画像/テキスト/PDFプレビューの全経路を移行。`HttpCancellationTest` (T05、ヘッダ前、本文停止、ダウンロード中断) |
| F15 | 部分 | — | — | 未 | `onRelease` 破棄、main frame エラー UI、SSO 戻る/タイムアウト。SSO 許可ホスト方針の統一と MFA・タブ往復は実機で要確認 |
| F16 | 部分 | 済 | 未 | 未 | GitHub Actions 定義 (GitHub 上での実行実績は未)。JVM テスト 225 件 (監査プローブ 21 件も成功)。Compose/instrumentation・結合テストは未 |
