# N01–N11修正報告の再監査

> R01–R09への対応報告を再監査しました。過去13件のプローブは成功しましたが、追加4件で3つの問題を再現しています。最新の判定は [THIRD_FOLLOWUP_AUDIT.md](C:/Users/2omur/development/better_moodle_app/THIRD_FOLLOWUP_AUDIT.md) を参照してください。

調査日: 2026-09-22。対象: HEAD `f10f698b4d565b88d8b9d1e1696431c666298d5c` と未コミットの作業ツリー全体。実装報告と現在のソース・テストを照合した。アプリ実装・正式テストは変更していない。

## 判定

**前回のT01–T08はすべて改善を確認。ただしN01–N11全体の完了判定はできない。特に、文章の意図しない上書きと、提出後の照会失敗に対する保護が未完了。**

今回の追加プローブ5件は、いずれも期待動作を満たさず失敗した。これとは別に、通知遷移・Worker・追加ページの実行順序に4件の問題を確認した。後者はCompose／端末での再現が未実施であり、再現済みの問題と区別する。

## 検証結果と報告との差

| 検証 | 今回の結果 |
|---|---|
| `testDebugUnitTest lintDebug assembleDebug --rerun-tasks --console=plain` | **BUILD SUCCESSFUL、55タスク実行**。JDK 17使用 |
| 通常構成の単体テストXML | **185件、失敗0、エラー0**。報告と一致 |
| 旧 `ReviewRegressionProbeTest` | **8件成功**。前回の具体的な再現条件は解消 |
| 今回の `SecondAuditProbeTest` | **5件中5件で不一致を再現**。通常の185件には含めない |
| Lint XML | **0 errors / 29 warnings**。報告の36 warningsとは異なる |
| `git diff --check` | エラーなし。改行変換のGit警告のみ |
| GitHub Actions | 定義を確認。GitHub上での実行実績は未確認 |
| 端末 | `adb devices -l` で接続1台を確認。実機UI・SSO・通知配送・大学サーバーへの提出は未実施 |

最初にAndroid Studio同梱JBRを使うと、現在のJava 25.0.3でGradleが起動時に失敗した。CIと同じJDK 17へ切り替えた。またofflineでは更新後の `lint-gradle:31.13.2` がキャッシュになく失敗したため、通常の依存取得を許可した実行で完了した。現在のAGPは8.13.2、Gradleは8.13であり、前回監査からビルド基盤も変わっている。README等の再現手順にもJDK条件を記載すること。

プローブ実行後、init scriptなしの通常テスト実行で185件のレポートへ戻した。以下の検証用コードとXMLはGit管理外のbuild配下にあり、cleanで消える。

- [追加プローブ](C:/Users/2omur/development/better_moodle_app/build/audit2/src/dev/rits/bettermoodle/SecondAuditProbeTest.kt)
- [今回の5件の結果XML](C:/Users/2omur/development/better_moodle_app/build/audit2/results/TEST-dev.rits.bettermoodle.SecondAuditProbeTest.xml)
- [旧8件の結果XML](C:/Users/2omur/development/better_moodle_app/build/audit2/results/TEST-dev.rits.bettermoodle.ReviewRegressionProbeTest.xml)
- [通常テストレポート](C:/Users/2omur/development/better_moodle_app/app/build/reports/tests/testDebugUnitTest/index.html)
- [Lintレポート](C:/Users/2omur/development/better_moodle_app/app/build/reports/lint-results-debug.html)

```powershell
$env:JAVA_HOME = 'C:/Program Files/Java/jdk-17'
./gradlew.bat testDebugUnitTest --tests dev.rits.bettermoodle.SecondAuditProbeTest --tests dev.rits.bettermoodle.ReviewRegressionProbeTest -I build/audit2/audit.init.gradle --offline --console=plain
```

現在は13件中5件失敗が再現結果。大学サーバーのデータを書き換える試験ではなく、FakeGatewayおよびローカルMockWebServerで検証した。

## 重要度順の指摘

### R01 / P1 — 提出API成功後に照会だけ失敗すると、再提出を止められない

根拠: [AssignmentEditor.kt:363](C:/Users/2omur/development/better_moodle_app/app/src/main/java/dev/rits/bettermoodle/data/AssignmentEditor.kt:363)、同ファイルの `write` / `reloadAfterWrite`。

`PendingWrite` を設定するのは書き込みそのものが例外になった場合だけ。APIが成功応答を返した後、状態照会が失敗するとpendingはnullのまま、busyだけ解除される。画面は旧状態のまま最終提出を再び受け付ける。

