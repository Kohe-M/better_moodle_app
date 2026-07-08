# KMP / iOS対応ロードマップ (確定版)

better_moodle_app を Kotlin Multiplatform 化して iOS に対応させるための実行計画。
実装は Claude / Codex などのエージェントに委任する前提で、各Phaseに **完了条件 (DoD)** を明記する。

- 前提環境: 開発は Windows 主体。iOSビルド・実機確認用に Mac を使用 (入手済み/予定)。
- UI戦略: **Track A = Compose Multiplatform (推奨)** と **Track B = SwiftUI + shared logic** の両案を記載。
  Phase 0〜2 は両トラック完全共通。Phase 3 で分岐する。

---

## 現状分析 (2026-07 時点)

単一 `app` モジュール、約7,800行。手動DI (`AppContainer`)、ViewModelなし
(`rememberLoadable` + `LoadableMemoryCache` によるComposable内ロード)。

### すでにKMP互換 (純Kotlin + kotlinx.serialization、テスト済み)

そのまま `shared/commonMain` に移動できるもの:

| ファイル | 内容 |
|---|---|
| `data/Models.kt` | DTO、時限定義、時間割モデル |
| `data/AssignmentModels.kt` / `QuizModels.kt` / `ForumModels.kt` / `PageModels.kt` / `CourseContentModels.kt` | 各機能のDTO |
| `data/ActionEventRouting.kt` / `ForumRouting.kt` / `QuizRouting.kt` | イベント→画面ルーティング判定 |
| `data/CourseModuleGrouping.kt` | セクション整形 |
| `data/QuizUiModels.kt` | クイズ表示ロジック |
| `ui/LoadState.kt` の `LoadableMemoryCache` | メモリキャッシュ (Composable部分は除く) |

対応する単体テスト (`ActionEventRoutingTest`, `CourseModuleGroupingTest`, `TimetableParserTest` 等) も
`commonTest` へ移動できる。**このテスト群が移行全体の安全網になる。**

### KMP非互換な依存と置き換え先

| 現在 | 使用箇所 | 置き換え |
|---|---|---|
| OkHttp | `MoodleClient.kt`, `SyllabusRepository.kt` | **Ktor Client 3.x** (Android=OkHttpエンジン, iOS=Darwinエンジン) |
| jsoup | `MoodleRepository.kt` (時間割HTML解析), `PageRouting.kt` | **Ksoup** (`com.fleeksoft.ksoup` — jsoup移植でAPIほぼ互換) |
| `java.time` | `AssignmentUiModels.kt`, `SyllabusRepository.kt`, `ui/Formats.kt` | **kotlinx-datetime** |
| `java.net.URI/URLEncoder` | `UrlPolicy.kt` | Ktor の `Url` / `URLBuilder` |
| `java.security` (MD5, SecureRandom), `java.util.Base64` | `auth/SsoLogin.kt` | kotlincrypto (MD5) + `kotlin.random` + `kotlin.io.encoding.Base64` |
| Android Keystore + DataStore | `SessionStore.kt` | DataStore は **KMP対応済み** (androidx 1.1+)。暗号化部分だけ `expect/actual TokenCipher` (Android Keystore / iOS Keychain) |

### OS別のまま残すもの (sharedに入れない)

- WebView 3画面: SSOログイン (`LoginScreen`), `MoodleWebScreen`, `PortalScreen` (CookieManager含む)
- `work/DeadlineWorker` (WorkManager)、通知チャンネル (`App.kt`)
- `PdfPreviewScreen` (Android PdfRenderer)、`DownloadShareDialog` (FileProvider)

---

## Phase 0: Android版の安定化と移行準備

KMP化前にAndroid内で土台を固める。**全作業Windowsで完結。**

1. **CIの整備**: GitHub Actions で `assembleDebug` + `testDebugUnitTest` を全push時に実行。
   移行中のリグレッション検出装置として必須。
2. **ツールチェーン更新**: Kotlin 2.0.21 → 最新stable (2.2.x)、AGP・Compose BOM追随。
   Track A の場合は Compose Multiplatform の対応バージョンに合わせる。
3. **残バグのクローズ** (README「次のステップ」より、任意だが推奨):
   - `rutime_table` 実アカウントでの時間割解析検証
   - 通知の既読同期 (`core_message_mark_notification_read`)

**DoD**: CIがグリーン。実機でログイン→時間割→締切→課題提出が通る。

## Phase 1: appモジュール内でのKMP互換化 (リスク最小の下準備)

モジュール分割の**前に**、非互換ライブラリを `app` 内で差し替える。
Androidで即動作確認でき、失敗の切り分けが容易なため、この順序が最重要ポイント。

1. `java.time` → kotlinx-datetime (`AssignmentUiModels`, `Formats`, `SyllabusRepository` の年度計算)
2. jsoup → Ksoup (`MoodleRepository` の時間割解析, `PageRouting`)。
   既存の `TimetableParserTest` / `PageRoutingTest` で解析結果の同一性を担保
