package org.pexserver.pac.check.shared;

import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.attribute.AttributeModifier;
import org.bukkit.inventory.EquipmentSlotGroup;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AttributeSwapGuardTest {
    @Test void sameMaterialCombatModifierChangesRefreshAttackCooldown() {
        Map<String, Set<AttributeModifier>> previous = Map.of("attack_speed", Set.of(modifier("old-speed", 1.0)));
        Map<String, Set<AttributeModifier>> next = Map.of("attack_speed", Set.of(modifier("new-speed", 2.0)));

        assertTrue(AttributeSwapGuard.attackAttributesChanged(
                Material.IRON_SWORD, previous, Material.IRON_SWORD, next));
    }

    @Test void sameMaterialAttackDamageModifierChangesRefreshAttackCooldown() {
        Map<String, Set<AttributeModifier>> previous = Map.of("attack_damage", Set.of(modifier("old-damage", 4.0)));
        Map<String, Set<AttributeModifier>> next = Map.of("attack_damage", Set.of(modifier("new-damage", 8.0)));

        assertTrue(AttributeSwapGuard.attackAttributesChanged(
                Material.IRON_SWORD, previous, Material.IRON_SWORD, next));
    }

    @Test void unrelatedSameMaterialModifiersDoNotRefreshAttackCooldown() {
        Map<String, Set<AttributeModifier>> previous = Map.of("movement_speed", Set.of(modifier("old-movement", 0.01)));
        Map<String, Set<AttributeModifier>> next = Map.of("movement_speed", Set.of(modifier("new-movement", 0.02)));

        assertFalse(AttributeSwapGuard.attackAttributesChanged(
                Material.IRON_SWORD, previous, Material.IRON_SWORD, next));
    }

    @Test void nonCombatItemSwitchDoesNotResetAttackCooldown() {
        assertFalse(AttributeSwapGuard.attackAttributesChanged(
                Material.DIRT, Map.of(), Material.COBBLESTONE, Map.of()));
    }

    @Test void emptyHotbarSlotIsTreatedAsAirForAttributeComparison() {
        assertEquals(Material.AIR, AttributeSwapGuard.typeOrAir(null));
        assertTrue(AttributeSwapGuard.attackAttributesChanged(
                AttributeSwapGuard.typeOrAir(null), Map.of(), Material.IRON_SWORD, Map.of()));
        assertTrue(AttributeSwapGuard.attackAttributesChanged(
                Material.IRON_SWORD, Map.of(), AttributeSwapGuard.typeOrAir(null), Map.of()));
    }

    @Test void differentVanillaWeaponTypesRefreshEvenWithoutCustomModifiers() {
        assertTrue(AttributeSwapGuard.attackAttributesChanged(
                Material.IRON_SWORD, Map.of(), Material.MACE, Map.of()));
    }

    private static AttributeModifier modifier(String key, double amount) {
        return new AttributeModifier(NamespacedKey.minecraft(key), amount,
                AttributeModifier.Operation.ADD_NUMBER, EquipmentSlotGroup.MAINHAND);
    }
}
