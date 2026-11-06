
Design · MD
---
name: Campus — University Timetable & Moodle Client
version: 1.1.1
updated: 2026-11-06
platforms: [ios, android, web]
colors:
  # ---- Light ----
  canvas: "#F2F1EE"          # アプリ背景（わずかに温かいオフホワイト。白いカードとの差で面を分ける）
  surface: "#FFFFFF"         # カード・シート
  surface-sunken: "#EFEEEA"  # 時間割グリッドの空きコマ、入力欄
  hairline: "#E4E2DD"        # 0.5–1px 区切り線
  ink: "#1B1B1F"             # 本文・見出し            (on surface 17.2:1)
  ink-secondary: "#5C5C66"   # 補足テキスト            (on surface 6.6:1 / on canvas 5.9:1)
  ink-tertiary: "#8B8B94"    # アイコン・装飾のみ。本文に使用禁止 (3.4:1)
  accent: "#2B59D9"          # 唯一のブランドアクセント (5.9:1)
  on-accent: "#FFFFFF"
  accent-soft: "#E8EEFF"     # 選択状態の背景
  danger: "#C8372D"          # 期限切れ・エラー (5.2:1)
  warning: "#A15C00"         # 24時間以内の締切 (5.2:1)
  success: "#1D7A4F"         # 提出済み・完了 (5.3:1)
  danger-soft: "#FDF1EF"     # 期限切れ・エラーの面 (danger 文字 4.7:1)
  warning-soft: "#FCF0DC"    # 24時間以内の面 (warning 文字 4.6:1)
  success-soft: "#E2F3EA"    # 提出済みの面 (success 文字 4.6:1)
  # ---- Dark ----
  canvas-dark: "#0E0E10"
  surface-dark: "#1C1C1F"
  surface-sunken-dark: "#141416"
  hairline-dark: "#2A2A2F"
  ink-dark: "#EDEDF0"            # 14.9:1
  ink-secondary-dark: "#A3A3AD"  # 7.0:1
  ink-tertiary-dark: "#74747E"   # 3.8:1 装飾のみ
  accent-dark: "#8AA6FF"         # 7.4:1
  on-accent-dark: "#0E1633"
  accent-soft-dark: "#1E2747"
  danger-dark: "#FF8A7F"
  warning-dark: "#F2B24C"
  success-dark: "#5CCB94"
  danger-soft-dark: "#3A1E1C"
  warning-soft-dark: "#33270F"
  success-soft-dark: "#15291F"
  # ---- Course palette（授業カラー。bg/fg ペアで使う。全ペア 5.4:1 以上を検証済み）----
  course-blue:   { bg: "#E3ECFF", fg: "#1F47B8", bg-dark: "#1B2A52", fg-dark: "#A9C1FF" }
  course-teal:   { bg: "#DDF3F0", fg: "#0B6B60", bg-dark: "#12332F", fg-dark: "#7FD6C9" }
  course-green:  { bg: "#E3F4E1", fg: "#2A6B22", bg-dark: "#1A331A", fg-dark: "#94D38A" }
  course-olive:  { bg: "#F0F1D9", fg: "#5B5F12", bg-dark: "#2D2F14", fg-dark: "#D0D47A" }
  course-amber:  { bg: "#FCEFD6", fg: "#8A5200", bg-dark: "#3A2A10", fg-dark: "#F2C06B" }
  course-orange: { bg: "#FDE6DA", fg: "#A13F12", bg-dark: "#3D2116", fg-dark: "#FFB08A" }
  course-red:    { bg: "#FCE3E3", fg: "#A8262B", bg-dark: "#3E1C1E", fg-dark: "#FF9EA0" }
  course-pink:   { bg: "#FBE4F0", fg: "#9C2366", bg-dark: "#3B1A2C", fg-dark: "#FF9CCF" }
  course-purple: { bg: "#EEE6FB", fg: "#5B34A8", bg-dark: "#2A2040", fg-dark: "#C8AFFF" }
  course-slate:  { bg: "#E7EAEE", fg: "#3D4A5C", bg-dark: "#252A31", fg-dark: "#B7C2D1" }
