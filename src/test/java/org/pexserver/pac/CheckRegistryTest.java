package org.pexserver.pac;

import org.junit.jupiter.api.Test;
import org.pexserver.pac.check.core.CheckModule;
import org.pexserver.pac.check.core.CheckRegistry;
import org.pexserver.pac.check.core.PacketCheck;
import org.pexserver.pac.check.core.PacketContext;
import org.pexserver.pac.check.core.BedrockViolationCheck;
import org.pexserver.pac.check.core.BedrockViolationContext;

import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

class CheckRegistryTest {
    @Test void modulesAreFoundByKeyAndDuplicatesAreRejected() {
        CheckRegistry registry = new CheckRegistry((uuid, module) -> true);
        CheckModule module = new PacketCheck() {
            @Override public String key() { return "example"; }
            @Override public void inspect(PacketContext context) { }
        };
        registry.register(module);
        assertSame(module, registry.get("EXAMPLE"));
        assertEquals(1, registry.modules().size());
        assertThrows(IllegalArgumentException.class, () -> registry.register(module));
        assertThrows(IllegalArgumentException.class, () -> registry.register(() -> "no-source"));
    }

    @Test void eachInputTypeReachesOnlyItsOwnDetectors() {
        CheckRegistry registry = new CheckRegistry((uuid, module) -> true);
        AtomicInteger packets = new AtomicInteger();
        AtomicInteger violations = new AtomicInteger();
        registry.register(new PacketCheck() {
            @Override public String key() { return "packet"; }
            @Override public void inspect(PacketContext context) { packets.incrementAndGet(); }
        });
        registry.register(new BedrockViolationCheck() {
            @Override public String key() { return "violation"; }
            @Override public void inspect(BedrockViolationContext context) { violations.incrementAndGet(); }
        });
        UUID uuid = UUID.randomUUID();
        registry.dispatch(new PacketContext(null, uuid, null, null, null));
        assertEquals(1, packets.get());
        assertEquals(0, violations.get());
        registry.dispatch(new BedrockViolationContext(null, uuid, "Speed", 1, "offset"));
        assertEquals(1, packets.get());
        assertEquals(1, violations.get());
    }
}
