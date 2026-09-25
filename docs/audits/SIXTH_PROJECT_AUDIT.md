# プロジェクト全体の再精査と修正方針 (第6回)

調査日: 2026-09-25。対象: HEAD `f10f698` と未コミットの作業ツリー (41ファイル変更・新規10ファイル)。アプリ実装・テストは変更していない。

## 結論

過去5回の監査は課題提出フロー (F/R/S/V/W系) に集中しており、その範囲は大きく改善している。今回はそれ以外の経路を中心に全ソースを読み直し、**ログイン・セッション境界・ファイル提出の入力・外部サービス連携**に、実利用で踏みやすい問題を見つけた。

最優先は次の3件。

1. **G01** SSOログイン中のポーリングが別の passport を使い、WebView側のトークン受け取りを失敗させる競合
2. **G02** セッション失効 (invalidtoken) で NavHost ごと破棄され、課題の未保存本文が消える
3. **G03** `&` `'` などを含むファイル名の提出が必ず失敗する

いずれもコード経路から確認したもので、実機・大学環境での再現は未実施。ビルドとテストは今回再実行していない (前回の記録では単体テスト225件成功)。

判定の表記: **コード確認** = 分岐・状態遷移から発生を確認。**契約確認** = Moodle/Salesforce側の既知仕様との照合。**要再現** = 実環境での発生確認が必要。

---

## P1 — ログインできない・入力内容を失う・提出できない

### G01 / P1 — ログイン中のポーリングが Moodle セッションの wantsurl を上書きする【コード確認・要再現】

**根拠:** [LoginScreen.kt:90](app/src/main/java/dev/rits/bettermoodle/ui/LoginScreen.kt:90) の `probeLaunchToken()` は、呼ぶたびに `SsoLogin.newPassport()` で**新しい passport** を作る。一方、WebView は画面の `passport` で launch.php を開き、[LoginScreen.kt](app/src/main/java/dev/rits/bettermoodle/ui/LoginScreen.kt) の `handleTokenUrl` はその passport で署名を検証する。

プローブは WebView の `MoodleSession` Cookie を付けて launch.php を1.5秒ごとに叩く。未ログインの launch.php は `require_login` で `$SESSION->wantsurl` に**自分のURL (プローブの passport 付き)** を保存する。SSO 完了後、Moodle は WebView を wantsurl へ戻すので、WebView に届くトークンURLは**プローブの passport で署名**されている。

**動作への影響:** WebView がトークンURLを先に検出すると md5 検証に失敗し、「ログイン情報を受け取れませんでした」になる。`loginError` が立つとポーリングも止まるため、プローブでの回収もされない。WebView がトークンURLを黙って破棄した場合だけプローブで成功する。結果として、ログインの成否が端末・タイミング依存になる。

**修正:**
- プローブも画面と同じ `passport` を使う (`probeLaunchToken(passport)`)。ログイン前の「前回セッション確認」も同じ値でよい。
- 念のため、この画面で発行した passport の集合を保持し、どれかで検証できれば受理する。
- ポーリング間隔を延ばす (1.5秒 × 最大10分 = 最大400回、大学サーバーへの負荷)。WebView が Moodle ホストへ戻ったときのプローブを主経路にし、定期ポーリングは5秒以上の補助にする。

**完了条件:** プローブと WebView が同じ passport を使うことを単体テストで固定。実機で「SSO完了 → WebViewがトークンURLを受信」「WebViewが破棄 → プローブで回収」の両方が成功する。

### G02 / P1 — セッション失効で課題の未保存本文が消える【コード確認】

**根拠:** [MainActivity.kt:165](app/src/main/java/dev/rits/bettermoodle/MainActivity.kt:165) は `key(a.generation) { LoggedInRoot(...) }`。どこかの通信で `invalidtoken` を受けると `AuthSession.onAuthError` → `LoggedOut` になり、`LoggedInRoot` とその `NavHost` がコンポジションから外れる。課題画面の `AssignmentViewModel` と `SavedStateHandle` (草稿の唯一の保存先) は、再ログイン後の新しい NavHost からは参照されない。

**動作への影響:** 長文を入力中にトークンが失効する (管理者による失効・期限・別端末からの失効など) と、**入力中の本文が警告なしに消える**。前回までに作り込んだ「保留草稿 (orphan)」経路は、同じ SavedStateHandle を再利用するときしか働かないので、この最も起きやすい経路では何も残らない。旧 NavHost の ViewModelStore もアクティビティ終了まで解放されない (リーク)。