typography:
  fontFamily-ja: '"Hiragino Sans", "Noto Sans JP", "Noto Sans CJK JP", "Yu Gothic UI", system-ui, sans-serif'
  fontFamily-latin: 'system-ui, -apple-system, "SF Pro Text", Roboto, "Segoe UI", sans-serif'
  fontFamily-mono: 'ui-monospace, "SF Mono", "Roboto Mono", monospace'
  numeric: tabular-nums      # 時刻・時限・残り時間は必ず等幅数字
  display:     { fontSize: 28px, fontWeight: 700, lineHeight: 1.25, letterSpacing: 0 }
  title-lg:    { fontSize: 22px, fontWeight: 700, lineHeight: 1.3 }
  title-md:    { fontSize: 17px, fontWeight: 600, lineHeight: 1.4 }
  body:        { fontSize: 16px, fontWeight: 400, lineHeight: 1.6 }
  body-sm:     { fontSize: 14px, fontWeight: 400, lineHeight: 1.55 }
  label:       { fontSize: 13px, fontWeight: 600, lineHeight: 1.3 }
  caption:     { fontSize: 12px, fontWeight: 400, lineHeight: 1.4 }
  grid-cell:   { fontSize: 11px, fontWeight: 600, lineHeight: 1.25 }   # 時間割セル専用。これ未満は禁止
  countdown:   { fontSize: 34px, fontWeight: 700, lineHeight: 1.1, fontVariantNumeric: tabular-nums }
spacing:
  base: 4px
  xxs: 2px
  xs: 4px
  sm: 8px
  md: 12px
  lg: 16px      # 画面左右マージン（phone）
  xl: 24px      # セクション間
  xxl: 32px
  gutter-phone: 16px
  gutter-tablet: 24px
rounded:
  xs: 6px       # チップ・バッジ
  sm: 8px       # 時間割セル
  md: 14px      # カード
  lg: 20px      # シート・大型カード
  pill: 999px   # セグメント・タブ・FAB
size:
  hit-min-ios: 44px
  hit-min-android: 48px
  grid-row-min: 64px
  grid-header-col: 28px
  tabbar-items-max: 5
motion:
  duration-fast: 120ms
  duration-base: 220ms
  duration-slow: 360ms
  easing-standard: cubic-bezier(0.2, 0, 0, 1)
  spring-ui: { stiffness: 380, damping: 32 }      # ボタン・チェック
  spring-layout: { stiffness: 260, damping: 30 }  # シート・カード展開
components:
  timetable-cell:
    backgroundColor: "{colors.course-*.bg}"
    textColor: "{colors.course-*.fg}"
    typography: "{typography.grid-cell}"
    rounded: "{rounded.sm}"
    padding: 6px
  timetable-cell-empty:
    backgroundColor: transparent
    border: 1px dashed "{colors.hairline}"
    rounded: "{rounded.sm}"
  now-card:
    backgroundColor: "{colors.course-*.bg}"   # 授業がないときは surface + hairline
    rounded: "{rounded.lg}"
    padding: "{spacing.lg}"
  deadline-row:
    minHeight: 56px
    padding: 12px 16px
    typography: "{typography.body-sm}"
  card:
    backgroundColor: "{colors.surface}"
    rounded: "{rounded.md}"
    padding: "{spacing.lg}"
    border: 0.5px "{colors.hairline}"
    shadow: none
  detail-card:                 # 詳細画面のまとまり (説明・詳細・提出物)
    backgroundColor: "{colors.surface}"
    rounded: "{rounded.md}"
    padding: "{spacing.lg}"
    header: 28px icon-tile (accent-soft / accent) + "{typography.title-md}"
  status-hero:                 # 詳細画面最上部の状態カード (提出期限・受付終了)
    backgroundColor: "{colors.*-soft}"   # 状態の色調。通常は accent-soft
    rounded: "{rounded.lg}"
    padding: "{spacing.lg}"
  grouped-list:                # 一覧はまとまりごとに1枚のカードにする
    backgroundColor: "{colors.surface}"
    rounded: "{rounded.md}"    # 先頭と末尾の角だけ
    margin: 0 "{spacing.gutter-phone}"
    divider: 0.5px "{colors.hairline}" (行頭の印の分だけ字下げ)
  button-primary:
    backgroundColor: "{colors.accent}"
    textColor: "{colors.on-accent}"
    typography: "{typography.label}"
    rounded: "{rounded.pill}"
    height: 48px
    padding: 0 20px
  chip-status:
    typography: "{typography.caption}"
    rounded: "{rounded.xs}"
    padding: 2px 8px
  chip-status-tone:            # 色調つきの札 (期限切れ・24時間以内の締切、未読件数など)
    backgroundColor: "{colors.*-soft}"
    typography: "{typography.label}"
    numeric: tabular-nums
    rounded: "{rounded.xs}"
    padding: 4px 8px
