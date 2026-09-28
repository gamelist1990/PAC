package org.pexserver.pac.check.shared;

/** Rotation sample with the exact signed and absolute deltas MX's checks consume. */
record MxAimInput(float deltaYaw, float deltaPitch,
                  float absDeltaYaw, float absDeltaPitch,
                  float previousYaw, float previousPitch, float currentPitch) { }
