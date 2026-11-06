# AGENTS.md

立命館大学の Moodle+R・シラバス・スチューデントポータルをまとめる非公式 Android アプリ。Kotlin + Jetpack Compose、単一 `app` モジュール。

このリポジトリで作業する人とエージェントの共通ルール。実装の細則は [docs/CONVENTIONS.md](docs/CONVENTIONS.md)。

| 対象 | 言語 |
|---|---|
| 利用者への報告、ドキュメント、コードコメント、UI 文言 | 日本語 |
| コミットメッセージ | 英語 |

## 変更前に読むもの

| 変更 | 読むもの |
|---|---|
| 実装の変更すべて | [docs/CONVENTIONS.md](docs/CONVENTIONS.md) の該当節 |
| UI | [DESIGN.md](DESIGN.md) 全文、[docs/DESIGN_RATIONALE.md](docs/DESIGN_RATIONALE.md) |
| 課題提出 | `AssignmentEditor.kt` の状態機械のコメント |
| Moodle WS の呼び出し | [README.md](README.md) の WS 関数表 |
| 依存更新、KMP | [ROADMAP.md](ROADMAP.md) |
| 過去の判断の理由 | `git log --grep=<ID>`、[docs/audits/](docs/audits/) の同じ ID |

コメントに `(G02)` のような ID があれば、docs/audits/ に理由が書いてある。消す前に読む。

## 壊してはいけないもの

1. **結果が不明な書き込みを再送しない。** 二重提出や上書きにつながる。`PendingWrite` を迂回しない
2. **セッションの境界を動かすのは `AuthSession` だけ。** 旧セッションの応答を新セッションに混ぜない
3. **トークンを外に出さない。** ログ、外部アプリ、外部サービスに渡さない。授業コードも外部 resolver へ送らない
4. **Moodle に推測のパラメータを送らない。** 書き込みの `warnings` は失敗として扱う
5. **通信は `HttpClients.shared` と `useResponse` を通す。** `CancellationException` を握りつぶさない
6. **端末に残っている保存形式を、移行なしで変えない。** キー名、形式、パレットの並び順を含む
7. **UI の値は DESIGN.md のトークンから取る。** 変えるときは DESIGN.md を先に直す
8. **実際の課題に提出して試さない。** 実サーバーへの書き込みはテスト用の課題でだけ行う
9. **確認していないことを確認済みと書かない。** 「実装済み」「JVM テスト済み」「実機確認済み」を書き分ける

現在の検証範囲は README の「課題提出の扱い」と ROADMAP の Phase 0 を見る。

## 検証

実装を変更したら、CI と同じコマンドを JDK 17 で通す。ドキュメントだけの変更では不要。

```bash
JAVA_HOME="C:/Program Files/Java/jdk-17" ./gradlew testDebugUnitTest lintDebug assembleDebug --console=plain
```

UI を変更したら、DESIGN.md §14 のチェックリストも確認する。

## Git

**実装が終わったら、検証、コミット、プッシュまで行う。** 都度の確認は要らない。プッシュ先は `main`。PR は作らない。

コミットもプッシュもしない場面:

- 調査、レビュー、質問への回答だけで終わる作業
- 利用者がコミットしないよう求めたとき
- 検証が通らないとき (自分の変更と無関係な失敗なら、内容を報告して判断を仰ぐ)

### 手順

1. `git status` と `git diff` で変更を確認する
2. 検証を通す
3. 自分のファイルだけをパス指定でステージする: `git add <path> ...`
4. `git diff --staged` を読み、意図しない変更がないか確かめてからコミットする
5. `git fetch origin` で確認し、遅れていれば `git pull --rebase --autostash origin main`
6. `git push origin main`
7. コミットハッシュ、検証結果、未検証のことを報告する

### ステージの規則

- `git add -A`、`git add .`、`git commit -a` を使わない。同じチェックアウトに、利用者や別セッションの未コミット変更があることがある
- 他者の未コミット変更をステージ、取り消し、整形しない。報告だけする
- 自分の変更と他者の変更が同じファイルに混ざったら、まとめてコミットせずに報告する
- 1コミット1目的。機能追加、修正、整理を混ぜない。関連するドキュメントの更新は同じコミットに入れる

コミットしないもの: `local.properties`、ビルド成果物、プレビュー画像、講義資料、トークン、実アカウントの応答。

### コミットメッセージ

```text
Fix SSO passport mismatch and make login/logout failures non-fatal (G01, G04, G14)

- G01: use the same passport for the WebView and the launch.php probe
- G04: report token store failures instead of crashing

Not verified on a real device.

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>
```

| 要素 | 規則 |
|---|---|
| 件名 | 英語の命令形。先頭は大文字、末尾にピリオドなし。72 文字以内を目安にする |
| 接頭辞 | `feat:` や `fix:` は付けない |
| 監査 ID | 指摘を直すときは件名の末尾に `(G03, G06)` |
| 本文 | 差分から読めない理由を書く。72 桁で折り返す。複数の項目は `- ` で並べる。自明なら省く |
| 未検証 | 実機未確認などがあれば本文に書く |
| 末尾 | エージェントは実行環境が指定する `Co-Authored-By:` 行を付ける。指定がなければ付けない |

### 利用者の確認が要る操作

- `--force` のプッシュ、履歴の書き換え、プッシュ済みコミットの amend
- `git reset --hard`、`git clean`、ブランチやタグの削除
- `--no-verify` などのフックの迂回
- リリース、タグ付け、リポジトリ設定の変更

プッシュが拒否されたら原因を解消する。force で通さない。

## 中断と引き継ぎ

作業を始めるとき:

1. `git status`、`git log -5`、`git branch -a` で状態を見る
2. 見覚えのない未コミット変更は他者のものとして扱い、触らない

途中で止めるとき:

1. 未完了の変更を `wip/<topic>` ブランチにコミットしてプッシュする (検証が通らなくてもよい)
2. コミット本文に、終わったこと、残り、次の一手を書く
3. ブランチ名を報告する

`wip/*` は完成したら main へ取り込み、そのブランチを消してよいか利用者に確認する。

## ドキュメントの維持

| 変えたもの | 更新先 |
|---|---|
| WS 関数 | README の WS 関数表 |
| タブ、画面構成 | README の機能表、docs/DESIGN_RATIONALE.md |
| ビルドツールの版 | README のビルド節 |
| デザイントークン | DESIGN.md、次に `ui/theme/Theme.kt` |
| 新しく見つけた落とし穴、暗黙のルール | docs/CONVENTIONS.md |
| 監査の指摘への対応 | 該当する監査文書の対応状況表 |
| コミットやプッシュの運用 | このファイル |

会話の中で決まったルールや、コードを読まないと分からない事実は、次の担当者のためにこれらの文書へ残す。
