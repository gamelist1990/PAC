package org.pexserver.pac.packet;

import com.github.retrooper.packetevents.event.PacketListener;
import com.github.retrooper.packetevents.event.PacketSendEvent;
import com.github.retrooper.packetevents.protocol.packettype.PacketType;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerEntityVelocity;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerEntityStatus;
import com.github.retrooper.packetevents.protocol.player.ClientVersion;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerExplosion;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerPlayerPositionAndLook;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerPlayerAbilities;
import org.pexserver.pac.PacPlugin;

import java.util.UUID;

/** Observes the final server motion packets that Java clients actually receive. */
public final class OutgoingMotionPackets implements PacketListener {
    private final PacPlugin plugin;
    private final ExternalMotionTracker tracker;
    private final PacketChecks packetChecks;

    public OutgoingMotionPackets(PacPlugin plugin, ExternalMotionTracker tracker,
                                 PacketChecks packetChecks) {
        this.plugin = plugin;
        this.tracker = tracker;
        this.packetChecks = packetChecks;
    }

    @Override public void onPacketSend(PacketSendEvent event) {
        if (event.isCancelled()) return;
        UUID uuid = event.getUser().getUUID();
        if (uuid == null) return;
        if (!plugin.isBedrockPlayer(uuid)) plugin.combatScene().outgoing(event);
        if (event.getPacketType() == PacketType.Play.Server.ENTITY_VELOCITY) {
            var velocity = new WrapperPlayServerEntityVelocity(event);
            if (velocity.getEntityId() != event.getUser().getEntityId()) return;
            var vector = velocity.getVelocity();
            long now = System.currentTimeMillis();
            tracker.velocity(uuid, vector.x, vector.y, vector.z, now);
            packetChecks.outgoingVelocity(uuid, vector.x, vector.y, vector.z, now);
            velocityReply(event,uuid,now);
        } else if (event.getPacketType() == PacketType.Play.Server.ENTITY_STATUS) {
            var status=new WrapperPlayServerEntityStatus(event);
            long now=System.currentTimeMillis();
            if(status.getStatus()==31&&packetChecks.consumeFishingPull(uuid,status.getEntityId(),now))
                velocityReply(event,uuid,now);
        } else if (event.getPacketType() == PacketType.Play.Server.EXPLOSION) {
            // Explosion knockback is additive to existing motion. Treat its packet as
            // a new externally driven transition until the client motion is observed.
            var knockback = new WrapperPlayServerExplosion(event).getKnockback();
            if (knockback != null && knockback.lengthSquared() > 1.0e-8) {
                long now=System.currentTimeMillis();
                tracker.addImpulse(uuid, knockback.x, knockback.y, knockback.z, now);
                velocityReply(event,uuid,now);
            }
        } else if (event.getPacketType() == PacketType.Play.Server.PLAYER_POSITION_AND_LOOK) {
            // Includes position packets sent directly by plugins, without a Bukkit teleport event.
            tracker.forget(uuid);
            plugin.environment().teleportSent(uuid,
                    new WrapperPlayServerPlayerPositionAndLook(event).getTeleportId());
        } else if (event.getPacketType() == PacketType.Play.Server.PLAYER_ABILITIES) {
            var abilities = new WrapperPlayServerPlayerAbilities(event);
            plugin.environment().sentFlightAbilities(uuid, abilities.isFlightAllowed(),
                    abilities.isFlying(), abilities.getFlySpeed());
        }
    }
    private void velocityReply(PacketSendEvent event,UUID uuid,long now) {
        var check=plugin.velocityResponse();
        if(event.getUser().getClientVersion().isOlderThan(ClientVersion.V_1_17)
                ||plugin.isBedrockPlayer(uuid)||!plugin.enabled(uuid,check)||plugin.isExempt(uuid,check))return;
        var impulse=tracker.current(uuid,now);
        check.sent(uuid,impulse,plugin.environment().teleportGeneration(uuid));
        if(impulse!=null)event.getTasksAfterSend().add(()->check.afterSend(event.getUser(),impulse.sequence()));
    }

}