---
 
# Campus DESIGN.md
 
大学の時間割 + Moodle サードパーティクライアント用のデザインシステム。
**エージェントはUIを生成・変更する前に必ずこのファイル全体を読むこと。**
トークン（上のYAML）は正確な値、本文はその使い方と理由。迷ったら本文の「原則」と「Do / Don't」を優先する。
 
---
 
## 0. このシステムの出典（何から抽出したか）
 
以下の「デザインで評価されているアプリ／公式ガイド」から、時間割・LMSクライアントに転用できる特徴だけを抽出している。ブランド固有の見た目（ロゴ・固有イラスト・配色の完全コピー）は持ち込まない。
 
| 参照元 | 評価の根拠 | 抽出した特徴 |
|---|---|---|
| Things 3（Cultured Code） | 余白とタイポグラフィ中心のミニマルUIが長年高評価。Today / Upcoming のビュー分け | 「今日やること」を最上位に置く情報設計／余白で階層を作り、線や影を減らす／完了時の小さく気持ちいいアニメーション |
| Fantastical（Flexibits） | DayTicker が「最高のカスタムiPhone UIの一つ」と評される | 横スクロールの日付リボン＋下の縦リスト連動／タイトルタップで「今日」に戻る／予定のない日を圧縮して表示 |
| Structured | 「最もよくデザインされた計画UIの一つ」と評されるビジュアルタイムライン | 1日を縦のタイムラインで可視化／現在時刻ライン／色+アイコンでブロックを識別 |
| Tide Guide（ADA 2026 Visuals & Graphics 受賞） | 大量の数値データを全画面チャートで明快に表示、空の色に合わせて配色が変化 | データが多くても「一目で読める」密度設計／時間帯で変わる控えめな背景トーン |
| Moonlitt（ADA 2026 Interaction 受賞） | 「best-in-class Liquid Glass integration」と評された | ガラス素材はナビゲーション層だけ、コンテンツは不透明で読みやすく |
| Focus Friend（Google Play Best App 2025） | 学習集中アプリ、「helpful and cute」 | 学生向けに適度な愛嬌（空状態・完了時）。ただし業務的画面には持ち込まない |
| Penmark（国内大学生向け時間割アプリ） | 国内で大規模に使われる時間割アプリ | 月〜土 × 1〜7限グリッド、課題締切・出欠数を一覧で確認、授業ごとの色分け |
| Apple HIG（Liquid Glass / iOS 26〜） | 公式 | タブバーはトップレベル遷移専用、コンテンツ層とUI層を分離、スクロールでタブバー縮小、ヒット領域44pt |
| Material 3 Expressive | 公式 | 強調タイポで重要情報を目立たせる／関連要素をコンテナでグループ化／スプリングモーション／タッチターゲット48dp |
| SmartHR Design System・デジタル庁デザインシステム | 日本語UIの公式ガイド | 日本語本文の行送り1.5倍以上、見出しは約1.25倍 |
 
---
 
## 1. 原則（優先順位順）
 
