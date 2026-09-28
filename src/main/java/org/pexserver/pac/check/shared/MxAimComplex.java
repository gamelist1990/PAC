package org.pexserver.pac.check.shared;

import org.pexserver.pac.check.shared.aim.model.math.Statistics;
import org.pexserver.pac.check.shared.aim.model.vectors.Pair;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** PAC-native port of MX's AimComplexCheck. */
final class MxAimComplex {
    record Finding(String source, String detail) { }
    private record Vec2(double x, double y) { }
    private record Vec2i(int x, int y) { }

    private final float[] buffer = new float[16];
    private final List<Vec2i> rotations10 = new ArrayList<>(10);
    private final List<Vec2> raw10 = new ArrayList<>(10);
    private final List<Vec2> generic7 = new ArrayList<>(7);
    private double oldShannonYaw;
    private double oldShannonPitch;

    List<Finding> sample(float yaw, float pitch, int sensitivity, int clientSensitivity,
                         boolean ignoreCinematic) {
        List<Finding> findings = new ArrayList<>(2);
        raw10.add(new Vec2(yaw, pitch));
        double gcdValue = Statistics.getGCDValue(0.5d) * 3.0d;
        rotations10.add(new Vec2i((int) (yaw / gcdValue), (int) (pitch / gcdValue)));
        if (rotations10.size() >= 10) checkSpikes(findings, sensitivity);
        if (raw10.size() >= 10) {
            if (!ignoreCinematic) checkRaw(findings, sensitivity, clientSensitivity);
            raw10.clear();
        }
        return findings;
    }

    private void checkRaw(List<Finding> findings, int sensitivity, int clientSensitivity) {
        List<Float> yaw = new ArrayList<>(10), pitch = new ArrayList<>(10);
        for (Vec2 rotation : raw10) {
            yaw.add((float) rotation.x());
            pitch.add((float) rotation.y());
        }
        int distinctYaw = Statistics.getDistinct(yaw);
        double shannonYaw = Statistics.getShannonEntropy(yaw);
        double shannonPitch = Statistics.getShannonEntropy(pitch);
        boolean valid = sensitivity >= 60 && sensitivity <= 150
                && clientSensitivity >= 60 && clientSensitivity < 150;
        if (valid && difference(shannonYaw, oldShannonYaw) < 1.0e-5
                && difference(shannonPitch, oldShannonPitch) < 1.0e-5) {
            buffer[11] += 1.0f;
            if (buffer[11] > 30.0f)
                findings.add(new Finding("MX complex perfect entropy", "yaw entropy=" + shannonYaw));
            if (buffer[11] > 30.0f) buffer[11] = 29.0f;
        } else buffer[11] = 0.0f;

        if (valid && difference(shannonYaw, shannonPitch) < 1.0e-5) {
            buffer[12] += 1.0f;
            if (buffer[12] > 30.0f)
                findings.add(new Finding("MX complex similar entropy", "yaw=" + shannonYaw + " pitch=" + shannonPitch));
            if (buffer[12] > 30.0f) buffer[12] = 29.0f;
        } else buffer[12] = 0.0f;

        if (distinctYaw < 8 && Math.abs(Statistics.getAverage(yaw)) > 2.5) {
            buffer[9] += 1.7f;
            if (buffer[9] >= 4.0f) {
                findings.add(new Finding("MX complex distinct", "distinct yaw=" + distinctYaw));
                buffer[9] = Math.max(0.0f, buffer[9] - 0.5f);
            }
        } else buffer[9] = Math.max(0.0f, buffer[9] - 0.35f);

        oldShannonYaw = shannonYaw;
        oldShannonPitch = shannonPitch;
    }

    private void checkSpikes(List<Finding> findings, int sensitivity) {
        List<Integer> yaw = new ArrayList<>(10), pitch = new ArrayList<>(10);
        for (Vec2i rotation : rotations10) {
            yaw.add(rotation.x());
            pitch.add(rotation.y());
        }
        rotations10.clear();

        Vec2 generic = new Vec2(Statistics.getKireikoGeneric(yaw), Statistics.getKireikoGeneric(pitch));
        generic7.add(generic);
        if (generic7.size() >= 7) {
            List<Double> gx = new ArrayList<>(7), gy = new ArrayList<>(7);
            for (Vec2 value : generic7) { gx.add(value.x()); gy.add(value.y()); }
            double xDeviation = Statistics.getStandardDeviation(gx);
            double yDeviation = Statistics.getStandardDeviation(gy);
            Pair<Double, Double> xSpikes = new Pair<>(Statistics.getMin(gx), Statistics.getMax(gx));
            Pair<Double, Double> ySpikes = new Pair<>(Statistics.getMin(gy), Statistics.getMax(gy));
            if (xDeviation > 5 && xDeviation < 22 && xSpikes.getY() < 50) {
                buffer[5] += Statistics.getAverage(gx) < 6.0 ? 0.0f : xDeviation < 10 ? 1.5f : 1.0f;
                // MX's Machine Heart punishment is intentionally disabled in its source.
                if (buffer[5] >= 7.0f) buffer[5] = 6.0f;
            } else buffer[5] = Math.max(0.0f,
                    buffer[5] + (xDeviation < 40 || xSpikes.getY() < 70 ? -0.4f : -0.8f));
            generic7.clear();
        }

        double varianceYaw = Statistics.getVariance(yaw);
        double variancePitch = Statistics.getVariance(pitch);
        double minVariance = Math.min(varianceYaw, variancePitch);
        double maxVariance = Math.max(varianceYaw, variancePitch);
        if (minVariance < 0.09 && maxVariance > 35
                && Statistics.getMin(pitch) != 0.0 && sensitivity > 50) {
            buffer[4] += 1.0f;
            if (buffer[4] > 2.5f) {
                findings.add(new Finding("MX complex randomizer", String.format(Locale.ROOT,
                        "yaw variance=%.5f pitch variance=%.5f", varianceYaw, variancePitch)));
                buffer[4] = 1.5f;
            }
        } else buffer[4] = Math.max(0.0f, buffer[4] - 0.4f);
    }

    private static double difference(double a, double b) {
        return Math.abs(Math.abs(a) - Math.abs(b));
    }
}
