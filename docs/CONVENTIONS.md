# 実装の規約

コードを読まないと分からないルールとその理由。パスは `app/src/main/java/dev/rits/bettermoodle/` からの相対。括弧内の ID は [docs/audits/](audits/) の指摘番号。

## 構成

| 層 | 場所 | 規則 |
|---|---|---|
| DI | `App.kt` の `AppContainer` | 手動 DI。画面は `container` を引数で受ける |
| 画面 | `ui/*Screen.kt` | Repository を直接呼ぶ。読み込みは `rememberLoadable`。ViewModel は課題画面だけ |
| 遷移 | `MainActivity.kt` | ルートはすべてここに定義する |
| 純ロジック | `data/` | Android API に依存させない。例外は `SessionStore`、`LocalPreferences`、`KeystoreCrypto` |
| 共通 UI | `ui/Common.kt`、`ui/Motif.kt`、`ui/Formats.kt` | 新しく作る前にここを探す |

- `data/` を Android から離しておくのは、JVM テストと KMP 移行 ([ROADMAP.md](../ROADMAP.md)) のため
- I/O は interface で差し替える: `AuthTokenStore`、`AssignmentGateway`、`DraftStateStore`、`DraftCodec`、`SyllabusUrlCache`
- 現在時刻は引数で受ける (`nowEpochSeconds`、`today`)。テストで固定するため
- UI 文言は Kotlin に直接書く。`strings.xml` はアプリ名だけ

## 認証とセッション

- ログイン、ログアウト、認証エラーは `data/AuthSession.kt` だけを通す
- 利用者のログアウトは `AppContainer.logout()`。下書きの削除を含む
- 画面の切り替えは `authState` を見る。`tokenFlow` を見ると、最初の通信が `notoken` で失敗することがある
- `LoggedInRoot` は `key(generation)` で作り直される。画面スタックと `remember` はセッションをまたがない
- 認証エラーにするのは `invalidtoken` だけ。`AUTH_ERROR_CODES` を広げない。通信失敗や権限エラーでトークンを消さないため
- 画面の寿命を超えて完了させる処理は `appScope` で動かす (ログアウト、既読同期)

非同期の結果をセッションに適用するとき:

| 場面 | 使うもの |
|---|---|
| 結果を適用する直前の確認 | `authSession.isCurrent(generation)` |
| 通知の発行や保存など、セッションに紐づく副作用 | `authSession.withCurrentSession(generation)` |
| 予約してから送る要求 | 予約時点のトークンを `tokenOverride` で渡す (S03) |

セッションに紐づく状態を足したら、後片付けも足す:

| 保存先 | 追加先 |
|---|---|
| メモリ | `AppContainer.clearSessionArtifacts()` |
| DataStore | `SessionStore.clearAuthTokens()`。キーを明示的に列挙している。`logout()` は全消去 |

課題の下書きは明示的なログアウトでだけ消す。`invalidtoken` では残し、同じ利用者の再ログインで復元する (G02)。

## 通信

- OkHttp クライアントは `HttpClients.shared` か、その `newBuilder()` を使う (G11)
- 呼び出しは `Call.useResponse`。`execute()` はキャンセルが効かない
- ファイル取得は `MoodleClient.fetchFile` か `downloadToFile` を通す。HTML やエラー JSON をファイルとして扱わないため (G12)
- `CancellationException` を握りつぶさない (G13)。`runCatching` と `catch (e: Exception)` はキャンセルも捕まえるので、先に再送出する
- 共有クライアントに Cookie を持たせない。シラバス API は Cookie があると `invalid_csrf` になる
- ログは `BuildConfig.DEBUG` の中でだけ出す。URL は scheme、host、path まで。トークンとクエリは出さない

```kotlin
try {
    load()
} catch (e: CancellationException) {
    throw e
} catch (e: Exception) {
    showError(e)
}
```

## Moodle Web Service

- 公式定義で確認できないパラメータを送らない。forum の `sortorder` で `invalidparameter` になった前例がある (199c101)
- パラメータは `MoodleRepository` の companion に `xxxParams()` として置き、テストで固定する
- Moodle はエラーを HTTP 200 の `{"exception": ...}` で返す。処理は `MoodleClient` に任せる
- 書き込みは `callForWarningList` を使い、`warnings` を失敗として扱う (F04)。形式が不正な応答も成功にしない
- 一覧は「続きなし」「続きあり」「不完全」を区別する (`ActionEventPage`、`DeadlineWindow`)。打ち切りを全件に見せない
- `tool_mobile_get_autologin_key` はサーバー側で 6 分に 1 回。取れなければ元の URL で開く
- 小テストの受験はアプリ内 WebView で行う。`start_attempt` などの受験 API を使わない

