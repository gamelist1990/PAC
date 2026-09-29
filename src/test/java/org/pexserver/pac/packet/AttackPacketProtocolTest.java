package org.pexserver.pac.packet;

import com.github.retrooper.packetevents.protocol.packettype.PacketType;
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientAttack;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

class AttackPacketProtocolTest {
    @Test void minecraft261PlusUsesDedicatedAttackPacket() {
        var attack = new WrapperPlayClientAttack(73);

        assertEquals(PacketType.Play.Client.ATTACK, attack.getPacketType());
        assertEquals(73, attack.getEntityId());
        assertNotEquals(PacketType.Play.Client.INTERACT_ENTITY, attack.getPacketType(),
                "26.1+ attacks must not be assumed to arrive through the legacy interact packet");
    }
}
