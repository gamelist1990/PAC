package org.pexserver.pac.check.shared;

import org.pexserver.pac.check.shared.aim.model.math.Statistics;

import java.util.ArrayList;
import java.util.List;

/** PAC-native MX sensitivity processor, including the profile's 14-value mode history. */
final class MxAimSensitivity {
    private final List<Integer> samples = new ArrayList<>(40);
    private final List<Integer> strictSamples = new ArrayList<>(10);
    private final List<Integer> history = new ArrayList<>(14);
    private int sensitivity = -1;
    private int clientSensitivity;
    private int lastStrictSensitivity;

    void process(float deltaPitch) {
        double absolute = Math.abs(deltaPitch);
        if (absolute < 0.31) {
            for (int index = 0; index < 200; index++) {
                double expected = Statistics.getGCDValue(MxMcpSensitivityValues.VALUES[index]);
                if (Math.abs(expected - absolute) < 1.0e-3) {
                    strictSamples.add(index);
                    if (strictSamples.size() >= 10) {
                        int found = (int) Statistics.getMin(strictSamples);
                        if (found == lastStrictSensitivity) {
                            history.clear();
                            for (int i = 0; i < 5; i++) appendHistory(found);
                        } else appendHistory(found);
                        strictSamples.clear();
                        lastStrictSensitivity = found;
                    }
                    break;
                }
            }
        }

        // MX's RawMovementListener leaves lastDeltaPitch at zero; preserve its signed GCD result.
        double gcd = deltaPitch;
        double modifier = Math.cbrt(0.8333 * gcd);
        double estimate = (1.666 * modifier - 0.3333) * 200.0;
        samples.add((int) estimate);
        if (samples.size() >= 40) {
            sensitivity = mode(samples);
            if (sensitivity >= 0 && sensitivity <= 200) {
                clientSensitivity = sensitivity;
                appendHistory(sensitivity);
            }
            samples.clear();
        }
    }

    int calculateSensitivity() {
        if (Statistics.getDistinct(history) != history.size()) {
            List<Integer> buckets = new ArrayList<>();
            for (int value : history) {
                int bucket = value / 5;
                if (buckets.contains(bucket)) return value;
                buckets.add(bucket);
            }
        }
        return -1;
    }

    int clientSensitivity() { return clientSensitivity; }

    private void appendHistory(int value) {
        if (history.size() >= 14) history.remove(0);
        history.add(value);
    }

    private static int mode(List<Integer> values) {
        int mode = values.get(0), maximumCount = 0;
        for (Integer value : values) {
            int count = 1;
            for (Integer candidate : values) {
                if (candidate.equals(value)) count++;
                if (count > maximumCount) {
                    mode = value;
                    maximumCount = count;
                }
            }
        }
        return mode;
    }
}