**再現済み:** `acknowledgedSubmitWithFailedReloadMustNotResubmit`。Fakeは最初の提出を適用して正常応答、その後のstatus取得のみ失敗させた。2回の操作で提出APIが**2回**呼ばれた。旧T03が守る「書き込み応答が消えた場合」とは別経路。サーバーが2回目を拒否する可能性はあるが、クライアントの再送防止条件は満たさない。

修正: 書き込みの送信前から確認対象を記録し、応答成功でも照合完了までは再送を禁止する。保存・ファイル変更・開始にも同じ契約を適用する。照会結果が直ちに未反映だったことだけで、サーバー処理が今後も適用されないと断定しない。

完了条件: 成功応答→照会失敗→連打、照会復旧、プロセス再生成のテスト。API適用済みの操作を再送しない。既存テストの「write followed by lost status reload」は草稿とエラー表示しか確認せず、この条件をカバーしていない。

### R02 / P1 — ファイルだけ変更した後の最終提出で、Web側の文章を古い文章に戻す

根拠: [AssignmentEditor.kt:453](C:/Users/2omur/development/better_moodle_app/app/src/main/java/dev/rits/bettermoodle/data/AssignmentEditor.kt:453) の `applyFilesResult` と最終提出前のdirty自動保存。

ファイル操作後に `savedText` は新しいサーバー本文へ更新するが、`draftText` は常に旧値のまま。このため、ユーザーが文章を編集していなくても、別端末で本文が更新されているとdirtyになる。最終提出はその旧本文を「未保存草稿」として自動保存する。

**再現済み:** `changingFilesMustNotTurnOldTextIntoADraftAndOverwriteWebEdit`。初期本文original→Webでchanged on web→アプリでファイル削除→最終提出。サーバー本文が**originalに戻った**。

修正: サーバー再読込時は、操作前に本文がdirtyだったかを保持する。未編集ならdraftとsavedを一緒に更新し、編集済みなら外部更新との競合として扱う。`settlePending`、開始後の反映など、savedだけを書き換える経路も統一する。

完了条件: テキスト未編集／編集済み × Web側更新あり／なし × ファイル追加・削除・結果照会のテスト。未編集の古い本文が自動保存対象にならないこと。

### R03 / P1 — 文章保存には外部変更の再確認がなく、後から付いた書式も上書きする

根拠: [AssignmentEditor.kt:209](C:/Users/2omur/development/better_moodle_app/app/src/main/java/dev/rits/bettermoodle/data/AssignmentEditor.kt:209)。`onlineTextIsRich` と編集可否は画面読込時のdataで判断し、そのまま保存する。変更前再照会はファイル操作だけに入っている。

**再現済み:** `saveMustRecheckExternalRichTextBeforeOverwriting`。画面読込後にWebで本文を `<strong>` 付きの別文章へ変更しても、保存APIが1回呼ばれ上書きした。属性の書式判定を直したT04だけではこの経路を防げない。

修正: 文章の基準値を保持し、保存前に最新本文・書式・編集可否を照合する。外部更新を検出したら、自動保存も含め停止して利用者に解決させる。再照会と保存の間の競合も残るため、完全な同時編集保護を保証できない場合はその範囲を明示する。

完了条件: 読込後のWeb変更、書式追加、権限変更、最終提出前の自動保存で同じ保護が働くこと。

### R04 / P2 — 通知宛先の処理が、自分のLaunchedEffectをキャンセルする

根拠: [MainActivity.kt:195](C:/Users/2omur/development/better_moodle_app/app/src/main/java/dev/rits/bettermoodle/MainActivity.kt:195)。`LaunchedEffect(pendingLink)` の先頭でpendingLinkの元StateFlowをnullにしてから、DataStoreの読込・保存・遷移を行う。

**コード経路、端末再現未実施:** DataStore処理中に再コンポーズされるとキーがlink→nullに変わり、このEffectがキャンセルされる。宛先はすでに消しているため、その場の再試行もできない。さらに消費済み保存を遷移前に行うので、その間の中断は「開いていないのに消費済み」を残す。

修正: ナビゲーション要求の所有者と状態遷移を整理し、少なくとも自分のEffectキーを処理途中で消さない。遷移・消費記録・再生成時の復元の順序を設計する。イベントID＋期限の永続キーだけでは「OSの再配達」と「利用者が同じ通知を明示的に再度開く」を区別できない点も扱う。

