package org.pexserver.pac.platform;

import net.minecraft.server.MinecraftServer;

/** Server-authoritative tick source; accessed only on the primary server thread. */
public final class NmsClock {
    public int tick() {
        return MinecraftServer.getServer().getTickCount();
    }
}
