# PAC architecture

## Java packet pipeline

`PacketChecks` decodes each PacketEvents movement packet once and passes a
`PacketContext` to `CheckRegistry`. `check/core` provides separate interfaces
for Java packets, main-thread Bukkit events, and Geyser prediction violations.
`CheckSettings` owns detector switches and thresholds. `ViolationService`
queues persistence, alerts, and optional punishment on the main thread.
Each `CheckModule` owns its per-player state.
Register a module in `PacPlugin.onEnable()`, add its configuration under
`detectors.<key>`, and implement `forget(UUID)` to release state at logout.
The registry powers `/pac detector list` and `/pac detector <key> on|off`.

Packet listeners run off the primary server thread. They must not call Bukkit
world or entity methods. `PacketContext.flag()` schedules the logging and
punishment path on the primary thread. Java-only packet checks return false
from `supportsBedrock()` so Bedrock connections use their own movement engine.
On 26.3, `PacketChecks` also captures changed `PLAYER_INPUT` key states. Ground
and air predictors evaluate those inputs, allowing the previous state for two
movement frames after a change. Without an input packet yet, they fall back to
the broader candidate set.

## Ground state

`GroundStateService` samples each online player's foot bounding box every
server tick on the primary thread. It checks nearby block collision shapes
and publishes immutable `GroundState(known, onGround, supportY, tick,
consecutiveTicks)` values. Unloaded chunks produce `known=false`.
`MotionEnvironment` requires at least two consecutive support ticks before
enabling its ordinary ground prediction. It samples once per server tick and
skips unloaded chunks. The packet check tracks rotation-only packets and uses
their yaw for the next position candidate. Each flying packet advances a client
physics step, including packets queued into the same server tick. The first
frames after a gap or movement-state change establish a new baseline. A separate
timer check bounds physics steps against elapsed time. This service never reads the client
on-ground bit or `Player#isOnGround()`.

The service sees the server's current world. A client-view compensated world,
transaction acknowledgments and version-specific collision ordering must be
implemented before prediction can safely judge movement near dynamic blocks.
Until that exists, nearby block place/break/piston changes and player effect
or game-mode changes suppress prediction briefly. Teleports hold prediction
until the matching client confirmation and a short settling interval. Outgoing velocity
and explosion knockback packets are captured and aligned with the client's
first matching physics step; prediction resumes after the transition. Snapshots
carry their sampled position, and checks reject distant packet positions.
The ground predictor uses the Paper 26.3 ordinary-block movement-speed branch
of `LivingEntity#getFrictionInfluencedSpeed`. `air-prediction` checks the
gravity/drag recurrence and horizontal air-control candidates in clear air
after consecutive movement frames.
Packets without coordinates use the last reported position, so sustained hover
cannot evade this check by omitting position updates.
The main-thread air watchdog records a player who remains in clear air without
any movement packets for three seconds; this is alert-only because network
stalls can also cause silence.
The ground candidate model reads the effective movement-speed attribute, so
ordinary speed and slowness effects can be simulated after effect-change grace.
Automatic punishment is configurable per detector; live calibration remains necessary.
Ground and air prediction now cancel a rejected packet and teleport the player
to its previous accepted position when their `cancel` setting is enabled.
Ground prediction also checks implausibly small upward takeoffs from a clear,
full-block floor. This covers SpeedHack's ground-to-air transition, which
previously disabled both predictors.

Item use slows the input vector in both candidate models. `surface-prediction`
tracks repeated vertical wall climbs and unsupported ground claims above
liquid, and corrects them toward a recently sampled safe floor.
`water-flow-prediction` samples the server's NMS fluid flow on the primary
thread and checks the no-input case where a player remains stationary in a
strong, unobstructed current. Water movement with active keys still needs a
full fluid simulation and client-world compensation.

## Bedrock

The Paper plugin uses Geyser API or Floodgate API to identify Bedrock players.
`bedrock-bridge` vendors the MIT-licensed Boar source. Its
Geyser adapter reads raw `PlayerAuthInputPacket` messages, maintains compensated
world and player state, runs Bedrock movement prediction, and sends
`CorrectPlayerMovePredictionPacket` rewinds when needed. It also calls PAC's
`acceptBedrockAuthInput` and `acceptBedrockViolation` methods. Raw input only
marks the Bedrock connection; violations dispatch to `check/bedrock/prediction`. PAC
records violations in SQLite and shows alerts. Automatic bans are enabled by
default and can be disabled per detector. Java-specific prediction is skipped for Bedrock connections.

`GeyserApi` identifies Bedrock connections and discovers the bridge. Its
public API does not expose the raw input stream or correction transport. The
extension uses Geyser Core and Cloudburst internals to read input and expose a
`rawSession(UUID)` lookup. These internals are version sensitive. The imported
Boar engine handles prediction and corrections, but upstream has known gaps in
movement coverage. `/pac detector bedrock-prediction off` and `/pac bypass`
cause Boar to exempt that movement from prediction and rewinds. Other Boar
checks and configuration remain in the Geyser extension.

`boat-flight` reads server-side boat positions while a player is aboard. It
requires sustained air hover or horizontal air movement, and covers Java and
Bedrock passengers through the same Bukkit event stream. `fast-place` counts
successful Java survival placements in a rolling 20-tick window and cancels
placements above the limit. `fast-break` records server block damage speed at
the start of a Java survival dig, then compares the finish packet against the
minimum break time and cancels early finishes. Fast place and fast break
violations participate in the configurable automatic ban policy.

`/pac bypass <online-name|uuid> <on|off>` accepts online Java names, Bedrock
names (including spaces), and UUIDs. The explicit bypass lasts until logout;
OP alone does not bypass any detector.

## Enforcement boundary

Malformed coordinates and rotations can be cancelled by default. Java movement
prediction can cancel and correct packets, and its violations can trigger
automatic bans under the configured threshold and probation policy. The exactness of ground, air, wall, liquid-surface, and water
current models and their false-positive rates still require live calibration.
