package org.pexserver.pac.bridge;

import ac.boar.anticheat.violation.Violation;
import org.cloudburstmc.protocol.bedrock.packet.PlayerAuthInputPacket;

import java.lang.reflect.Method;
import java.util.UUID;

/** Reflection boundary between the Geyser extension loader and the Paper plugin. */
public final class PacPaperBridge {
    private static volatile Object paperPlugin;
    private static volatile Method inputReceiver;
    private static volatile Method inventoryInputFilter;
    private static volatile Method violationReceiver;
    private static volatile Method exemptionReader;
    private static volatile Method recordingOnlyReader;
    private static volatile Method rollbackEnabledReader;
    private static volatile Method diagnosticReceiver;
    private static volatile Method disconnectReceiver;
    private static volatile Method attackReceiver;

    private PacPaperBridge() { }

    public static void forwardAuthInput(UUID uuid, PlayerAuthInputPacket packet) {
        if (uuid == null) return;
        try {
            Object plugin = plugin();
            if (plugin == null) return;
            Method receiver = inputReceiver;
            if (receiver == null) {
                receiver = plugin.getClass().getMethod("acceptBedrockAuthInput", UUID.class, long.class,
                        double.class, double.class, double.class, double.class, double.class, double.class,
                    float.class, float.class, String.class);
                inputReceiver = receiver;
            }
            var position = packet.getPosition();
            var delta = packet.getDelta();
            var rotation = packet.getRotation();
            if (position == null || delta == null || rotation == null) return;
            Object accepted = receiver.invoke(plugin, uuid, packet.getTick(),
                    (double) position.getX(), (double) position.getY(), (double) position.getZ(),
                    (double) delta.getX(), (double) delta.getY(), (double) delta.getZ(),
                    rotation.getY(), rotation.getX(), packet.getInputMode().name());
            if (Boolean.FALSE.equals(accepted)) reset();
        } catch (ReflectiveOperationException | LinkageError ignored) { reset(); }
    }

    public static void forwardAttack(UUID uuid, int entityId) {
        if (uuid == null) return;
        try {
            Object plugin = plugin();
            if (plugin == null) return;
            Method receiver = attackReceiver;
            if (receiver == null) {
                receiver = plugin.getClass().getMethod("acceptBedrockAttack", UUID.class, int.class);
                attackReceiver = receiver;
            }
            receiver.invoke(plugin, uuid, entityId);
        } catch (ReflectiveOperationException | LinkageError ignored) { reset(); }
    }

    /** Return pass, preserve external movement, or zero movement for inventory input. */
    public static int bedrockInventoryInput(UUID uuid, boolean directional, boolean jump) {
        if (uuid == null) return 0;
        try {
            Object plugin = plugin();
            if (plugin == null) return 0;
            Method filter = inventoryInputFilter;
            if (filter == null) {
                filter = plugin.getClass().getMethod("bedrockInventoryInput",
                        UUID.class, boolean.class, boolean.class);
                inventoryInputFilter = filter;
            }
            Object mode = filter.invoke(plugin, uuid, directional, jump);
            return mode instanceof Number number ? number.intValue() : 0;
        } catch (ReflectiveOperationException | LinkageError ignored) { reset(); return 0; }
    }


    public static boolean forwardViolation(Violation violation) {
        try {
            Object plugin = plugin();
            if (plugin == null) return false;
            Method receiver = violationReceiver;
            if (receiver == null) {
                receiver = plugin.getClass().getMethod("acceptBedrockViolation", UUID.class,
                        String.class, int.class, String.class);
                violationReceiver = receiver;
            }
            Object accepted = receiver.invoke(plugin, violation.player().getSession().uuid(),
                    violation.check().name()
                            + (violation.check().type().isBlank() ? "" : "-" + violation.check().type()),
                    violation.vl(), violation.verbose());
            if (Boolean.TRUE.equals(accepted)) return true;
            reset();
        } catch (ReflectiveOperationException | LinkageError ignored) { reset(); }
        return false;
    }

    public static boolean isPacMovementExempt(UUID uuid) {
        if (uuid == null) return false;
        try {
            Object plugin = plugin();
            if (plugin == null) return false;
            Method reader = exemptionReader;
            if (reader == null) {
                reader = plugin.getClass().getMethod("bedrockMovementExempt", UUID.class);
                exemptionReader = reader;
            }
            return Boolean.TRUE.equals(reader.invoke(plugin, uuid));
        } catch (ReflectiveOperationException | LinkageError ignored) { reset(); return false; }
    }

    public static boolean isRecordingOnly() {
        try {
            Object plugin = plugin();
            if (plugin == null) return false;
            Method reader = recordingOnlyReader;
            if (reader == null) {
                reader = plugin.getClass().getMethod("isDebugRecording");
                recordingOnlyReader = reader;
            }
            return Boolean.TRUE.equals(reader.invoke(plugin));
        } catch (ReflectiveOperationException | LinkageError ignored) { reset(); return false; }
    }

    public static boolean isRollbackEnabled() {
        try {
            Object plugin = plugin();
            if (plugin == null) return true;
            Method reader = rollbackEnabledReader;
            if (reader == null) {
                reader = plugin.getClass().getMethod("rollbackEnabled");
                rollbackEnabledReader = reader;
            }
            return Boolean.TRUE.equals(reader.invoke(plugin));
        } catch (ReflectiveOperationException | LinkageError ignored) {
            reset();
            return true;
        }
    }

    public static void reportDiagnostic(UUID uuid, String check, int level, String detail) {
        if (uuid == null) return;
        try {
            Object plugin = plugin();
            if (plugin == null) return;
            Method receiver = diagnosticReceiver;
            if (receiver == null) {
                receiver = plugin.getClass().getMethod("recordBedrockDiagnostic", UUID.class,
                        String.class, int.class, String.class);
                diagnosticReceiver = receiver;
            }
            receiver.invoke(plugin, uuid, check, level, detail);
        } catch (ReflectiveOperationException | LinkageError ignored) { reset(); }
    }

    public static void forwardDisconnect(UUID uuid) {
        if (uuid == null) return;
        try {
            Object plugin = plugin();
            if (plugin == null) return;
            Method receiver = disconnectReceiver;
            if (receiver == null) {
                receiver = plugin.getClass().getMethod("forgetBedrockBridge", UUID.class);
                disconnectReceiver = receiver;
            }
            receiver.invoke(plugin, uuid);
        } catch (ReflectiveOperationException | LinkageError ignored) { reset(); }
    }

    private static Object plugin() throws ReflectiveOperationException {
        Object plugin = paperPlugin;
        if (plugin != null) {
            Object enabled = plugin.getClass().getMethod("isEnabled").invoke(plugin);
            if (Boolean.TRUE.equals(enabled)) return plugin;
            reset();
        }
        Class<?> bukkit = Class.forName("org.bukkit.Bukkit");
        Object manager = bukkit.getMethod("getPluginManager").invoke(null);
        plugin = manager.getClass().getMethod("getPlugin", String.class).invoke(manager, "PAC");
        if (plugin == null || !Boolean.TRUE.equals(
                plugin.getClass().getMethod("isEnabled").invoke(plugin))) return null;
        paperPlugin = plugin;
        return plugin;
    }

    private static void reset() {
        inventoryInputFilter = null;
        paperPlugin = null;
        inputReceiver = null;
        violationReceiver = null;
        exemptionReader = null;
        recordingOnlyReader = null;
        rollbackEnabledReader = null;
        diagnosticReceiver = null;
        disconnectReceiver = null;
        attackReceiver = null;
    }
}
