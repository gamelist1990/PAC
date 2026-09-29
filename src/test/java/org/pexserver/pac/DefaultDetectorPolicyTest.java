package org.pexserver.pac;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;
import org.pexserver.pac.check.bedrock.prediction.BedrockPredictionCheck;
import org.pexserver.pac.check.core.CheckModule;
import org.pexserver.pac.check.core.CheckRegistry;
import org.pexserver.pac.check.core.CheckSettings;
import org.pexserver.pac.check.core.PacketCheck;
import org.pexserver.pac.check.core.PacketContext;
import org.pexserver.pac.check.shared.ExploitActionCheck;
import org.pexserver.pac.check.shared.VehicleMovementCheck;

import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

class DefaultDetectorPolicyTest {
    private record StubPacketCheck(String key) implements PacketCheck {
        @Override public void inspect(PacketContext context) { }
    }

    @Test void xrayIsOptInAndNoClipPreventionIsEnabledWithoutAutoBan() {
        var stream = getClass().getClassLoader().getResourceAsStream("config.yml");
        assertNotNull(stream);
        var config = YamlConfiguration.loadConfiguration(new InputStreamReader(stream, StandardCharsets.UTF_8));
        ConfigurationSection detectors = config.getConfigurationSection("detectors");
        assertNotNull(detectors);
        assertEquals(26, detectors.getKeys(false).size());
        assertTrue(detectors.contains("inventory-move"));
        for (String key : detectors.getKeys(false)) {
            assertEquals(!key.equals("xray"),
                    config.getBoolean("detectors." + key + ".enabled"),
                    key + " has an unexpected default state");
            assertEquals(!key.equals("noclip") && !key.equals("vehicle-movement")
                            && !key.equals("exploit-actions"),
                    config.getBoolean("detectors." + key + ".ban-enabled"),
                    key + " has an unexpected BAN default");
        }
        assertFalse(config.getBoolean("detectors.xray.cancel"));
        assertTrue(config.getBoolean("detectors.noclip.cancel"));
        assertEquals(0.03, config.getDouble("detectors.noclip.collision-tolerance"), 1.0e-12);
        assertTrue(config.getBoolean("detectors.packet-flood.cancel"));
        assertTrue(config.getBoolean("detectors.nuker.cancel"));
        assertTrue(config.getBoolean("detectors.anti-hunger.cancel"));
        assertTrue(config.getBoolean("detectors.timer-prediction.cancel"));
        assertTrue(config.getBoolean("detectors.critical-packet.cancel"));
        assertTrue(config.getBoolean("punishments.rollback-enabled"));
        assertTrue(config.getBoolean("detectors.simulation-killaura.cancel"));
        assertEquals(PacPlugin.CONFIG_VERSION,
                config.getInt(PacPlugin.CONFIG_VERSION_KEY));
        assertTrue(new BedrockPredictionCheck().automaticBanEligible());
        assertFalse(new VehicleMovementCheck(null).automaticBanEligible());
        assertFalse(new VehicleMovementCheck(null).automaticKickEligible());
        assertFalse(new ExploitActionCheck(null).automaticBanEligible());
        assertFalse(new ExploitActionCheck(null).automaticKickEligible());
        assertTrue(config.getBoolean("detectors.exploit-actions.cancel"));
        assertTrue(config.getBoolean("punishments.probation-enabled"));
    }

    @Test void movementRejectionDefaultsOnEvenWhenOlderConfigOmitsCancelOptions() {
        CheckRegistry registry = new CheckRegistry((uuid, module) -> true);
        for (String key : new String[]{"critical-packet", "inventory-move", "invalid-movement", "invalid-pitch", "packet-flood", "nuker", "anti-hunger",
                "motion-prediction", "air-prediction", "timer-prediction", "surface-prediction",
                "water-flow-prediction", "water-motion-prediction", "noclip", "vehicle-movement"}) {
            registry.register(new StubPacketCheck(key));
        }

        CheckSettings settings = new CheckSettings();
        settings.reload(registry, new YamlConfiguration());

        assertTrue(settings.rollbackEnabled());
        for (CheckModule module : registry.modules())
            assertTrue(settings.cancel(module), module.key() + " should reject movement by default");
        YamlConfiguration disabled = new YamlConfiguration();
        disabled.set("punishments.rollback-enabled", false);
        settings.reload(registry, disabled);
        assertFalse(settings.rollbackEnabled());
    }
}
