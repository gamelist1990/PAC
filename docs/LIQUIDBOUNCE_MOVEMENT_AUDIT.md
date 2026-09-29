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
| BlockWalk | Covered for default fake-support outcomes | Default Cobweb/Snow full-shape walking is checked through repeated unsupported-air ground claims, hover/gravity evidence and server collision geometry; a named regression covers fake support |
| Clip | Covered | Swept-AABB noclip prevention, now enabled by default with auto-ban disabled |
| ElytraFly | Covered | Elytra recurrence, collision replay and residual buffering |
| ElytraRecast | Not a violation by itself | START_FALL_FLYING can be a legal client action. PAC judges the resulting server-accepted glide state and Elytra trajectory rather than fingerprinting repeated recast packets |
| EntityControl | Covered for unauthorized movement | VEHICLE_MOVE is rejected when the player is not the server's controlling passenger; legal local saddle/control presentation alone is not treated as cheating |
| ExtendedFirework | Covered | Vanilla firework acceleration replay remains active during boost |
| Fly | Covered | Air recurrence, horizontal air candidates, gravity fit, hover and silence evidence |
| Freeze | Not a violation by itself | Standing still / withholding optional movement can be legitimate; airborne silence is alert-only |
| HighJump | Covered | Server jump-strength takeoff envelope; current default 0.8Y regression |
| InventoryMove | Covered | InventoryMoveCheck correlates movement with inventory state |
| LiquidWalk | Covered | Liquid surface ground claims + water motion/flow prediction |
| LongJump | Covered | Takeoff, horizontal/air prediction, timer and server-velocity response models |
| NoClip | Covered | Swept-AABB wall penetration rejection |
| NoJumpDelay | Not a violation by itself | Precise repeated jump input is not illegal unless resulting movement violates physics |
| NoPose | Not a violation by itself | A local pose/dimension presentation change is not punished on its own. Server pose/collision transitions are sampled and impossible movement still falls into collision/noclip prediction |
| NoPush | Covered for current nextgen sources | Fluid-current suppression and SINKING zero-Y behavior diverge from water prediction. Entity pushes use server-confirmed contact evidence; fishing-rod pulls are tracked as the exact additive vanilla impulse; BLOCKS reconstructs LocalPlayer's suffocating-block push-out geometry and only scores repeated failure to make any valid outward progress. |
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
| VehicleControl | Covered for current default bypass paths | BoatFlight preserves evidence across Rehook, extreme VEHICLE_MOVE packets are rejected before vanilla applies them, unsaddled EntityControl packets are rejected using server controlling-passenger state, gravity-bound living vehicles validate vertical recurrence, and airborne minecarts use vanilla 0.04 gravity plus Bukkit's configured flying Y modifier. Generic custom vehicles remain non-punitive |

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
- exact fishing-hook pull vector captured as additive client motion
- NoPush BLOCKS detection mirrors the client's suffocating-block neighbor search and requires six stable suppressed frames

## Remaining high-value work

1. **Custom/non-vanilla vehicle horizontal physics** — current LiquidBounce VehicleControl authority, large packet motion, living-vehicle vertical recurrence and airborne minecart gravity are covered. Plugin-defined custom vehicles can intentionally exceed vanilla transport rules, so their generic telemetry remains alert-only rather than becoming a setback/BAN signal.
2. **Live calibration** — replay real vanilla 26.3 traces for Slime/Bed restitution, Honey takeoff, block-push escape, entity-contact/fishing pulls, vehicle transitions and high-latency combat before making conservative telemetry more punitive.
3. **Plugin-defined transport semantics** — arbitrary custom vehicle plugins can intentionally exceed vanilla horizontal motion while still using a vanilla Bukkit entity type. PAC should continue to consume explicit server velocity/teleport evidence rather than treating every custom transport outcome as a cheat.

A module listed as “Not a violation by itself” is intentionally not fingerprinted. PAC should detect impossible outcomes, not the presence of a specific client.
