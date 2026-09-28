# Boar source in PAC

The `ac.boar` packages in this module are derived from
[opencollab-incubator/Boar](https://github.com/opencollab-incubator/Boar),
MIT license, revision `b8a7584d4d6b91b9efe37daf8643bfb44495a07f`.
The original license is in `third-party/Boar-LICENSE` and in the distribution.

PAC changes: build under the PAC Gradle project with Lombok 1.18.48; compile
against Geyser 2.11.2-SNAPSHOT; use public GeyserSession getters in the
adapter; rename the extension to PAC Bedrock Engine; add raw session lookup,
raw input and violation forwarding to PAC; use PAC Bedrock as the default
message prefix. The prediction and correction algorithms remain upstream.

The source is pinned. It is not automatically updated when Geyser or Minecraft
changes. Review, rebuild, and live test it for each target version.
