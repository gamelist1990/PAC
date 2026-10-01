package org.pexserver.pac.check.core;

import org.bukkit.configuration.file.YamlConfiguration;
import org.pexserver.pac.check.bedrock.prediction.BedrockPredictionCheck;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class BedrockRollbackSettingsTest {
    @Test void reloadKeepsDetectionActiveWhenCorrectionsAreDisabled() {
        CheckRegistry registry = new CheckRegistry((uuid, module) -> true);
        CheckModule prediction = new BedrockPredictionCheck();
        registry.register(prediction);
        CheckSettings settings = new CheckSettings();
        YamlConfiguration config = new YamlConfiguration();
        settings.reload(registry, config);
        assertTrue(settings.rollbackEnabled());
        assertTrue(settings.cancel(prediction));

        config.set("punishments.rollback-enabled", false);
        settings.reload(registry, config);
        assertFalse(settings.rollbackEnabled());
        assertTrue(settings.enabled(prediction));

        config.set("punishments.rollback-enabled", true);
        config.set("detectors.bedrock-prediction.cancel", false);
        settings.reload(registry, config);
        assertTrue(settings.rollbackEnabled());
        assertFalse(settings.cancel(prediction));
        assertTrue(settings.enabled(prediction));

        config.set("detectors.bedrock-prediction.cancel", true);
        settings.reload(registry, config);
        assertTrue(settings.cancel(prediction));
    }
}
