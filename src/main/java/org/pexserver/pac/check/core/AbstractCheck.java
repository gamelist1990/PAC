package org.pexserver.pac.check.core;

import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

public abstract class AbstractCheck implements CheckModule {
    private final ConcurrentHashMap<UUID, Long> lastFlag = new ConcurrentHashMap<>();

    protected final void flagLimited(PacketContext context, String detail) {
        flagLimited(context.uuid(), () -> context.flag(this, detail));
    }

    protected final void flagLimited(PacketContext context, Supplier<String> detail) {
        flagLimited(context.uuid(), () -> context.flag(this, detail.get()));
    }

    protected final void flagLimited(UUID uuid, Runnable report) {
        long now = System.nanoTime();
        Long previous = lastFlag.get(uuid);
        if (previous != null && now - previous <= 500_000_000L) return;
        boolean accepted = previous == null
                ? lastFlag.putIfAbsent(uuid, now) == null
                : lastFlag.replace(uuid, previous, now);
        if (accepted) report.run();
    }

    @Override public void forget(UUID uuid) { lastFlag.remove(uuid); }
}
