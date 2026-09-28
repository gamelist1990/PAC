# PAC Java API

PAC は Bukkit Services Manager を通じて、他の Bukkit / Paper プラグイン向けの Java API を提供します。HTTP サーバーは起動しません。公開 API は `org.pexserver.pac.api.PacApi` で、現在のバージョンは `1` です。

## 1. 連携プラグインから使う

PAC をコンパイル時だけの依存にし、連携プラグインのJARへ PAC のクラスを同梱しないでください。

```kotlin
dependencies {
    compileOnly(files("libs/PAC-0.1.0.jar"))
}
```

`plugin.yml` では起動順のため `softdepend` に追加します。

```yaml
softdepend: [PAC]
```

サービスは `onEnable` で取得し、未導入時は `null` を扱います。

```java
import org.bukkit.Bukkit;
import org.pexserver.pac.api.PacApi;

public final class MyPlugin extends JavaPlugin {
    private PacApi pac;

    @Override
    public void onEnable() {
        pac = Bukkit.getServicesManager().load(PacApi.class);
        if (pac == null) {
            getLogger().info("PAC is not installed; PAC integration is disabled.");
        }
    }
}
```

## 2. 読み取りと変更の権限

| 操作 | 初期状態 | 実行スレッド |
|---|---|---|
| 検知器、設定、BAN、統計の読み取り | 許可 | APIにより同期または非同期 |
| `detectorStates()`、`settings()`、設定値の読み取り | 許可 | メインスレッド |
| 検知器・設定・ワールド上書きの変更 | `api.control-authority: true` が必要 | メインスレッド |
| 偽検知マークの変更 | `api.control-authority: true` が必要 | API内部で非同期 |
| BAN・解除 | `api.control-authority: true` が必要 | API内部で非同期 |
| 履歴、統計、JSONエクスポート | 許可 | `CompletableFuture` で非同期 |

初期設定の `api.control-authority` は `false` です。変更操作を許可するには `plugins/PAC/config.yml` で `api.control-authority: true` にし、`/pac reload` を実行してください。このフラグ自体はAPIから変更できません。

メインスレッド指定のメソッドを別スレッドから呼ぶと例外になります。DBを読む・書くメソッドは `CompletableFuture` を返します。完了時の処理で Bukkit API を使う場合は、`Bukkit.getScheduler().runTask(...)` でメインスレッドへ戻してください。

## 3. 検知器の確認と設定

検知器名は `PacDetector`、標準設定は `PacSetting`、検知器別の設定項目は `DetectorSetting` で指定します。IDE補完が使え、存在しない設定項目を指定するとエラーになります。

```java
import org.pexserver.pac.api.DetectorSetting;
import org.pexserver.pac.api.PacDetector;
import org.pexserver.pac.api.PacSetting;

boolean enabled = pac.isDetectorEnabled(PacDetector.MOTION_PREDICTION);
Object buffer = pac.detectorSetting(
        PacDetector.MOTION_PREDICTION, DetectorSetting.BUFFER_THRESHOLD);

pac.setDetectorEnabled(PacDetector.MOTION_PREDICTION, false);
pac.setDetectorSetting(PacDetector.MOTION_PREDICTION,
        DetectorSetting.BUFFER_THRESHOLD, 10);
pac.setSetting(PacSetting.ROLLBACK_ENABLED, false);

// Debug記録モードを切り替えます。記録中も検知と移動補正は行い、制裁は止めます。
pac.setSetting(PacSetting.DEBUG_RECORDING_MODE, true);
```

`detectorStates()` は全検知器の状態を返します。`detectorStates(world)` は指定ワールドで有効な状態、グローバル値、ワールド上書きの有無、補正の状態、自動BAN / kick の適格性と設定状態を返します。`settings()` はスカラー設定の読み取り用スナップショットで、Mapのキーには設定パスを使います。互換用の文字列設定パスAPIは残っていますが、新しいコードでは enum を使ってください。

## 4. ワールド別の検知設定

ワールド上書きがない項目はグローバル設定へフォールバックします。`World` または `World#getUID()` を指定できます。

