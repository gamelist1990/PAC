package org.pexserver.pac.check.java.action;

import com.github.retrooper.packetevents.event.PacketReceiveEvent;
import com.github.retrooper.packetevents.protocol.player.DiggingAction;
import com.github.retrooper.packetevents.util.Vector3i;
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientPlayerDigging;
import org.pexserver.pac.PacPlugin;
import org.pexserver.pac.check.core.AbstractCheck;
import org.pexserver.pac.check.core.PacketCheck;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** Stops high-rate and broad-area mining packet patterns before they reach game mode handling. */
public final class NukerCheck extends AbstractCheck implements PacketCheck {
    private final PacPlugin plugin;
    private final Map<UUID, NukerPacketWindow> windows = new ConcurrentHashMap<>();

    public NukerCheck(PacPlugin plugin) { this.plugin = plugin; }
    @Override public String key() { return "nuker"; }
    @Override public boolean supportsBedrock() { return false; }
    @Override public void inspect(org.pexserver.pac.check.core.PacketContext context) { }

    /** Called only for PLAYER_DIGGING packets by the packet adapter. */
    public void onDigging(PacketReceiveEvent event) {
        if (event.isCancelled()) return;
        UUID uuid = event.getUser().getUUID();
        if (uuid == null || plugin.isBedrockPlayer(uuid) || plugin.isExempt(uuid, this)
                || !plugin.enabled(uuid, this)) return;

        WrapperPlayClientPlayerDigging packet = new WrapperPlayClientPlayerDigging(event);
        DiggingAction action = packet.getAction();
        if (action != DiggingAction.START_DIGGING && action != DiggingAction.FINISHED_DIGGING
                && action != DiggingAction.CANCELLED_DIGGING) return;

        Vector3i position = packet.getBlockPosition();
        boolean trackTarget = action == DiggingAction.START_DIGGING;
        var snapshot = windows.computeIfAbsent(uuid, ignored -> new NukerPacketWindow())
                .record(System.nanoTime(), trackTarget ? position.getX() : null,
                        trackTarget ? position.getY() : null, trackTarget ? position.getZ() : null);
        int maxPackets = plugin.maxNukerPacketsPerSecond();
        int maxTargets = plugin.maxNukerTargetsPerSecond();
        if (!NukerPacketWindow.suspicious(snapshot, maxPackets, maxTargets)) return;

        if (plugin.cancel(this, uuid)) event.setCancelled(true);
        String detail = "mining packet burst: " + snapshot.packets() + " dig actions/s, "
                + snapshot.uniqueTargets() + " distinct start targets/s; limit="
                + maxPackets + " packets/s or " + maxTargets + " targets/s";
        flagLimited(uuid, () -> plugin.flag(uuid, this, detail, Map.of(
                "dig_packets_per_second", (double) snapshot.packets(),
                "distinct_dig_targets_per_second", (double) snapshot.uniqueTargets(),
                "dig_packet_limit", (double) maxPackets,
                "distinct_target_limit", (double) maxTargets)));
    }

    @Override public void forget(UUID uuid) {
        super.forget(uuid);
        windows.remove(uuid);
    }
}
