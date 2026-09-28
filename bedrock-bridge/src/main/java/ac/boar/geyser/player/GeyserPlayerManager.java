package ac.boar.geyser.player;

import ac.boar.anticheat.ack.BatchAcknowledgmentTransport;
import ac.boar.anticheat.ack.BoarAcknowledgmentTransport;
import ac.boar.anticheat.data.vanilla.AttributeInstance;
import ac.boar.anticheat.player.BoarPlayer;
import ac.boar.anticheat.player.BoarPlayerManager;
import ac.boar.anticheat.player.accessor.EntityAccessor;
import ac.boar.anticheat.player.accessor.InventoryAccessor;
import ac.boar.anticheat.player.accessor.WorldAccessor;
import ac.boar.anticheat.player.data.BlockMappingInfo;
import ac.boar.api.anticheat.model.NetworkSession;
import ac.boar.protocol.BoarConnection;
import ac.boar.protocol.CloudburstConnection;
import ac.boar.geyser.anticheat.player.accessor.GeyserEntityAccessor;
import ac.boar.geyser.anticheat.player.accessor.GeyserInventoryAccessor;
import ac.boar.geyser.anticheat.player.accessor.GeyserWorldAccessor;
import ac.boar.geyser.mappings.entity.GeyserEntity;
import ac.boar.geyser.model.GeyserNetworkSession;
import ac.boar.mappings.entity.Entity;
import it.unimi.dsi.fastutil.ints.Int2IntMap;
import it.unimi.dsi.fastutil.ints.Int2IntOpenHashMap;
import it.unimi.dsi.fastutil.ints.IntArrayList;
import it.unimi.dsi.fastutil.ints.IntList;
import org.cloudburstmc.protocol.bedrock.data.definitions.BlockDefinition;
import org.geysermc.geyser.entity.attribute.GeyserAttributeType;
import org.geysermc.geyser.level.block.Blocks;
import org.geysermc.geyser.registry.type.BlockMappings;
import org.geysermc.geyser.session.GeyserSession;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

public class GeyserPlayerManager extends BoarPlayerManager<GeyserSession> {

    @Override
    protected NetworkSession createNetworkSession(GeyserSession session) {
        return new GeyserNetworkSession(session);
    }

    @Override
    protected BoarConnection getConnection(GeyserSession connection) {
        return new CloudburstConnection(connection.getUpstream().getSession());
    }

    @Override
    protected Entity getPlayerEntity(GeyserSession session) {
        return new GeyserEntity(session.getPlayerEntity());
    }

    @Override
    protected BlockMappingInfo getMappingInfo(GeyserSession session) {
        BlockMappings mappings = session.getBlockMappings();
        BlockDefinition bedrockAir = mappings.getBedrockAir();
        BlockDefinition bedrockWater = mappings.getBedrockWater();
        BlockDefinition bedrockLava = mappings.getBedrockBlock(Blocks.LAVA.defaultBlockState());
        BlockDefinition bedrockPowderSnow = mappings.getBedrockBlock(Blocks.POWDER_SNOW.defaultBlockState());

        IntList airIds = new IntArrayList(3);
        airIds.add(bedrockAir.getRuntimeId());
        airIds.add(mappings.getBedrockBlockId(Blocks.CAVE_AIR.defaultBlockState().javaId()));
        airIds.add(mappings.getBedrockBlockId(Blocks.VOID_AIR.defaultBlockState().javaId()));

        Int2IntMap bedrockBlockToJava = new Int2IntOpenHashMap(mappings.getJavaToBedrockBlocks().length);
        bedrockBlockToJava.defaultReturnValue(0);

        for (int i = 0; i < mappings.getJavaToBedrockBlocks().length; i++) {
            bedrockBlockToJava.put(mappings.getJavaToBedrockBlocks()[i].getRuntimeId(), i);
        }

        return new BlockMappingInfo(bedrockAir, bedrockWater, bedrockLava,
                bedrockPowderSnow, airIds, mappings::isItemFrame,
                bedrockBlockToJava::get, mappings::getBedrockBlockId
        );
    }

    @Override
    protected WorldAccessor createWorldAccessor(GeyserSession session) {
        return new GeyserWorldAccessor(session);
    }

    @Override
    protected EntityAccessor createEntityAccessor(GeyserSession session) {
        return new GeyserEntityAccessor(session);
    }

    @Override
    protected InventoryAccessor createInventoryAccessor(GeyserSession session) {
        return new GeyserInventoryAccessor(session);
    }

    @Override
    protected Map<String, AttributeInstance> createDefaultAttributes() {
        Map<String, AttributeInstance> attributes = new HashMap<>();
        for (GeyserAttributeType type : GeyserAttributeType.values()) {
            final String identifier = type.getBedrockIdentifier();
            if (identifier == null || attributes.containsKey(type.getBedrockIdentifier())) {
                continue;
            }

            attributes.put(identifier, new AttributeInstance(type.getDefaultValue()));
        }

        return attributes;
    }

    @Override
    protected BoarAcknowledgmentTransport createAckTransport(BoarPlayer player) {
        return new BatchAcknowledgmentTransport(player);
    }

    @Override
    protected ScheduledFuture<?> beginTicking(GeyserSession session, Runnable ticker) {
        return session.getTickEventLoop().scheduleAtFixedRate(ticker, 50, 50, TimeUnit.MILLISECONDS);
    }
}
