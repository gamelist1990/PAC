package org.pexserver.pac;

import org.junit.jupiter.api.Test;
import org.pexserver.pac.movement.SurfaceBouncePredictor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SurfaceBouncePredictorTest {
    @Test void slimeRestitutionMatchesGeneralizedVanillaFormula() {
        double next = SurfaceBouncePredictor.nextDisplacementAfterBounce(
                -0.30, -0.20, 0.08, 0.98f, false, 1.0f);

        double bounceVelocity = ((-0.20 / -0.30) * 0.08 - -0.30);
        double expected = (bounceVelocity - 0.08) * (double) 0.98f;
        assertEquals(expected, next, 1.0e-12);
    }

    @Test void bedUsesServerRestitutionValue() {
        double slime = SurfaceBouncePredictor.nextDisplacementAfterBounce(
                -0.40, -0.10, 0.08, 0.98f, false, 1.0f);
        double bed = SurfaceBouncePredictor.nextDisplacementAfterBounce(
                -0.40, -0.10, 0.08, 0.98f, false, 0.66f);

        assertTrue(bed > 0);
        assertTrue(bed < slime);
    }

    @Test void crouchingAndTinyFallsSuppressBounce() {
        assertFalse(SurfaceBouncePredictor.shouldBounce(
                -0.30, 0.08, false, 1.0f, true));
        assertFalse(SurfaceBouncePredictor.shouldBounce(
                -0.05, 0.08, false, 1.0f, false));
    }

    @Test void slowFallingUsesReducedImpactThresholdButNormalPostBounceGravity() {
        assertTrue(SurfaceBouncePredictor.shouldBounce(
                -0.03, 0.08, true, 1.0f, false));
        double next = SurfaceBouncePredictor.nextDisplacementAfterBounce(
                -0.03, -0.01, 0.08, 0.98f, true, 1.0f);
        assertTrue(Double.isFinite(next));
    }
}
