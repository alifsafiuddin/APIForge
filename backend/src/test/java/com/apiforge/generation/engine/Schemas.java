package com.apiforge.generation.engine;

import java.util.Map;

import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

/** Builds schemas and JSON values from text for engine unit tests. */
final class Schemas {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private Schemas() {
    }

    static Map<String, Object> object(String json) {
        return JSON.readValue(json, new TypeReference<>() {
        });
    }

    static Object value(String json) {
        return JSON.readValue(json, Object.class);
    }

    static Schema schema(String json) {
        return schema(json, "{}");
    }

    /** @param components the version's component schemas, by name */
    static Schema schema(String json, String components) {
        return new SchemaResolver(object(components)).resolve(value(json), "#/test");
    }

    static SchemaValidator validator() {
        return new SchemaValidator(new GuardedPattern());
    }

    static ValueSynthesizer synthesizer() {
        GuardedPattern patterns = new GuardedPattern();
        return new ValueSynthesizer(new SchemaValidator(patterns), patterns);
    }

    static String toJson(Object value) {
        return JSON.writeValueAsString(value);
    }
}
