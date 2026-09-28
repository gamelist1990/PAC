package org.pexserver.pac.check.core;

import org.pexserver.pac.PacPlugin;

import java.util.UUID;

/** One violation sent by the Geyser prediction engine. */
public record BedrockViolationContext(PacPlugin plugin, UUID uuid,
                                      String check, int level, String detail) {
    public void flag(CheckModule module) {
        plugin.flag(uuid, module, check + " engine-level=" + level + ": " + detail);
    }
}
