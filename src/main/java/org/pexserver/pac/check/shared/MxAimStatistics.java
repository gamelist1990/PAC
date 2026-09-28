package org.pexserver.pac.check.shared;

import org.pexserver.pac.check.shared.aim.model.math.Statistics;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Function;

/** PAC-native port of MX's AimStatisticsCheck. */
final class MxAimStatistics {
    record Finding(String source, String detail) { }
    private final float[] buffer = new float[16];
    private final List<Float> yawWindow = new ArrayList<>(25);
    private final List<Float> pitchWindow = new ArrayList<>(25);
    private final List<Double> shannonAnalysis = new ArrayList<>();

    List<Finding> sample(float yaw, float pitch) {
        yawWindow.add(yaw);
        pitchWindow.add(pitch);
        if (yawWindow.size() < 25) return List.of();
        List<Finding> findings = analyze();
        yawWindow.clear();
        pitchWindow.clear();
        return findings;
    }

    private List<Finding> analyze() {
        List<Finding> findings = new ArrayList<>(3);
        float total = 0.0f;
        List<Double> zFactorYaw = Statistics.getZScoreOutliers(yawWindow, 2.0);
        List<Float> jiffYaw = Statistics.getJiffDelta(yawWindow, 5);
        List<Float> jiffPitch = Statistics.getJiffDelta(pitchWindow, 5);
        List<Float> jiffOmni = new ArrayList<>(Math.min(jiffYaw.size(), jiffPitch.size()));
        for (int i = 0; i < Math.min(jiffYaw.size(), jiffPitch.size()); i++)
            jiffOmni.add(jiffYaw.get(i) / jiffPitch.get(i));

        int infinite = 0;
        for (float value : jiffOmni) if (Float.isInfinite(value)) infinite++;
        double iqr = Statistics.getIQR(jiffOmni);
        if (iqr > 12.5 && iqr < 96 && infinite > 0) {
            buffer[8] += iqr > 20 ? 1.4f : 0.8f;
            if (buffer[8] > 11.0f) {
                findings.add(new Finding("MX statistics iqr", "IQR=" + iqr + " infinite=" + infinite));
                buffer[8] = 9.0f;
            }
        } else if (iqr < 13 || infinite == 0) {
            buffer[8] = Math.max(0.0f, buffer[8] + (iqr < 7 ? -5.0f : -3.5f));
        }

        double kTest = Statistics.kolmogorovSmirnovTest(
                Statistics.getJiffDelta(yawWindow, 6), Function.identity());
        if (kTest > 10 && Math.abs(Statistics.getAverage(yawWindow)) < 13) total++;

        shannonAnalysis.add(Statistics.getShannonEntropy(jiffYaw));
        if (shannonAnalysis.size() > 9) {
            Set<Double> unique = new HashSet<>(shannonAnalysis);
            double diff = difference(Statistics.getMin(unique), Statistics.getMax(unique));
            // MX calculates this candidate but leaves its punishment disabled.
            if (unique.size() <= 5 && unique.size() > 3 && diff < 0.38) { /* intentionally dormant */ }
            shannonAnalysis.clear();
        }

        int jiffPatterns = 0;
        for (int i = 0; i < jiffYaw.size(); i++) {
            float value = jiffYaw.get(i);
            if (!String.valueOf(value).contains("E") || value == 0) continue;
            for (int r = 0; r < jiffYaw.size(); r++)
                if (r != i && value == jiffYaw.get(r)) jiffPatterns++;
        }
        if (jiffPatterns > 2 && Statistics.getAverage(yawWindow) > 3.0
                && jiffPatterns != 6 && jiffPatterns != 12 && jiffPatterns != 4) {
            findings.add(new Finding("MX statistics bot-pattern", "pattern matches=" + jiffPatterns));
        }

        boolean positive = false, negative = false;
        for (double value : zFactorYaw) {
            if (value > 10) positive = true;
            if (value < -10) negative = true;
        }
        if (zFactorYaw.size() == 2 && positive && negative && Statistics.getMax(zFactorYaw) < 55) {
            buffer[0] += 1.5f;
            if (buffer[0] > 4) total++;
            if (buffer[0] > 7.0f) {
                findings.add(new Finding("MX statistics z-factor", "outliers=" + zFactorYaw));
                buffer[0] = 6.0f;
            }
        } else buffer[0] = Math.max(0.0f, buffer[0] - 1.2f);

        if (total < 2.0f) buffer[10] = Math.max(0.0f, buffer[10] - 2.0f);
        else if (total > 2.0f) {
            buffer[10] += 5.0f;
            if (buffer[10] >= 15.0f) {
                findings.add(new Finding("MX statistics improbable", "buffer=" + buffer[10]));
                buffer[10] += 13.0f;
            }
        }
        return findings;
    }

    private static double difference(double a, double b) {
        return Math.abs(Math.abs(a) - Math.abs(b));
    }
}
