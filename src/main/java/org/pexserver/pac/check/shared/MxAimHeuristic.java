package org.pexserver.pac.check.shared;

import org.pexserver.pac.check.shared.aim.model.math.Simplification;
import org.pexserver.pac.check.shared.aim.model.math.Statistics;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/** PAC-native port of MX AimHeuristicCheck and its seven registered components. */
final class MxAimHeuristic {
    record Finding(String source, String detail) { }
    private record Vec2(double x, double y) { }
    private record Vec2f(float x, float y) { }

    private final Basic basic = new Basic();
    private final Constant constant = new Constant();
    private final Invalid invalid = new Invalid();
    private final Inconsistent inconsistent = new Inconsistent();
    private final Pattern pattern = new Pattern();
    private final Factor factor = new Factor();
    private final Smooth smooth = new Smooth();

    List<Finding> sample(MxAimInput input, int sensitivity, int clientSensitivity,
                         boolean ignoreCinematic) {
        List<Finding> findings = new ArrayList<>(5);
        findings.addAll(basic.process(input, sensitivity, ignoreCinematic));
        findings.addAll(constant.process(input, sensitivity, clientSensitivity, ignoreCinematic));
        findings.addAll(invalid.process(input));
        findings.addAll(inconsistent.process(input, sensitivity, clientSensitivity, ignoreCinematic));
        findings.addAll(pattern.process(input));
        findings.addAll(factor.process(input, false));
        findings.addAll(smooth.process(input, ignoreCinematic));
        return findings;
    }

    List<Finding> noRotation() { return factor.process(null, true); }

    private static boolean pitchMagnitudeUnchanged(MxAimInput input) {
        // MX's source checks absDelta.y twice; preserve that active behavior.
        return input.absDeltaPitch() == 0.0f && input.absDeltaPitch() == 0.0f;
    }

    private static final class Basic {
        private final List<Vec2> rawRotations = new ArrayList<>(10);
        private final float[] buffer = new float[16];
        private int streak;
        private float vl, interpolationVl;
        private String reason = "";

        List<Finding> process(MxAimInput input, int sensitivity, boolean ignoreCinematic) {
            if (ignoreCinematic || pitchMagnitudeUnchanged(input)) return List.of();
            List<Finding> findings = new ArrayList<>(2);
            rawRotations.add(new Vec2(input.previousYaw(), input.previousPitch()));
            // Exact pitch decimal multiples are common in vanilla mouse aiming
            // and are not evidence of a randomizer by themselves. Do not turn
            // this single-sample MX heuristic into a live violation.
            if (rawRotations.size() >= 10) checkDefaultAim(sensitivity, findings);
            return findings;
        }