完了条件: DataStore読込／保存を遅延した通知タップ、cold start、ログイン待ち、回転、プロセス再生成、明示的再タップのCompose/端末テスト。

### R05 / P2 — Workerの世代チェックと通知・保存は依然として非原子的

根拠: [DeadlineWorker.kt:72](C:/Users/2omur/development/better_moodle_app/app/src/main/java/dev/rits/bettermoodle/work/DeadlineWorker.kt:72)、[同:95](C:/Users/2omur/development/better_moodle_app/app/src/main/java/dev/rits/bettermoodle/work/DeadlineWorker.kt:95)。AuthSessionのMutexはWorkerの通知発行・DataStore編集には使われていない。

**コード経路、端末再現未実施:** 世代確認→ログアウト・通知全消去→旧Workerがnotifyという順序はまだ可能。保存直前チェック後にログアウト／別ログイン→旧キーを `replaceNotifiedEventIds` で保存する経路も残る。チェック箇所の追加は競合の時間幅を縮めるが、排除しない。

修正: セッション境界の処理と副作用を同じ調停機構で保護し、永続通知キーもセッション／アカウント単位にする。既読同期も旧要求が新世代の同じIDのinFlightを削除しないよう、要求単位の所有権を持たせる。

完了条件: チェック通過直後にWorkerを止め、ログアウト／Bへログインを完了してから通知・保存を再開する決定的なテスト。既存AuthSessionTestの世代比較だけでは検証できない。

### R06 / P2 — 正当なHTML提出ファイルまで再取得を拒否する

根拠: [MoodleClient.kt:170](C:/Users/2omur/development/better_moodle_app/app/src/main/java/dev/rits/bettermoodle/data/MoodleClient.kt:170)。Content-Typeがtext/htmlなら、元の提出物の種類に関係なく拒否する。

**再現済み:** `legitimateHtmlSubmissionMustRemainDownloadable`。MoodleFileのmimetypeもtext/html、サイズ一致のhomework.htmlに対しIOExceptionとなった。HTML課題ファイルが既存提出にあると、別ファイルを追加したり、HTMLを残して他のファイルを削除したりできない。

修正: 期待ファイルのMIME・名前・サイズ等を使い、正当なHTMLとログインページを区別する。安全に判別できない場合は明示的にWeb編集へ誘導する。無条件許可への変更もしない。

完了条件: 正当なHTML、PDF取得に対するログインHTML、リダイレクト、MIME欠落のHTTPテスト。

### R07 / P2 — 0バイトを「不明」とみなしてサイズ不一致を成功扱いする

根拠: [AssignmentFileDraft.kt:134](C:/Users/2omur/development/better_moodle_app/app/src/main/java/dev/rits/bettermoodle/data/AssignmentFileDraft.kt:134)。期待値と実測値の両方が正の場合だけ比較する。

**再現済み:** `knownEmptyFileMustNotMatchNonemptyServerFile`。ローカルの既知サイズ0バイトに対しサーバー100バイトでもverifySavedが成功した。逆に期待値が正、サーバー0でも検出しない。これは報告済みの「同サイズ・別内容」という限界とは別の不具合。

修正: サイズ不明はnullable等で0と区別し、ローカルで確定した0バイトも厳密比較する。情報不足なら検証済み成功と断定しない。

完了条件: 0→0、0→正、正→0、正→別サイズ、不明の各ケース。

### R08 / P2 — 通知一覧の追加ページは更新世代に対応していない

根拠: [NotificationsScreen.kt:58](C:/Users/2omur/development/better_moodle_app/app/src/main/java/dev/rits/bettermoodle/ui/NotificationsScreen.kt:58)。締切一覧と違い、追加ページは今も `remember(firstPage)` に依存し、追加要求の世代確認もない。

**コード経路、画面再現未実施:** 先頭ページが等しいまま後続だけ変わると、更新しても古い後続ページが残る。追加取得中の更新では旧offsetの応答を新しい一覧へ混ぜる可能性もある。

修正・完了条件: 明示的な更新世代を導入し、追加取得中の更新・先頭同一で後続のみ変更・offsetずれをテストする。締切画面も最初の新ページを取得し終えるまで旧カーソルで追加取得しないことを確認する。

### R09 / P2 — Workerは上限到達後も毎回先頭から取り直し、続きに進まない

根拠: [MoodleRepository.kt:100](C:/Users/2omur/development/better_moodle_app/app/src/main/java/dev/rits/bettermoodle/data/MoodleRepository.kt:100) のdeadlinesWithinと、[DeadlineWorker.kt:29](C:/Users/2omur/development/better_moodle_app/app/src/main/java/dev/rits/bettermoodle/work/DeadlineWorker.kt:29)。不完全フラグは付くが、継続カーソルを持ち越さずsuccessで終了する。

