package org.pexserver.pac.bridge;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class PacPaperBridgePolicyTest {
    private final UUID uuid = UUID.randomUUID();

    @BeforeEach @AfterEach void clearBridge() throws Exception {
        for (Field field : PacPaperBridge.class.getDeclaredFields()) {
            if (Modifier.isStatic(field.getModifiers())) {
                field.setAccessible(true);
                field.set(null, null);
            }
        }
    }

    @Test void cachedMethodReadsLivePolicyOnEveryAction() throws Exception {
        FakePlugin plugin = new FakePlugin();
        inject(plugin);
        assertTrue(PacPaperBridge.isRollbackEnabled(uuid));
        plugin.rollback = false;
        assertFalse(PacPaperBridge.isRollbackEnabled(uuid));
        plugin.rollback = true;
        assertTrue(PacPaperBridge.isRollbackEnabled(uuid));
        plugin.recording = true;
        assertFalse(PacPaperBridge.isRollbackEnabled(uuid));
    }

    @Test void missingOrBrokenPolicyNeverForcesRollback() throws Exception {
        inject(new Object() { public boolean isEnabled() { return true; } });
        assertFalse(PacPaperBridge.isRollbackEnabled(uuid));
        assertFalse(PacPaperBridge.isRollbackEnabled((UUID) null));
    }

    private void inject(Object plugin) throws Exception {
        Field field = PacPaperBridge.class.getDeclaredField("paperPlugin");
        field.setAccessible(true);
        field.set(null, plugin);
    }

    public static final class FakePlugin {
        boolean rollback = true, recording;
        public boolean isEnabled() { return true; }
        public boolean bedrockRollbackEnabled(UUID uuid) { return rollback && !recording; }
    }
}
