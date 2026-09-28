package org.pexserver.pac.check.core;

import com.github.retrooper.packetevents.event.PacketReceiveEvent;
import com.github.retrooper.packetevents.protocol.world.Location;
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientPlayerFlying;
import org.pexserver.pac.PacPlugin;
import org.pexserver.pac.packet.JavaInputCapture;
import org.pexserver.pac.packet.ExternalMotionTracker;

import java.util.UUID;

/** One decoded movement packet. Detector implementations must not access Bukkit world data here. */
public record PacketContext(PacPlugin plugin, UUID uuid, PacketReceiveEvent event,
                           WrapperPlayClientPlayerFlying flying, Location location,
                           JavaInputCapture.Window inputs,
                           ExternalMotionTracker.Impulse externalImpulse,
                           long movementEpoch, boolean timingUncertain,
                           org.pexserver.pac.movement.ServerTickTiming.Snapshot serverTiming) {
    public PacketContext(PacPlugin plugin, UUID uuid, PacketReceiveEvent event,
                         WrapperPlayClientPlayerFlying flying, Location location,
                         JavaInputCapture.Window inputs, ExternalMotionTracker.Impulse externalImpulse,
                         long movementEpoch, boolean timingUncertain) {
        this(plugin, uuid, event, flying, location, inputs, externalImpulse, movementEpoch,
                timingUncertain, org.pexserver.pac.movement.ServerTickTiming.Snapshot.NORMAL);
    }
    public PacketContext(PacPlugin plugin, UUID uuid, PacketReceiveEvent event,
                         WrapperPlayClientPlayerFlying flying, Location location,
                         JavaInputCapture.Window inputs, ExternalMotionTracker.Impulse externalImpulse,
                         long movementEpoch) {
        this(plugin, uuid, event, flying, location, inputs, externalImpulse, movementEpoch, false);
    }
    public PacketContext(PacPlugin plugin, UUID uuid, PacketReceiveEvent event,
                         WrapperPlayClientPlayerFlying flying, Location location) {
        this(plugin, uuid, event, flying, location, null, null, -1);
    }
    public PacketContext(PacPlugin plugin, UUID uuid, PacketReceiveEvent event,
                         WrapperPlayClientPlayerFlying flying, Location location,
                         JavaInputCapture.Window inputs) {
        this(plugin, uuid, event, flying, location, inputs, null, -1);
    }
    public PacketContext(PacPlugin plugin, UUID uuid, PacketReceiveEvent event,
                         WrapperPlayClientPlayerFlying flying, Location location,
                         JavaInputCapture.Window inputs,
                         ExternalMotionTracker.Impulse externalImpulse) {
        this(plugin, uuid, event, flying, location, inputs, externalImpulse, -1);
    }
    public void flag(CheckModule module, String detail) { plugin.flag(uuid, module, detail); }
    public void cancel(CheckModule module) { if (plugin.cancel(module, uuid)) event.setCancelled(true); }
}
