package org.pexserver.pac.api;

import java.util.UUID;

/** Read-only snapshot of an active PAC ban. */
public record BanInfo(UUID playerId, String playerName, String reason, long createdAt,
                      long expiresAt, boolean permanent, int stage, String supportId) { }
