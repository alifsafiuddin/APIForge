package com.apiforge.schema;

import java.util.Map;

import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

/** Builds schemas and JSON values from text for engine unit tests. */
public final class Schemas {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private Schemas() {
    }

    public static Map<String, Object> object(String json) {
        return JSON.readValue(json, new TypeReference<>() {
        });
    }

    public static Object value(String json) {
        return JSON.readValue(json, Object.class);
    }

    public static Schema schema(String json) {
        return schema(json, "{}");
    }

    /** @param components the version's component schemas, by name */
    public static Schema schema(String json, String components) {
        return new SchemaResolver(object(components)).resolve(value(json), "#/test");
    }

    public static SchemaValidator validator() {
        return new SchemaValidator(new GuardedPattern());
    }


    public static String toJson(Object value) {
        return JSON.writeValueAsString(value);
    }
}