**コード上の制限:** 24時間窓に20ページを超えるイベントがあり、次の実行でも先頭1000件が窓内に残る場合、後続の締切へ進まない。「次回実行で続きを拾えるようにする」というコメントは保証できない。キーを保持することと取得位置を継続することは別。

修正・完了条件: 上限時の継続戦略または利用者に届く不完全状態を実装する。1000件超の同一窓を複数回実行して後続まで処理できるテスト。短いretryを追加するだけでは解消しない。

## N01–N11の再判定

| 項目 | 確認できた修正 | 現在の判定 |
|---|---|---|
| N01 | filepath保持、draft HTTPテスト、サイズ照合 | 部分。R06/R07、同名置換確認は未実装。内容一致の保証は別途必要 |
| N02 | 応答消失時の照会、準備中の排他、busy表示 | 部分。R01/R02、PendingWrite永続化、画面離脱・再生成が残る |
| N03 | 属性付きHTMLの保護、比較規則 | 部分。旧T04は成功。R03の外部更新は防がない |
| N04 | AuthSessionでlogin/logout/invalidtokenの直列化 | 改善確認。Worker境界はR05。端末のCookie/SSOは未検証 |
| N05 | useResponse、残存executeの移行、本文中断テスト | 対象実装と旧T05成功を確認。アップロード中断・実画面離脱は未検証 |
| N06 | 満杯ページの未進行を不完全扱い、締切更新世代 | 部分。R08/R09。End/More/Incompleteはsealed型でなくフラグと理由の表現 |
| N07 | 画像拡張子判定、個別フォーラムのWebボタン | 対象実装・旧T07成功。画面からの閲覧受入は未検証 |
| N08 | warning配列契約、既読ID照合、シラバス構造不正の分類 | 対象実装・契約テスト成功。大学サーバーの実応答とは未照合 |
| N09 | 空一覧分岐の外側へstale表示、投稿画面への追加 | コード上の修正確認。Composeテスト未実施 |
| N10 | 既読要求のアプリscope化、消費済みキー保存 | 部分。R04/R08、セッションをまたぐ既読処理の競合試験が必要 |
| N11 | 185件、HTTP draft・認証遅延・契約テスト | 改善確認。今回の5件、Compose、CI実行、テストMoodle、実機受入が残る |

## 報告に明記された残件の評価

- **同名・同サイズの内容照合:** 既知の限界だが、APIにハッシュがないだけで検証不能とはいえない。必要なら認証付き再ダウンロードで比較するか、未検証の置換として扱う。保存成功と内容確認済みを区別する。
- **PendingWriteが再生成で消える:** 「再読込で正になる」は成功した照会時点の状態についてだけ。送信中のプロセス終了、古い読み取り結果、再照会失敗を含む受入条件を満たさない。N02完了から切り離さない。
- **同名置換の確認ダイアログ:** FOLLOWUP_REVIEW末尾には未実装と明記されている。`Plan.replaced` はあるが確認UIにはつながっていない。利用者の選択直後に置換されるため、提出物保護の未完了条件として維持する。
- **NonCancellable:** 通信のキャンセルを避けてもプロセス終了には耐えない。送信とその後の照会は別のNonCancellableブロックなので、画面離脱後の一連の完了保証も実行テストが必要。アプリの現在トークンが切り替わる場合の扱いも検証する。
- **CI・結合・実機未実施:** 報告と整合する。今回は端末接続のみ確認した。自動テスト成功をこれらの完了に読み替えない。

## 次の修正順序

1. **R01–R03:** 提出操作の状態機械と本文の基準値・競合処理をまとめて修正。今回の3プローブを正式テストへ移植する。
2. **R04–R05:** 通知遷移とセッション副作用の所有権を修正。遅延・割込みを明示的に制御するテストを追加する。
3. **R06–R09:** ファイル種類／サイズの境界、一覧更新、Workerの継続を修正。残り2プローブを正式化する。
4. **受入:** 同名置換確認・プロセス復元のComposeテスト、テスト用Moodleとの結合、実機SSO/通知、GitHub CIの実行証拠を揃える。

今回の結論は「前回の修正は無効」ではなく、**前回の8再現条件は解消したが、データ保護の完了条件にはまだ穴がある**というもの。通常185件と旧8件を維持し、今回の失敗条件を追加してから完了判定を更新する。

