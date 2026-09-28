package org.pexserver.pac.check.shared;

import org.pexserver.pac.check.shared.aim.model.math.Statistics;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Port of MX's CinematicComponent because AimML gates its trained classifiers on it. */
final class MxAimCinematic {
    private final List<Double> yawSamples = new ArrayList<>(20);
    private final List<Double> pitchSamples = new ArrayList<>(20);
    private long lastSmooth, lastHighRate;
    private double lastDeltaYaw, lastDeltaPitch;
    private int notCinematic;

    boolean process(float deltaYaw, float deltaPitch, long now) {
        double differenceYaw = Math.abs(deltaYaw - lastDeltaYaw);
        double differencePitch = Math.abs(deltaPitch - lastDeltaPitch);
        double joltYaw = Math.abs(differenceYaw - deltaYaw);
        double joltPitch = Math.abs(differencePitch - deltaPitch);
        boolean cinematic = now - lastHighRate > 250L || now - lastSmooth < 9_000L;
        if (joltYaw > 1.0 && joltPitch > 1.0) lastHighRate = now;
        yawSamples.add((double) deltaYaw);
        pitchSamples.add((double) deltaPitch);

        if (yawSamples.size() >= 20 && pitchSamples.size() >= 20) {
            Set<Double> yawEntropy = new HashSet<>(), pitchEntropy = new HashSet<>();
            List<Double> stackYaw = new ArrayList<>(10), stackPitch = new ArrayList<>(10);
            for (Double value : yawSamples) {
                stackYaw.add(value);
                // The upstream implementation adds yawSample to both stacks.
                stackPitch.add(value);
                if (stackYaw.size() >= 10 && stackPitch.size() >= 10) {
                    yawEntropy.add(Statistics.getShannonEntropy(stackYaw));
                    pitchEntropy.add(Statistics.getShannonEntropy(stackPitch));
                    stackYaw.clear();
                    stackPitch.clear();
                }
            }
            if (yawEntropy.size() != 1 || pitchEntropy.size() != 1
                    || !yawEntropy.iterator().next().equals(pitchEntropy.iterator().next())) notCinematic = 20;
            Graph yawGraph = graph(yawSamples);
            Graph pitchGraph = graph(pitchSamples);
            if (yawGraph.positive() > yawGraph.negative() || pitchGraph.positive() > pitchGraph.negative())
                lastSmooth = now;
            yawSamples.clear();
            pitchSamples.clear();
        }

        if (notCinematic > 0) {
            notCinematic--;
            cinematic = false;
        }
        lastDeltaYaw = deltaYaw;
        lastDeltaPitch = deltaPitch;
        return cinematic;
    }

    private static Graph graph(List<Double> values) {
        double largest = 0;
        for (double value : values) if (value > largest) largest = value;
        int positive = 0, negative = 0;
        for (int row = 1; row > 0; row--) {
            for (double value : values) {
                double normalized = 2.0 * value / largest;
                if (normalized > row && normalized < row + 1) positive++;
                else negative++;
            }
        }
        return new Graph(positive, negative);
    }

    private record Graph(int positive, int negative) { }
}
