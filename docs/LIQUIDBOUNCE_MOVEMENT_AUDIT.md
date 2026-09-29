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
| AntiBounce | Partial | Ordinary-air gravity is covered; special bounce-block response still needs dedicated restitution replay |
| AntiLevitation | Covered | Active levitation / slow-falling state is included in air recurrence |
| AvoidHazards | Not a violation by itself | Input/pathing automation |
| BlockBounce | Partial | Excessive ordinary-ground takeoff is covered, but bouncy support blocks are intentionally excluded from the ordinary-ground recurrence |
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
| NoSlow | Partial | Item use, sneaking, slowness attributes, water, powder snow and cobweb are modeled; special block speed-factor terrain remains an audit target |
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
| TerrainSpeed | Partial | Ice friction, water and FastClimb are covered; special block speed-factor variants need dedicated terrain replay |
| TridentBoost | Covered | Paper Riptide event provides the authoritative vanilla impulse |
| VehicleBoost | Partial | Large post-dismount motion can hit normal prediction, but no dedicated dismount-impulse model yet |
| VehicleControl | Partial | BoatFlight is modeled; horses/other client-steered rideables need vehicle-specific prediction |

## Concrete hardening added in PR #6

- repeated airborne `onGround=true` spoof detection
- vanilla Elytra firework replay and ExtendedFirework residual detection
- server-authoritative Riptide launch validation
- PaperBypass-style repeated-position padding + rapid distant-jump rejection
- extreme wall-adjacent Spider clip rejection
- FastClimb and climbable multi-block clip detection
- noclip prevention enabled by default while keeping noclip automatic bans disabled
- regressions for HighJump, AirJump, ReverseStep and AntiLevitation

## Remaining high-value work

1. **Special terrain response** — model Soul Sand / Honey / Slime speed, jump and bounce factors without weakening vanilla Soul Speed, bouncing, or plugin-modified attributes.
2. **Vehicle transitions** — model dismount velocity and non-boat controlled vehicles before adding punitive evidence.
3. **Latency manipulation outside movement physics** — review PingSpoof interaction with combat rewind/reach separately; do not solve spoofing by simply reducing legitimate high-ping tolerance.
4. **Live calibration** — replay real vanilla traces for the newly covered transitions before making any currently conservative signal more punitive.

A module listed as “Not a violation by itself” is intentionally not fingerprinted. PAC should detect impossible outcomes, not the presence of a specific client.