**修正:**
- 草稿を SavedStateHandle ではなく、アプリ内の永続ストア (DataStore または Room、キーは `assignId`) に保存する。認証トークンと同じ Keystore 鍵で暗号化し、所有者は **Moodle の userid** (`core_webservice_get_site_info`) で識別する。トークンハッシュではなく userid にすれば、同じ利用者の再ログインを「所有者一致」と判定でき、W03 の設計上の限界も解消する。
- ログアウト時は、未保存の草稿があれば確認ダイアログを出す。invalidtoken による強制ログアウトでは草稿を残し、同じ userid で再ログインしたら復元する。
- 永続ストアに移したら、SavedStateHandle 側の orphan 機構は縮小する (保存先を1か所にする)。

**完了条件:** 入力中に invalidtoken を返すテストで、再ログイン後に同じ本文が復元される。別 userid では本文が表示されない。タスク終了・端末再起動後も残る。

### G03 / P1 — 特定の文字を含むファイル名を提出できない【契約確認】

**根拠:** [MoodleRepository.kt:301](app/src/main/java/dev/rits/bettermoodle/data/MoodleRepository.kt:301) は、upload.php の応答ファイル名が送ったファイル名と異なると `uploadfailed` にする。Moodle の upload.php はファイル名を `clean_param(PARAM_FILE)` で整形し、制御文字と `& < > " \` | ' : \ /` を**削除**する。[AssignmentViewModel.kt:205](app/src/main/java/dev/rits/bettermoodle/ui/AssignmentViewModel.kt:205) は ContentProvider の表示名をそのまま使う。

**動作への影響:** `レポート&資料.pdf` や `it's.docx` のようなファイルを選ぶと、アップロード自体は成功するのに「ファイルの準備に失敗しました: Upload stored a different filename」となり、提出できない。利用者には原因が分からない。

**修正:** アップロード前に PARAM_FILE と同じ規則でファイル名を整形する関数を用意し、整形後の名前で重複判定・アップロード・保存後検証を行う。名前が変わる場合は追加前に「◯◯として提出されます」と表示する。空になった場合は `submission.<拡張子>` にする。

**完了条件:** `&` `'` `:` を含む名前、全角文字、先頭末尾の空白、`.` だけの名前をテストし、整形後の名前で upload→save→verify が通る。

---

## P2 — クラッシュ・機能停止・データ誤認

### G04 / P2 — アプリ scope の例外でアプリが落ちる、またはログイン画面で固まる【コード確認】

**根拠:** [App.kt:84](app/src/main/java/dev/rits/bettermoodle/App.kt:84) の `appScope` には `CoroutineExceptionHandler` がない。[AuthSession.kt:75-90](app/src/main/java/dev/rits/bettermoodle/data/AuthSession.kt:75) の `start()`、`login()`、`logout()` は、DataStore の IOException/CorruptionException や Keystore の例外 (`encryptToken`) をそのまま投げる。`launch` 内の未捕捉例外は Android ではプロセスを終了させる。

**動作への影響:** 端末の Keystore 不調や DataStore 破損があると、起動時またはログイン直後にクラッシュする。`login()` が失敗しても LoginScreen の `tokenDelivered` は true のままなので、クラッシュしなかった場合もボタンが反応しなくなる。

**修正:** `login()` は結果 (成功・失敗理由) を返し、LoginScreen は失敗時に `tokenDelivered` を戻してエラーを表示する。`start()` の collect は `catch` で LoggedOut に倒して記録する。`appScope` に最終防衛の `CoroutineExceptionHandler` を付ける。Keystore の鍵が無効になった場合 (`KeyPermanentlyInvalidatedException` 等) は鍵を作り直し、再ログインを促す。

**完了条件:** `AuthTokenStore` のフェイクで save/read/logout の各例外を注入し、クラッシュせずに状態が LoggedOut かエラー表示になる。

### G05 / P2 — シラバス検索が Salesforce の更新で止まる【契約確認・要再現】

**根拠:** [SyllabusRepository.kt:239](app/src/main/java/dev/rits/bettermoodle/data/SyllabusRepository.kt:239) で Aura の `fwuid` を固定値で持つ。Salesforce の fwuid はプラットフォームのリリース (年3回) で変わり、古い値の要求には `clientOutOfSync` が返る。現在の実装ではこれを `InvalidResponse` として扱うだけ。

**動作への影響:** Salesforce の定期更新のたびに、シラバス検索・コース画面の「シラバス」ボタンが「応答形式が変わった可能性があります」で使えなくなり、アプリの更新を出すまで直らない。

**修正:** `clientOutOfSync` 応答 (または検索ページHTML) から現在の fwuid を取り出して記憶し、1回だけ再試行する。取得できた fwuid は DataStore にキャッシュする。固定値は初期値としてだけ使う。

**完了条件:** clientOutOfSync の応答例を使ったテストで、新しい fwuid で再試行して成功する。再試行は1回に限る。

