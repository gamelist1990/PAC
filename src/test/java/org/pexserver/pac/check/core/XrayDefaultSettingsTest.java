package org.pexserver.pac.check.core;

import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class XrayDefaultSettingsTest {
    @Test void experimentalChecksAreOptInByDefault() {
        CheckRegistry registry = new CheckRegistry((uuid, module) -> true);
        CheckModule xray = new EventCheck() { @Override public String key() { return "xray"; } };
        CheckModule noClip = new EventCheck() { @Override public String key() { return "noclip"; } };
        CheckModule motion = new EventCheck() { @Override public String key() { return "motion-prediction"; } };
        registry.register(xray);
        registry.register(noClip);
        registry.register(motion);

        CheckSettings settings = new CheckSettings();
        settings.reload(registry, new YamlConfiguration());

        assertFalse(settings.enabled(xray));
        assertFalse(settings.enabled(noClip));
        assertTrue(settings.enabled(motion));
        assertFalse(settings.cancel(xray));
        assertTrue(settings.cancel(noClip));
    }
}