ID の取り違えに注意する:

| 名前 | 中身 | 使う場所 |
|---|---|---|
| `courseId` | コース | `core_course_get_contents` |
| `moduleId`、`courseModuleId`、`cmid` | コースモジュール。URL の `?id=` | `core_course_get_course_module` |
| `assignId`、`forumInstanceId`、`quizInstanceId` | インスタンス | `mod_*` の個別 API |

課題 API は引数名も違う: 取得と開始は `assignid`、保存と最終提出は `assignmentid` (F01)。

時間割はダッシュボードのカスタムブロック `rutime_table` の HTML を jsoup で解析する。コース名は `12345:科目名 §クラス` の形式で、先頭 5 桁が授業コード。

## SSO

- コールバックは `urlscheme` を指定しても `moodlemobile://` で届く。サイト側の強制設定のため、`SsoLogin` は両方のスキームを受ける
- WebView はカスタムスキームへの 302 を通知せず捨てることがある。OkHttp のプローブ `fetchTokenByProbe` を併用する
- プローブと WebView は同じ passport を使う。未ログインの `launch.php` が戻り先を上書きするため、別の値だと署名が合わなくなる (G01)
- トークンの署名は `md5(siteUrl + passport)` で検証する

## シラバス

- Salesforce の Aura API を、認証と Cookie なしで直接呼ぶ
- `fwuid` は Salesforce の更新で変わる。`clientOutOfSync` を受けたら取り直して 1 回だけ再試行する (G05)
- 授業コードを外部サービスへ送らない。外部 resolver をフォールバックに足さない
- 失敗を「見つからない」と混同しない (`SyllabusResolveResult`)

## URL、WebView、遷移

- 受け入れる URL はすべて `data/UrlPolicy.kt` で判定する。外から来た URL は先に `normalizeMoodleUrl` を通す
- ファイル URL へのトークン付与は `appendMoodleToken` だけ。秘密パラメータ付きの URL は WebView にも外部アプリにも渡らないようになっている
- ルート引数は `Uri.encode` する
- 利用者の操作からの遷移は `navigateFromUser`。遷移中の二重タップを捨てるため
- 通知からの遷移では `navigateFromUser` を使わない。起動直後は RESUMED でないので宛先が捨てられる
- 通知タップの宛先は「遷移、消費済みの記録、クリア」の順に処理する (G10)
- WebView は `releaseWebView` で破棄し、main frame のエラーは `WebViewErrorPanel` で出す

画面から URL を開く入口は `LoggedInRoot` の `openUrl` 1 か所 (G10)。振り分けは次の順:

1. pluginfile のプレビュー
2. ネイティブ画面
3. アプリ内の Moodle WebView
4. Custom Tab
5. 「このリンクは開けません」

ネイティブ画面には「Moodleで開く」の導線を置く。未対応の活動はエラーにせず、コース画面か WebView へ送る。

## 課題提出

最も壊してはいけない領域。変更前に `data/AssignmentEditor.kt` の状態機械のコメントを読む。

- 結果の分からない書き込み (`PendingWrite`) を再送しない。自動で解除しない。解除できるのは、適用を確認できたときと、最新状態を取得したうえで利用者が明示したときだけ
- `pendingWrite` は `setPending` でだけ変える。送信より前に `onCheckpoint` で保存され、プロセスが落ちても復元できる
- 新しい書き込みは `runExclusive` と `performWrite` を通す
- 保存前にサーバーを再照会する。基準値 `savedText` と違えば上書きせず止める (`textConflict`)
- 書式付きの本文はアプリから上書きせず、Moodle の編集へ誘導する
- ファイル提出の保存は置き換えになる。draft には「既存 + 追加」の全体を入れる。既存ファイルは `(filepath, filename)` で識別し、保存後に照合する
- ファイル名は `AssignmentFileDraft.cleanFilename` で Moodle の `PARAM_FILE` と同じ規則に整える (G03)
- `mod_assign_start_submission` は制限時間付きの課題でだけ呼ぶ
- 下書きは `noBackupFilesDir` に暗号化して保存する。所有者は Moodle の userid で判定する (G02)
- 所有者を確認できない下書きの本文を画面に出さない (W03)

## 締切通知

- `Result.retry()` は取得の例外でだけ使う。ページ上限やカーソル異常は `Result.success()` で終え、次回カーソルから続ける
- 通知済み集合を絞り込めるのは、先頭から取り切った実行だけ (`shouldPruneNotifiedKeys`)
- 通知の発行と通知済みの保存は `withCurrentSession` の中で行う

## 保存データの互換性

インストール済みの端末にデータが残っている。形式を変えるなら、古い形式を読む処理も書く。