## 対応状況 (2026-09-22 実装後)

`SecondAuditProbeTest` 5 件・`ReviewRegressionProbeTest` 8 件とも新実装で成功
(`./gradlew.bat testDebugUnitTest --tests dev.rits.bettermoodle.SecondAuditProbeTest --tests dev.rits.bettermoodle.ReviewRegressionProbeTest -I build/audit2/audit.init.gradle --offline`、JDK 17)。
通常構成の単体テストは **201 件、失敗 0**。今回の 5 プローブは正式テストへ移植済み。GitHub CI 実行、テスト用 Moodle 結合、実機受入は未実施。

| ID | 状態 | 対応内容 | 正式テスト | 残り |
|---|---|---|---|---|
| R01 | 実装・単体済 | 成功応答の後の照会失敗は `PendingWrite(acknowledged=true)` として保持し、照会で確定するまで全書き込みを拒否。受理済みで未反映と読めても自動解除せず、再確認か利用者の明示的な解除 (`dismissPendingWrite`) のみ。保存・ファイル・開始・最終提出の全経路に適用 | `AssignmentEditorTest.acknowledged submit with failed reload must not resubmit` ほか 2 件 | プロセス再生成時の PendingWrite 永続化 (未) |
| R02 | 実装・単体済 | 再読込は `withReloadedText`: 未編集なら draft と saved を一緒に更新、編集中に本文が外部で変わっていれば `textConflict`。ファイル操作・開始・提出・照会確定・通常再読込で統一。競合中は自動保存・最終提出を止め、「再読込して破棄」で解消 | `changing files must not turn old text into a draft...`、`edited text plus web change ... blocks submit` | — |
| R03 | 実装・単体済 | `saveText` は送信前に再照会し、基準値 (`savedText`) からの変更・書式付き・編集権限を最新で判定。同じ内容が既に保存されていれば送らずに一致させる。最終提出前の自動保存も同じ経路 | `save must recheck external rich text...` ほか 3 件 | 再照会〜保存の間の同時編集は API に条件付き更新が無いため防げない (文書化) |
| R04 | 実装済 | Effect のキー (pendingLink) を処理終了まで変えない。遷移要求→消費済み記録→クリアの順。`PendingDeepLink.explicit` で「OS の再配達 (savedInstanceState あり)」と「利用者の明示的なタップ (onNewIntent / 新規起動)」を区別し、後者は常に開く | — (Compose) | Compose/端末テスト |
| R05 | 実装・単体済 | `AuthSession.withCurrentSession(generation)`: セッション境界と同じ Mutex 内で通知発行・通知済み保存・カーソル保存を行う。`clearAuthTokens` も通知済み・カーソル・宛先を消す。既読同期は要求トークンで所有権を持ち、旧要求が新要求の inFlight を消さない | `SecondAuditRegressionTest.worker side effects inside withCurrentSession are not interleaved with logout`、`old read request completing later...` | 端末での再現 |
| R06 | 実装・単体済 | 提出物自体が HTML (mimetype / .html) のときだけ text/html を受け入れ、それ以外の HTML とログインページへのリダイレクト (pluginfile 以外への 30x) は拒否。MIME 欠落はサイズ一致なら受け入れ。サイズ不明のファイルは検証不能として取得しない | `legitimate html submission must remain downloadable` ほか 2 件 | — |
| R07 | 実装・単体済 | `MoodleFile.filesize` / `SubmissionFileFingerprint.sizeBytes` を nullable にし、0 バイトは既知サイズとして厳密比較。不明は「検証済み」と断定しない | `OnlineTextFormatTest.verifySaved detects...` (0→0 / 0→正 / 正→0 / 不明) | — |
| R08 | 実装済 | 通知一覧にも更新世代を導入 (追加ページ・追加要求の世代確認)。締切・通知とも先頭ページ再取得中は旧カーソル/offset で続きを取らない | — (Compose) | Compose テスト |
| R09 | 実装・単体済 | ページ上限で打ち切った位置 (`DeadlineWindow.nextAfterEventId`) を `DeadlineCursor` として DataStore に保存し、次回はそこから続ける。継続実行・不完全実行では通知済み集合を絞らない (`shouldPruneNotifiedKeys`)。カーソル異常時は保存しない | `deadlinesWithin continues from a saved cursor and the worker policy reaches later pages` | 1000 件超の実データ |

**ビルド環境:** AGP 8.13.2 / Gradle 8.13 に合わせ README の再現手順を JDK 17 に更新した。
