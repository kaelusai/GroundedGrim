package ac.grim.grimac.manager.config;

import org.yaml.snakeyaml.DumperOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.error.YAMLException;
import org.yaml.snakeyaml.nodes.MappingNode;
import org.yaml.snakeyaml.nodes.Node;
import org.yaml.snakeyaml.nodes.ScalarNode;
import org.yaml.snakeyaml.nodes.SequenceNode;

import java.io.IOException;
import java.io.StringReader;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;
import java.util.Locale;

final class LightningCombatPunishments {
    private LightningCombatPunishments() {
    }

    static boolean update(Path file) throws IOException {
        if (!Files.isRegularFile(file)) return false;
        file = file.toRealPath();
        String before = Files.readString(file);
        String after = addMissingGroups(before);
        if (before.equals(after)) return false;

        Path backup = file.resolveSibling(file.getFileName() + ".pre-lightning.bak");
        try {
            Files.copy(file, backup, StandardCopyOption.COPY_ATTRIBUTES);
        } catch (FileAlreadyExistsException ignored) {
        }

        Path temporary = Files.createTempFile(file.getParent(), file.getFileName() + ".", ".tmp");
        try {
            Files.copy(file, temporary, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.COPY_ATTRIBUTES);
            Files.writeString(temporary, after);
            try {
                Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException ignored) {
                Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(temporary);
        }
        return true;
    }

    static String addMissingGroups(String content) {
        Node root;
        try {
            root = new Yaml().compose(new StringReader(content));
        } catch (YAMLException ignored) {
            return content;
        }
        if (!(root instanceof MappingNode mapping)
                || !(value(mapping, "Punishments") instanceof MappingNode groups)
                || groups.getFlowStyle() != DumperOptions.FlowStyle.BLOCK || groups.isMerged()) return content;

        StringBuilder additions = new StringBuilder();
        int indent = groups.getStartMark().getColumn();
        for (String check : List.of("WallHit", "EntityPierce")) {
            if (value(groups, check) != null || configured(groups, check)) continue;
            additions.append(("""
                    %s:
                      remove-violations-after: 300
                      checks:
                        - "%s"
                      commands:
                        - "10:5 [alert]"
                        - "10:5 [webhook]"
                        - "10:5 [proxy]"
                        - "10:5 [log]"
                    """).formatted(check, check).indent(indent));
        }
        if (additions.isEmpty()) return content;

        int end = content.offsetByCodePoints(0, groups.getEndMark().getIndex());
        String prefix = content.substring(0, end);
        String newline = content.contains("\r\n") ? "\r\n" : "\n";
        String separator = prefix.endsWith("\n") ? "" : newline;
        return prefix + separator + additions.toString().replace("\n", newline) + content.substring(end);
    }

    private static Node value(MappingNode mapping, String key) {
        for (var tuple : mapping.getValue()) {
            if (tuple.getKeyNode() instanceof ScalarNode scalar && scalar.getValue().equals(key)) {
                return tuple.getValueNode();
            }
        }
        return null;
    }

    private static boolean configured(MappingNode groups, String check) {
        String name = check.toLowerCase(Locale.ROOT);
        for (var tuple : groups.getValue()) {
            if (!(tuple.getValueNode() instanceof MappingNode group)
                    || !(value(group, "checks") instanceof SequenceNode checks)) continue;
            for (Node node : checks.getValue()) {
                if (!(node instanceof ScalarNode scalar)) continue;
                String selector = scalar.getValue().toLowerCase(Locale.ROOT);
                // Exclusions also express an operator's choice; do not re-enable the check.
                if (selector.startsWith("!")) selector = selector.substring(1);
                if (name.contains(selector)) return true;
            }
        }
        return false;
    }
}