        private void checkDefaultAim(int sensitivity, List<Finding> findings) {
            List<Double> yawChanges = new ArrayList<>(10);
            Set<Double> distinctYawChanges = new HashSet<>();
            double oldYaw = rawRotations.get(0).x();
            for (Vec2 rotation : rawRotations) {
                distinctYawChanges.add(Math.abs(rotation.x() - oldYaw));
                oldYaw = rotation.x();
            }

            double oldYawResult = rawRotations.get(0).x();
            double oldPitchResult = rawRotations.get(0).y();
            double oldYawChange = 0.0;
            double yawChangeFirst = Math.abs(rawRotations.get(0).x() - rawRotations.get(1).x());
            int machineKnownMovement = 0, constantRotations = 0, gcd = 0;
            int aggressiveIncreasing = 0, aggressiveDecreasing = 0;
            int aggressiveLargeIncreasing = 0, aggressiveLargeDecreasing = 0;
            int robotizedAmount = 0, aggressiveAim = 0, infinitives = 0;
            for (Vec2 rotation : rawRotations) {
                double yawChange = Math.abs(rotation.x() - oldYawResult);
                double pitchChange = Math.abs(rotation.y() - oldPitchResult);
                double robotized = Math.abs(yawChange - yawChangeFirst);
                double yawChangeDifference = yawChange - oldYawChange;
                double interpolation = Simplification.scaleVal(yawChange / robotized, 2);
                if (robotized < 2 && yawChange > 2.5) robotizedAmount++;
                if (robotized < 0.99 && yawChange > 4) machineKnownMovement++;
                if (robotized < 0.02 && yawChange > 3) constantRotations++;
                if (robotized < 2 && yawChange > 3) aggressiveAim++;
                if (Double.isInfinite(interpolation) && yawChange > 0) {
                    infinitives++;
                    if (infinitives > 1 && yawChange < 0.4) infinitives--;
                }
                if (robotized != 0) yawChanges.add(robotized);
                if (yawChange == 0.1 || pitchChange == 0.1) gcd++;
                if (yawChange == 0.01 || pitchChange == 0.01) gcd++;
                if (yawChangeDifference > 0.01 && yawChangeDifference < 2) aggressiveIncreasing++;
                if (yawChangeDifference < -0.01 && yawChangeDifference > -2) aggressiveDecreasing++;
                if (yawChangeDifference > 2) aggressiveLargeIncreasing++;
                if (yawChangeDifference < -2) aggressiveLargeDecreasing++;
                oldYawResult = rotation.x();
                oldPitchResult = rotation.y();
                oldYawChange = yawChange;
            }

            if (sensitivity > 65) {
                if (robotizedAmount > 8) addComponent("heuristic(sync)", 125);
                if (aggressiveAim > 8) addComponent("heuristic(aggressive)", 50);
                if (machineKnownMovement > 7) addComponent("heuristic(aim)", 100);
                if (constantRotations > 3) addComponent("heuristic(constant)", 65);
            } else {
                if (machineKnownMovement > 8) addComponent("heuristic(aim)", 100);
                if (constantRotations > 6) addComponent("heuristic(constant)", 65);
            }
            if (infinitives > 1 && Math.abs(Statistics.getAverage(distinctYawChanges)) > 3.2)
                interpolationVl += 55;
            if (gcd > 0) addComponent("pattern(gcd)", 1000);
            if (aggressiveIncreasing > 3 && aggressiveDecreasing > 3)
                interpolationVl += 25;
            if (aggressiveLargeIncreasing > 3 && aggressiveLargeDecreasing > 3
                    && aggressiveLargeIncreasing + aggressiveLargeDecreasing > 8) {
                streak++;
                if (streak > 2) addComponent("pattern(snap)", 55);
            } else streak = 0;

            if (vl > 400) {
                findings.add(new Finding("MX heuristic component", reason + " buffer=" + vl));
                vl = 360;
            }
            if (interpolationVl > 400) {
                findings.add(new Finding("MX heuristic interpolation", "buffer=" + interpolationVl));
                interpolationVl -= 65;
            }
            if (vl > 0) vl -= 5;
            if (vl > 400) vl -= 10;
            if (interpolationVl > 0) interpolationVl -= 5;
            if (interpolationVl > 380) interpolationVl -= 10;
            rawRotations.clear();
        }

        private void addComponent(String name, float amount) { reason = name; vl += amount; }
    }

    private static final class Constant {
        private static final double EXPANDER = Math.pow(2, 24);
        private static final double MODULO_THRESHOLD = 60.0;
        private static final double LINEAR_THRESHOLD = 0.1;
        private static final float MIN_DELTA = 0.1f, MAX_DELTA = 20.0f;
        private float lastYaw, lastPitch;
        private float buffer1, buffer2, buffer3;

