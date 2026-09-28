package org.pexserver.pac.platform;

import org.bukkit.plugin.java.JavaPlugin;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

/** Installs the bundled Geyser extension before Geyser scans its extension directory. */
public final class BedrockExtensionInstaller {
    private static final String RESOURCE = "embedded/pac-bedrock-geyser.jar";

    private BedrockExtensionInstaller() { }

    public static void install(JavaPlugin plugin) {
        Path plugins = plugin.getDataFolder().toPath().toAbsolutePath().normalize().getParent();
        Path extensions = plugins.resolve("Geyser-Spigot").resolve("extensions");
        Path destination = extensions.resolve("pac-bedrock-geyser.jar");
        Path pending = null;
        try (InputStream bundled = plugin.getResource(RESOURCE)) {
            if (bundled == null) {
                plugin.getLogger().severe("Bundled Bedrock extension is missing from PAC.jar");
                return;
            }
            Files.createDirectories(extensions);
            pending = Files.createTempFile(extensions, "pac-bedrock-geyser-", ".tmp");
            Files.copy(bundled, pending, StandardCopyOption.REPLACE_EXISTING);
            boolean replacingExisting = Files.exists(destination);
            if (replacingExisting && Files.mismatch(pending, destination) == -1) {
                plugin.getLogger().info("Bundled Bedrock extension is already installed.");
                return;
            }

            // PAC.jar is the source of truth. If an older copy already exists in
            // Geyser's extensions directory, atomically replace it before Geyser
            // scans and loads extensions.
            try {
                Files.move(pending, destination, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException ignored) {
                Files.move(pending, destination, StandardCopyOption.REPLACE_EXISTING);
            }
            pending = null;
            if (replacingExisting) {
                plugin.getLogger().info("Updated existing Geyser Bedrock extension from the bundled PAC version.");
            } else {
                plugin.getLogger().info("Installed bundled Bedrock extension for Geyser startup.");
            }
        } catch (IOException e) {
            plugin.getLogger().severe("Cannot install bundled Bedrock extension: " + e.getMessage());
        } finally {
            if (pending != null) {
                try { Files.deleteIfExists(pending); }
                catch (IOException e) { plugin.getLogger().warning("Cannot remove temporary Bedrock extension: " + e.getMessage()); }
            }
        }
    }
}
