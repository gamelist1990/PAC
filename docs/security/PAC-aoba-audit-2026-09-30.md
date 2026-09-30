# PAC / Aoba defensive audit — 2026-09-30

監査基準: PAC main `7209398` (v0.1.32)、Aoba master `c698b09d4f4e76db4b1dee0a0d6b2051324b9ce0`。
作業場所: `/home/pexserver/WorkSpace/DevBox/PAC`。
静的ソース照合、PACのモデルへの再現入力、JUnitによる補間・移動・プロトコル境界検証を実施。
実サーバーでAobaを起動した試験、ネットワーク遅延装置を使ったPvP試験は未実施。
この文書は「全クライアントを検出できる」という保証ではない。

## 発見事項と状態

| 優先度 | 問題 | 根拠 | この変更での状態 |
|---|---|---|---|
| 高 | 動く相手へのKillAura/Reachの幾何判定が停止 | 旧 `KillAuraCheck.onDamage` / `ReachCheck.onDamage` は `stableTargetBox` とPing 150ms以下を要求。`stableBox` は約250msの静止を要求 | 現行Javaクライアントでは送信座標とPONG応答に基づく補間候補に置換 |
| 高 | 通常の被弾・KBが攻撃検査を免除し、証拠もリセット | `recentExternalMotion` によるreturn、`onIncomingDamage` による `resetAnalysis` | KillAura/ReachのKB免除を削除。被弾時は統計的aimの状態だけをリセットし、幾何証拠を保持 |
| 高 | 全水平KB無視が新しい合法速度として学習される | `GroundMotionSequence.accept` のexternalTransitionは未確認の初回を観測変位でrebase。`externalImpulseMismatch` はfalse。モデル再生で後続offset=0 | 未修正。速度送信の前後を応答で区切り、衝突・ジャンプリセット・攻撃減速を含む複数候補のKB応答検査が必要 |
| 高 | NoFallのtrue接地フラグを検証しない | Aoba `NoFall.onSendPacket` は座標を変えずonGroundをtrueにする。PACの `GroundClaimSequence` は支持面上のfalse申告だけを扱う | 未修正。落下経路と支持面からtrue接地申告を独立に検証する必要あり |
| 中 | NaN/Infinity/範囲外パケットがcancel後も予測器へ入る | `CheckRegistry.dispatch` は全チェックに配送。予測器はcancel済みも処理し、NaNで初期化解除する経路がある | 輸送アダプタで配送前に拒否。速度やtimerでcancelされた有限座標は引き続き処理し、元の連続性対策を保持 |
| 中 | Criticalsがクライアント固有のsignature中心 | Aobaは小さい3位置パケットを使うが、PACの4パケットsignatureとmini-jump範囲に一致しない。小さい動きはburstのrise条件にも届かない | 静的に検出経路の不足を確認。実サーバーでの攻撃ダメージ成立は未検証。汎用的な接地・上昇・下降履歴による判定が必要 |
| 中 | same-tickの複数攻撃だけでmulti-target判定 | 正常パケットが遅延後に同じサーバーtickへ集中し得る | burst単独では判定せず、成立不能な攻撃rayを必要とする。幾何証拠も異なるtickを必要とする |

## モデル再生で確認した残存の穴

### 水平AntiKnockback

静止した通常地面上のプレイヤーに、server impulse `(0.8, 0, 0)` を渡し、その後も静止座標を入力。
初回のsampleは `evaluated=false`, `offset=0.3388000473678112`, `externalImpulseMismatch=false`。
続く9位置sampleの最大offsetは `0.0`、全て `externalImpulseMismatch=false`。
これはPACの地面予測モデルが、無視された水平KBを継続的なKB違反として保持していない証拠。
PAC全体・Paper本体・他プラグインの全状況で必ず成功するという実機保証ではない。
AobaのAntiKnockbackはvelocity packetやexplosion impulseのベクトルを縮小し、釣竿のentity statusもキャンセルするため、同種の応答欠落を評価する必要がある。

### NoFall

通常の重力・dragに従う20落下位置に `claimedGround=true` を付け、`GroundClaimSequence` に入力。
結果は `confirmed=0`, `repairClaim=0`。
運動座標を合法に保ったままground bitだけを偽装する挙動は、座標予測の残差だけでは検出できない。
Paper側の落下ダメージ挙動は別途実機検証が必要。

## KillAura / Reachの変更

