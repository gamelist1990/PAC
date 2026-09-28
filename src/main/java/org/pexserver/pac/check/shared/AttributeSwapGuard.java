package org.pexserver.pac.check.shared;

import com.google.common.collect.Multimap;
import org.bukkit.Material;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeModifier;
import org.bukkit.craftbukkit.entity.CraftPlayer;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerItemHeldEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.pexserver.pac.PacPlugin;

import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/** Resets charged attack strength when a hotbar swap changes combat attributes. */
public final class AttributeSwapGuard implements Listener {
    private static final Set<String> COMBAT_ATTRIBUTE_KEYS = Set.of(
            "attack_damage", "attack_speed", "attack_knockback", "sweeping_damage_ratio",
            "entity_interaction_range");

    private final PacPlugin plugin;

    public AttributeSwapGuard(PacPlugin plugin) {
        this.plugin = plugin;
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onHeldItemChange(PlayerItemHeldEvent event) {
        Player player = event.getPlayer();
        if (plugin.isBedrockPlayer(player.getUniqueId())
                || !(player instanceof CraftPlayer craftPlayer)) return;

        ItemStack previous = player.getInventory().getItem(event.getPreviousSlot());
        ItemStack next = player.getInventory().getItem(event.getNewSlot());
        if (!attackAttributesChanged(previous, next)) return;

        // Wurst's AttributeSwap sends a held-slot change immediately before an
        // attack, then can switch back on a later client tick. Clearing the
        // server attack-strength ticker on every effective combat-attribute
        // change means the attack packet cannot reuse charge earned with the
        // previous item. This also covers custom attributes on two items of the
        // same material. Geyser players use their separate Bedrock combat path.
        craftPlayer.getHandle().resetAttackStrengthTicker();
    }

    static boolean attackAttributesChanged(ItemStack previous, ItemStack next) {
        return attackAttributesChanged(typeOrAir(previous), mainHandModifiers(previous),
                typeOrAir(next), mainHandModifiers(next));
    }

    static Material typeOrAir(ItemStack stack) {
        return stack == null ? Material.AIR : stack.getType();
    }

    static boolean attackAttributesChanged(Material previousType,
                                           Map<String, ? extends Collection<AttributeModifier>> previous,
                                           Material nextType,
                                           Map<String, ? extends Collection<AttributeModifier>> next) {
        return !combatModifiers(previous).equals(combatModifiers(next))
                || (previousType != nextType
                && (hasDefaultCombatAttributes(previousType) || hasDefaultCombatAttributes(nextType)));
    }

    private static boolean hasDefaultCombatAttributes(Material material) {
        String name = material.name();
        return name.endsWith("_SWORD") || name.endsWith("_AXE") || name.endsWith("_PICKAXE")
                || name.endsWith("_SHOVEL") || name.endsWith("_HOE") || name.endsWith("_SPEAR")
                || name.equals("MACE") || name.equals("SPEAR") || name.equals("TRIDENT");
    }

    private static Map<String, Collection<AttributeModifier>> mainHandModifiers(ItemStack stack) {
        if (stack == null) return Map.of();
        var meta = stack.getItemMeta();
        if (meta == null) return Map.of();
        Multimap<Attribute, AttributeModifier> modifiers = meta.getAttributeModifiers(EquipmentSlot.HAND);
        if (modifiers == null || modifiers.isEmpty()) return Map.of();
        Map<String, Collection<AttributeModifier>> result = new HashMap<>();
        for (Map.Entry<Attribute, AttributeModifier> entry : modifiers.entries()) {
            String key = entry.getKey().getKey().getKey();
            result.computeIfAbsent(key, ignored -> new HashSet<>()).add(entry.getValue());
        }
        return result;
    }

    private static Map<String, Set<AttributeModifier>> combatModifiers(
            Map<String, ? extends Collection<AttributeModifier>> modifiers) {
        if (modifiers.isEmpty()) return Map.of();
        Map<String, Set<AttributeModifier>> result = new HashMap<>();
        for (Map.Entry<String, ? extends Collection<AttributeModifier>> entry : modifiers.entrySet()) {
            if (!COMBAT_ATTRIBUTE_KEYS.contains(entry.getKey()) || entry.getValue().isEmpty()) continue;
            result.put(entry.getKey(), Set.copyOf(entry.getValue()));
        }
        return result.isEmpty() ? Map.of() : Map.copyOf(result);
    }
}