```java
import org.bukkit.Bukkit;
import org.bukkit.World;
import org.pexserver.pac.api.PacDetector;

World world = Bukkit.getWorld("world");
pac.setWorldDetectorEnabled(PacDetector.MOTION_PREDICTION, world, false);
pac.setWorldDetectorCancel(PacDetector.MOTION_PREDICTION, world, true);

// enabledだけ戻し、cancelの上書きは残す
pac.clearWorldDetectorEnabledOverride(PacDetector.MOTION_PREDICTION, world);

// cancelだけ戻す
pac.clearWorldDetectorCancelOverride(PacDetector.MOTION_PREDICTION, world);

// 両方の上書きを消して、グローバル設定へ戻す
pac.clearWorldDetectorOverride(PacDetector.MOTION_PREDICTION, world);
```

ワールド別の変更と解除はメインスレッドから呼び出します。上書き値は PAC の設定ファイルへ保存されます。

## 5. 検知イベント

`PacDetectionEvent` は検知記録ごとにメインスレッドで発火します。検知器enum、score、説明、数値metrics、Debug記録状態を取得できます。Debug記録中の検知もイベントとして届きます。

```java
import org.bukkit.event.EventHandler;
import org.pexserver.pac.api.event.PacDetectionEvent;

@EventHandler
public void onPacDetection(PacDetectionEvent event) {
    getLogger().info(event.playerName() + " / " + event.pacDetector()
            + " / " + event.score() + " / " + event.metrics());
}
```

BAN後は `PacBanEvent` が発火し、`event.ban()` からBAN情報とサポートIDを取得できます。

## 6. 履歴、統計、エクスポート

全プレイヤーの履歴は identity を `null` にし、特定プレイヤーの履歴はUUIDまたは記録済みユーザー名を渡します。ページサイズは最大50件です。履歴レコードの `metricsJson()` は数値metricsのJSONオブジェクトです。

```java
import org.pexserver.pac.api.DetectionRecord;
import org.pexserver.pac.api.PacDetector;

pac.detectionHistory(null, 1, 20).thenAccept(page -> {
    for (DetectionRecord record : page.records()) {
        getLogger().info(record.playerName() + " / " + record.detectorKey()
                + " / " + record.detail() + " / " + record.metricsJson());
    }
});

pac.detectorStatistics(PacDetector.KILL_AURA).thenAccept(result ->
        result.ifPresent(stats -> getLogger().info(
                stats.detectorKey() + " detections=" + stats.detections()
                        + " falsePositives=" + stats.falsePositives())));
```

`detectorStatistics()` は全検知器の集計を返します。`markFalsePositive(id, true)` は履歴の誤検知フラグを更新し、API制御権限を必要とします。`exportStatistics()` はDBのスナップショットをJSONへ書き出し、生成したファイルの `Path` を返します。

## 7. BANとサポートID

`activeBans()` と `activeBan(UUID)` で有効なBANを取得できます。`findActiveBan(String)` はUUID、完全一致名、曖昧でない名前の先頭部分で検索します。BANレコードにはプレイヤー情報、期限、ステージ、内部理由、サポートIDが含まれます。

```java
pac.findActiveBan("ExamplePlayer").ifPresent(ban ->
        getLogger().info("Support ID: " + ban.supportId()));

pac.supportCase("PAC-7KQ2-M9DX").thenAccept(result ->
        result.ifPresent(support -> getLogger().info(
                support.playerName() + " / " + support.supportId())));
```

`supportCase(id)` は解除後や期限切れ後もサポート記録を検索します。`ban(uuid, name, reason, duration)` と `unban(uuid)` はAPIからの制裁操作です。`duration == null` は永久BANです。どちらもAPI制御権限が必要で、BAN時は対象がオンラインなら切断されます。

## 8. APIの型と互換性

`PacDetector`, `PacSetting`, `DetectorSetting` は設定名を型安全に扱うためのenumです。UUID、プレイヤー名、サポートID、理由のように実行時に決まる値は文字列またはUUIDで渡します。既存の文字列検知器メソッドは互換用に残していますが、非推奨です。
