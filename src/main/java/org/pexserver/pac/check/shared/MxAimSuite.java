package org.pexserver.pac.check.shared;

import java.util.ArrayList;
import java.util.List;

/** Runs MX's five registered aim engines and its pretrained model windows on one rotation stream. */
final class MxAimSuite {
    record Finding(String source, String detail) { }
    record Result(List<Finding> findings, KillAuraCheck.ModelWindows modelWindows) { }

    private final MxAimSensitivity sensitivity = new MxAimSensitivity();
    private final MxAimCinematic cinematic = new MxAimCinematic();
    private final MxAimAnalysis analysis = new MxAimAnalysis();
    private final MxAimComplex complex = new MxAimComplex();
    private final MxAimStatistics statistics = new MxAimStatistics();
    private final MxAimHeuristic heuristic = new MxAimHeuristic();
    private final List<Float> legacyYaw = new ArrayList<>(600);
    private final List<Float> legacyPitch = new ArrayList<>(600);
    private final List<Float> rnnYaw = new ArrayList<>(150);
    private final List<Float> rnnPitch = new ArrayList<>(150);

    private boolean hasRotation;
    private boolean firstRotationPending = true;
    private float lastYaw;
    private float lastPitch;
    private long lastTeleport;

    void initialize(float yaw, float pitch) {
        if (!Float.isFinite(yaw) || !Float.isFinite(pitch)) return;
        lastYaw = yaw;
        lastPitch = pitch;
        hasRotation = true;
        firstRotationPending = true;
    }

    Result sample(float yaw, float pitch, long now, long lastAttack, boolean ignoreCinematic,
                  boolean ignoreUnchangedRotation) {
        if (!Float.isFinite(yaw) || !Float.isFinite(pitch)) return new Result(List.of(), null);
        if (!hasRotation) {
            hasRotation = true;
            lastYaw = yaw;
            lastPitch = pitch;
            sensitivity.process(0.0f);
            cinematic.process(0.0f, 0.0f, now);
            firstRotationPending = false;
            return new Result(List.of(), null);
        }
        if (ignoreUnchangedRotation && yaw == lastYaw && pitch == lastPitch)
            return new Result(List.of(), null);

        // MX creates RotationEvent(profile, current, previous), so getDelta() is previous-current.
        MxAimInput input = new MxAimInput(lastYaw - yaw, lastPitch - pitch,
                (float) Math.abs(Math.abs(lastYaw) - Math.abs(yaw)),
                (float) Math.abs(Math.abs(lastPitch) - Math.abs(pitch)),
                lastYaw, lastPitch, pitch);
        lastYaw = yaw;
        lastPitch = pitch;

        sensitivity.process(input.deltaPitch());
        boolean isCinematic = cinematic.process(input.deltaYaw(), input.deltaPitch(), now);
        boolean teleporting = firstRotationPending || now - lastTeleport < 500L;
        if (firstRotationPending) firstRotationPending = false;
        if (teleporting) return new Result(List.of(), null);

        long sinceAttack = now - lastAttack;
        if (lastAttack <= 0L || sinceAttack > 3_500L) {
            if (sinceAttack > 3_000L) clearModelWindows();
            return new Result(List.of(), null);
        }

        int calculatedSensitivity = sensitivity.calculateSensitivity();
        int clientSensitivity = sensitivity.clientSensitivity();
        List<Finding> findings = new ArrayList<>(8);
        for (MxAimAnalysis.Finding finding : analysis.sample(
                input.deltaYaw(), input.deltaPitch(), calculatedSensitivity))
            findings.add(new Finding(finding.source(), finding.detail()));
        for (MxAimComplex.Finding finding : complex.sample(input.deltaYaw(), input.deltaPitch(),
                calculatedSensitivity, clientSensitivity, ignoreCinematic))
            findings.add(new Finding(finding.source(), finding.detail()));
        for (MxAimStatistics.Finding finding : statistics.sample(input.deltaYaw(), input.deltaPitch()))
            findings.add(new Finding(finding.source(), finding.detail()));
        for (MxAimHeuristic.Finding finding : heuristic.sample(input, calculatedSensitivity,
                clientSensitivity, ignoreCinematic))
            findings.add(new Finding(finding.source(), finding.detail()));

        KillAuraCheck.ModelWindows windows = null;
        if (sinceAttack <= 3_000L && !isCinematic) windows = addModelSample(input.deltaYaw(), input.deltaPitch());
        return new Result(List.copyOf(findings), windows);
    }

    List<Finding> noRotation(long now, long lastAttack) {
        if (firstRotationPending || now - lastTeleport < 1_000L || lastAttack <= 0L
                || now - lastAttack > 3_500L) return List.of();
        List<Finding> findings = new ArrayList<>(1);
        for (MxAimHeuristic.Finding finding : heuristic.noRotation())
            findings.add(new Finding(finding.source(), finding.detail()));
        return List.copyOf(findings);
    }

    void teleported(long now) {
        lastTeleport = now;
        firstRotationPending = true;
        clearModelWindows();
    }

    private KillAuraCheck.ModelWindows addModelSample(float yaw, float pitch) {
        legacyYaw.add(yaw);
        legacyPitch.add(pitch);
        rnnYaw.add(yaw);
        rnnPitch.add(pitch);
        KillAuraCheck.ModelWindow legacy = null;
        KillAuraCheck.ModelWindow rnn = null;
        if (rnnYaw.size() == 150) {
            rnn = new KillAuraCheck.ModelWindow(List.copyOf(rnnYaw), List.copyOf(rnnPitch));
            rnnYaw.clear();
            rnnPitch.clear();
        }
        if (legacyYaw.size() == 600) {
            legacy = new KillAuraCheck.ModelWindow(List.copyOf(legacyYaw), List.copyOf(legacyPitch));
            legacyYaw.clear();
            legacyPitch.clear();
        }
        return legacy == null && rnn == null ? null : new KillAuraCheck.ModelWindows(legacy, rnn);
    }

    void clearModelWindows() {
        legacyYaw.clear();
        legacyPitch.clear();
        rnnYaw.clear();
        rnnPitch.clear();
    }
}
