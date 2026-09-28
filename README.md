# PAC

**PAC は Java Edition と Bedrock Edition のプレイヤーを守る、Paper 向けアンチチートです。**

プレイヤーの移動とサーバーの状態を照合し、不自然な動きや操作を検知します。管理者はゲーム内のチェスト型UIから検知、通知、補正、制裁を設定できます。

<!-- pac-release-status:start -->
**最新版:** `v0.1.1` · **対応Minecraft:** `26.3`（Paper）  
[![Releases downloads](https://img.shields.io/github/downloads/gamelist1990/PAC/total?label=Releases%20downloads)](https://github.com/gamelist1990/PAC/releases) · [最新版をダウンロード](https://github.com/gamelist1990/PAC/releases/latest)
<!-- pac-release-status:end -->

> 対応するMinecraft版は上記の1バージョンです。新しい版への対応時は、配布ビルドとこの表示を一緒に更新します。

## 主な機能

| 分野 | 内容 |
| --- | --- |
| 移動 | 地上・空中・水中の移動予測、速度、飛行、地面・水面の不正な挙動、BoatFly、NoSlowdown |
| 戦闘 | KillAura、Reach、攻撃時の視点、Criticals、不正なノックバック応答 |
| ブロック操作 | Scaffold、FastPlace、FastBreak、Nuker |
| パケット・操作 | Packet Flood、Timer、Inventory Move、AntiHunger、CrashChest |
| 管理 | チェスト型UI、検知通知、履歴と統計、Debug記録、JSON出力、個別の検知・補正・自動制裁設定 |
| 連携 | 他のプラグイン向け[公開Java API](docs/API.md)、検知イベント、ワールド別設定、BANとサポートID |

Java Editionの移動検知はプレイヤーの物理とブロック・エンティティの衝突形状を参照します。Bedrock Editionは同梱のGeyser拡張で入力と移動を検証します。プラグインによる速度変更やテレポートも予測状態へ反映します。

検知モジュールと自動BANは原則として初期状態で有効です。実験的なXrayとNoClipは初期状態で無効です。Bedrockの予測差は診断と位置補正に利用し、予測差だけでは自動BANしません。

## 導入

**必要な環境:** 上記の対応Minecraft版のPaper、Java 25、PacketEvents 2.14.0。Bedrockプレイヤーを受け入れる場合はGeyser-Spigotも必要です。Floodgateは任意です。

1. PaperサーバーにPacketEventsを導入します。Bedrock対応時はGeyser-Spigotも導入します。
2. GitHubの**Releases**から `PAC-<バージョン>.jar` を入手し、サーバーの `plugins/` に配置します。
3. サーバーを起動します。PACは同梱のBedrock拡張をGeyserへ配置・更新します。
4. `/pac ui` で検知と制裁を確認します。

Bedrock拡張の確認方法は[導入ガイド](bedrock-bridge/INSTALL.md)を参照してください。

## よく使うコマンド

| コマンド | 用途 |
| --- | --- |
| `/pac ui` | 検知、補正、制裁、統計を画面で管理 |
| `/pac alerts [on\|off]` | 管理者向け通知の切り替え |
| `/pac history <ページ> [プレイヤー]` | 全体または指定プレイヤーの検知履歴 |
| `/pac debug [on\|off\|status\|export]` | 記録モードと統計JSONの出力 |
| `/pac detector list` | 検知モジュールの一覧 |
| `/pac bypass <名前またはUUID> <on\|off>` | 明示的な検知除外 |
| `/pac support <ID>` | BANのサポートIDを照会 |

全コマンドは `/pac help` を参照してください。OPであることだけでは検知を回避できません。Debug記録モードでは自動制裁を停止し、移動補正と記録は続けます。

## 開発・ビルド

Windowsでは `gradlew.bat build`、Linux/macOSでは `./gradlew build` を実行します。配布用ファイルは `build/libs/PAC-<バージョン>.jar` です。`-plain.jar` と `-shaded.jar` は配布用ではありません。

GitHubへのpushでビルドを実行します。`main` にpushされたコミット数に応じてパッチ版数を進め、テスト成功後に配布用JARをGitHub Releasesへ公開します。対応Minecraft版は `src/main/resources/plugin.yml` の `api-version` から読み取り、READMEへ反映します。Releasesの累計ダウンロード数も公開後に表示します。

PAC本体は[MIT License](LICENSE)です。同梱コンポーネントのライセンスは `third-party/` と配布JAR内の `licenses/` を参照してください。
