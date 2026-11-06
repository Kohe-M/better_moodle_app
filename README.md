# Moodle+R Companion (better_moodle_app)

立命館大学の **Moodle+R / シラバス / スチューデントポータル** をひとつにまとめた非公式Androidアプリ。

公式Moodleアプリの「時間割が一覧で見えない」「通知がごちゃまぜ」という課題を解決する。

開発のルールは [AGENTS.md](AGENTS.md) と [docs/CONVENTIONS.md](docs/CONVENTIONS.md)、デザインは [DESIGN.md](DESIGN.md)。

## 機能

| タブ | 内容 |
|------|------|
| 今日 | 今と次の授業 (Now Card)、今日の授業、直近の締切。右上から通知へ |
| 時間割 | ダッシュボードの時間割ブロックを解析し、**スクロール不要の全画面グリッド**で表示。今日の曜日をハイライト。コマをタップするとコースへ |
| 課題 | **課題(assign)・小テスト(quiz)等の締切だけ**をまとめた一覧。アプリ内の完了チェック付き |
| コース | 履修コースの検索と一覧。シラバス、スチューデントポータル (アプリ内WebView) への入口。右上から設定へ |

さらに **WorkManager** が6時間ごとに締切をチェックし、24時間以内に迫った課題をローカル通知する
(ページ上限やサーバー応答の異常で 24 時間分を取り切れなかった場合は取得できた分だけ通知し、通知済み記録は削らない)。

## ビルドと実行

Android Studio でこのフォルダを開いて Run するだけ。CLIの場合:

```bash
JAVA_HOME="C:/Program Files/Java/jdk-17" ./gradlew assembleDebug
```

```bash
adb install app/build/outputs/apk/debug/app-debug.apk
```

- **JDK 17 を使う** (CI と同じ)
- AGP 9.4.1 / Gradle 9.6.0 / minSdk 26 / targetSdk 36 / Kotlin 2.2.10 + Jetpack Compose (Material 3)
- DIフレームワークなし (`AppContainer` による手動DI)、DB なし (DataStore、SharedPreferences、暗号化した下書きファイル) の軽量構成

## アーキテクチャと調査結果

### 認証 — 公式アプリと同じSSOトークンフロー

`lms.ritsumei.ac.jp` はモバイルWeb Service有効 (`enablemobilewebservice=1`, `typeoflogin=3` = アプリ内ブラウザSSO) を確認済み。

1. `admin/tool/mobile/launch.php?service=moodle_mobile_app&passport={乱数}&urlscheme=bettermoodle` をアプリ内WebViewで開く
2. 学内SSO (Azure AD / Microsoft学生アカウント) でログイン
3. `moodlemobile://token=BASE64("md5(siteUrl+passport):::wstoken[:::privatetoken]")` へのリダイレクトを捕捉してトークン保存 ([SsoLogin.kt](app/src/main/java/dev/rits/bettermoodle/auth/SsoLogin.kt))。サイト側の設定で `urlscheme` の指定によらず `moodlemobile` で届く。WebView がリダイレクトを捨てる場合に備え、`launch.php` を直接叩くプローブも併用する

以後は `POST /webservice/rest/server.php` (`wstoken` + `wsfunction`) で全データを取得 ([MoodleClient.kt](app/src/main/java/dev/rits/bettermoodle/data/MoodleClient.kt))。

### 使用しているMoodle WS関数

| 関数 | 用途 |
|------|------|
| `core_webservice_get_site_info` | ユーザ情報 |
| `core_block_get_dashboard_blocks` (`returncontents=1`) | **時間割**: カスタムブロック `rutime_table` のHTMLを取得し jsoup で解析 |
| `core_calendar_get_action_events_by_timesort` | **締切**: 公式アプリのタイムラインと同じAPI。`assign`/`quiz` 等でフィルタ |
| `message_popup_get_popup_notifications` | 通知一覧 |
| `core_course_get_enrolled_courses_by_timeline_classification` | コース一覧 (シラバス画面は進行中のみ、「すべてのコース」は進行中/今後/過去/すべてを切替) |
| `core_course_get_contents` / `core_course_get_course_module` | コース内容とURL→ネイティブ画面の解決 |
| `mod_assign_get_assignments` / `mod_assign_get_submission_status` | 課題の表示 (`assignid` 引数) |
| `mod_assign_save_submission` / `mod_assign_submit_for_grading` | 文章・ファイルの保存と最終提出 (**`assignmentid` 引数**。warning応答は失敗扱い) |
| `mod_assign_start_submission` | 制限時間付き課題の計測開始のみ (通常課題では呼ばない) |
| `core_files_get_unused_draft_itemid` + `webservice/upload.php` | ファイル提出のdraft領域を「既存+追加」の全体集合で組み立てる (追加が置換にならないように)。既存は `(filepath, filename)` で識別し同じパスへ再アップロード |
| `mod_quiz_*` / `mod_forum_*` / `mod_page_get_pages_by_courses` | 小テスト・フォーラム・ページ教材のネイティブ表示 |
| `core_message_mark_notification_read` | 通知を開いたときの既読同期 |
| `tool_mobile_get_autologin_key` | WebViewで開くときの自動ログイン |

