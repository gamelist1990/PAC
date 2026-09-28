package ac.boar.anticheat.compensated.cache.entity;

import ac.boar.anticheat.compensated.cache.entity.state.CachedEntityState;
import ac.boar.anticheat.data.EntityDimensions;
import ac.boar.anticheat.player.BoarPlayer;
import ac.boar.anticheat.util.math.Vec3;
import ac.boar.anticheat.util.reach.PositionInterpolator;
import ac.boar.mappings.entity.Entity;
import ac.boar.mappings.entity.EntityDefinition;
import ac.boar.mappings.entity.EntityType;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.Setter;
import lombok.ToString;
import org.cloudburstmc.protocol.bedrock.data.entity.EntityDataMap;
import org.cloudburstmc.protocol.bedrock.data.entity.EntityDataTypes;

@ToString
@RequiredArgsConstructor
@Getter
@Setter
public final class EntityCache {
    private final BoarPlayer player;
    private final EntityType type;
    private final EntityDefinition definition;
    private final long runtimeId;

    private EntityDimensions dimensions;
    private Vec3 serverPosition = Vec3.ZERO;
    private boolean inVehicle;

    private EntityDataMap metadata = new EntityDataMap();

    public void setMetadata(EntityDataMap metadata) {
        if (metadata != null) {
            this.metadata.putAll(metadata);
        }

        this.refreshDimensions();
    }

    /**
     * Rebuilds the collision dimensions from Geyser's live entity state.
     * Packet metadata is retained and applied as an override because metadata
     * updates may contain only one changed field.
     */
    public void refreshDimensions() {
        Entity liveEntity = this.player.getEntityAccessor().entityByRuntimeId(this.runtimeId);

        float width = liveEntity != null && liveEntity.bbWidth() > 0
                ? liveEntity.bbWidth()
                : this.definition.width();
        float height = liveEntity != null && liveEntity.bbHeight() > 0
                ? liveEntity.bbHeight()
                : this.definition.height();

        if (this.metadata.containsKey(EntityDataTypes.WIDTH)) {
            Float metadataWidth = this.metadata.get(EntityDataTypes.WIDTH);
            if (metadataWidth != null && metadataWidth > 0) {
                width = metadataWidth;
            }
        }

        if (this.metadata.containsKey(EntityDataTypes.HEIGHT)) {
            Float metadataHeight = this.metadata.get(EntityDataTypes.HEIGHT);
            if (metadataHeight != null && metadataHeight > 0) {
                height = metadataHeight;
            }
        }

        float scale = 1.0F;
        if (this.metadata.containsKey(EntityDataTypes.SCALE)) {
            Float metadataScale = this.metadata.get(EntityDataTypes.SCALE);
            if (metadataScale != null && metadataScale > 0) {
                scale = metadataScale;
            }
        }

        this.dimensions = EntityDimensions.fixed(width * scale, height * scale);
    }

    public boolean isBoatFamily() {
        return isBoatFamily(this.definition.identifier());
    }

    public static boolean isBoatFamily(String identifier) {
        if (identifier == null) {
            return false;
        }

        String normalized = identifier.toLowerCase(java.util.Locale.ROOT);
        return normalized.equals("minecraft:boat")
                || normalized.equals("minecraft:chest_boat")
                || normalized.endsWith("_boat")
                || normalized.endsWith("_chest_boat")
                || normalized.endsWith("_raft")
                || normalized.endsWith("_chest_raft");
    }

    private CachedEntityState current;

    public boolean affectedByOffset;
    public float getYOffset() {
        if (this.affectedByOffset) {
            return this.definition.offset();
        }

        return 0;
    }

    public void init() {
        this.current = new CachedEntityState(this.player, this);
    }

    public void interpolate(Vec3 pos, boolean lerp) {
        if (!lerp) {
            this.current.setTeleportPos(pos);
        } else {
            final PositionInterpolator lv = this.current.getInterpolator();
            if (lv != null) {
                lv.refreshPositionAndAngles(pos);
            }
        }
    }
}
