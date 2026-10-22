package com.apiforge.schema;

import static com.apiforge.schema.Schemas.schema;
import static com.apiforge.schema.Schemas.value;
import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

/**
 * Reference resolution and the flattened allOf view: bounds are tightened, required and properties
 * united, enums and types intersected, impossible combinations reported. Validation keeps using the
 * original members, so a keyword the flattened view does not merge (e.g. a second pattern) is still
 * checked.
 */
class SchemaResolverTest {

    private final SchemaValidator validator = Schemas.validator();

    private static List<Object> list(Object value) {
        return new ArrayList<>((List<?>) value);
    }

    private boolean validates(String json, String schemaJson) {
        return validator.validate(value(json), schema(schemaJson)).valid();
    }

    @Test
    void allOfTightensNumericAndLengthBounds() {
        Map<String, Object> merged = schema("""
                {"allOf": [
                  {"type": "integer", "minimum": 1, "maximum": 100},
                  {"minimum": 10, "maximum": 50},
                  {"minimum": 5, "maximum": 80}
                ]}""").merged();

        assertThat(merged).containsEntry("type", "integer");
        assertThat(((Number) merged.get("minimum")).intValue()).isEqualTo(10);
        assertThat(((Number) merged.get("maximum")).intValue()).isEqualTo(50);

        Map<String, Object> text = schema("""
                {"allOf": [{"type": "string", "minLength": 2, "maxLength": 20}, {"minLength": 5, "maxLength": 8}]}""")
                .merged();
        assertThat(((Number) text.get("minLength")).intValue()).isEqualTo(5);
        assertThat(((Number) text.get("maxLength")).intValue()).isEqualTo(8);
    }

    @Test
    void allOfUnitesRequiredAndPropertiesAndCombinesSharedProperties() {
        Schema merged = schema("""
                {"allOf": [
                  {"$ref": "#/components/schemas/Base"},
                  {"type": "object", "required": ["name", "id"],
                   "properties": {"name": {"type": "string"}, "id": {"maximum": 99}}}
                ]}""", """
                {"Base": {"type": "object", "required": ["id"], "properties": {"id": {"type": "integer", "minimum": 1}}}}""");

        assertThat(list(merged.merged().get("required"))).containsExactly("id", "name");
        Map<?, ?> properties = (Map<?, ?>) merged.merged().get("properties");
        List<Object> names = List.copyOf(properties.keySet());
        assertThat(names).containsExactlyInAnyOrder("id", "name");
        // Both declarations of "id" apply.
        assertThat(validator.validate(value("{\"id\": 5, \"name\": \"x\"}"), merged).valid()).isTrue();
        assertThat(validator.validate(value("{\"id\": 100, \"name\": \"x\"}"), merged).valid()).isFalse();
        assertThat(validator.validate(value("{\"id\": 0, \"name\": \"x\"}"), merged).valid()).isFalse();
    }

    @Test
    void allOfIntersectsEnumsAndTypes() {
        assertThat(list(schema("{\"allOf\": [{\"enum\": [\"a\", \"b\", \"c\"]}, {\"enum\": [\"c\", \"b\", \"z\"]}]}")
                .merged().get("enum"))).containsExactly("b", "c");
        assertThat(schema("{\"allOf\": [{\"type\": [\"string\", \"null\"]}, {\"type\": [\"null\", \"integer\"]}]}")
                .merged()).containsEntry("type", "null");
    }

    @Test
    void impossibleAllOfIsReportedInsteadOfGuessed() {
        assertThat(schema("{\"allOf\": [{\"type\": \"string\"}, {\"type\": \"integer\"}]}").merged())
                .containsEntry(Schema.PROBLEM_KEY, "allOf members declare incompatible types");
        assertThat(schema("{\"allOf\": [{\"type\": \"string\"}, false]}").merged())
                .containsEntry(Schema.PROBLEM_KEY, "an allOf member accepts no value");
        // An empty enum intersection accepts nothing, and validation agrees.
        assertThat(list(schema("{\"allOf\": [{\"enum\": [\"a\"]}, {\"enum\": [\"b\"]}]}").merged().get("enum")))
                .isEmpty();
        assertThat(validates("\"a\"", "{\"allOf\": [{\"enum\": [\"a\"]}, {\"enum\": [\"b\"]}]}")).isFalse();
    }

