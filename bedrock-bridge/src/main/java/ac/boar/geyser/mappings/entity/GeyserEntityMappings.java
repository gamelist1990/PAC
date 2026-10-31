package ac.boar.geyser.mappings.entity;

import ac.boar.anticheat.Boar;
import ac.boar.anticheat.util.ReferencePopulator;
import ac.boar.mappings.entity.EntityDefinition;
import ac.boar.mappings.entity.EntityType;
import org.geysermc.geyser.registry.Registries;

import java.util.Locale;

public final class GeyserEntityMappings {

    public static void finalizeEntityMappings(ReferencePopulator<EntityType> refPopulator,  ReferencePopulator<EntityDefinition> defPopulator) {
        refPopulator.populate((key, populator) -> {
            try {
                String stripedIdentifier = key.contains(":") ? key.substring(key.indexOf(":") + 1) : key;
                org.geysermc.mcprotocollib.protocol.data.game.entity.type.EntityType type = org.geysermc.mcprotocollib.protocol.data.game.entity.type.EntityType.valueOf(stripedIdentifier.toUpperCase(Locale.ROOT));
                populator.populate(new GeyserEntityType(type));

                org.geysermc.geyser.entity.EntityTypeDefinition<?> entityDefinition = findDefinition(type);
                if (entityDefinition == null) {
                    // Not necessarily a problem if we have a type but not definition
                    Boar.debug("Could not find EntityDefinition with key: '" + key + "'.", Boar.DebugMessage.INFO);
                    return;
                }

                defPopulator.populate(key, new GeyserEntityDefinition(entityDefinition));
            } catch (IllegalArgumentException e) {
                Boar.debug("Could not find EntityType with key: '" + key + "'.", Boar.DebugMessage.WARNING);
            }
        });

        refPopulator.ensurePopulated(false);
        defPopulator.ensurePopulated(false);
    }

    private static org.geysermc.geyser.entity.EntityTypeDefinition<?> findDefinition(
            org.geysermc.mcprotocollib.protocol.data.game.entity.type.EntityType type) {
        var geyserType = org.geysermc.geyser.entity.GeyserEntityType.of(type);
        try {
            try {
                return (org.geysermc.geyser.entity.EntityTypeDefinition<?>) geyserType.getClass()
                        .getMethod("definition").invoke(geyserType);
            } catch (NoSuchMethodException ignored) {
                Object registry = Registries.class.getField("JAVA_ENTITY_TYPES").get(null);
                return (org.geysermc.geyser.entity.EntityTypeDefinition<?>) registry.getClass()
                        .getMethod("get", Object.class).invoke(registry, geyserType);
            }
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("Unable to resolve Geyser entity definitions", e);
        }
    }
}
