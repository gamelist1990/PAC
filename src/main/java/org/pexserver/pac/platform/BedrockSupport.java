package org.pexserver.pac.platform;

import org.bukkit.Bukkit;
import org.geysermc.floodgate.api.FloodgateApi;
import org.geysermc.geyser.api.GeyserApi;
import org.geysermc.geyser.api.extension.Extension;

import java.util.UUID;

public final class BedrockSupport {
    public boolean geyserAvailable() {
        if (!Bukkit.getPluginManager().isPluginEnabled("Geyser-Spigot")) return false;
        try {
            return GeyserApi.api() != null;
        } catch (IllegalStateException | LinkageError ignored) { return false; }
    }
    public boolean engineAvailable() {
        if (!geyserAvailable()) return false;
        try {
            Extension extension = GeyserApi.api().extensionManager().extension("pacbridge");
            return extension != null && extension.isEnabled();
        } catch (IllegalStateException | LinkageError ignored) { return false; }
    }
    /** Return the bridge's Geyser Core session; callers must respect its network thread. */
    public Object rawSession(UUID uuid) {
        if (!engineAvailable()) return null;
        try {
            Extension extension = GeyserApi.api().extensionManager().extension("pacbridge");
            return extension.getClass().getMethod("rawSession", UUID.class).invoke(extension, uuid);
        } catch (ReflectiveOperationException | IllegalStateException | LinkageError ignored) { return null; }
    }
    public boolean isBedrock(UUID uuid) {
        if (Bukkit.getPluginManager().isPluginEnabled("floodgate")) {
            try { if (FloodgateApi.getInstance().isFloodgatePlayer(uuid)) return true; }
            catch (IllegalStateException | LinkageError ignored) { }
        }
        if (Bukkit.getPluginManager().isPluginEnabled("Geyser-Spigot")) {
            try { return GeyserApi.api() != null && GeyserApi.api().connectionByUuid(uuid) != null; }
            catch (IllegalStateException | LinkageError ignored) { }
        }
        return false;
    }
}