    @Test
    void validationChecksEveryAllOfMemberEvenWhenTheFlattenedViewKeepsOnlyOneKeyword() {
        String schema = "{\"allOf\": [{\"type\": \"string\", \"pattern\": \"^a\"}, {\"pattern\": \"z$\"}]}";
        assertThat(schema(schema).merged()).containsEntry("pattern", "^a");

        assertThat(validates("\"abcz\"", schema)).isTrue();
        assertThat(validates("\"abc\"", schema)).isFalse();
        assertThat(validates("\"bcz\"", schema)).isFalse();
        assertThat(validates("7", "{\"allOf\": [{\"multipleOf\": 2}, {\"multipleOf\": 3}]}")).isFalse();
        assertThat(validates("12", "{\"allOf\": [{\"multipleOf\": 2}, {\"multipleOf\": 3}]}")).isTrue();
    }

    @Test
    void referencesOutsideComponentsCyclesAndMissingTargetsAreUnresolvable() {
        assertThat(schema("{\"$ref\": \"other.yaml#/Pet\"}").problem()).contains("cannot be resolved");
        assertThat(schema("{\"$ref\": \"#/components/schemas/Missing\"}").problem()).contains("cannot be resolved");
        assertThat(schema("{\"$ref\": \"#/components/schemas/A\"}",
                "{\"A\": {\"$ref\": \"#/components/schemas/B\"}, \"B\": {\"$ref\": \"#/components/schemas/A\"}}").problem())
                .contains("cannot be resolved");
    }

    @Test
    void referenceChainsResolveAndKeepSiblingKeywords() {
        Schema resolved = schema("{\"$ref\": \"#/components/schemas/Id\", \"maximum\": 10}",
                "{\"Id\": {\"$ref\": \"#/components/schemas/Number\"}, \"Number\": {\"type\": \"integer\", \"minimum\": 1}}");

        assertThat(resolved.problem()).isNull();
        assertThat(resolved.name()).isEqualTo("Number");
        assertThat(validator.validate(5, resolved).valid()).isTrue();
        assertThat(validator.validate(11, resolved).valid()).isFalse();
        assertThat(validator.validate(0, resolved).valid()).isFalse();
    }

    @Test
    void deeplyNestedAllOfStopsWithAProblem() {
        String nested = "{\"type\": \"string\"}";
        for (int depth = 0; depth < 12; depth++) {
            nested = "{\"allOf\": [" + nested + "]}";
        }
        assertThat(schema(nested).merged().get(Schema.PROBLEM_KEY)).asString().contains("nested more than");
    }

    @Test
    void booleanAndMissingSchemasAcceptEverythingOrNothing() {
        assertThat(validates("{\"anything\": 1}", "true")).isTrue();
        assertThat(schema("false").isNever()).isTrue();
        assertThat(new SchemaResolver(null).resolve(null, "#/x").problem()).isNull();
    }

    @Test
    void oneOfAlternativeCombinesTheParentKeywordsWithTheAlternative() {
        Schema parent = schema("""
                {"type": "object", "required": ["kind"], "properties": {"kind": {"type": "string"}},
                 "oneOf": [{"properties": {"size": {"type": "integer", "maximum": 3}}, "required": ["size"]}]}""");
        Schema alternative = parent.resolver().alternative(parent, ((List<?>) parent.raw().get("oneOf")).getFirst(),
                "#/test/oneOf/0");

        assertThat(alternative.merged()).doesNotContainKey("oneOf");
        assertThat(list(alternative.merged().get("required"))).containsExactly("kind", "size");
        assertThat(validator.validate(value("{\"kind\": \"box\", \"size\": 2}"), alternative).valid()).isTrue();
        assertThat(validator.validate(value("{\"kind\": \"box\", \"size\": 4}"), alternative).valid()).isFalse();
    }
}
