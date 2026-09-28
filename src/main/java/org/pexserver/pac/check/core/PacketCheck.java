package org.pexserver.pac.check.core;

/** Detector for PacketEvents Java protocol packets (including Geyser translation). */
public interface PacketCheck extends CheckModule {
    default boolean supportsBedrock() { return true; }
    void inspect(PacketContext context);
}
