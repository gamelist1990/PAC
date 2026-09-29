# LiquidBounce movement coverage audit

Reference: LiquidBounce `nextgen` at `68b967b2114ef1ffeed9d1be5bce07fd1477d9ca` (2026-09-28), plus the public movement-module overview.

The goal is not client-name detection. PAC should reject movement only when the server can prove that the observed packet trajectory, collision state, effect state, or authoritative impulse cannot occur under the accepted Minecraft rules.

Status meanings:

- **Covered** — PAC has a server-side model/check for the relevant impossible behavior, with regression coverage where practical.
- **Partial** — important variants are covered, but a specialized terrain/vehicle/transition case still needs a dedicated model.
- **Not a violation by itself** — the module can reproduce legal player input/behavior. Detecting the module name would create false positives; PAC should only act if another impossible behavior is produced.

| LiquidBounce module | PAC status | Server-side basis / notes |
| --- | --- | --- |
| AirJump | Covered | Air vertical recurrence; mid-air 0.42Y re-jump regression |
| Anchor | Not a violation by itself | Input automation toward a location |
| AntiBounce | Covered | Vanilla generalized restitution is replayed from the server block state; suppressed Slime/Bed rebound is detected on the following physics frame |
| AntiLevitation | Covered | Active levitation / slow-falling state is included in air recurrence |
| AvoidHazards | Not a violation by itself | Input/pathing automation |
| BlockBounce | Covered | Surface jump factor and generalized restitution are sampled server-side; client-added jump motion on Honey/Slime/Bed exceeds the takeoff envelope |
| BlockWalk | Partial | Unsupported-air ground claims, hover and collision checks cover impossible support; unusual thin-block transitions remain collision-dependent |
| Clip | Covered | Swept-AABB noclip prevention, now enabled by default with auto-ban disabled |
| ElytraFly | Covered | Elytra recurrence, collision replay and residual buffering |
| ElytraRecast | Partial | Server controls accepted glide state; illegal flight after recast falls into air/Elytra checks |
| EntityControl | Partial | Server vehicle authority helps, but non-boat rideables do not yet have a full PAC vehicle predictor |
| ExtendedFirework | Covered | Vanilla firework acceleration replay remains active during boost |
| Fly | Covered | Air recurrence, horizontal air candidates, gravity fit, hover and silence evidence |
| Freeze | Not a violation by itself | Standing still / withholding optional movement can be legitimate; airborne silence is alert-only |
| HighJump | Covered | Server jump-strength takeoff envelope; current default 0.8Y regression |
| InventoryMove | Covered | InventoryMoveCheck correlates movement with inventory state |
| LiquidWalk | Covered | Liquid surface ground claims + water motion/flow prediction |
| LongJump | Covered | Takeoff, horizontal/air prediction, timer and server-velocity response models |
| NoClip | Covered | Swept-AABB wall penetration rejection |
| NoJumpDelay | Not a violation by itself | Precise repeated jump input is not illegal unless resulting movement violates physics |
| NoPose | Partial | Pose transitions and collision dimensions are sampled; protocol/pose-only visual differences are not automatically punishable |
| NoPush | Partial | Server velocity/knockback, entity-push allowance and water flow are modeled; not every vehicle/block push source has a dedicated replay |
| NoSlow | Covered | Item use, sneaking, slowness attributes, water, powder snow and cobweb are modeled; Soul Sand/Honey block speed factors and Slime slipperiness are now replayed from server-authoritative terrain state |
| NoWeb | Covered | Exact cobweb stuck multipliers plus sustained-speed evidence |
| Parkour | Not a violation by itself | Edge jump automation is legal input |
| ReverseStep | Covered | Vertical air/gravity recurrence; Strict -1.0Y regression |
| SafeWalk | Not a violation by itself | Sneak-like edge avoidance |
| SnapTap | Not a violation by itself | Keyboard/input prioritization |
| Sneak | Not a violation by itself | Legal input automation |
| Speed | Covered | Ground/air prediction, sustained speed envelope and timer prediction |
| Spider | Covered | Sustained wall climb + immediate extreme wall-clip branch |
| Sprint | Not a violation by itself | Legal sprint input; excess resulting speed is covered by prediction |
| Step | Covered | Collision-aware ground replay + impossible same-tick position burst handling |
| Strafe | Covered | Horizontal air candidate prediction |
| TargetStrafe | Not a violation by itself | Legal steering can be automated; resulting excess speed is still checked |
| TerrainSpeed | Covered | Current nextgen IceSpeed, WaterSpeed and FastClimb paths are covered by friction/water prediction and climb-state enforcement |
| TridentBoost | Covered | Paper Riptide event provides the authoritative vanilla impulse |
| VehicleBoost | Covered | A dedicated post-dismount window rejects the current 2.0 horizontal / 1.0 vertical self-boost while preserving vehicle momentum and invalidating on external server motion |
| VehicleControl | Covered for current default bypass paths | BoatFlight preserves evidence across Rehook, extreme VEHICLE_MOVE packets are rejected before vanilla applies them, unsaddled EntityControl packets are rejected using the server controlling-passenger state, and gravity-bound living vehicles validate vertical recurrence. Generic custom/non-living vehicle telemetry remains non-punitive |

## Concrete hardening added in PR #6

- repeated airborne `onGround=true` spoof detection
- vanilla Elytra firework replay and ExtendedFirework residual detection
- server-authoritative Riptide launch validation
- PaperBypass-style repeated-position padding + rapid distant-jump rejection
- extreme wall-adjacent Spider clip rejection
- FastClimb and climbable multi-block clip detection
- noclip prevention enabled by default while keeping noclip automatic bans disabled
- regressions for HighJump, AirJump, ReverseStep and AntiLevitation
- server-authoritative block speed-factor replay for Soul Sand/Honey and slime slipperiness enforcement
- special bounce surfaces excluded from the ordinary jump envelope to avoid Slime/Honey false positives
- target hitbox history reconstructed at ATTACK receive time; reported ping grants neither raw reach nor historical rewind
- post-dismount VehicleBoost rejection plus conservative non-boat VehicleControl telemetry
- vehicle heuristics are explicitly ineligible for automatic BAN/KICK
- 26.2+ generalized Slime/Bed restitution and Honey jump-factor replay
- packet-level VEHICLE_MOVE envelope before vanilla applies extreme client coordinates
- server controlling-passenger authority enforcement for EntityControl
- living-vehicle vertical gravity/drag recurrence for low-speed VehicleControl
- BoatFlight evidence continuity across short LiquidBounce Rehook cycles
- sub-block Phase tolerance tightened to 0.03 while keeping noclip auto-ban disabled

## Remaining high-value work

1. **Non-living/custom vehicle physics** — current LiquidBounce default/high-speed VehicleControl and server-authority bypasses are covered, but minecart/custom-plugin vehicle motion remains conservative because server plugins may intentionally override vanilla transport rules.
2. **Latency confidence** — the current LiquidBounce PingSpoof delays keepalive/ping traffic. PAC now gives reported RTT zero authority over reach distance and zero authority over target-history rewind; latency itself is still not labelled cheating because genuine network delay is observationally ambiguous.
3. **Live calibration** — replay real vanilla 26.3 traces for Slime/Bed restitution, Honey takeoff, vehicle transitions and high-latency combat before making the conservative vehicle/reach signals more punitive.

A module listed as “Not a violation by itself” is intentionally not fingerprinted. PAC should detect impossible outcomes, not the presence of a specific client.