時間割ブロックのDOM構造 (`table.timetable`, `td.time`, `.subject`) は
[Moodle-Schedule-Extension](https://github.com/mutyuki/Moodle-Schedule-Extension) の解析結果に基づく。
コース名は `12345:科目名 §クラス` 形式で、**先頭5桁が授業コード**。
時限は 1限 9:00–10:35 〜 7限 20:10–21:45 ([Models.kt](app/src/main/java/dev/rits/bettermoodle/data/Models.kt))。

### シラバス (Salesforce Experience Cloud) — 直接API化済み

- `syllabus.ritsumei.ac.jp/syllabus/s/` は **Aura ベースの Salesforce Experience Cloud** (認証不要・公開)。カスタムオブジェクト `R_Syllabus__c`
- シラバス詳細URL: `…/s/r-syllabus/{SalesforceレコードID}/{年度+授業コード}?language=ja` (例: `202610001`)
- 検索API ([SyllabusRepository.kt](app/src/main/java/dev/rits/bettermoodle/data/SyllabusRepository.kt) で直接呼び出し):
  - `POST /syllabus/s/sfsites/aura?r=1&aura.ApexAction.execute=1` (form-urlencoded)
  - descriptor: `aura://ApexActionController/ACTION$execute`
  - Apexクラス: `R_SyllabusPublicPageController`、method: `getSyllabusRecords` (一覧) / `getSyllabusRecordCount` (件数)
  - 検索条件は `params.params.action` に `{lang, keyword, faculty, year, term, week, period, professionalCareer, limits}`
  - 応答: `actions[0].returnValue.returnValue.result[]` に `Id`, `R_SlCourseName__c` ("10001:科目名"形式), 教員名・曜日時限・キャンパス・単位数など
- **認証まわりの実測 (2026-07)**: Cookieを持たないクライアントなら `aura.token="null"`・fwuid任意で成功。
  Cookieありのブラウザセッションではセッション紐付きCSRFトークンが必要 (`invalid_csrf`)。
  そのためアプリのHTTPクライアントは意図的にCookieを保持しない。
  fwuidの検証が将来入った場合はトップページの `/sfsites/auraFW/javascript/{fwuid}/aura_prod.js` から動的取得する。
  解決不能時も外部resolverへ授業コードを送信せず、直接APIの失敗状態として扱う。

### スチューデントポータル

`sp.ritsumei.ac.jp/studentportal/s/` も Salesforce Experience Cloud だが **Microsoft SSO必須**
(立命館のID基盤はAzure AD連携 — MOCHA+R の資料より)。
MVPではWebView統合とし、CookieManagerでセッションを永続化。
API化する場合はWebViewのセッションCookie (`sid`) を使って `sfsites/aura` を呼ぶ方式が考えられる。

### 課題提出の扱い (対応 / 未対応 / 未検証)

| 状態 | 内容 |
|---|---|
| 対応 (単体テストまで) | 通常課題の文章 (プレーンテキスト→HTML) 保存、ファイル追加・削除 (既存ファイルをパス付きで引き継ぎ、保存後に一覧とサイズを照合)、最終提出、提出確認文の表示。未保存草稿の離脱・再読込確認、プロセス再生成後の草稿復元。保存前にサーバー本文を再照会し、Web 側の変更 (書式追加・権限変更を含む) があれば上書きせず停止。応答が返らなかった書き込み・成功応答後に照会だけ失敗した書き込み・照会しても未反映の書き込みは「結果未確認」として保持し、適用確認か利用者の明示的な解除まで再送しない。草稿は本文の基準値・競合状態と一緒に保存され、プロセス再生成後も Web 側の変更を上書きしない |
| Webへ誘導 | グループ提出、書式付き (画像・表・リンク) の既存文章の編集、Web Serviceで取得できない課題。各画面の「Moodleで開く」から常時WebViewへ抜けられる |
| 制限付き対応 | 制限時間付き課題は確認ダイアログ後に開始APIを呼ぶ。残り時間の表示はなし |
| 未検証 | 大学サーバー (Moodle 4.x 想定) の実応答での提出。テストは `MockWebServer` による契約テスト (draft 組み立てを含む) と草稿状態・認証競合・キャンセルの JVM テストまで。同名・同サイズで内容だけ違う置換はサイズ比較では検出できない |

## 既知の制約・次のステップ

- [ ] `rutime_table` ブロックの実際のHTMLでの時間割解析検証 (実アカウントでの動作確認が必要。取得できない場合はエラー表示にフォールバック)
- [x] シラバスresolverの自前化 (Aura API直接呼び出しのみ。外部Worker fallbackなし)
- [ ] ポータルのネイティブ化 (休講情報などの抽出)
- [x] 通知の既読同期 (`core_message_mark_notification_read`)
- [x] Moodle Web Service token のAndroid Keystore暗号化保存
- [x] CI (GitHub Actions: 単体テスト・Lint・assembleDebug)
- [ ] 実機での受入テスト ([PROJECT_REVIEW.md](docs/audits/PROJECT_REVIEW.md) のチェックリスト)
- [ ] Google Play 配信するなら大学への確認を推奨 (非公式ツールのため)

## 免責

本アプリは個人の利便性向上のための非公式ツールです。大学・Moodle本体の仕様変更で動かなくなる可能性があります。