1. **「次に何があるか」を1秒で答える。** 起動直後の画面は「今/次の授業（教室・開始まで何分）」と「直近の締切」。時間割グリッドはその次。
2. **コンテンツ層は静かに、操作層は浮かせる。** 時間割・課題リストなどのコンテンツは不透明・フラット。ナビゲーション（タブバー、ツールバー、FAB）だけがOSのマテリアル（iOSはLiquid Glass、Androidはtonal surface）を使う。
3. **色は意味にだけ使う。** 色の役割は3つだけ：①授業の識別（course palette）②状態（danger/warning/success）③唯一のアクセント（操作可能・選択中）。装飾のための色は使わない。
4. **線より余白、影より面の明度差。** 区切りはまず余白、次に面の色差、最後に0.5–1pxのhairline。カードに影は付けない。
5. **日本語で崩れない。** 授業名は長い（例:「情報理工学特別講義Ⅱ（データサイエンス応用）」）。すべてのテキスト要素に「長い日本語が入った場合」の振る舞いを定義する。
6. **オフラインと同期状態を常に正直に表示する。** Moodleのデータは古くなる。最終同期時刻を隠さない。
7. **ネイティブ部品を優先する。** タブバー・ナビゲーションバー・シート・メニューはOS標準コンポーネントを使い、見た目だけ真似た自作部品を作らない。
---
 
## 2. 情報設計（ナビゲーション）
 
### タブ（最大5、推奨4）
| タブ | 中身 | 備考 |
|---|---|---|
| **今日** | Now Card、今日の授業タイムライン、今日〜48時間の締切 | 起動時のデフォルト |
| **時間割** | 週グリッド（月〜金/土）、学期・クォーター切替 | 起動設定で「時間割を最初に開く」も選べる |
| **課題** | Moodle の締切一覧（期限切れ / 今日 / 明日 / 今週 / それ以降） | バッジは「24時間以内の未提出件数」のみ |
| **コース** | 履修中コース一覧 → コース詳細（セクション/活動） | |
| （設定はコース or プロフィールから到達。タブにしない） | | |
 
- タブバーはトップレベル遷移専用。「提出」「追加」などのアクションをタブに入れない。
- iOS：タブバーはスクロールダウンで縮小（minimize on scroll）。**次の授業のミニ表示**をタブバー上のアクセサリ領域に置いてよい（音楽アプリのミニプレーヤーと同じ位置づけ）。
- Android：Navigation Bar（M3）。アクセサリの代わりに「今日」タブ上部に固定。
### 画面遷移の深さ
- 最大3階層（タブ → コース → 活動）。4階層目が必要ならシートで出す。
- 活動（課題・小テスト・フォーラム）の詳細は push、編集・提出・メモはシート。
---
 
## 3. 色
 
### 使い方
- `canvas` はアプリ背景、`surface` はカード。ライトでは canvas をわずかに暗くし、白いカードを浮かせる（影なしで階層が出る）。
- `accent` は1画面に「主要操作1つ＋選択状態」まで。リンク文字、選択中タブ、主要ボタン、現在時刻ラインに使う。
- `ink-tertiary` はコントラスト3.4:1のため **アイコン・区切り記号・プレースホルダー以外の文字に使わない**。
- 状態色は必ず **テキストかアイコンと併用**（色だけで状態を伝えない）。
### 授業カラー（course palette）
- 10色。bg（面）と fg（文字・アイコン）を必ずペアで使う。bg 上に `ink` を載せてもよいが、fg の方が識別性が高いので推奨は fg。
- 自動割り当て：コースIDのハッシュ mod 10。隣接コマ（同曜日の連続時限・同時限の隣曜日）で同色が並ぶ場合は次の色にずらす。
- ユーザーが変更可能。ただしパレット外の任意色（カラーピッカー）は提供しない（コントラスト保証が崩れるため）。
- 授業カラーを UI クローム（タブバー、ボタン）に使わない。授業に属する要素にだけ使う。
- 終わった授業は半透明にせず、`surface-sunken` の面と `ink-secondary` の文字で控えめにする。
### ダーク
- 純黒 `#000` は使わない（`canvas-dark #111113`）。OLEDでのスミアとコントラスト過多を避ける。
- ダークでは面の明度差で階層を作る：canvas-dark < surface-sunken-dark < surface-dark。
- 授業カラーは `bg-dark` / `fg-dark` に切り替える。ライトの bg に透明度をかけて流用しない。
### 時間帯トーン（任意・控えめに）
Tide Guide 由来。「今日」画面の最上部背景だけ、朝・昼・夕方・夜で canvas を ±2% 程度色相シフトしてよい。コントラストに影響する変更は禁止。設定でOFF可能にする。
 
