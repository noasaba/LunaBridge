# Versioning policy

LunaBridgeの製品バージョンはSemVer形式で管理し、`gradle.properties`の`projectVersion`を唯一の正本とする。Paperの`plugin.yml`、Velocityのプラグイン注釈・生成メタデータ、JARファイル名は、この値からビルド時に生成しなければならない。ソースコードへ製品バージョンを重複して手書きしない。

## 現在のバージョン対応表

| 対象 | 現在値 | 正本・用途 |
| --- | --- | --- |
| 製品 | `0.3.0-beta.18` | `gradle.properties`の`projectVersion` |
| Wire protocol | LunaChat側管理 | LunaBridgeはMinecraft transportを所有しない |
| Config schema | `5` | Discord外部投稿の表示名テンプレート対応 |
| Paper API | `26.2` / build 117 | コンパイル対象および`plugin.yml`の最低API |
| Velocity API | `4.1.0-SNAPSHOT` | コンパイル依存。製品バージョンではない |

## バージョンを上げる条件

コードまたは配布リソースを変更し、生成JARの動作・公開API・プロトコル・設定解釈・依存関係・対応プラットフォームのいずれかが変わる変更セットでは、コミット前に必ず`projectVersion`を上げる。複数コミットで一つの未公開リリースを作る場合も、最初のコード変更時点で次のバージョンへ移行し、そのリリース内では同じpre-release番号を使用できる。

- PATCH: 後方互換な不具合修正、セキュリティ強化、内部改善。
- MINOR: 後方互換な機能追加。`0.x`期間中は、wire protocolなどの互換性を切る変更にも次のMINORを使用する。
- MAJOR: `1.0.0`以降の意図的な互換性破壊。
- pre-release: 検証途中の配布物は`-beta.N`または`-rc.N`を付け、同じ系列で再配布するたびに`N`を増やす。正式版に`-SNAPSHOT`を残さない。

文書・コメント・テストだけの変更で生成JARの内容や契約が変わらない場合は、製品バージョンを据え置ける。ただしコード変更と同じ変更セットに含まれる場合は例外にならない。

## 独立して管理する番号

- Wire protocol: LunaChat側のnetwork transportで管理する。LunaBridgeにはwire protocolを持たせない。
- Config schema: 永続設定のキー、意味、既定値、移行処理が変わる場合に上げ、migration testを追加する。単なる検証強化で保存形式が同じなら据え置ける。
- Paper `api-version`: 最低対応Paper/Minecraft APIを変える場合だけ更新する。製品バージョンの代わりではない。
- 依存ライブラリのバージョン: 製品バージョンとは別物。依存更新で配布物が変わる場合は製品バージョンも上げる。

## 変更時チェックリスト

1. 変更前にこの文書と`AGENTS.md`を読む。
2. 変更の互換性を判定し、`gradle.properties`の`projectVersion`を先に更新する。
3. protocolまたはconfig schemaを変える場合は、それぞれの番号と移行・拒否テストも更新する。
4. `./gradlew clean test assemble --rerun-tasks`を実行する。
5. Paper JAR内の`plugin.yml`、Velocity JAR内の`velocity-plugin.json`、JARファイル名が同じ製品バージョンであることを確認する。
6. `rg`で旧製品バージョンの残存を確認し、READMEや運用文書の成果物名を更新する。
7. バージョン更新をコード変更と同じコミットへ含める。
