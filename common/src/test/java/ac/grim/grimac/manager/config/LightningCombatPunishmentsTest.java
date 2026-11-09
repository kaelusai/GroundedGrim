package ac.grim.grimac.manager.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.yaml.snakeyaml.Yaml;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class LightningCombatPunishmentsTest {
    @TempDir
    Path directory;

    @Test
    void addsMissingGroupsAndPreservesExistingText() {
        String input = "# custom\nPunishments:\n  Reach:\n    checks: [Reach, Hitboxes]\n    commands: ['1:1 [log]']\n";
        String output = LightningCombatPunishments.addMissingGroups(input);
        assertTrue(output.startsWith(input));
        Map<?, ?> groups = groups(output);
        assertEquals(groups(input).get("Reach"), groups.get("Reach"));
        assertEquals(List.of("WallHit"), ((Map<?, ?>) groups.get("WallHit")).get("checks"));
        assertEquals(List.of("EntityPierce"), ((Map<?, ?>) groups.get("EntityPierce")).get("checks"));
        assertEquals(output, LightningCombatPunishments.addMissingGroups(output));
    }

    @ParameterizedTest
    @ValueSource(strings = {"WallHit", "wall", "!WallHit", "EntityPierce", "!entity", ""})
    void respectsCustomGroupsAndExclusions(String selector) {
        String input = "Punishments:\n  Custom:\n    checks: ['" + selector + "']\n    commands: []\n";
        Map<?, ?> groups = groups(LightningCombatPunishments.addMissingGroups(input));
        assertEquals(groups(input).get("Custom"), groups.get("Custom"));
        for (String check : List.of("WallHit", "EntityPierce")) {
            if (check.toLowerCase().contains(selector.replace("!", "").toLowerCase())) {
                assertFalse(groups.containsKey(check), selector);
            }
        }
    }

    @Test
    void preservesNamedGroupsEvenWhenTheyAreEmpty() {
        String input = "Punishments:\n  WallHit: {}\n  EntityPierce: {}\n";
        assertEquals(input, LightningCombatPunishments.addMissingGroups(input));
    }

    @Test
    void insertsInsideQuotedSectionWithoutChangingOtherRootKeys() {
        String input = "# 😀\n'Punishments': # keep\n    Custom:\n        checks: [Reach]\nOther:\n  WallHit: unchanged\n";
        String output = LightningCombatPunishments.addMissingGroups(input);
        Map<?, ?> parsed = new Yaml().load(output);
        assertEquals(Map.of("WallHit", "unchanged"), parsed.get("Other"));
        assertTrue(groups(output).containsKey("WallHit"));
        assertTrue(groups(output).containsKey("EntityPierce"));
        assertTrue(output.startsWith(input.substring(0, input.indexOf("Other:"))));
        assertTrue(output.endsWith("Other:\n  WallHit: unchanged\n"));
    }

    @Test
    void preservesWindowsLineEndingsAndHandlesMissingFinalNewline() {
        String input = "Punishments:\r\n  Custom:\r\n    checks: [Reach]";
        String output = LightningCombatPunishments.addMissingGroups(input);
        assertTrue(output.startsWith(input + "\r\n"));
        assertFalse(output.replace("\r\n", "").contains("\n"));
        assertTrue(groups(output).containsKey("EntityPierce"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "alerts: true\n", "Punishments: {}\n", "Punishments:\n", "Punishments: [\n"})
    void leavesUnrelatedEmptyOrInvalidConfigurationUntouched(String input) {
        assertEquals(input, LightningCombatPunishments.addMissingGroups(input));
    }

    @ParameterizedTest
    @ValueSource(strings = {"de", "en", "es", "fr", "it", "ja", "nl", "pt", "ru", "tr", "zh"})
    void backfillsBundledDefaultsWithoutOverwritingTheirGroups(String locale) throws IOException {
        try (var stream = getClass().getResourceAsStream("/punishments/" + locale + ".yml")) {
            assertNotNull(stream);
            String input = new String(stream.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
            String output = LightningCombatPunishments.addMissingGroups(input);
            Map<?, ?> before = groups(input);
            Map<?, ?> after = groups(output);
            before.forEach((key, value) -> assertEquals(value, after.get(key), locale + ": " + key));
            assertTrue(after.containsKey("EntityPierce"), locale);
            assertEquals(output, LightningCombatPunishments.addMissingGroups(output));
        }
    }

    @Test
    void backsUpTheOriginalAndDoesNotRewriteAnUpdatedFile() throws IOException {
        Path file = directory.resolve("punishments.yml");
        String input = "Punishments:\n  Reach:\n    checks: [Reach]\n";
        Files.writeString(file, input);
        assertTrue(LightningCombatPunishments.update(file));
        Path backup = directory.resolve("punishments.yml.pre-lightning.bak");
        assertEquals(input, Files.readString(backup));
        String updated = Files.readString(file);
        assertFalse(LightningCombatPunishments.update(file));
        assertEquals(updated, Files.readString(file));
        assertEquals(input, Files.readString(backup));
        try (var files = Files.list(directory)) {
            assertEquals(2, files.count());
        }
    }

    @Test
    void keepsAnExistingBackup() throws IOException {
        Path file = directory.resolve("punishments.yml");
        Path backup = directory.resolve("punishments.yml.pre-lightning.bak");
        Files.writeString(file, "Punishments:\n  Reach:\n    checks: [Reach]\n");
        Files.writeString(backup, "older backup");
        assertTrue(LightningCombatPunishments.update(file));
        assertEquals("older backup", Files.readString(backup));
    }

    @Test
    void ignoresMissingFile() throws IOException {
        assertFalse(LightningCombatPunishments.update(directory.resolve("missing.yml")));
    }

    private Map<?, ?> groups(String input) {
        Map<?, ?> root = new Yaml().load(input);
        return (Map<?, ?>) root.get("Punishments");
    }
}