---
 
## 4. タイポグラフィ（日本語前提）
 
- フォントはOSのシステムフォント（iOS: ヒラギノ角ゴ/SF、Android: Noto Sans CJK JP/Roboto）。Webフォントを同梱しない。
- 本文 16px / 行送り 1.6。見出しは 1.25〜1.4。日本語本文を 1.5 未満にしない。
- **時刻・時限・日数・残り時間は必ず `tabular-nums`**。カウントダウンが揺れないように。
- 最小サイズは `caption 12px`。例外は時間割セル `grid-cell 11px` のみ（それ以下にしない。収まらない場合は省略表示に切り替える）。
- 太さは 400 / 600 / 700 の3段階のみ。
- 全角英数字をUI文言に使わない（`１限` ではなく `1限`）。
- Dynamic Type / フォントサイズ設定に追従する。最大サイズ時は時間割グリッドを「リスト表示」に自動切替。
### 強調（M3 Expressive の emphasized type の考え方）
重要情報は「色」より先に「サイズと太さ」で強調する。例：Now Card の「あと 12 分」は `countdown` スタイル、教室名は `title-md`、授業名は `body`。
 
### 長い授業名の扱い
| 場所 | 振る舞い |
|---|---|
| 時間割セル | 最大3行、末尾省略。ユーザー設定の「略称」があれば優先 |
| Now Card | 2行まで、末尾省略 |
| リスト行 | 2行まで、末尾省略。詳細画面で全文 |
| 画面タイトル | 大タイトル時2行まで、スクロール後のインラインタイトルは1行省略 |
 
---
 
## 5. レイアウトと余白
 
- 4pxグリッド。値は YAML の spacing トークンのみ使用（間の値を作らない）。
- 画面左右マージン：phone 16px / tablet 24px。
- セクション間 24px、カード内パディング 16px、リスト行の上下 12px。
- ヒット領域：iOS 44×44pt、Android 48×48dp 以上。見た目が小さいアイコンはパディングで領域を広げる。隣接ターゲット間は 8px 以上。
- セーフエリア・ジェスチャーバー領域にコンテンツの操作要素を置かない。
### 画面幅ごとの時間割
| 幅 | 表示 |
|---|---|
| < 360px | 曜日5列、セルは略称＋教室のみ |
| 360–599px（一般的なスマホ） | 曜日5〜6列、セルは授業名2〜3行＋教室 |
| 600–839px（折りたたみ・小型タブレット） | 左に週グリッド、右に選択コマの詳細（2ペイン） |
| ≥ 840px | 週グリッド＋右サイドに今日の締切 |
 
---
 
## 6. 形状・素材・奥行き
 
- 角丸：チップ6 / セル8 / カード14 / シート20 / ボタン・セグメントはピル。
- ネストした角丸は「外側の角丸 − 内側の余白」で内側を決める（同心円的に揃える。iOS 26 の concentric の考え方）。
- **影は使わない**（例外：OS標準のシート・メニュー・FABが持つ影）。
- ガラス/ブラー素材はナビゲーション層のみ。**時間割セル、カード、リスト行にブラー・半透明を使わない**。ガラスを重ねない。
- iOS では標準の TabView / NavigationStack / sheet を使えば自動で Liquid Glass になる。背景色を上書きしてガラス効果を壊さない。
---
 
## 7. モーション
 
- ベースは短く・物理的に。UI操作はスプリング（`spring-ui`）、レイアウト変化は `spring-layout`、フェードは `duration-fast`。
- 意味のあるモーションだけ：
  - **課題の完了チェック**：円が塗られる→行が 220ms で「提出済み」セクションへ移動（Things 3 のチェック感）。
  - **時間割セル → 詳細**：セルがそのままカードに拡大する共有要素遷移（container transform）。
  - **「今日」へ戻る**：画面タイトル or 時間割の曜日ヘッダーをタップで今日/今週にスクロール（Fantastical）。
  - **現在時刻ライン**：1分ごとに位置更新。アニメーションはしない（常時動くものを作らない）。
