# Moodle+R Companion (better_moodle_app)

立命館大学の **Moodle+R / シラバス / スチューデントポータル** をひとつにまとめた非公式Androidアプリ。

公式Moodleアプリの「時間割が一覧で見えない」「通知がごちゃまぜ」という課題を解決する。

## 機能

| タブ | 内容 |
|------|------|
| 時間割 | ダッシュボードの時間割ブロックを解析し、**スクロール不要の全画面グリッド**で表示。今日の曜日をハイライト。コマをタップするとコース/シラバスへ |
| 締切 | **課題(assign)・小テスト(quiz)等の締切だけ**を日付ごとにまとめた一覧。24時間以内は赤で強調 |
| 通知 | Moodleの通知一覧。「課題関連 / その他」フィルタ付き |
| シラバス | 履修中コース一覧から1タップでシラバスを開く。授業コード直接入力も可 |
| ポータル | スチューデントポータル (sp.ritsumei.ac.jp) をアプリ内WebViewで表示。Microsoft SSOのセッションは永続化 |

さらに **WorkManager** が6時間ごとに締切をチェックし、24時間以内に迫った課題をローカル通知する。

## ビルドと実行

Android Studio でこのフォルダを開いて Run するだけ。CLIの場合:

```
JAVA_HOME="C:/Program Files/Android/Android Studio/jbr" ./gradlew assembleDebug
adb install app/build/outputs/apk/debug/app-debug.apk
```

- minSdk 26 / targetSdk 35 / Kotlin 2.0 + Jetpack Compose (Material 3)
- DIフレームワークなし (`AppContainer` による手動DI)、DB なし (DataStore のみ) の軽量構成

## アーキテクチャと調査結果

### 認証 — 公式アプリと同じSSOトークンフロー

`lms.ritsumei.ac.jp` はモバイルWeb Service有効 (`enablemobilewebservice=1`, `typeoflogin=3` = アプリ内ブラウザSSO) を確認済み。

1. `admin/tool/mobile/launch.php?service=moodle_mobile_app&passport={乱数}&urlscheme=bettermoodle` をアプリ内WebViewで開く
2. 学内SSO (Azure AD / Microsoft学生アカウント) でログイン
3. `bettermoodle://token=BASE64("md5(siteUrl+passport):::wstoken[:::privatetoken]")` へのリダイレクトを `shouldOverrideUrlLoading` で捕捉してトークン保存 ([SsoLogin.kt](app/src/main/java/dev/rits/bettermoodle/auth/SsoLogin.kt))

以後は `POST /webservice/rest/server.php` (`wstoken` + `wsfunction`) で全データを取得 ([MoodleClient.kt](app/src/main/java/dev/rits/bettermoodle/data/MoodleClient.kt))。

### 使用しているMoodle WS関数

| 関数 | 用途 |
|------|------|
| `core_webservice_get_site_info` | ユーザ情報 |
| `core_block_get_dashboard_blocks` (`returncontents=1`) | **時間割**: カスタムブロック `rutime_table` のHTMLを取得し jsoup で解析 |
| `core_calendar_get_action_events_by_timesort` | **締切**: 公式アプリのタイムラインと同じAPI。`assign`/`quiz` 等でフィルタ |
| `message_popup_get_popup_notifications` | 通知一覧 |
| `core_course_get_enrolled_courses_by_timeline_classification` | 履修中コース一覧 (シラバス画面) |

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

## 既知の制約・次のステップ

- [ ] `rutime_table` ブロックの実際のHTMLでの時間割解析検証 (実アカウントでの動作確認が必要。取得できない場合はエラー表示にフォールバック)
- [x] シラバスresolverの自前化 (Aura API直接呼び出しのみ。外部Worker fallbackなし)
- [ ] ポータルのネイティブ化 (休講情報などの抽出)
- [ ] 通知の既読同期 (`core_message_mark_notification_read`)
- [x] Moodle Web Service token のAndroid Keystore暗号化保存
- [ ] Google Play 配信するなら大学への確認を推奨 (非公式ツールのため)

## 免責

本アプリは個人の利便性向上のための非公式ツールです。大学・Moodle本体の仕様変更で動かなくなる可能性があります。
