package org.pexserver.pac.packet;

import com.github.retrooper.packetevents.protocol.player.ClientVersion;
import org.junit.jupiter.api.Test;
import org.pexserver.pac.movement.MotionCollisionSnapshot;

import static org.junit.jupiter.api.Assertions.assertEquals;

class PacketChecksClientVersionTest {
    @Test void clientVersionsSelectTheMatchingVanillaStepImplementation() {
        assertEquals(MotionCollisionSnapshot.StepProfile.PRE_1_8,
                PacketChecks.stepProfile(ClientVersion.V_1_7_10));
        assertEquals(MotionCollisionSnapshot.StepProfile.V1_8_TO_1_13,
                PacketChecks.stepProfile(ClientVersion.V_1_13_2));
        assertEquals(MotionCollisionSnapshot.StepProfile.V1_14_TO_1_20,
                PacketChecks.stepProfile(ClientVersion.V_1_20_5));
        assertEquals(MotionCollisionSnapshot.StepProfile.V1_21_PLUS,
                PacketChecks.stepProfile(ClientVersion.V_1_21));
    }
}