- 「視差効果を減らす / Remove animations」設定時はすべてクロスフェード（≤120ms）に置き換える。
- ローディングは 300ms 未満なら何も出さない。それ以上はスケルトン（形だけの灰色面）。スピナー単体の全画面表示は禁止。
---
 
## 8. コンポーネント
 
### 8.1 Now Card（「今日」画面の最上部）
```
╭──────────────────────────────╮
│ 次の授業 · 3限                  │  label / ink-secondary
│ あと 12 分                      │  countdown / ink
│ データモデル論                   │  body / ink（2行まで）
│ 📍 クリエーションコア C301       │  title-md / course fg
│ 13:00–14:35   [出席を記録]      │  caption tabular / ボタンは secondary
╰──────────────────────────────╯
```
- カード全体を授業カラーの bg で塗る（授業がないときは surface + hairline）。左端の色帯は使わない。
- 状態遷移：授業前「あと N 分」→ 授業中「残り N 分」＋進捗バー → 授業後は次の授業へ。今日の授業が終われば「今日の授業は終わりです」＋明日の1限。
- 空き時間が90分以上あるときは「空きコマ 2時間」と表示（学生の実際の関心事）。
- タップで授業詳細。長押しでメモ。
### 8.2 時間割グリッド
- 行＝時限（1〜7限、大学ごとに開始終了時刻を設定可能）、列＝曜日（月〜金、土はデータがある場合のみ表示）。
- 行ヘッダー（28px幅）：時限番号 `label`、その下に開始時刻 `caption` tabular。
- 列ヘッダー：曜日。**今日の曜日は accent 文字＋accent-soft の丸背景**。
- セル：`timetable-cell`。授業名（fg、600、最大3行）＋教室（caption、1行）。連続時限の授業は1つのセルとして縦に結合。
- 空きコマ：塗らない。hairline の破線枠のみ、タップで「授業を追加」。
- 現在の時限：該当行のヘッダーに accent のドット、今日の列の該当セルに 2px の accent 枠。
- 休講・教室変更：セル右上に小さなバッジ（danger/warning）。色だけでなく「休」「変」の文字を入れる。
- 未読の Moodle 更新：セル右上に 6px のドット（accent）。数字は出さない。
- グリッドは1画面に1週間全体が収まることを優先（縦スクロールは7限まで入らない場合のみ）。
- 学期切替は画面上部のセグメント（春/秋、Q1–Q4）。
### 8.3 課題（締切）リスト
- セクション見出し：「期限切れ」「今日」「明日」「今週」「来週以降」。Moodle 5.0 と同じく相対表現（今日/明日）を使い、7日以上先は `10/14(火)` 形式。
- 行構成（左→右）：完了チェック（44pt領域）｜授業カラーの 8px ドット＋授業略称 `caption`｜課題名 `body-sm` の 600（完了扱いは 400）、2行まで｜右端に締切 `caption tabular`（期限切れ・24時間以内は `chip-status-tone`）。
- 締切の色：期限切れ `danger`、24時間以内 `warning`、それ以外 `ink-secondary`。**必ず「あと3時間」「2日前に期限切れ」などの文字と併用**。
- 提出済みはセクション末尾の折りたたみ「提出済み（4）」へ移動。
- スワイプ：右→「完了にする（アプリ内のみ）」、左→「リマインダー」。Moodle 上の提出と、アプリ内の「完了扱い」を**視覚的に区別**する（提出済み＝success の✓、手動完了＝ink-secondary の✓）。
- 種類アイコン（課題/小テスト/フォーラム/その他）は ink-secondary の単色線アイコン。
### 8.4 コース一覧・コース詳細
- 一覧：1件ずつのカードではなく、まとまり（今学期・過去など）ごとに1枚のカードにした行のリスト（grouped-list）。行頭に授業カラーの角丸スクエア（36px、bg色＋頭文字 fg）。
- 詳細ヘッダー：カードに、授業カラーのスクエア（48px）＋コース名（title-lg、2行）、曜日時限・教室の札（授業カラー bg＋fg）、「シラバス」「Moodleで開く」。
- セクション（週/トピック）はセクションごとに1枚のカードにし、折りたたむ。**現在週のセクションを自動で展開し、そこへスクロール**。
- 活動行：種類のアイコンタイル（課題・小テストは accent-soft、資料等は surface-sunken）＋名称（600）＋右端に状態（完了✓、締切、ダウンロード済みアイコン）。
- ファイル：ダウンロード状態を3種で表示（未取得 / 取得中の進捗リング / 取得済み）。オフラインで開けるかが一目でわかること。
### 8.4.1 詳細画面（課題・小テスト・フォーラム・ページ）
- 文章を並べるだけにしない。上から「種類（アイコン＋課題など）と題名」→ status-hero → detail-card の順に積む。
- status-hero：期限の日時を title-lg、残り時間を状態色で、状態の札は surface 地に状態色の文字で載せる。
- detail-card：見出しにアイコンタイル（28px）。「ラベル … 値」はアイコン付きの行にし、値を 600 で右に寄せ、行間に hairline。
- ファイルは種類のアイコンタイル（40px、accent-soft）＋ファイル名（600）＋メタ情報1行＋文字ボタン。
- フォーラムの投稿は1件ずつカードにし、投稿者の頭文字の丸と日時を先頭に置く。
- Primary ボタン（最終提出・受験する）は画面の末尾に全幅で1つだけ。
### 8.5 同期・オフライン表示
- 画面上部ではなく、各リストの末尾に `caption / ink-secondary` で「最終同期 9:41」。
- オフライン時：ナビゲーションバー直下に 1行のバナー（surface-sunken 背景、ink-secondary、「オフライン — 9:41 時点の情報」）。赤くしない。
- 同期失敗：該当リストの先頭にインラインで「更新できませんでした [再試行]」。トーストだけで済ませない。
- プルリフレッシュは全リストで有効。
### 8.6 空状態
- 1文の説明＋1つの操作。イラストは小さく（最大96px）、ブランドの線画1種類のみ。
- 例：課題ゼロ →「今週の締切はありません 🎉」は可。キャラクター・過度な祝福演出は不可（Focus Friend的な愛嬌は空状態と完了時のみに留める）。
### 8.7 ボタン
- Primary：accent 塗り、ピル、高さ48。1画面1つまで。
- Secondary：accent-soft 塗り＋accent文字。
- Tertiary：文字のみ accent。
- 破壊的操作（コース非表示、ログアウト）：danger 文字の tertiary ＋確認シート。
- アイコンのみのボタンにはアクセシビリティラベル必須。
### 8.8 通知・ウィジェット
- 通知文面は「【データモデル論】レポート2 の締切まで3時間」のように授業名を先頭に。
- ホーム画面ウィジェット：小＝Now Card の縮小版、中＝今日の授業リスト、大＝今週グリッド。ウィジェット内でも授業カラーと tabular-nums を維持。
- iOS Live Activity / Android の常駐通知：授業中の残り時間（任意機能、デフォルトOFF）。
---
 