3. OkHttp → Ktor Client (OkHttpエンジン指定で実質挙動維持)。
   - `MoodleClient.call` / `uploadFile` (multipart), `SyllabusRepository` のAura呼び出しを移植
   - **注意**: シラバスAura APIは「Cookieを持たない」ことが前提 (CSRF回避)。Ktorで cookie 無効を明示
   - キャンセル対応 (`executeCancellable` 相当) はKtorのsuspend APIが標準で満たす
4. `UrlPolicy` → Ktor `URLBuilder`、`SsoLogin` → 純Kotlin化 (MD5/Base64/乱数)
5. `SessionStore` を2層に分割:
   - `TokenStorage` (DataStore操作、KMP互換に書き換え)
   - `TokenCipher` interface + `AndroidKeystoreCipher` 実装 (レガシー平文トークンからの移行ロジック維持)

**DoD**: `data/` と `auth/` から `android.*` / `okhttp3` / `org.jsoup` / `java.*` importが消える
(SessionStoreの実装クラスとUI層は除く)。全テスト・実機動作がPhase 0と同等。

## Phase 2: shared モジュール抽出

```
better_moodle_app/
├─ app/        Androidアプリ (Compose UI, WebView, WorkManager, Keystore実装)
├─ shared/     KMP共通 (androidTarget + iosArm64 + iosSimulatorArm64)
└─ iosApp/     Phase 3で追加
```

1. `:shared` モジュール作成 (kotlin-multiplatform + kotlinx-serialization + Ktor + Ksoup + kotlinx-datetime + DataStore)
2. 「現状分析」のKMP互換ファイル群 + Phase 1で互換化した
   `MoodleClient` / `MoodleRepository` / `SyllabusRepository` / `SsoLogin`(ロジック部) / `UrlPolicy` /
   `TokenStorage` を `commonMain` へ移動。パッケージは `dev.rits.bettermoodle.shared.*` 等に整理
3. `expect/actual`: `TokenCipher` (actual: Android Keystore / iOS Keychain)、
   DataStoreのファイルパス生成
4. テストを `commonTest` へ (JUnit4 → kotlin-test へ書き換え)
5. `app` は `:shared` 依存に切り替え (ChatGPT案のPhase 4に相当する差し替えはここで同時に完了する —
   ViewModel層がなく画面が直接Repositoryを呼ぶ構成のため、import差し替えのみで済む)

**DoD**: Android版がshared経由で従来どおり動作 (機能変更ゼロ)。
`./gradlew :shared:compileKotlinIosArm64` がWindows上で通る (iOSバイナリのコンパイル自体はWindowsでも可能。リンク・実行はMacが必要)。

## Phase 3: iOSプロジェクト雛形 (ここからMac使用、トラック分岐)

共通作業:
- Xcode + iOSシミュレータ環境構築、`iosApp/` 作成
- CIにmacOSランナーのiOSビルドジョブ追加

### Track A: Compose Multiplatform (推奨)

既存Compose UI約4,400行を最大限再利用する。

1. `:shared` (または `:composeApp`) に CMP プラグインを追加し、UI移動の受け皿を作る
2. 依存の置き換え: Navigation → `org.jetbrains.androidx.navigation` (KMP版)、
   ViewModel不使用なのでLifecycle依存は最小で済む
3. **UIを画面単位で commonMain へ移動**。順序は依存が軽いものから:
   `Common` / `LoadState` / `Formats` → `DeadlinesScreen` → `CourseListScreen` / `SyllabusScreen`
   → `NotificationsScreen` → `TimetableScreen` → `CourseScreen` / `AssignmentScreen` / `QuizScreen` / `ForumScreen` / `PageScreen`
4. プラットフォーム差分は expect/actual Composable 化:
   - WebView (`LoginScreen`のSSO / `MoodleWebScreen` / `PortalScreen`) → iOS側は `UIKitView` + WKWebView
     (または compose-webview-multiplatform ライブラリ)
   - `HtmlText` (AnnotatedString化が純Kotlinならそのまま、Android API依存があれば分岐)
   - `PdfPreviewScreen` / `FilePreviewScreen` / `DownloadShareDialog` → 最初はiOS未対応でOK (Phase 5で実装)
5. `iosApp/` は CMP の `MainViewController()` を表示するだけの薄いSwiftコード

### Track B: SwiftUI + shared logic (ChatGPT原案)

1. `:shared` を XCFramework として出力 (`embedAndSignAppleFrameworkForXcode`)
2. **SKIE (Touchlab) を導入** — suspend関数→Swift async/await、Flow→AsyncSequence、
   sealed class→Swift enum の変換。これがないとSwift側の体験が著しく悪い
3. SwiftUI で画面を新規実装 (Phase 5のMVP範囲から)
4. ObservableObject ラッパーで shared Repository を呼ぶ

