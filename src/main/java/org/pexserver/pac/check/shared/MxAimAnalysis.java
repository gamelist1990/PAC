package org.pexserver.pac.check.shared;

import org.pexserver.pac.check.shared.aim.model.math.Statistics;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.function.Function;

/** PAC-native port of MX's AimAnalysisCheck state and window calculations. */
final class MxAimAnalysis {
    record Finding(String source, String detail) { }

    private final List<Float> rawYaw = new ArrayList<>(100);
    private final List<Float> limitedYaw = new ArrayList<>(100);
    private final List<Float> longTermAnalysis = new ArrayList<>(10);
    private final float[] buffer = new float[16];
    private boolean query;

    List<Finding> sample(float yaw, float pitch, int calculatedSensitivity) {
        List<Finding> findings = new ArrayList<>(3);
        rawYaw.add(yaw);
        if (Math.abs(yaw) > 1.35 || Math.abs(pitch) > 1.35 && Math.abs(yaw) > 0.32)
            limitedYaw.add(yaw);
        if (rawYaw.size() >= 100) checkRaw(calculatedSensitivity, findings);
        if (limitedYaw.size() >= 100) checkLimited(findings);
        return findings;
    }

    private void checkRaw(int sensitivity, List<Finding> findings) {
        List<Float> yaw = new ArrayList<>(100);
        List<Float> yawAbsSource = new ArrayList<>(100);
        for (float delta : rawYaw) {
            yaw.add(delta);
            // MX currently feeds the signed yaw delta into xAbs as well.
            yawAbsSource.add(delta);
        }
        List<Double> deviations = new ArrayList<>(10);
        int resultDistinct = 0;
        List<Float> stack = new ArrayList<>(10);
        for (float delta : yaw) {
            stack.add(delta);
            if (stack.size() >= 10) {
                deviations.add(Statistics.getStandardDeviation(Statistics.getJiffDelta(stack, 5)));
                resultDistinct += Statistics.getDistinct(Statistics.getJiffDelta(stack, 4));
                stack.clear();
            }
        }

        List<Double> outliers = Statistics.getZScoreOutliers(deviations, 0.5f);
        float distinctRank = (float) resultDistinct / 60.0f;
        if (outliers.isEmpty() || outliers.size() == 1
                && Math.abs(outliers.get(0)) > 10 && Math.abs(outliers.get(0)) < 100) {
            // MX requires two consecutive matching windows before it flags.
            if (query) findings.add(new Finding("MX aim analysis linear",
                    "invalid deviation outliers=" + Arrays.toString(outliers.toArray())));
            query = true;
        } else query = false;

        boolean validSensitivity = sensitivity > 20 && sensitivity < 140;
        if (distinctRank < 1.0 && distinctRank > 0.7
                && Statistics.getAverage(yawAbsSource) > 1.8 && validSensitivity) {
            if (buffer[1] < 0.01f) {
                if (distinctRank < 0.8) increase(1, 0.2f);
            } else {
                float limit = 6.0f;
                increase(1, distinctRank > 0.9 ? 0.08f : distinctRank > 0.8 ? 2.0f : 3.0f);
                if (buffer[1] >= limit) {
                    findings.add(new Finding("MX aim analysis rank",
                            String.format(Locale.ROOT, "rank=%.4f sensitivity=%d buffer=%.2f",
                                    distinctRank, sensitivity, buffer[1])));
                    buffer[1] = limit - 1.0f;
                }
            }
        } else increase(1, -2.25f);
        rawYaw.clear();
    }

    private void checkLimited(List<Finding> findings) {
        List<Float> stack = new ArrayList<>(10);
        int resultDistinct = 0;
        for (float delta : limitedYaw) {
            stack.add(delta);
            if (stack.size() >= 10) {
                resultDistinct += Statistics.getDistinct(Statistics.getJiffDelta(stack, 4));
                stack.clear();
            }
        }
        longTermAnalysis.add((float) resultDistinct / 60.0f);
        if (longTermAnalysis.size() >= 10) {
            double average = Statistics.getAverage(longTermAnalysis);
            long normal = longTermAnalysis.stream().filter(value -> value > 0.97).count();
            if (average < 0.95 && normal < 4) {
                findings.add(new Finding("MX aim analysis long-term",
                        String.format(Locale.ROOT, "average rank=%.4f; normal=%d/10", average, normal)));
            }
            longTermAnalysis.clear();
        }
        limitedYaw.clear();
    }

    private void increase(int index, float amount) {
        buffer[index] = Math.max(0.0f, buffer[index] + amount);
    }
}