        List<Finding> process(MxAimInput input, int sensitivity, int clientSensitivity,
                              boolean ignoreCinematic) {
            if (ignoreCinematic || pitchMagnitudeUnchanged(input)) return List.of();
            float yaw = input.absDeltaYaw(), pitch = input.absDeltaPitch();
            boolean sensitivityTooLow = sensitivity < 50.0 && sensitivity > -1.0
                    || clientSensitivity < 50;
            long expandedYaw = (long) (yaw * EXPANDER);
            long expandedLastYaw = (long) (lastYaw * EXPANDER);
            long expandedPitch = (long) (pitch * EXPANDER);
            long expandedLastPitch = (long) (lastPitch * EXPANDER);
            double constantYaw = Statistics.getGcd(expandedYaw, expandedLastYaw) / EXPANDER;
            double constantPitch = Statistics.getGcd(expandedPitch, expandedLastPitch) / EXPANDER;
            List<Finding> findings = new ArrayList<>(1);

            long pitchGcd = Statistics.getGcd(expandedPitch, expandedLastPitch);
            boolean validAngles = yaw > 0.25f && pitch > 0.25f && pitch < MAX_DELTA && yaw < MAX_DELTA;
            if (pitchGcd < 131072L && validAngles && !sensitivityTooLow) {
                buffer1 = Math.min(buffer1 + 1, 200);
                if (buffer1 > 10) {
                    findings.add(new Finding("MX heuristic constant-1", "gcd=" + pitchGcd));
                    buffer1 = 4;
                }
            } else if (buffer1 > 0) buffer1 -= 2;

            double currentX = yaw / constantYaw, currentY = pitch / constantPitch;
            double previousX = lastYaw / constantYaw, previousY = lastPitch / constantPitch;
            boolean validDelta = yaw > MIN_DELTA && pitch > MIN_DELTA && yaw < MAX_DELTA && pitch < MAX_DELTA;
            if (validDelta) {
                double moduloX = currentX % previousX, moduloY = currentY % previousY;
                double floorModuloX = Math.abs(Math.floor(moduloX) - moduloX);
                double floorModuloY = Math.abs(Math.floor(moduloY) - moduloY);
                if (moduloX > MODULO_THRESHOLD && floorModuloX > LINEAR_THRESHOLD
                        && moduloY > MODULO_THRESHOLD && floorModuloY > LINEAR_THRESHOLD
                        && !sensitivityTooLow) {
                    buffer2 = Math.min(buffer2 + 1, 200);
                    if (buffer2 > 6) {
                        findings.add(new Finding("MX heuristic constant-2", "moduloX=" + moduloX + " moduloY=" + moduloY));
                        buffer2 = 4;
                    }
                } else if (buffer2 > 0) buffer2 -= 2;
            }

            if (validDelta) {
                double moduloX = currentX % previousX, moduloY = currentY % previousY;
                double floorModuloX = Math.abs(Math.floor(moduloX) - moduloX);
                double floorModuloY = Math.abs(Math.floor(moduloY) - moduloY);
                if (moduloX > 60 && floorModuloX > 0.1 && moduloY > 60 && floorModuloY > 0.1
                        && !sensitivityTooLow) {
                    buffer3 = Math.max(buffer3 + (pitch < 1 || pitch > 13 ? 2 : 1), 0);
                    float limit = 7.0f;
                    if (buffer3 > (sensitivity < 70 ? limit + 1 : limit)) {
                        findings.add(new Finding("MX heuristic constant-3", "pitch=" + pitch));
                        buffer3 = 0;
                    }
                } else if (buffer3 > 0) buffer3 -= 2;
            }
            lastYaw = yaw;
            lastPitch = pitch;
            return findings;
        }
    }

    private static final class Invalid {
        private static final float INVALID_PITCH = 90f + 1e-6f;
        private int buffer;
        List<Finding> process(MxAimInput input) {
            if (pitchMagnitudeUnchanged(input)) return List.of();
            List<Finding> findings = new ArrayList<>(1);
            float yaw = input.absDeltaYaw(), pitch = input.absDeltaPitch();
            if (Statistics.isExponentiallySmall(pitch) && pitch > 0.0 && yaw > 0.5f) {
                buffer += 20;
                if (buffer > 70) findings.add(new Finding("MX heuristic invalid pitch", "pitch delta=" + input.deltaPitch()));
            } else buffer--;
            if (input.currentPitch() > INVALID_PITCH)
                findings.add(new Finding("MX heuristic unlimited pitch", "pitch=" + input.currentPitch()));
            return findings;
        }
    }

