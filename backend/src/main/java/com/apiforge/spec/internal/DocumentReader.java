package com.apiforge.spec.internal;

import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

import org.yaml.snakeyaml.error.Mark;
import org.yaml.snakeyaml.error.MarkedYAMLException;
import org.yaml.snakeyaml.error.YAMLException;

import com.apiforge.spec.domain.ImportMessage;
import com.apiforge.spec.domain.ImportMessage.Code;
import com.apiforge.spec.domain.SpecificationRejectedException;
import com.apiforge.spec.domain.SpecificationVersion.SourceFormat;
import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonLocation;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.StreamReadConstraints;
import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.core.exc.StreamConstraintsException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Turns uploaded bytes into a JSON tree under strict limits, before any OpenAPI library sees
 * the content: valid UTF-8 only, bounded nesting and string sizes, bounded YAML alias expansion
 * ("billion laughs", see {@link YamlLoader}), and no duplicate keys (which would silently hide a
 * duplicate path or operation). Every failure is reported as a readable message, with a line
 * and column where the parser provides one.
 */
public final class DocumentReader {

    /** The decoded text, its detected format and the parsed tree. */
    public record ReadDocument(String text, SourceFormat format, JsonNode tree) {
    }

    private final ObjectMapper jsonMapper;
    private final YamlLoader yamlLoader;

    public DocumentReader(int maxSizeBytes, int maxNestingDepth, int maxYamlAliases) {
        StreamReadConstraints constraints = StreamReadConstraints.builder()
                .maxNestingDepth(maxNestingDepth)
                .maxStringLength(maxSizeBytes)
                .maxDocumentLength(maxSizeBytes)
                .build();

        this.jsonMapper = new ObjectMapper(JsonFactory.builder()
                .streamReadConstraints(constraints)
                .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
                .build());
        this.yamlLoader = new YamlLoader(maxSizeBytes, maxNestingDepth, maxYamlAliases);
    }

    public ReadDocument read(byte[] content) {
        String text = decode(content);
        if (text.isBlank()) {
            throw rejected(Code.EMPTY_DOCUMENT, "The document is empty.", null);
        }
        if (text.indexOf('\u0000') >= 0) {
            throw rejected(Code.INVALID_ENCODING, "The document contains binary data; expected JSON or YAML text.", null);
        }
        SourceFormat format = detectFormat(text);
        JsonNode tree = parse(text, format);
        if (tree == null || !tree.isObject()) {
            throw rejected(Code.NOT_AN_OBJECT,
                    "The document must be a JSON or YAML object with an 'openapi' field at the top level.", null);
        }
        return new ReadDocument(text, format, tree);
    }

    /** Serializes a tree as compact JSON (what the OpenAPI parser receives). */
    public String toJson(JsonNode tree) {
        try {
            return jsonMapper.writeValueAsString(tree);
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("Cannot serialize parsed document", ex);
        }
    }

    private static String decode(byte[] content) {
        int offset = content.length >= 3 && (content[0] & 0xFF) == 0xEF && (content[1] & 0xFF) == 0xBB
                && (content[2] & 0xFF) == 0xBF ? 3 : 0;
        try {
            return StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(content, offset, content.length - offset))
                    .toString();
        } catch (CharacterCodingException ex) {
            throw rejected(Code.INVALID_ENCODING, "The document is not valid UTF-8 text.", null);
        }
    }

    /** JSON documents start with an object; everything else is treated as YAML (JSON is also valid YAML). */
    static SourceFormat detectFormat(String text) {
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (!Character.isWhitespace(c)) {
                return c == '{' || c == '[' ? SourceFormat.JSON : SourceFormat.YAML;
            }
        }
        return SourceFormat.YAML;
    }

    private JsonNode parse(String text, SourceFormat format) {
        if (format == SourceFormat.JSON) {
            try {
                return jsonMapper.readTree(text);
            } catch (StreamConstraintsException ex) {
                throw rejected(Code.LIMIT_EXCEEDED,
                        "The document exceeds a safety limit: " + firstLine(ex.getOriginalMessage()),
                        location(ex.getLocation()));
            } catch (JsonProcessingException ex) {
                throw translate(firstLine(ex.getOriginalMessage()), location(ex.getLocation()), format);
            }
        }
        Object document;
        try {
            document = yamlLoader.load(text);
        } catch (YamlLoader.RecursiveStructureException ex) {
            throw rejected(Code.LIMIT_EXCEEDED,
                    "The document uses a YAML anchor inside itself (a recursive structure), which cannot be represented as OpenAPI.",
                    null);
        } catch (MarkedYAMLException ex) {
            String problem = ex.getProblem() != null ? ex.getProblem() : ex.getMessage();
            throw translate(firstLine(problem), location(ex.getProblemMark()), format);
        } catch (YAMLException ex) {
            throw translate(firstLine(String.valueOf(ex.getMessage())), null, format);
        }
        return jsonMapper.valueToTree(document);
    }

    private static SpecificationRejectedException translate(String message, String location, SourceFormat format) {
        String lower = message.toLowerCase(Locale.ROOT);

        if (lower.contains("duplicate field") || lower.contains("duplicate key") || lower.contains("found duplicate")) {
            return rejected(Code.DUPLICATE_KEY,
                    "Duplicate key: " + message + ". Duplicate keys can hide paths or operations, so they are rejected.",
                    location);
        }
        if (lower.contains("aliases") || lower.contains("nesting depth") || lower.contains("code points")
                || lower.contains("exceeds the")) {
            return rejected(Code.LIMIT_EXCEEDED, "The document exceeds a safety limit: " + message, location);
        }
        return rejected(Code.PARSE_ERROR, "Invalid " + format.name() + ": " + message, location);
    }

    private static String location(Mark mark) {
        return mark == null ? null : "line " + (mark.getLine() + 1) + ", column " + (mark.getColumn() + 1);
    }

    private static String location(JsonLocation location) {
        if (location == null || location.getLineNr() <= 0) {
            return null;
        }
        return "line " + location.getLineNr() + ", column " + location.getColumnNr();
    }

    private static String firstLine(String message) {
        String trimmed = message.strip();
        int newline = trimmed.indexOf('\n');
        String line = newline < 0 ? trimmed : trimmed.substring(0, newline).strip();
        return line.length() > 300 ? line.substring(0, 300) + "…" : line;
    }

    private static SpecificationRejectedException rejected(Code code, String message, String location) {
        return new SpecificationRejectedException(ImportMessage.error(code, message, location));
    }
}
