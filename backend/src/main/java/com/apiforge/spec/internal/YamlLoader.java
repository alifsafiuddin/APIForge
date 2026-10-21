package com.apiforge.spec.internal;

import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

import org.yaml.snakeyaml.DumperOptions;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;
import org.yaml.snakeyaml.nodes.Tag;
import org.yaml.snakeyaml.representer.Representer;
import org.yaml.snakeyaml.resolver.Resolver;

/**
 * Loads YAML with SnakeYAML so that anchors and aliases are expanded correctly, while bounding
 * that expansion ("billion laughs"), nesting, document size and duplicate keys. Jackson's YAML
 * parser is deliberately not used for this: it reports aliases as plain strings, which would
 * silently corrupt documents that use them.
 *
 * <p>Scalars are typed like JSON: timestamps stay strings (a date in an example must not turn
 * into a number), and only {@code true/false} are booleans (not YAML 1.1's yes/no/on/off).
 */
final class YamlLoader {

    /** Signals a document whose anchors reference themselves (cannot be represented as JSON). */
    static final class RecursiveStructureException extends RuntimeException {

        private static final long serialVersionUID = 1L;

        RecursiveStructureException() {
            super("Recursive YAML anchors are not supported");
        }
    }

    private static final Pattern JSON_BOOLEAN = Pattern.compile("^(?:true|True|TRUE|false|False|FALSE)$");

    private final LoaderOptions options;

    YamlLoader(int maxSizeBytes, int maxNestingDepth, int maxAliases) {
        options = new LoaderOptions();
        options.setMaxAliasesForCollections(maxAliases);
        options.setNestingDepthLimit(maxNestingDepth);
        options.setCodePointLimit(maxSizeBytes);
        options.setAllowDuplicateKeys(false);
        options.setAllowRecursiveKeys(false);
    }

    Object load(String text) {
        // A new Yaml instance per call: SnakeYAML instances are not thread-safe.
        Yaml yaml = new Yaml(new SafeConstructor(options), new Representer(new DumperOptions()), new DumperOptions(),
                options, new JsonCompatibleResolver());
        Object document = yaml.load(text);
        ensureAcyclic(document, new IdentityHashMap<>());
        return document;
    }

    private static void ensureAcyclic(Object node, IdentityHashMap<Object, Boolean> ancestors) {
        if (!(node instanceof Map<?, ?>) && !(node instanceof List<?>)) {
            return;
        }
        if (ancestors.put(node, Boolean.TRUE) != null) {
            throw new RecursiveStructureException();
        }
        if (node instanceof Map<?, ?> map) {
            for (Object value : map.values()) {
                ensureAcyclic(value, ancestors);
            }
        } else {
            for (Object item : (List<?>) node) {
                ensureAcyclic(item, ancestors);
            }
        }
        ancestors.remove(node);
    }

    /** SnakeYAML's resolver without YAML 1.1 timestamps and yes/no/on/off booleans. */
    private static final class JsonCompatibleResolver extends Resolver {

        @Override
        protected void addImplicitResolvers() {
            addImplicitResolver(Tag.BOOL, JSON_BOOLEAN, "tfTF");
            addImplicitResolver(Tag.INT, INT, "-+0123456789");
            addImplicitResolver(Tag.FLOAT, FLOAT, "-+0123456789.");
            addImplicitResolver(Tag.MERGE, MERGE, "<");
            addImplicitResolver(Tag.NULL, NULL, "~nN\0");
            addImplicitResolver(Tag.NULL, EMPTY, null);
        }
    }
}