### G06 / P2 — 巨大ファイルを選ぶと上限確認の前に全体をキャッシュへコピーする【コード確認】

**根拠:** [AssignmentViewModel.kt:206](app/src/main/java/dev/rits/bettermoodle/ui/AssignmentViewModel.kt:206) は、選んだファイルを無制限に `cacheDir` へコピーしてから、Editor で `maxSubmissionSizeBytes` を確認する。

**動作への影響:** 誤って数GBの動画を選ぶと、長時間「ファイルを準備中」のまま端末のストレージを消費し、最後にサイズ超過で失敗する。容量不足で他のキャッシュにも影響する。

**修正:** コピー前に `OpenableColumns.SIZE` で上限を確認し、コピー中も上限を超えた時点で中断する (サイズ不明のプロバイダ対策)。進捗を表示する。

### G07 / P2 — 表示中のビットマップを recycle するため描画時にクラッシュし得る【コード確認・要再現】

**根拠:** [PdfPreviewScreen.kt:297](app/src/main/java/dev/rits/bettermoodle/ui/PdfPreviewScreen.kt:297)、[PageScreen.kt:282](app/src/main/java/dev/rits/bettermoodle/ui/PageScreen.kt:282)、[FilePreviewScreen.kt:114](app/src/main/java/dev/rits/bettermoodle/ui/FilePreviewScreen.kt:114) が `onDispose` で `Bitmap.recycle()` を呼ぶ。Compose は破棄と同じフレームで古い `ImageBitmap` を描画することがあり、「recycled bitmap を描画しようとした」例外になる。PDF は1ページ最大約16MBのビットマップを LazyColumn で次々に作るので、高速スクロールで発生しやすい。

**修正:** 手動の `recycle()` をやめて GC に任せる (API 26以降、ビットマップの画素はネイティブヒープにあり、参照が切れれば解放される)。PDF はページ数分の LRU キャッシュ (合計サイズ上限付き) を置く。

### G08 / P2 — アプリ内 Moodle WebView の SSO 許可ホストが少なく、再ログインで止まり得る【コード確認・要再現】

**根拠:** [UrlPolicy.kt:91](app/src/main/java/dev/rits/bettermoodle/data/UrlPolicy.kt:91) の `isAllowedSsoWebViewUrl` は lms と Microsoft の5ホストだけを許可する。ログイン画面 (全 https を許可) と基準が違う。autologin キーは6分に1回しか取れないため ([MoodleRepository.kt:396](app/src/main/java/dev/rits/bettermoodle/data/MoodleRepository.kt:396))、2回目以降に Moodle 画面を開くと SSO へ飛ぶことがある。MFA 登録・デバイス登録・シームレスSSO (`login.microsoft.com`、`autologon.microsoftazuread-sso.com`、`mysignins.microsoft.com` 等) を経由すると遷移がブロックされる。

**修正:** Moodle WebView でも「SSO 途中の https は許可し、Moodle に戻れなければバナーで外部ブラウザへ誘導」に揃えるか、実機ログで経由ホストを確認して許可リストを更新する。autologin の時刻は失敗時に戻し、ログアウト時にリセットする。

### G09 / P2 — ポータルは戻る操作とタブ切替で状態を失う【コード確認】

**根拠:** [PortalScreen.kt:88](app/src/main/java/dev/rits/bettermoodle/ui/PortalScreen.kt:88) は `onPageFinished` でしか `canGoBack` を更新しない。ポータル (Salesforce Lightning) は history API で画面を切り替えるので、ページ内遷移後も `canGoBack` が false のままになり、戻るキーでポータル内ではなくアプリの前画面へ戻る。また [MainActivity.kt:718](app/src/main/java/dev/rits/bettermoodle/MainActivity.kt:718) の NavHost はタブを離れると WebView を破棄するので、タブを戻るたびにトップから再読込になる。

**修正:** `doUpdateVisitedHistory` で `canGoBack` を更新する。WebView インスタンスをタブの外 (MainTabs レベル) で保持するか、`saveState/restoreState` で履歴を復元する。

### G10 / P2 — 通知タップの宛先を「開く前に」消費済みにする【コード確認】

**根拠:** [MainActivity.kt:205](app/src/main/java/dev/rits/bettermoodle/MainActivity.kt:205) の `openUrl` は `scope.launch` で非同期に `courseModule` の通信を行う。直後の `markDeepLinkConsumed` は通信の完了を待たない。コメントにある「遷移要求 → 記録」の順序は実際には保証されていない。

**動作への影響:** 通信中にプロセスが終了すると、開いていない宛先が消費済みになり、OS の再配達では開かれない。影響は小さいが、コメントと実装が食い違っている。

