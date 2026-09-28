package ac.boar.anticheat.packets.other;

import ac.boar.anticheat.ack.types.VehicleClearAck;
import ac.boar.anticheat.ack.types.VehicleSetAck;
import ac.boar.anticheat.compensated.cache.entity.EntityCache;
import ac.boar.anticheat.player.BoarPlayer;
import ac.boar.protocol.api.CloudburstPacketEvent;
import ac.boar.protocol.api.PacketListener;
import org.cloudburstmc.protocol.bedrock.data.entity.EntityLinkData;
import org.cloudburstmc.protocol.bedrock.packet.InteractPacket;
import org.cloudburstmc.protocol.bedrock.packet.SetEntityLinkPacket;

public class VehiclePackets implements PacketListener {
    @Override
    public void onPacketReceived(CloudburstPacketEvent event) {
        final BoarPlayer player = event.getPlayer();

        if (event.getPacket() instanceof InteractPacket packet) {
            if (packet.getRuntimeEntityId() == player.runtimeEntityId
                    && packet.getAction() == InteractPacket.Action.LEAVE_VEHICLE) {
                player.nextVehicleLinkGeneration();
                player.vehicleData = null;
                player.setBoundingBox(player.position);
                player.markVehicleTransition();
                return;
            }

            EntityCache target = player.compensatedWorld.getEntity(packet.getRuntimeEntityId());
            if (target != null && target.isBoatFamily()) {
                player.markVehicleTransition();
            }
        }
    }

    @Override
    public void onPacketSend(CloudburstPacketEvent event) {
        final BoarPlayer player = event.getPlayer();
        if (event.getPacket() instanceof SetEntityLinkPacket packet) {
            final EntityLinkData link = packet.getEntityLink();
            if (link == null) {
                return;
            }

            long entityId = packet.getEntityLink().getFrom();
            long riderId = packet.getEntityLink().getTo();

            // We handle this separately.
            if (riderId != player.runtimeEntityId) {
                final EntityCache riderCache = player.compensatedWorld.getEntity(riderId);
                if (riderCache != null) {
                    riderCache.setInVehicle(link.getType() != EntityLinkData.Type.REMOVE);
                }

                return;
            }

            // The boat may leave the compensated cache before its REMOVE link
            // arrives. Dismount must still clear prediction and old rewinds.
            if (link.getType() != EntityLinkData.Type.REMOVE
                    && player.compensatedWorld.getEntity(entityId) == null) {
                return;
            }
            player.markVehicleTransition();
            player.getTeleportUtil().getQueuedTeleports().clear();
            long generation = player.nextVehicleLinkGeneration();

            if (link.getType() == EntityLinkData.Type.REMOVE) {
                // Clear immediately as well as through the acknowledgment.
                // Waiting only for the latency acknowledgment can leave the
                // player logically mounted and block normal post-dismount input.
                player.vehicleData = null;
                player.setBoundingBox(player.position);
                player.queueAcknowledgment(new VehicleClearAck(generation));
                return;
            }

            player.queueAcknowledgment(new VehicleSetAck(entityId, generation));
        }
    }
}