| 対象 | 変えてはいけないもの |
|---|---|
| `ui/theme/Theme.kt` の授業カラー | 並び順。インデックスで保存している |
| `CourseColors.key` | `i:`、`c:`、`t:` の形式。ID を優先する順序 |
| `AssignmentDraftPersistence` | キー名。形式を変えたら `FORMAT_VERSION` を上げて移行する |
| `PendingWrite` | 直列化の形 |
| `SessionStore.Keys` | キー名 |
| `deadlineNotificationKey` | `id@timesort` の形式 |
| `DeadlineDoneStore.keyOf` | `e:<id>` の形式 |

読めない値は保護する側に倒す。例えば、解釈できない競合フラグは「競合あり」として扱う。

`LoadableMemoryCache` のキーは型と 1 対 1 にする。`"courses"`、`"timetable"`、`"notifications"` は複数の画面で共有している。キャストが無検査なので、同じキーに別の型を入れると使う側で落ちる。

## UI の実装

デザインの正は [DESIGN.md](../DESIGN.md)。YAML が正確な値、本文が使い方。

| 種類 | 使うもの |
|---|---|
| 色 | `AppTheme.colors` |
| 授業の色 | `courseColor(...)` |
| 文字 | `MaterialTheme.typography`。DESIGN.md との対応は `Theme.kt` のコメント |
| 余白 | `Spacing` |
| 角丸 | `Radius` |
| 日時、残り時間の文言 | `ui/Formats.kt` |

- 画面に `Color(0x...)` を書かない。足りない値は DESIGN.md、`Theme.kt` の順に足す
- 読み込みは `rememberLoadable(cacheKey)`。古いデータには `StaleDataBanner` を出す
- 読み込み中は `SkeletonList` か `LoadingBox`。全画面スピナーを使わない
- 授業カラーのキーは `CourseColors.key` で作る。授業コードは入口によって欠けるので、ID を優先する
- 締切の「完了」はアプリ内の扱いで、Moodle の提出とは別物。表示でも区別する
- 不要な補助ラベルや説明文を足さない。レイアウトで伝える
- `enableEdgeToEdge` を有効にしている。Scaffold を持たない画面は自分でシステムバーを避ける
- 装飾は読み上げから外す。アイコンだけのボタンには `contentDescription` を付ける

## テスト

- 置き場所は `app/src/test/java/dev/rits/bettermoodle/`。JUnit4 の JVM テストだけで、計装テスト、Robolectric、Compose UI テストはない
- `android.util.Log` や `android.net.Uri` はテストで例外になる。純ロジックでは `java.net.URI` を使い、`Log` を呼ばない
- UI の分岐は `data/` か `internal fun` へ出してテストする
- `kotlinx-coroutines-test` は入っていない。`runBlocking` を使う
- Moodle の契約は MockWebServer でリクエスト本文まで検証する。`UrlPolicy` は loopback の http だけを許可している
- 時刻は固定し、タイムゾーンは `ZoneId.of("Asia/Tokyo")` にする
- 修正には再発を防ぐテストを足す
- テストクラスは振る舞いで命名する。`*AuditRegressionTest` を増やさない (G17)。メソッド名はバッククォートの英文

## 監査文書

[docs/audits/](audits/) は履歴。当時のコードに対する記録なので、行番号とリンクは古くなっている。

- 新しい監査では未使用の接頭辞を使う。使用済みは F、N、T、R、S、V、W、G
- 判定は「コード確認」「契約確認」「要再現」で書き分ける
- 指摘を直したら、文書末尾の対応状況表にテスト名と未確認事項を書く

## 環境の落とし穴

- JDK は 17。CI と揃える
- `gradlew` は実行権限なし (100644) でコミットされており、CI が `chmod +x` している
- `gradle.properties` の `android.builtInKotlin=false`、`android.newDsl=false` などは AGP 8 の挙動を保つためのもの。新 DSL へ移行するまで外さない
- `build/review-probes/`、`build/audit*/` は git 管理外の監査プローブ。消えても構わないが、他の環境にはない
- `LF will be replaced by CRLF` の警告は `core.autocrlf=true` によるもので、無視してよい
- Bash ツールの長い heredoc では、バックスラッシュや `\r\n` が壊れることがある。編集ツールかスクリプトファイルを使う

## 既存コードの例外

次は規約に合っていない。前例として真似しない。

| 場所 | 内容 |
|---|---|
| `ui/HtmlText.kt` | リンク色の直書き |
| `ui/PdfPreviewScreen.kt` | 背景色の直書き |
| `ui/WebViewSupport.kt` | M3 の `Button` と `.dp` の直書き |
| `*AuditRegressionTest` | 監査の回で命名したテストクラス |
