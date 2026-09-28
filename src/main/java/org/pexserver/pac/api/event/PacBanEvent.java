package org.pexserver.pac.api.event;

import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;
import org.pexserver.pac.api.BanInfo;

/** Fired synchronously after PAC stores a new ban and support ID. */
public final class PacBanEvent extends Event {
    private static final HandlerList HANDLERS = new HandlerList();
    private final BanInfo ban;

    public PacBanEvent(BanInfo ban) { this.ban = ban; }
    public BanInfo ban() { return ban; }
    public BanInfo getBan() { return ban; }

    @Override public HandlerList getHandlers() { return HANDLERS; }
    public static HandlerList getHandlerList() { return HANDLERS; }
}