## 9. 主要画面の構成（上から順に）
 
**今日**：大タイトル「今日 9月28日(月)」→ Now Card → 今日の授業タイムライン（縦、現在時刻ライン付き、Structured 的）→ 締切（今日・明日のみ、最大5件＋「すべて見る」）→ 最終同期
 
**時間割**：学期セグメント → 週グリッド → （タブレットのみ）右ペイン詳細
 
**課題**：フィルタ（すべて / 未提出 / コース別）→ セクション化リスト → 提出済み折りたたみ
 
**コース**：検索 → 今学期のコース → 過去のコース（折りたたみ）
 
**授業詳細（時間割セルから）**：コース名 → 曜日時限・教室・教員 → 出欠カウンター（出席 / 遅刻 / 欠席、数字は tabular）→ 直近の締切 → メモ → Moodleコースへ
 
---
 
## 10. 文言（マイクロコピー）
 
- です・ます調は通知と空状態のみ。ラベル・ボタンは体言止め（「提出」「再試行」）。
- 時刻：`13:00`（24時間制）。日付：`9/28(月)`。年が違う場合のみ `2027/1/15(金)`。
- 時限：`3限`。範囲：`3–4限`（en dash）。
- 相対時間：`あと12分` `残り40分` `3時間後` `明日 23:59` `2日前に期限切れ`。
- Moodle 用語はアプリ内で統一：assignment＝課題、quiz＝小テスト、forum＝フォーラム、resource＝資料。
---
 