    private static final class Inconsistent {
        private final List<Float> yawSamples = new ArrayList<>(30), pitchSamples = new ArrayList<>(30);
        private float lastYaw, lastPitch, buffer;
        List<Finding> process(MxAimInput input, int sensitivity, int clientSensitivity,
                              boolean ignoreCinematic) {
            if (pitchMagnitudeUnchanged(input)) return List.of();
            boolean invalidSensitivity = sensitivity < 75 || sensitivity > 175
                    || clientSensitivity < 75 || clientSensitivity > 170;
            if (ignoreCinematic || invalidSensitivity) return List.of();
            float yaw = Math.abs(input.absDeltaYaw()), pitch = Math.abs(input.absDeltaPitch());
            float differenceYaw = Math.abs(yaw - lastYaw), differencePitch = Math.abs(pitch - lastPitch);
            float joltX = Math.abs(yaw - differenceYaw), joltY = Math.abs(pitch - differencePitch);
            yawSamples.add((float) Statistics.roundToPlace(joltX, 2));
            pitchSamples.add((float) Statistics.roundToPlace(joltY, 2));
            List<Finding> findings = new ArrayList<>(1);
            if (yawSamples.size() + pitchSamples.size() >= 60) {
                if (joltX != 0.0f && joltY != 0.0f) {
                    var outliersYaw = Statistics.getOutliers(yawSamples);
                    var outliersPitch = Statistics.getOutliers(pitchSamples);
                    int duplicates = Statistics.getDuplicates(yawSamples) + Statistics.getDuplicates(pitchSamples);
                    int outliersX = outliersYaw.getX().size() + outliersYaw.getY().size();
                    int outliersY = outliersPitch.getX().size() + outliersPitch.getY().size();
                    if (duplicates <= 3 && outliersX < 10 && outliersY < 7 && buffer++ >= 2) {
                        findings.add(new Finding("MX heuristic inconsistent",
                                "outliers=" + outliersX + "," + outliersY + " duplicates=" + duplicates));
                    } else if ((outliersX == 0 || outliersY == 0) && (outliersX > 1 || outliersY > 1)
                            && duplicates <= 3 && buffer++ >= 2) {
                        findings.add(new Finding("MX heuristic inconsistent",
                                "zero-side outliers=" + outliersX + "," + outliersY + " duplicates=" + duplicates));
                    } else buffer -= 0.5f;
                }
                yawSamples.clear();
                pitchSamples.clear();
            }
            lastYaw = yaw;
            lastPitch = pitch;
            return findings;
        }
    }

    private static final class Pattern {
        private static final int PATTERN_LENGTH = 3, SAMPLE_SIZE = 100;
        private final List<Vec2f> sample = new ArrayList<>(SAMPLE_SIZE);
        private Vec2f oldDelta = new Vec2f(0, 0);
        private float buffer;
        private int longTermRating, toCheck;
        List<Finding> process(MxAimInput input) {
            if (pitchMagnitudeUnchanged(input)) return List.of();
            float yawFactor = input.deltaYaw() - oldDelta.x();
            float pitchFactor = input.deltaPitch() - oldDelta.y();
            sample.add(new Vec2f(yawFactor, pitchFactor));
            List<Finding> findings = new ArrayList<>(1);
            if (sample.size() >= SAMPLE_SIZE) {
                boolean flagged = false;
                List<Float> filtered = new ArrayList<>();
                for (int i = 1; i < SAMPLE_SIZE; i++) {
                    if (Math.abs(sample.get(i).x()) > 1.0f) {
                        float difference = Math.abs(sample.get(i).x() - sample.get(i - 1).y());
                        if (difference < 1.0e-4f) filtered.add(difference);
                    }
                }
                if (filtered.size() > 3) {
                    flagged = true;
                    if (buffer++ >= 2.5f) {
                        findings.add(new Finding("MX heuristic raw patterns", filtered.toString()));
                        buffer -= 1;
                    }
                }
                if (!flagged) {
                    List<Vec2f> patterns = new ArrayList<>();
                    for (int i = 0; i <= sample.size() - PATTERN_LENGTH; i++) {
                        for (int j = i + PATTERN_LENGTH; j <= sample.size() - PATTERN_LENGTH; j++) {
                            Vec2f match = null;
                            for (int k = 0; k < PATTERN_LENGTH; k++) {
                                Vec2f first = sample.get(i + k), second = sample.get(j + k);
                                if (Objects.equals(first, second)) { match = first; break; }
                            }
                            if (match != null && !patterns.contains(match)) patterns.add(match);
                        }
                    }
                    longTermRating += patterns.size();
                    if (++toCheck >= 8) { toCheck = 0; longTermRating = 0; }
                    for (Vec2f found : patterns) {
                        float x = Math.abs(found.x()), y = Math.abs(found.y());
                        if ((x > 1.0 || y > 1.0) && x > 0.26 && y > 0.26) {
                            flagged = true;
                            if (buffer++ >= 2.5f) {
                                findings.add(new Finding("MX heuristic repeated pattern", found.toString()));
                                buffer -= 1.0f;
                            }
                            break;
                        }
                    }
                }
                if (!flagged) buffer = Math.max(0.0f, buffer - 0.3f);
                sample.clear();
            }
            oldDelta = new Vec2f(input.deltaYaw(), input.deltaPitch());
            return findings;
        }
    }

