package ac.boar.anticheat.check.impl.prediction;

import java.util.EnumSet;
import org.junit.jupiter.api.Test;
import static ac.boar.anticheat.check.impl.prediction.PredictionEvidence.Kind.*;
import static org.junit.jupiter.api.Assertions.*;

class PredictionEvidenceTest {
    @Test void unrelatedOffsetsDoNotTurnOneCollisionMismatchIntoPhase() {
        PredictionEvidence evidence = new PredictionEvidence();
        assertTrue(evidence.observe(1, EnumSet.of(Flight)).isEmpty());
        assertTrue(evidence.observe(2, EnumSet.of(Flight)).isEmpty());
        assertEquals(EnumSet.of(Flight), evidence.observe(3, EnumSet.of(Flight, Phase)));
    }

    @Test void eachClassificationNeedsItsOwnContinuousEvidence() {
        PredictionEvidence evidence = new PredictionEvidence();
        assertTrue(evidence.observe(1, EnumSet.of(Speed)).isEmpty());
        assertTrue(evidence.observe(2, EnumSet.of(Flight)).isEmpty());
        assertTrue(evidence.observe(3, EnumSet.of(Speed)).isEmpty());
        assertTrue(evidence.observe(4, EnumSet.of(Speed)).isEmpty());
        assertEquals(EnumSet.of(Speed), evidence.observe(5, EnumSet.of(Speed)));
    }

    @Test void missingTicksAndExemptionsDiscardEvidence() {
        PredictionEvidence evidence = new PredictionEvidence();
        evidence.observe(1, EnumSet.of(Phase));
        evidence.observe(2, EnumSet.of(Phase));
        assertTrue(evidence.observe(4, EnumSet.of(Phase)).isEmpty());
        evidence.reset();
        assertTrue(evidence.observe(5, EnumSet.of(Phase)).isEmpty());
        assertTrue(evidence.observe(6, EnumSet.noneOf(PredictionEvidence.Kind.class)).isEmpty());
        assertTrue(evidence.observe(7, EnumSet.of(Phase)).isEmpty());
    }

    @Test void loggedSprintJumpSpeedFitsAirEnvelopeButLargeExcessDoesNot() {
        // Log: expected squared speed 0.05560679, actual 0.115217686.
        double air = PredictionEvidence.speedEnvelope(0.13F, false);
        assertTrue(0.115217686 < air * air);
        assertTrue(0.60 * 0.60 > air * air);
        assertTrue(0.60 * 0.60 > Math.pow(PredictionEvidence.speedEnvelope(0.13F, true), 2));
        assertTrue(PredictionEvidence.speedEnvelope(0.26F, false) > air);
    }
}