## 11. アクセシビリティ（必須チェック）
 
- テキストのコントラスト 4.5:1 以上（大きい文字 3:1）。本ファイルのトークンは検証済み。新色を追加する場合は必ず再計算する。
- 色だけで情報を伝えない（状態は文字/アイコンを併記、授業は名称を併記）。
- ヒット領域 44pt / 48dp。
- スクリーンリーダー：時間割セルの読み上げ順は「月曜 3限、データモデル論、教室 C301、休講」の形式。グリッドは行（時限）ではなく曜日→時限の順で読み上げる。
- 最大フォントサイズでレイアウトが破綻しないこと（グリッド→リスト自動切替）。
- 動きを減らす設定を尊重。
---
 
## 12. プラットフォーム別の適用
 
| 項目 | iOS (26+) | Android (M3 Expressive) |
|---|---|---|
| ナビゲーション | 標準 TabView（Liquid Glass、縮小対応、アクセサリに次の授業） | Navigation Bar / 大画面は Navigation Rail |
| 主要追加操作 | ツールバー右上の「＋」 | FAB（extended は「今日」画面のみ） |
| シート | 標準 sheet（detents: medium / large） | Modal Bottom Sheet |
| 色 | 本ファイルのトークン固定 | 本ファイルのトークン固定（Dynamic Color は**使わない**：授業カラーのコントラスト保証が崩れるため。設定で任意ON可能にする場合も授業カラーは固定） |
| モーション | システムのスプリング | M3 Expressive の motion scheme（standard を基本、完了時のみ expressive） |
| ヒット領域 | 44pt | 48dp |
 
---
 
## 13. Do / Don't
 
**Do**
- 起動直後に「次の授業」と「直近の締切」が見えるようにする。
- 時刻・残り時間に tabular-nums を使う。
- 授業に属する要素だけに授業カラーを使う。
- 長い日本語授業名で全画面を確認する。
- 最終同期時刻とオフライン状態を表示する。
- OS標準のナビゲーション部品を使う。
**Don't**
- カードに影を付けない。グラデーション背景を使わない。
- カードや行の左端に色帯（アクセントバー）を引いて強調しない。色は面（bg）・アイコンタイル・札で付ける。
- コンテンツ（セル・カード・リスト）にガラス/ブラー/半透明を使わない。ガラスを重ねない。
- accent 以外のブランド色を追加しない。1画面に Primary ボタンを2つ置かない。
- `ink-tertiary` を本文に使わない。11px 未満の文字を作らない。
- 締切を色だけで表現しない。赤を「期限切れ」とエラー以外に使わない（ファイルの種類などに使わない）。
- タブを5つより増やさない。タブにアクションを入れない。
- 全画面スピナー、自動で消えるだけのエラートーストを使わない。
- 授業カラーを任意のカラーピッカーで選ばせない。
- 大学公式アプリ・Moodle公式のロゴや配色を模倣しない（非公式クライアントであることを誤認させない）。
---
 
## 14. エージェント向け最終チェックリスト
 
UIを生成・変更したら、コミット前に以下をすべて確認する。
 
- [ ] 使った色・余白・角丸・文字サイズがすべて YAML トークン由来か（直書きの値がないか）
- [ ] ライト/ダーク両方で表示を確認したか
- [ ] 「情報理工学特別講義Ⅱ（データサイエンス応用）」級の長い授業名で崩れないか
- [ ] 時刻・数値に tabular-nums が効いているか
- [ ] 状態が色＋文字/アイコンで伝わるか
- [ ] ヒット領域が 44pt / 48dp 以上か
- [ ] オフライン・同期失敗・空・ローディング（スケルトン）の4状態を実装したか
- [ ] 最大フォントサイズ・動きを減らす設定で確認したか
- [ ] ガラス素材がナビゲーション層だけに使われているか