1. 観察者ごとに、実際に送信したspawn・relative move・position sync・teleport・destroyを保持。送信前のBukkit座標でネットワーク位置を初期化しない。
2. 26.3のstepped position pathも保持し、各stepのtick offsetを補間候補の保持期間に含める。
3. 対象更新後に最大20回/秒のランダムID PINGを送信。対応するPONG以外は状態を進めない。古い/重複IDも状態を巻き戻さない。
4. PONG直後に過去位置を捨てない。後続6クライアントphysics packetを待ち、遅延burstでも補間開始位置を残す。送信時刻の250ms履歴も保持する。
5. ATTACK時点で対象候補・視点角・直近の受理位置を固定する。最大64攻撃のqueueで、後続攻撃による対象/視点の上書きを避ける。
6. Ray距離と補間率の2変数に対する凸多角形clippingで、連続したAABB補間との交差を解く。斜め移動の「二つのboxを囲む巨大box」による架空のhit空間をKillAuraに追加しない。
7. 説明できるrayが一つでもあれば幾何違反にしない。背面判定も全候補のAABB端と複数の攻撃者eyeを評価する。固定0.1blockの幾何marginを使い、申告Ping/速度からhitboxを膨らませない。
8. テレポートは応答と補間の終了まで幾何検査を保留し、離れた座標間に攻撃可能な通路を作らない。
9. pose/scaleについて5.5秒のdimension履歴を保守的に使用する。位置の移動量をdimension marginに混ぜない。
10. 応答が5秒以上停止した場合は、設定がcancelを許可するとき同期完了まで攻撃を保留する。これ自体をチートscore/BAN理由にはしない。recording modeでは既存の無介入方針に従う。
11. 対象数1024、位置160/対象、未応答128/観察者を上限とする。履歴欠落・未知のID・UUID再使用は厳格判定の証拠にしない。
12. Reachは同じ送信候補を使う。距離は各swept unionからの保守的な下限で評価し、過剰なreach拒否を避ける。

## まだ保証できない範囲

- 移動中のJava対象へのray miss / rear attack / Reachは新モデルの対象。Java 1.17未満などPING/PONG非対応の接続は静止boxの保守的fallbackを使う。
- BedrockはGeyserのAuthInputと入力mode制限を維持。MOUSE/keyboard/gamepad/controllerのみ厳格KillAura対象、touch/VRは除外。Java PONGでBedrockの表示場面を確認したと見なさない。移動対象の新補間モデルはJava向け。
- 壁ブロックの受信/変化履歴はentity履歴とは別物。壁抜き検査は静止した対象と近傍collision変化のない条件を残している。完全なmoving-target through-wall判定にはブロック場面同期が必要。
- ACKがない初回spawn、履歴overflow、車両、未確認テレポートなどは不確かな幾何から違反を作らない。プラグインを途中でenableした接続は、絶対位置sync/teleportが来るまで移動対象のnetwork baselineがないことがある。
- 正しいray/rangeを維持し、人間と同じ入力列を生成する自動攻撃は幾何だけでは断定不能。Aobaのraycast/rotationを合法に保つmodeに対する「必ず検出」の保証はしない。
- 追加のPING/PONGはPacketEvents/ViaVersion/他のtransaction利用プラグインとの実機互換性確認が必要。

## その他のAoba挙動とPACの照合

| Aoba挙動 | PAC側の主な検査経路 | 評価 |
|---|---|---|
| Fly / anti-kick / Jetpack / Glide | air gravity recurrence, AirGravityWindow, AirHoverWindow, Elytra replay | 対応経路あり。全modeの実機検出は未検証 |
| Speed / Strafe / NoSlowdown | ground/air motion replay, sustained speed envelope, item-use multipliers | サーバー属性を基準とする経路あり。閾値内の小さい改変は別途長期評価が必要 |
| Timer | movement timer, decoded packet flood, server tick timing | 対応経路あり。報告Pingだけでphysics tickを追加しない |
| Noclip / ClickTP / MaceAura | collision/range, rapid position jump, positionless frame制限 | 大きい移動への対応あり。signatureだけで全modeを保証しない |
| Nuker / FastBreak / Scaffold | digging progress, action windows, place geometry | 対応経路あり。多彩な条件での実機テストが必要 |
| AntiKnockback / NoFall / Criticals | 上述の不足 | 優先的な追加修正候補 |
| XRay / ESP / Freecamなど視覚情報の変更 | サーバー側情報配信、optional Xray統計 | 正常通信と同一なら運動予測でクライアントの見た目を識別できない |

## 検証

`bash gradlew test build --no-daemon --console=plain`。
新規ケース: 移動対象の正当な攻撃/完全背面攻撃、パケット間補間、斜め移動の偽hit空間、ジャンプ・多数の補間率、未知/再送PONG、テレポート、ACK停止と復帰、履歴overflow、遅延burst、pose寸法の保持、NaN/Infinity/範囲外/不正pitch、same-tick証拠の除外。
最終テスト集計: 558 tests, 0 failures, 0 errors, 0 skipped.

実機確認には、通常client/Aobaの両方で、横移動・ジャンプ・被弾・sprint jump reset・複数対象・高RTT・jitter・burst・teleport・pose変更を組み合わせたPvP replayが必要。
本監査では本番サーバーの設定変更・JAR差し替え・main mergeは実施していない。

## ソース

- PAC baseline: https://github.com/gamelist1990/PAC/tree/7209398
- Aoba pinned source: https://github.com/Cocolots/Aoba-Client/tree/c698b09d4f4e76db4b1dee0a0d6b2051324b9ce0
- Aoba modules: `src/main/java/net/aoba/module/modules/` (combat/KillAura, AntiKnockback, Criticals; movement/NoFall, Fly, Speed, Noclip; misc/Timer)
