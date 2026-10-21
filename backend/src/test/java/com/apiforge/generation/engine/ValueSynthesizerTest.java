package com.apiforge.generation.engine;

import static com.apiforge.generation.engine.Schemas.schema;
import static com.apiforge.generation.engine.Schemas.toJson;
import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class ValueSynthesizerTest {

    private final ValueSynthesizer synthesizer = Schemas.synthesizer();
    private final SchemaValidator validator = Schemas.validator();

    private Object valid(String json) {
        return valid(json, "{}", false);
    }

    private Object valid(String json, String components, boolean complete) {
        Schema schema = schema(json, components);
        ValueSynthesizer.Result result = synthesizer.valid(schema, complete, "seed");
        assertThat(result.ok()).as("failure: %s", result.failure()).isTrue();
        assertThat(validator.validate(result.value(), schema).valid()).as("synthesized value must validate").isTrue();
        return result.value();
    }

    private String failure(String json) {
        ValueSynthesizer.Result result = synthesizer.valid(schema(json), false, "seed");
        assertThat(result.ok()).as("expected no valid value, got %s", result.value()).isFalse();
        return result.failure();
    }

    @Nested
    class PreferenceOrder {

        @Test
        void constWins() {
            assertThat(valid("{\"type\":\"string\",\"const\":\"fixed\",\"example\":\"other\"}")).isEqualTo("fixed");
        }

        @Test
        void validExampleBeforeDefaultAndEnum() {
            assertThat(valid("{\"type\":\"string\",\"example\":\"ex\",\"default\":\"def\"}")).isEqualTo("ex");
            assertThat(valid("{\"type\":\"string\",\"examples\":[\"first\",\"second\"]}")).isEqualTo("first");
        }

        @Test
        void invalidExampleIsSkipped() {
            assertThat(toJson(valid("{\"type\":\"integer\",\"minimum\":10,\"example\":3,\"default\":12}"))).isEqualTo("12");
            assertThat(valid("{\"type\":\"integer\",\"minimum\":10,\"example\":3}")).isEqualTo(10L);
        }

        @Test
        void firstEnumValueThatSatisfiesTheOtherConstraints() {
            assertThat(valid("{\"type\":\"string\",\"enum\":[\"toolong\",\"ok\"],\"maxLength\":3}")).isEqualTo("ok");
        }

        @Test
        void enumWhereNoValueFitsIsImpossible() {
            assertThat(failure("{\"type\":\"string\",\"enum\":[\"toolong\"],\"maxLength\":3}"))
                    .contains("none of the enum values");
        }
    }

    @Nested
    class Strings {

        @ParameterizedTest
        @CsvSource(delimiter = '|', textBlock = """
                date      | 2024-05-10
                date-time | 2024-05-10T10:30:00Z
                time      | 10:30:00
                email     | user@example.com
                uuid      | 3f2b8c1e-5d4a-4e6b-9c7d-1a2b3c4d5e60
                uri       | https://example.com/resource
                hostname  | example.com
                ipv4      | 192.0.2.10
                ipv6      | 2001:db8::10
                byte      | c2FtcGxl
                """)
        void formatsGetRealisticSamples(String format, String expected) {
            assertThat(valid("{\"type\":\"string\",\"format\":\"" + format + "\"}")).isEqualTo(expected);
        }

        @Test
        void unknownFormatFallsBackToPlainText() {
            assertThat(valid("{\"type\":\"string\",\"format\":\"x-custom\"}")).isEqualTo("sample");
        }

        @Test
        void lengthIsFittedWhenThereIsNoPattern() {
            assertThat(valid("{\"type\":\"string\",\"minLength\":10}")).isEqualTo("samplexxxx");
            assertThat(valid("{\"type\":\"string\",\"maxLength\":3}")).isEqualTo("sam");
        }

        @Test
        void patternIsSatisfiedAndDeterministic() {
            String json = "{\"type\":\"string\",\"pattern\":\"^[A-Z]{3}-[0-9]{4}$\"}";
            Object first = valid(json);

            assertThat((String) first).matches("[A-Z]{3}-[0-9]{4}");
            assertThat(valid(json)).isEqualTo(first);
        }

        @Test
        void patternTogetherWithLengthConstraints() {
            assertThat((String) valid("{\"type\":\"string\",\"pattern\":\"^[a-z]+$\",\"minLength\":4,\"maxLength\":6}"))
                    .matches("[a-z]{4,6}");
        }

        @Test
        void impossibleLengthRangeIsReported() {
            assertThat(failure("{\"type\":\"string\",\"minLength\":5,\"maxLength\":2}")).contains("no value satisfying");
        }

        @Test
        void invalidPatternIsReported() {
            assertThat(failure("{\"type\":\"string\",\"pattern\":\"([a-z\"}")).contains("not a regular expression Java can evaluate");
        }
    }

    @Nested
    class Numbers {

        @ParameterizedTest
        @CsvSource(delimiter = '|', textBlock = """
                {"type":"integer"}                                          | 1
                {"type":"integer","minimum":5}                              | 5
                {"type":"integer","minimum":5,"exclusiveMinimum":true}      | 6
                {"type":"integer","exclusiveMinimum":5}                     | 6
                {"type":"integer","maximum":-3}                             | -3
                {"type":"integer","maximum":0,"exclusiveMaximum":true}      | -1
                {"type":"integer","minimum":3,"maximum":12,"multipleOf":5}  | 5
                {"type":"integer","multipleOf":7}                           | 7
                {"type":"number"}                                           | 1.5
                {"type":"number","minimum":0,"maximum":10}                  | 5
                {"type":"number","exclusiveMinimum":true,"minimum":0,"multipleOf":0.01} | 0.01
                {"type":"number","minimum":0.1,"maximum":0.1}               | 0.1
                """)
        void satisfiesBoundsAndMultiples(String json, String expected) {
            assertThat(toJson(valid(json))).isEqualTo(expected);
        }

        @Test
        void emptyRangeIsImpossible() {
            assertThat(failure("{\"type\":\"integer\",\"minimum\":10,\"maximum\":5}")).contains("no value satisfying");
            assertThat(failure("{\"type\":\"integer\",\"exclusiveMinimum\":1,\"exclusiveMaximum\":2}"))
                    .contains("no value satisfying");
        }

        @Test
        void integralValuesAreLongs() {
            assertThat(valid("{\"type\":\"integer\",\"format\":\"int64\"}")).isInstanceOf(Long.class);
        }
    }

    @Nested
    class Arrays {

        @Test
        void minimalUsesMinItemsAndCompleteAddsOne() {
            String json = "{\"type\":\"array\",\"items\":{\"type\":\"string\"}}";
            assertThat(valid(json)).isEqualTo(List.of());
            assertThat(valid(json, "{}", true)).isEqualTo(List.of("sample"));
            assertThat(valid("{\"type\":\"array\",\"minItems\":2,\"items\":{\"type\":\"integer\"}}"))
                    .isEqualTo(List.of(1L, 1L));
        }

        @Test
        void uniqueItemsGetDistinctValues() {
            assertThat(valid("{\"type\":\"array\",\"minItems\":3,\"uniqueItems\":true,\"items\":{\"type\":\"integer\"}}"))
                    .isEqualTo(List.of(1L, 2L, 3L));
            assertThat(valid("{\"type\":\"array\",\"minItems\":3,\"uniqueItems\":true,"
                    + "\"items\":{\"enum\":[\"a\",\"b\",\"c\"]}}")).isEqualTo(List.of("a", "b", "c"));
        }

        @Test
        void uniquenessThatCannotBeMetIsReported() {
            assertThat(failure("{\"type\":\"array\",\"minItems\":3,\"uniqueItems\":true,\"items\":{\"enum\":[\"a\",\"b\"]}}"))
                    .contains("items must be unique");
        }

        @Test
        void oversizedMinItemsIsRefused() {
            assertThat(failure("{\"type\":\"array\",\"minItems\":5000}")).contains("exceeds the generator limit");
        }
    }

    @Nested
    class Objects {

        private static final String COMPONENTS = """
                {"Node":{"type":"object","properties":{"name":{"type":"string"},
                                                       "children":{"type":"array","items":{"$ref":"#/components/schemas/Node"}}}},
                 "Loop":{"type":"object","required":["next"],"properties":{"next":{"$ref":"#/components/schemas/Loop"}}}}""";

        @Test
        void minimalHasRequiredPropertiesOnlyAndSkipsReadOnly() {
            Object value = valid("""
                    {"type":"object","required":["id","name"],
                     "properties":{"id":{"type":"integer","readOnly":true},"name":{"type":"string"},"tag":{"type":"string"}}}""");

            assertThat(value).isEqualTo(Map.of("name", "sample"));
        }

        @Test
        void completeAddsOptionalPropertiesButNeverReadOnly() {
            Object value = valid("""
                    {"type":"object","required":["name"],
                     "properties":{"id":{"type":"integer","readOnly":true},"name":{"type":"string"},"tag":{"type":"string"}}}""",
                    "{}", true);

            assertThat(value).isEqualTo(Map.of("name", "sample", "tag", "sample"));
        }

        @Test
        void recursiveOptionalPropertiesStayFinite() {
            Object value = valid("{\"$ref\":\"#/components/schemas/Node\"}", COMPONENTS, true);

            assertThat(toJson(value)).startsWith("{\"children\":[{\"children\":[{").hasSizeLessThan(400);
        }

        @Test
        void recursiveRequiredPropertyIsImpossible() {
            ValueSynthesizer.Result result = synthesizer.valid(schema("{\"$ref\":\"#/components/schemas/Loop\"}", COMPONENTS),
                    false, "seed");

            assertThat(result.ok()).isFalse();
            assertThat(result.failure()).contains("recursive schema");
        }

        @Test
        void minPropertiesIsMetWithOptionalThenAdditionalProperties() {
            assertThat(valid("{\"type\":\"object\",\"minProperties\":1,\"properties\":{\"a\":{\"type\":\"integer\"}}}"))
                    .isEqualTo(Map.of("a", 1L));
            assertThat(valid("{\"type\":\"object\",\"minProperties\":2,\"additionalProperties\":{\"type\":\"boolean\"}}"))
                    .isEqualTo(Map.of("property1", true, "property2", true));
        }

        @Test
        void requiredPropertyWithoutSchemaGetsAPlaceholder() {
            assertThat(valid("{\"type\":\"object\",\"required\":[\"free\"]}")).isEqualTo(Map.of("free", "sample"));
        }
    }

    @Nested
    class Composition {

        private static final String COMPONENTS = """
                {"Base":{"type":"object","required":["id"],"properties":{"id":{"type":"integer","minimum":100}}},
                 "Address":{"type":"object","required":["kind","city"],
                            "properties":{"kind":{"type":"string","enum":["address"]},"city":{"type":"string"}}},
                 "Pickup":{"type":"object","required":["kind","point"],
                           "properties":{"kind":{"type":"string","enum":["pickup"]},"point":{"type":"string"}}}}""";

        @Test
        void allOfMembersAreMerged() {
            assertThat(valid("{\"allOf\":[{\"$ref\":\"#/components/schemas/Base\"},"
                    + "{\"required\":[\"name\"],\"properties\":{\"name\":{\"type\":\"string\",\"minLength\":7}}}]}",
                    COMPONENTS, false)).isEqualTo(Map.of("id", 100L, "name", "samplex"));
        }

        @Test
        void allOfWithIncompatibleTypesIsImpossible() {
            assertThat(failure("{\"allOf\":[{\"type\":\"string\"},{\"type\":\"integer\"}]}")).contains("incompatible types");
        }

        @Test
        void oneOfPicksTheFirstAlternativeAndSetsTheDiscriminator() {
            Object value = valid("""
                    {"oneOf":[{"$ref":"#/components/schemas/Address"},{"$ref":"#/components/schemas/Pickup"}],
                     "discriminator":{"propertyName":"kind","mapping":{"address":"#/components/schemas/Address"}}}""",
                    COMPONENTS, false);

            assertThat(value).isEqualTo(Map.of("kind", "address", "city", "sample"));
        }

        @Test
        void oneOfWhoseAlternativesAlwaysOverlapIsImpossible() {
            assertThat(failure("{\"oneOf\":[{\"type\":\"string\"},{\"type\":\"string\",\"maxLength\":50}]}"))
                    .contains("oneOf/anyOf");
        }

        @Test
        void anyOfAcceptsAnyMatchingAlternative() {
            assertThat(valid("{\"anyOf\":[{\"type\":\"string\",\"minLength\":3},{\"type\":\"integer\"}]}"))
                    .isEqualTo("sample");
        }
    }

    @Nested
    class OpenApi31 {

        @Test
        void typeArraysUseTheFirstNonNullType() {
            assertThat(valid("{\"type\":[\"null\",\"integer\"],\"minimum\":4}")).isEqualTo(4L);
        }

        @Test
        void onlyNullAllowed() {
            assertThat(valid("{\"type\":\"null\"}")).isNull();
        }

        @Test
        void falseSchemaIsImpossible() {
            assertThat(failure("false")).contains("accepts no value");
        }
    }
}
