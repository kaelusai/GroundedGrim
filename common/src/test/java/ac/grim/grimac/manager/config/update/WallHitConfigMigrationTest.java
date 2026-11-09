package ac.grim.grimac.manager.config.update;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.yaml.snakeyaml.Yaml;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.*;

class WallHitConfigMigrationTest {
    @TempDir
    Path directory;

    @Test
    void replacesOldDefaultAndKeepsBackup() throws IOException {
        String old = legacyConfig();
        assertEquals(List.of(), migrate(old));
        assertEquals(old, Files.readString(directory.resolve("config.yml.v13.bak")));
        Map<?, ?> updated = new Yaml().load(Files.readString(directory.resolve("config.yml")));
        assertEquals(14, updated.get("config-version"));
    }

    @Test
    void preservesCustomExemptions() throws IOException {
        String old = legacyConfig().replace("      - \"STONE_SLAB\"\n", "      - \"STONE\"\n");
        assertEquals(ignored(old), migrate(old));
    }

    @Test
    void preservesSubsetOfOldDefaults() throws IOException {
        String old = "config-version: 13\nconfig-flavor: V2\nWallHit:\n    ignored-blocks: [OAK_DOOR]\n";
        assertEquals(List.of("OAK_DOOR"), migrate(old));
    }

    @Test
    void everyLocaleChecksAllMaterialsByDefault() throws IOException {
        for (String locale : List.of("de", "en", "es", "fr", "it", "ja", "nl", "pl", "pt", "ro", "ru", "tr", "zh")) {
            try (var stream = getClass().getResourceAsStream("/config/" + locale + ".yml")) {
                assertNotNull(stream, locale);
                Map<?, ?> defaults = new Yaml().load(stream);
                assertEquals(14, defaults.get("config-version"), locale);
                assertEquals(List.of(), ((Map<?, ?>) defaults.get("WallHit")).get("ignored-blocks"), locale);
            }
        }
    }

    private List<?> migrate(String old) throws IOException {
        Path file = directory.resolve("config.yml");
        Files.writeString(file, old);
        new ConfigUpdater(GrimConfigSpecs.class, Logger.getLogger("config-test"))
                .update(file.toFile(), GrimConfigSpecs.mainConfig());
        return ignored(Files.readString(file));
    }

    private String legacyConfig() throws IOException {
        try (var stream = getClass().getResourceAsStream("/config/wallhit-v13.yml")) {
            assertNotNull(stream);
            return new String(stream.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
        }
    }

    private List<?> ignored(String yaml) {
        Map<?, ?> root = new Yaml().load(yaml);
        return (List<?>) ((Map<?, ?>) root.get("WallHit")).get("ignored-blocks");
    }
}
