package org.pexserver.pac.check.shared;

import com.github.retrooper.packetevents.event.PacketSendEvent;
import com.github.retrooper.packetevents.protocol.packettype.PacketType;
import com.github.retrooper.packetevents.protocol.player.User;
import com.github.retrooper.packetevents.protocol.player.ClientVersion;
import com.github.retrooper.packetevents.protocol.teleport.RelativeFlag;
import com.github.retrooper.packetevents.protocol.vector.positionpath.SteppedPositionPath;
import com.github.retrooper.packetevents.wrapper.play.server.*;
import java.security.SecureRandom;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** Packet-thread only: never samples Bukkit world/entity objects. */
public final class CombatSceneTracker {
    private final Map<UUID, CombatSceneWindow> scenes = new ConcurrentHashMap<>();
    private record Identity(UUID uuid, long at) { }
    private final Map<Integer, Identity> identities = new ConcurrentHashMap<>();
    /** Identity only; network coordinates are never initialized from Bukkit positions. */
    void sampleEntity(int id, UUID uuid, long now) {
        identities.put(id, new Identity(uuid, now));
        if (identities.size() > 4096) identities.entrySet().removeIf(e -> now - e.getValue().at > 10_000);
    }
    private final SecureRandom random = new SecureRandom();
    public CombatSceneWindow.View view(UUID observer, int entityId, UUID target, long now) {
        var scene = scenes.get(observer);
        return scene == null ? CombatSceneWindow.View.unknown() : scene.view(entityId, target, now);
    }
    public record Frozen(UUID target, CombatSceneWindow.View view) { }
    public Frozen capture(UUID observer, int entityId, long now) {
        var scene = scenes.get(observer);
        if (scene == null) return new Frozen(null, CombatSceneWindow.View.unknown());
        synchronized (scene) {
            UUID target = scene.targetUuid(entityId);
            return new Frozen(target, scene.view(entityId, target, now));
        }
    }
    // ATTACK identifies the entity id; UUID is verified later on the main thread.
    public CombatSceneWindow scene(UUID observer) { return scenes.get(observer); }
    public void movement(UUID uuid) {
        var scene = scenes.get(uuid); if (scene != null) scene.movement();
    }
    public boolean acknowledge(UUID observer, int id) {
        var scene = scenes.get(observer);
        return scene != null && scene.acknowledge(id);
    }
    public void barrier(User user) {
        var scene = scenes.get(user.getUUID());
        if (scene == null || user.getClientVersion().isOlderThan(ClientVersion.V_1_17)) return;
        synchronized (scene) {
            long now = System.currentTimeMillis();
            if (!scene.barrierDue(now)) return;
            int id = random.nextInt();
            scene.barrier(id, now);
            user.sendPacket(new WrapperPlayServerPing(id));
        }
    }
    public void outgoing(PacketSendEvent event) {
        if (event.isCancelled() || event.getUser().getUUID() == null) return;
        var type = event.getPacketType();
        UUID uuid = event.getUser().getUUID();
        if (type == PacketType.Play.Server.RESPAWN) { forget(uuid); return; }
        var scene = scenes.computeIfAbsent(uuid, ignored -> new CombatSceneWindow());
        long now = System.currentTimeMillis();
        boolean changed = true;
        if (type == PacketType.Play.Server.SPAWN_ENTITY) {
            var p = new WrapperPlayServerSpawnEntity(event); var v = p.getPosition();
            if (!p.getEntityType().isInstanceOf(com.github.retrooper.packetevents.protocol.entity.type.EntityTypes.LIVINGENTITY)) return;
            scene.spawn(p.getEntityId(), p.getUUID().orElse(null), new CombatSceneWindow.Position(v.x, v.y, v.z), now);
        } else if (type == PacketType.Play.Server.SPAWN_PLAYER) {
            var p = new WrapperPlayServerSpawnPlayer(event); var v = p.getPosition();
            scene.spawn(p.getEntityId(), p.getUUID(), new CombatSceneWindow.Position(v.x, v.y, v.z), now);
        } else if (type == PacketType.Play.Server.ENTITY_RELATIVE_MOVE) {
            var p = new WrapperPlayServerEntityRelativeMove(event);
            var old = scene.position(p.getEntityId());
            if (old != null) {
                var path = p.getDelta().applyAsPath(new com.github.retrooper.packetevents.util.Vector3d(old.x(), old.y(), old.z()));
                path(scene, p.getEntityId(), path, now);
            }
        } else if (type == PacketType.Play.Server.ENTITY_RELATIVE_MOVE_AND_ROTATION) {
            var p = new WrapperPlayServerEntityRelativeMoveAndRotation(event);
            var old = scene.position(p.getEntityId());
            if (old != null) path(scene, p.getEntityId(), p.getDelta().applyAsPath(
                    new com.github.retrooper.packetevents.util.Vector3d(old.x(), old.y(), old.z())), now);
        } else if (type == PacketType.Play.Server.ENTITY_POSITION_SYNC) {
            var p = new WrapperPlayServerEntityPositionSync(event);
            if (scene.position(p.getId()) == null) {
                Identity identity = identities.get(p.getId());
                if (identity != null && now - identity.at < 10_000) {
                    var v = p.getPosition().getEndPosition();
                    scene.spawn(p.getId(), identity.uuid, new CombatSceneWindow.Position(v.x, v.y, v.z), now);
                }
            } else path(scene, p.getId(), p.getPosition(), now);
        } else if (type == PacketType.Play.Server.ENTITY_TELEPORT) {
            var p = new WrapperPlayServerEntityTeleport(event); var v = p.getPosition();
            var old = scene.position(p.getEntityId());
            if (old == null) {
                Identity identity = identities.get(p.getEntityId());
                if (identity != null && now - identity.at < 10_000 && p.getRelativeFlags().getFullMask() == 0)
                    scene.spawn(p.getEntityId(), identity.uuid, new CombatSceneWindow.Position(v.x, v.y, v.z), now);
            } else {
                var flags = p.getRelativeFlags();
                scene.move(p.getEntityId(), new CombatSceneWindow.Position(
                        v.x + (flags.has(RelativeFlag.X) ? old.x() : 0),
                        v.y + (flags.has(RelativeFlag.Y) ? old.y() : 0),
                        v.z + (flags.has(RelativeFlag.Z) ? old.z() : 0)), now, true);
            }
        } else if (type == PacketType.Play.Server.DESTROY_ENTITIES) {
            for (int id : new WrapperPlayServerDestroyEntities(event).getEntityIds()) scene.destroy(id);
        } else changed = false;
        if (changed) event.getTasksAfterSend().add(() -> barrier(event.getUser()));
    }
    private static void path(CombatSceneWindow scene, int id,
            com.github.retrooper.packetevents.protocol.vector.positionpath.PositionPath path, long now) {
        if (path instanceof SteppedPositionPath stepped) {
            for (var step : stepped.getSteps())
                scene.move(id, new CombatSceneWindow.Position(step.x(), step.y(), step.z()), now + Math.max(0L, step.tickOffset()) * 50L, false);
        } else {
            var v = path.getEndPosition();
            scene.move(id, new CombatSceneWindow.Position(v.x, v.y, v.z), now, false);
        }
    }
    public void forget(UUID uuid) { scenes.remove(uuid); }
}