**トレードオフ**: Track Aは実装工数最小・保守1系統だが、WebView等のinterop実装が必要。
Track BはUI工数が大きい (全画面Swift書き直し) 代わりにネイティブ操作感と将来のiOS固有UI対応が容易。
**迷ったらTrack Aで開始し、操作感に不満が出た画面だけ後からSwiftUI化するハイブリッドも可能**
(CMPはSwiftUIとの共存ができる)。

**DoD**: シミュレータでアプリが起動し、shared の `MoodleClient` 経由で
(手動投入したトークンで) 締切一覧が表示できる。

## Phase 4: iOS SSOログイン (最大の技術リスク、最優先で検証)

トラック共通。ここが通れば残りは作業量の問題。

1. WKWebView で `admin/tool/mobile/launch.php?service=moodle_mobile_app&passport={乱数}&urlscheme=bettermoodle` を表示
2. `WKNavigationDelegate.decidePolicyFor` で `bettermoodle://token=...` リダイレクトを捕捉
   (Androidの `shouldOverrideUrlLoading` 相当)
3. shared の `SsoLogin` トークン検証ロジック (`md5(siteUrl+passport)` 照合) をそのまま使用
4. トークンを iOS Keychain へ保存 (`TokenCipher` の actual 実装)

**先行検証項目**: Azure AD SSOがWKWebViewで完走するか (Microsoftログインは通常WKWebViewで問題ないが、
条件付きアクセスポリシー次第でブロックされる可能性)。Phase 3完了直後にスパイクで確認する。

**DoD**: iOS実機/シミュレータで学内アカウントログイン→トークン永続化→再起動後も自動ログイン。

## Phase 5: iOS MVP完成

MVP範囲 (この順で実装):

1. ログイン (Phase 4)
2. 締切一覧 (`DeadlinesScreen`)
3. 課題詳細・提出状況 (`AssignmentScreen`)
4. コース一覧・コース内容 (`CourseListScreen` / `CourseScreen`)
5. 提出ファイルプレビュー — iOS は **QuickLook** (`QLPreviewController`) でPDF/画像/Officeを一括カバー
   (AndroidのPdfRenderer相当の自前実装は不要)

後回し: 通知、時間割、シラバス、ポータルWebView、バックグラウンド更新、ファイルアップロード。

**DoD**: TestFlight (内部テスト) で自分のアカウントの課題確認が日常利用できる。

## Phase 6: iOS機能拡充 (OS別機能)

- **時間割** (`TimetableScreen`): Track Aならほぼ無償で動く。iPhoneの画面幅でのグリッド調整のみ
- **シラバス**: Aura API呼び出しはshared済み。詳細表示はSFSafariViewController
- **通知一覧** + 既読同期
- **締切リマインダー**: `BGAppRefreshTask` + `UNUserNotificationCenter`。
  ⚠️ iOSはWorkManagerと違い実行間隔が保証されない (システム裁量)。
  「起動時チェック + ベストエフォートのバックグラウンド更新」として設計する
- **ポータル**: WKWebView + `WKWebsiteDataStore` でSSOセッション永続化
- **ファイルアップロード** (課題提出): `UIDocumentPickerViewController` → shared `uploadFile`

## Phase 7: 配信・運用

- TestFlight外部テスト → 必要ならApp Store申請
  - ⚠️ 非公式アプリのため「大学の商標/名称」「アカウント必須アプリ (審査用デモアカウント要求)」が審査リスク。
    個人利用ならTestFlight/AltStore配布に留める選択も現実的
- CI: PR毎に Android (`assembleDebug` + test, ubuntuランナー) / iOS (`iosSimulatorArm64Test` + Xcodeビルド, macosランナー)
- Moodle/シラバス側の仕様変更検知: 主要WS関数のスモークテストを定期実行 (任意)

---

## 実施順序まとめ

```
Phase 0  Android安定化 + CI          [Windows]  小
Phase 1  app内でKMP互換ライブラリ化   [Windows]  中  ← 全体で最も重要な下準備
Phase 2  shared抽出 + Android切替     [Windows]  中
Phase 3  iOS雛形 (Track A/B分岐)      [Mac]      小〜中
Phase 4  iOS SSOログイン検証          [Mac]      中  ← 最大の技術リスク、早期に潰す
Phase 5  iOS MVP                      [Mac]      Track A: 小 / Track B: 大
Phase 6  iOS機能拡充                  [Mac]      中
Phase 7  配信・運用                   [Mac+CI]   小
```

ChatGPT原案との主な差分:
1. **「Repository/UseCaseをUIから剥がす」フェーズは不要** — 本コードベースは既に画面→Repository直呼びで
   UI/データ層が分離済み。代わりに「ライブラリのKMP互換化 (Phase 1)」を独立フェーズとして最優先に置いた
2. **Android版のshared差し替え (原案Phase 4) はPhase 2に統合** — ViewModel層がないため移動と同時に完了する
3. **UI戦略にCMP案を追加** — UIが100% Composeなので、SwiftUI書き直しより再利用が合理的 (Track A推奨)
4. **iOS SSOログインを独立フェーズ化** — 技術リスクが最も高いため、MVP機能実装の前に単独で検証する