**修正:** `openUrl` を suspend 関数にして route 解決と navigate を待ってから記録する。LoggedInRoot と MainTabs に重複している `openUrl` を1つにまとめる ([MainActivity.kt:180](app/src/main/java/dev/rits/bettermoodle/MainActivity.kt:180) と [MainActivity.kt:624](app/src/main/java/dev/rits/bettermoodle/MainActivity.kt:624))。

---

## P3 — 保守性・運用・細部

| ID | 内容 | 根拠 | 方針 |
|---|---|---|---|
| G11 | ファイル取得用の OkHttpClient が6個ある (接続プール・スレッドプールも6組)。タイムアウトも統一されていない | `MoodleClient`、`SsoLogin.probeHttp`、`SyllabusRepository`、`PdfPreviewScreen.kt:196`、`DownloadShareDialog.kt:133`、`FilePreviewScreen.kt:151` | 共通クライアントを1つ作り、用途別の差分は `newBuilder()` で派生する |
| G12 | 共有・PDF 用ダウンロードは HTML/JSON のエラー応答を検査しない (PDF は `%PDF` 確認のみ)。認証切れのエラーページを「ファイル」として他アプリへ渡し得る。pluginfile の認証エラーは `onAuthError` にも伝わらない | `DownloadShareDialog.kt` の `downloadSharedFile` | `MoodleClient.downloadToFile` と同じ検査 (HTML拒否・リダイレクト先確認) に統一する |
| G13 | `forumPosts` 内の既読化呼び出しが `runCatching` で CancellationException も握りつぶす | [MoodleRepository.kt:375](app/src/main/java/dev/rits/bettermoodle/data/MoodleRepository.kt:375) | Cancellation を再送出する。既読化は NotificationReadSync と同様にアプリ scope へ |
| G14 | `configChanges` に `screenLayout|smallestScreenSize|uiMode` がない。折りたたみ端末・分割画面・ダークモード切替で Activity が作り直され、SSO 途中の WebView と passport を失う | [AndroidManifest.xml:30](app/src/main/AndroidManifest.xml:30) | ログイン状態を ViewModel に移す (configChanges を増やすより確実) |
| G15 | 通知権限をログイン前・起動のたびに要求する | [MainActivity.kt:98](app/src/main/java/dev/rits/bettermoodle/MainActivity.kt:98) | ログイン後、締切画面などで理由を説明してから1回要求する |
| G16 | `targetSdk = 35`、AGP 8.13 / Kotlin 2.0.21 / Compose BOM 2024.12。Google Play で配布する場合は2026年の target API 要件を満たさない可能性がある | [app/build.gradle.kts:15](app/build.gradle.kts:15) | 安定化後に API 36 と依存を一括更新する (ROADMAP の KMP 移行前に) |
| G17 | 3,000行超の変更が未コミット。監査文書が6個ルートに散在し、テストも `FifthAuditRegressionTest` のように監査回で命名されている | `git status` | 機能単位でコミットする。監査文書は `docs/audits/` へ移し、テストは対象の振る舞いで命名し直す |
| G18 | コース画面の「シラバス」ボタンは解決中の表示がなく連打で並行要求になる。コース画面だけ `StaleDataBanner` がない | `CourseScreen.kt` | 解決中はボタンを無効化する。他画面と同じバナーを出す |

---

## 修正の進め方

1. **G01・G04** (ログイン): 同じ passport の使用、login の失敗通知、例外処理。変更は小さく効果が大きいので最初に行う。
2. **G03・G06** (ファイル提出の入力): ファイル名の整形とサイズの事前確認。提出 API の状態機械には触れず、入口だけを直す。
3. **G02** (草稿の永続化): 保存先を userid 付きの暗号化ストアに移し、SavedStateHandle と orphan 機構を整理する。設計変更を伴うので単独のブランチで行う。
4. **G05・G08・G09** (外部サービス連携): fwuid の自動更新、SSO 許可ホスト、ポータルの戻る操作。実機ログで経由ホストを確認しながら進める。
5. **G07・G10–G18**: 描画・通信基盤の整理と保守性。G11 (HTTP クライアント統一) を先に行うと G12 が簡単になる。

各段階の前に、現在の未コミット変更を機能単位でコミットしておく (G17)。そうすれば、今回の修正と過去の監査対応を区別して差分を確認できる。

## 未確認事項

- 実機での SSO (G01・G08)、Salesforce の clientOutOfSync 応答形式 (G05)、webservice/pluginfile.php の認証エラー応答 (G12) は、大学環境で確認が必要。
- 過去5回の監査で扱った課題提出の状態機械・通知ルーティングは、今回は再検証していない。
