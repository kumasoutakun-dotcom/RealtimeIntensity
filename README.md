# 簡易リアルタイム震度計(参考値) - Android/Kotlin

Androidスマートフォンの加速度センサーを使い、気象庁・防災科研方式の
リアルタイム震度算出処理をできるだけ忠実に再現することを目指した
学習・検証用アプリです。**気象庁の正式な震度計ではありません。**
アルゴリズムの調査経緯・出典・簡略化した点は必ず `ALGORITHM.md` を
読んでください。実装の正確さに関する説明はすべてそちらに集約しています。

## ビルド方法

このプロジェクトは Android Studio (Giraffe以降を想定) でそのまま
開けるGradle構成になっていますが、`gradlew`(Gradle Wrapper本体の
バイナリ)は同梱していません。開発サンドボックス環境からは
Android SDK/Google Mavenリポジトリにネットワーク接続できず、
実機ビルドでの動作確認ができなかったためです。

1. Android Studioで「Open」からこのフォルダを開く。
   Android Studioが自動的にGradle Wrapperを生成し、依存関係を
   ダウンロードします(要インターネット接続、初回は数分かかります)。
2. `compileSdk = 34` / `minSdk = 26` としています。手元の環境に合わせて
   `app/build.gradle.kts` を調整してください。
3. `applicationId`は仮に `com.example.rtintensity` としています。
   公開/配布する場合は変更してください。

**重要**: 上記の事情により、このコードは実機やAndroid Studio上で
一度もビルド・実行して動作確認をしていません。API名やimportの誤りが
残っている可能性があるため、最初にビルドしてエラーが出た場合は
修正が必要になることを前提にしてください。

## 実装状況(何が本格実装で、何が簡易実装か)

| 項目 | 状況 |
|---|---|
| リアルタイム震度フィルタ(6段biquad+ゲイン) | 本格実装。係数の出典は`ALGORITHM.md`参照 |
| 3軸合成・震度変換・気象庁式丸め | 本格実装 |
| センサー取得・単位変換 | 本格実装 |
| CSV保存 | 本格実装(9列、要件通り) |
| 静止時オフセットのモニタ(要件9) | 本格実装(シェイク不要の常時モニタ方式) |
| 波形グラフ表示 | 簡易実装。自前Canvas描画で、生波形/フィルタ後/震度推移の3枚を表示。デザインは最小限 |
| オフライン検証(K-NET/KiK-net) | 本格実装。ただしFFTは自前の素朴なradix-2実装(ゼロ埋めあり) |
| UIデザイン全般 | 動作確認優先の簡素なレイアウト。見た目の作り込みはしていない |

## 学校のChromebookでの開発について

Android Studioはメモリ・ディスクを多く使うIDEです。学校のChromebookが
Linux(Crostini)コンテナに対応していてもリソースが厳しい場合があります。
その場合の代替案:
- クラウド上の開発環境(GitHub Codespaces等)でコードを編集し、
  ビルドだけ別のPCやCIサービスに任せる。
- 表示・波形描画・CSV保存を伴わない「フィルタ係数の計算ロジックだけ」を
  Kotlin単体(Android非依存)のコンソールプログラムとして、
  もっと軽量な環境で先に検証する、という段階的な進め方も可能です
  (`filter`パッケージと`processor`パッケージはAndroid APIに依存していないため、
  そのままKotlin/JVMのコンソールプログラムやテストとして動かせます)。

## ディレクトリ構成

```
app/src/main/java/com/example/rtintensity/
  MainActivity.kt              画面・センサー・記録の統括
  filter/                      6段biquad IIRフィルタ(Android非依存)
  processor/                   フィルタ適用・3軸合成・震度計算(Android非依存)
  sensor/                      SensorManagerのラッパー
  recorder/                    CSV書き出し
  ui/                          波形表示用カスタムView
  validation/                  K-NET/KiK-netオフライン検証
ALGORITHM.md                   アルゴリズム調査資料(A~G)
```
