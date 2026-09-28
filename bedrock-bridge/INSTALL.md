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
