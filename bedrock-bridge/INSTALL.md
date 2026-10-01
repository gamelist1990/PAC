# PAC Bedrock engine installation

Run `gradlew.bat build` (or `./gradlew build`). The Paper plugin JAR embeds the
Geyser extension. `bundleBedrock` additionally creates a ZIP for distribution.

1. Install PacketEvents and Geyser-Spigot (and Floodgate if needed).
2. Put the release file `PAC-<version>.jar` in Paper's `plugins` directory.
3. Start the server. PAC installs the embedded extension in
   `plugins/Geyser-Spigot/extensions` before Geyser loads extensions. If
   `pac-bedrock-geyser.jar` already exists, PAC compares it with the bundled
   version and automatically replaces it when an update is available.
4. Check the Geyser startup log for `PAC-Bedrock` and `/pac settings` for
   `bedrock-engine-active=true`.

The extension uses the MIT-licensed Boar source to predict Bedrock movement
inside Geyser. It consumes `PlayerAuthInputPacket`, tracks client-visible
world and entity state, and uses `CorrectPlayerMovePredictionPacket` for
rewinds. PAC receives raw-input observations and violations for SQLite and
staff alerts. The engine's own configuration is in Geyser's extension data
folder. `/pac detector bedrock-prediction off` and `/pac bypass` exempt the
affected Bedrock movement from prediction and rewinds. Other Boar checks use
the extension's configuration.

Install only this PAC extension, not the original Boar extension alongside
it. The extension requires Geyser-Spigot in the same JVM as PAC and depends
on Geyser Core and Cloudburst internals. Validate it on a staging server
with your Geyser build and Bedrock clients before production use. Boar does
not cover every movement case perfectly. Automatic bans are enabled in PAC by
default and can be disabled per detector. See `UPSTREAM.md` and `licenses/Boar-LICENSE` for source
attribution and license.

### Movement timing and moving blocks

The timer warms up for five seconds and while loading, teleporting, or in an
unloaded chunk. It retains up to two seconds of transport delay credit and
requires excess timing to persist for one second before reporting. Nearby
server PistonArm/MovingBlock notifications grant 750 ms of movement grace,
refreshed on acknowledgment; movement anchors are resynchronized and stale
prediction rewinds discarded. Server teleports remain authoritative.

Bedrock corrections read PAC's live `punishments.rollback-enabled` and
`detectors.bedrock-prediction.cancel` settings on every action, including
world cancellation overrides. Recording mode also disables corrections.
With corrections disabled, prediction and reporting continue, incoming movement
flags/deltas are preserved, and each observed position anchors the next tick.
If PAC's policy cannot be read, the extension does not force a rollback.

Physics follows consecutive client ticks rather than packet arrival intervals.
High ping, server stalls, and delayed packet bursts do not reset detection by
themselves. Missing client ticks resynchronize the position and allow two
settling inputs; duplicate/older ticks are discarded without moving the anchor.
Correction replay uses the corresponding historical input, is bounded to 200
ticks, and resynchronizes when required history has expired.

Validate on a staging server with join lag and extending/retracting piston,
slime, and honey platforms. The moving-block grace depends on Geyser emitting
these block-entity notifications; it does not exempt every static piston nearby.
