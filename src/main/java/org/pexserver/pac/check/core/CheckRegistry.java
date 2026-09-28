package org.pexserver.pac.check.core;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.concurrent.CopyOnWriteArrayList;

public final class CheckRegistry {
    @FunctionalInterface public interface Eligibility {
        boolean accepts(UUID uuid, CheckModule module);
    }

    private final Eligibility eligibility;
    private final Map<String, CheckModule> modules = new LinkedHashMap<>();
    private final List<PacketCheck> packetChecks = new CopyOnWriteArrayList<>();
    private final List<BedrockViolationCheck> bedrockViolationChecks = new CopyOnWriteArrayList<>();
    private volatile PacketCheck[] packetCheckSnapshot = new PacketCheck[0];
    private final MovementDispatchMetrics movementDispatchMetrics = new MovementDispatchMetrics();

    public CheckRegistry(Eligibility eligibility) { this.eligibility = Objects.requireNonNull(eligibility); }

    public void register(CheckModule module) {
        Objects.requireNonNull(module);
        String key = module.key().toLowerCase(Locale.ROOT);
        if (key.isBlank()) throw new IllegalArgumentException("Detector key is blank.");
        if (!(module instanceof PacketCheck || module instanceof BedrockViolationCheck || module instanceof EventCheck))
            throw new IllegalArgumentException("Detector has no input source: " + key);
        if (modules.putIfAbsent(key, module) != null)
            throw new IllegalArgumentException("Duplicate detector: " + key);
        if (module instanceof PacketCheck check) {
            packetChecks.add(check);
            packetCheckSnapshot = packetChecks.toArray(PacketCheck[]::new);
        }
        if (module instanceof BedrockViolationCheck check) bedrockViolationChecks.add(check);
    }

    public CheckModule get(String key) { return modules.get(key.toLowerCase(Locale.ROOT)); }
    public Collection<CheckModule> modules() { return java.util.List.copyOf(modules.values()); }
    public MovementDispatchMetrics movementDispatchMetrics() { return movementDispatchMetrics; }

    public void dispatch(PacketContext context) {
        long started = System.nanoTime();
        try {
            for (PacketCheck check : packetCheckSnapshot) {
                if (eligibility.accepts(context.uuid(), check)) check.inspect(context);
            }
        } finally {
            movementDispatchMetrics.record(System.nanoTime() - started);
        }
    }

    public void dispatch(BedrockViolationContext context) {
        for (BedrockViolationCheck check : bedrockViolationChecks) {
            if (eligibility.accepts(context.uuid(), check)) check.inspect(context);
        }
    }

    public void forget(UUID uuid) { modules.values().forEach(module -> module.forget(uuid)); }
}
