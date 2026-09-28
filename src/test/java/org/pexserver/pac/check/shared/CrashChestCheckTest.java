package org.pexserver.pac.check.shared;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CrashChestCheckTest {
    @Test void rejectsWurstSizedNestedList() {
        CompoundTag root = new CompoundTag();
        ListTag payload = new ListTag();
        for (int i = 0; i < 40_000; i++) payload.add(new ListTag());
        root.put("www.wurstclient.net", payload);
        assertTrue(CrashChestCheck.tooComplex(root));
    }

    @Test void allowsOrdinarySmallCustomData() {
        CompoundTag root = new CompoundTag();
        root.putString("owner", "builder");
        ListTag payload = new ListTag();
        payload.add(new CompoundTag());
        root.put("items", payload);
        assertFalse(CrashChestCheck.tooComplex(root));
    }
}
