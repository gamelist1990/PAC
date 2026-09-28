package org.pexserver.pac.movement;

import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.*;

class BoundedStateCacheTest {
    @Test void unchangedBlockReusesGeometryButAChangedBlockIsResampled() {
        BoundedStateCache<String, String, Integer> cache = new BoundedStateCache<>(4);
        AtomicInteger calculations = new AtomicInteger();
        assertEquals(1, cache.get("block", "stone", calculations::incrementAndGet));
        assertEquals(1, cache.get("block", "stone", calculations::incrementAndGet));
        assertEquals(2, cache.get("block", "slab", calculations::incrementAndGet));
        assertEquals(2, cache.get("block", "slab", calculations::incrementAndGet));
        assertEquals(1, cache.size());
    }

    @Test void boundAndLeastRecentlyUsedEvictionSurviveStateReplacements() {
        BoundedStateCache<String, String, Integer> cache = new BoundedStateCache<>(2);
        AtomicInteger calculations = new AtomicInteger();
        Supplier<Integer> factory = calculations::incrementAndGet;
        cache.get("a", "stone", factory);
        cache.get("b", "stone", factory);
        cache.get("a", "slab", factory);
        cache.get("c", "stone", factory); // b is the oldest position.
        assertEquals(3, cache.get("a", "slab", factory));
        assertEquals(5, cache.get("b", "stone", factory));
        assertEquals(2, cache.size());
    }

    @Test void chunkAndBlockInvalidationsDoNotEvictUnrelatedWorlds() {
        BoundedStateCache<String, String, Integer> cache = new BoundedStateCache<>(8);
        AtomicInteger calculations = new AtomicInteger();
        Supplier<Integer> factory = calculations::incrementAndGet;
        cache.get("world-a/chunk-0", "state", factory);
        cache.get("world-a/chunk-1", "state", factory);
        cache.get("world-b/chunk-0", "state", factory);
        cache.invalidateIf(key -> key.startsWith("world-a/"));
        assertEquals(4, cache.get("world-a/chunk-1", "state", factory));
        assertEquals(3, cache.get("world-b/chunk-0", "state", factory));
        cache.invalidate("world-a/chunk-1");
        assertEquals(1, cache.size());
        assertEquals(5, cache.get("world-a/chunk-1", "state", factory));
    }

    @Test void failedSamplesNeitherGrowTheCacheNorDestroyPreviousValidGeometry() {
        BoundedStateCache<String, String, Integer> cache = new BoundedStateCache<>(1);
        cache.get("a", "stone", () -> 7);
        assertThrows(IllegalStateException.class, () -> cache.get("a", "slab", () -> {
            throw new IllegalStateException("sample failed");
        }));
        assertThrows(NullPointerException.class, () -> cache.get("b", "stone", () -> null));
        assertEquals(1, cache.size());
        assertEquals(7, cache.get("a", "stone", () -> fail("valid geometry lost")));
    }
}
