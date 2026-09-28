package org.pexserver.pac.api;

import java.util.UUID;

/** Persistent support lookup record; records remain available after unban or expiry. */
public record SupportCaseInfo(String supportId, UUID playerId, String playerName,
                              String staffReason, long createdAt, long expiresAt,
                              boolean permanent, int stage, boolean active) { }
