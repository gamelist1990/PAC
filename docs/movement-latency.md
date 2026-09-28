# Java movement latency handling

Server velocity send time is not client application time. Movement already in
flight may still describe the state before knockback. `MovementLatencyWindow`
marks this ambiguity for the ground and air prediction checks using a cached,
main-thread ping sample: RTT + 100 ms, bounded to 100–1500 ms from the send time.
Repeated delivery of the same impulse cannot move that deadline.

A movement receive gap of at least 100 ms starts a fixed 150 ms recovery window
for queued packets. This can happen at most once every two seconds; arrivals
within a burst do not extend it. Stable high ping alone does not activate it.
Ground and air predictors discard their uncertain recurrence/evidence and
reinitialize after recovery. The speed envelope retains legitimate momentum;
for velocity changes it uses the server's horizontal impulse as its bound.
Other checks, including packet rate and timer checks, remain dispatched.

## Server tick lag

`ServerTickTiming` observes the monotonic interval between main-thread world
sampling ticks, with a fast rise and gradual decay of the estimated milliseconds
per tick. Packet listeners can detect an ongoing stall from the age of the last
tick, even before the main thread resumes. This is a short-term tick estimate,
not Paper's long-window TPS statistic.

During measured tick delay, ground, air, Elytra and the sustained speed envelope
derive client step counts from ordered movement packets and explicit positionless
frames, bounded to 40. They do not multiply gravity, drag or legal speed by TPS.
Water simulation also accepts ordered same-time batch arrivals. Existing 200 ms
world/collision freshness and spatial coverage checks remain conservative: old
geometry is not made fresh by enlarging its timestamp allowance.

At a 250 ms tick stall, ground/air/water/surface/flow prediction is resynchronized.
The post-stall recovery lasts up to 1500 ms. The airborne silence watchdog also
discards stale evidence in this period. The timer's extra backlog allowance is
derived solely from observed server delay, capped at 15 seconds; it expires after
the backlog interval plus 1500 ms. At expiry, attributed server backlog is removed
from the timer lead so an ordinary 20 Hz client does not receive a delayed flag.
Continued excess client simulation still accumulates evidence.

This integration targets Java movement prediction. It does not change Bedrock's
embedded prediction engine or unrelated combat/action detectors. Sustained stalls
of 250 ms per tick or longer conservatively suspend the affected motion verdicts;
the system cannot accurately reconstruct changing world geometry during a freeze.
`ServerTickTimingTest` covers 20/10/5 TPS, ordered batched free-fall, continued speed
detection, a 10-second stall, timer backlog expiry and normal-speed recovery.

This is bounded conservative resynchronization, not a client acknowledgement
protocol or a full network/world rewind. Ground/air prediction cannot judge
knockback suppression inside this ambiguity window. Delay beyond the cap,
large jitter outside the recovery interval, and delayed world-state changes
still need real-server validation. Do not treat the simulated cases as proof
that every lag condition is covered.

Run `gradlew.bat test`. `MovementLatencyWindowTest` simulates RTTs of 0, 50,
200, 500 and 1000 ms, pre-velocity arrivals, queued packet bursts, fixed deadline
expiry, excessive ping, retained momentum and sustained illegal speed after
recovery. No live network shaping or player session is needed for these tests.
