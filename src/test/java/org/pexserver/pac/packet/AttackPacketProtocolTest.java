package org.pexserver.pac.packet;

import com.github.retrooper.packetevents.protocol.packettype.PacketType;
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientAttack;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

class AttackPacketProtocolTest {
    @Test void minecraft261PlusExposesDedicatedAttackProtocol() throws Exception {
        assertNotNull(PacketType.Play.Client.ATTACK);
        assertNotEquals(PacketType.Play.Client.INTERACT_ENTITY, PacketType.Play.Client.ATTACK,
                "26.1+ ATTACK must remain distinct from the legacy interact packet");

        assertEquals(int.class,
                WrapperPlayClientAttack.class.getMethod("getEntityId").getReturnType());
        assertEquals(void.class,
                WrapperPlayClientAttack.class.getMethod("setEntityId", int.class).getReturnType());
    }
}
