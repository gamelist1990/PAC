package org.pexserver.pac.check.core;

import java.util.UUID;

/** Common identity and lifecycle for one detector. */
public interface CheckModule {
    String key();
    default boolean automaticBanEligible() { return true; }
    default boolean automaticKickEligible() { return true; }
    default void forget(UUID uuid) { }
}