    private static final class Factor {
        private final List<Double> stack = new ArrayList<>(3);
        private boolean lastNoRotation;
        private double lastHash;
        private float buffer;
        private int ticksToReset;

        List<Finding> process(MxAimInput input, boolean noRotation) {
            if (noRotation || input != null && input.absDeltaPitch() == 0) {
                if (!lastNoRotation) addStack(0.0);
                lastNoRotation = true;
            } else if (input != null) {
                addStack(Simplification.scaleVal(input.absDeltaYaw(), 2));
                lastNoRotation = false;
            }
            if (stack.size() != 3) return List.of();
            double hash = stack.get(0) + stack.get(1) + stack.get(2);
            if (hash == lastHash) return List.of();
            double centre = stack.get(1);
            List<Finding> findings = new ArrayList<>(1);
            if (centre > 35 && centre != 360.0f) {
                boolean bad = stack.get(0) < 1.2 && stack.get(2) < 1.2
                        || stack.get(0) > 55 && stack.get(1) < 2 && stack.get(2) > 55
                        || Statistics.getMax(stack) > 70 && Statistics.getMin(stack) < 1.2
                        && Statistics.getDistinct(stack) != 3;
                if (bad) {
                    float local = centre > 160 ? 3 : centre < 60 ? 1 : 2;
                    buffer += local;
                    if (buffer >= 3.0f) {
                        findings.add(new Finding("MX heuristic factor", "stack=" + Arrays.toString(stack.toArray())));
                        buffer = 2.0f;
                    }
                }
            } else {
                if (++ticksToReset >= 2500) { ticksToReset = 0; buffer = 0; }
            }
            lastHash = hash;
            return findings;
        }

        private void addStack(double value) {
            if (stack.size() >= 3) stack.remove(0);
            stack.add(value);
        }
    }

    private static final class Smooth {
        private final List<Double> stack = new ArrayList<>(20);
        List<Finding> process(MxAimInput input, boolean ignoreCinematic) {
            if (ignoreCinematic || pitchMagnitudeUnchanged(input)) return List.of();
            double angle = Math.toDegrees(Math.atan2(input.deltaYaw(), input.deltaPitch()));
            if (angle < 0) angle += 360;
            angle %= 90;
            if (input.absDeltaPitch() > 1.5 && input.absDeltaYaw() > 0.32 || input.absDeltaYaw() > 1.5)
                stack.add(angle);
            if (stack.size() < 20) return List.of();
            List<Float> jiff = Statistics.getJiffDelta(stack, 1);
            float previous = 999, prePrevious = 999;
            for (float value : jiff) {
                if (value == 0.0f && previous == 0.0f && prePrevious == 0.0f) {
                    stack.clear();
                    return List.of(new Finding("MX heuristic smoothing", jiff.toString()));
                }
                prePrevious = previous;
                previous = value;
            }
            stack.clear();
            return List.of();
        }
    }
}
