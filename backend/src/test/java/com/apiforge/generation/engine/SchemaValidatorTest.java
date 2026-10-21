package com.apiforge.generation.engine;

import static com.apiforge.generation.engine.Schemas.schema;
import static com.apiforge.generation.engine.Schemas.value;
import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.Set;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class SchemaValidatorTest {

    private final SchemaValidator validator = Schemas.validator();

    private SchemaValidator.Result validate(String instance, String schema) {
        return validator.validate(value(instance), schema(schema));
    }

    private SchemaValidator.Result validate(String instance, String schema, String components) {
        return validator.validate(value(instance), schema(schema, components));
    }

    @Nested
    class Types {

        @ParameterizedTest
        @CsvSource(delimiter = '|', textBlock = """
                "text"        | {"type":"string"}             | true
                12            | {"type":"string"}             | false
                12            | {"type":"integer"}            | true
                12.0          | {"type":"integer"}            | true
                12.5          | {"type":"integer"}            | false
                12.5          | {"type":"number"}             | true
                true          | {"type":"boolean"}            | true
                "true"        | {"type":"boolean"}            | false
                {}            | {"type":"object"}             | true
                []            | {"type":"object"}             | false
                []            | {"type":"array"}              | true
                null          | {"type":"string"}             | false
                null          | {"type":"string","nullable":true} | true
                null          | {"type":["string","null"]}    | true
                7             | {"type":["string","integer"]} | true
                true          | {"type":["string","integer"]} | false
                "anything"    | {}                            | true
                """)
        void typeKeyword(String instance, String schema, boolean valid) {
            assertThat(validate(instance, schema).valid()).isEqualTo(valid);
        }

        @Test
        void typeMismatchStopsFurtherChecksAndNamesBothTypes() {
            SchemaValidator.Result result = validate("12", """
                    {"type":"string","minLength":5,"enum":["a"]}""");

            assertThat(result.violations()).singleElement().satisfies(violation -> {
                assertThat(violation.keyword()).isEqualTo("type");
                assertThat(violation.message()).isEqualTo("expected string but found number");
            });
        }

        @Test
        void nullableWithoutTypeDoesNotRestrict() {
            assertThat(validate("5", "{\"nullable\":true}").valid()).isTrue();
        }
    }

    @Nested
    class Values {

        @Test
        void enumComparesJsonValues() {
            assertThat(validate("2", "{\"enum\":[1,2.0,3]}").valid()).isTrue();
            assertThat(validate("\"x\"", "{\"enum\":[\"a\",\"b\"]}").violations())
                    .extracting(SchemaValidator.Violation::keyword).containsExactly("enum");
        }

        @Test
        void constKeyword() {
            assertThat(validate("{\"a\":1}", "{\"const\":{\"a\":1.0}}").valid()).isTrue();
            assertThat(validate("\"b\"", "{\"const\":\"a\"}").violations())
                    .extracting(SchemaValidator.Violation::keyword).containsExactly("const");
        }
    }

    @Nested
    class Numbers {

        @ParameterizedTest
        @CsvSource(delimiter = '|', textBlock = """
                0    | {"minimum":0}                              | true
                -1   | {"minimum":0}                              | false
                0    | {"minimum":0,"exclusiveMinimum":true}      | false
                0.01 | {"minimum":0,"exclusiveMinimum":true}      | true
                0    | {"exclusiveMinimum":0}                     | false
                1    | {"exclusiveMinimum":0}                     | true
                100  | {"maximum":100}                            | true
                101  | {"maximum":100}                            | false
                100  | {"maximum":100,"exclusiveMaximum":true}    | false
                100  | {"exclusiveMaximum":100}                   | false
                5    | {"minimum":1,"exclusiveMinimum":5}         | false
                """)
        void bounds(String instance, String schema, boolean valid) {
            assertThat(validate(instance, schema).valid()).isEqualTo(valid);
        }

        @Test
        void boundViolationsNameTheKeyword() {
            assertThat(validate("0", "{\"exclusiveMinimum\":0}").violations())
                    .extracting(SchemaValidator.Violation::keyword).containsExactly("exclusiveMinimum");
            assertThat(validate("0", "{\"minimum\":0,\"exclusiveMinimum\":true}").violations())
                    .extracting(SchemaValidator.Violation::keyword).containsExactly("exclusiveMinimum");
        }

        @Test
        void multipleOfUsesExactDecimals() {
            assertThat(validate("129.99", "{\"multipleOf\":0.01}").valid()).isTrue();
            assertThat(validate("0.3", "{\"multipleOf\":0.1}").valid()).isTrue();
            assertThat(validate("10.005", "{\"multipleOf\":0.01}").violations())
                    .extracting(SchemaValidator.Violation::keyword).containsExactly("multipleOf");
        }
    }

    @Nested
    class Strings {

        @Test
        void lengthCountsCodePointsNotUtf16Units() {
            String emoji = "\"😀😀\""; // two code points, four UTF-16 units
            assertThat(validate(emoji, "{\"maxLength\":2}").valid()).isTrue();
            assertThat(validate(emoji, "{\"minLength\":3}").violations())
                    .extracting(SchemaValidator.Violation::keyword).containsExactly("minLength");
        }

        @Test
        void patternIsUnanchoredLikeJsonSchema() {
            assertThat(validate("\"xxABCxx\"", "{\"pattern\":\"ABC\"}").valid()).isTrue();
            assertThat(validate("\"TLS-0042\"", "{\"pattern\":\"^[A-Z]{3}-[0-9]{4}$\"}").valid()).isTrue();
            assertThat(validate("\"tls-0042\"", "{\"pattern\":\"^[A-Z]{3}-[0-9]{4}$\"}").violations())
                    .extracting(SchemaValidator.Violation::keyword).containsExactly("pattern");
        }

        @Test
        void invalidPatternIsReportedAsUnevaluatedNotAsAViolation() {
            SchemaValidator.Result result = validate("\"x\"", "{\"pattern\":\"([a-z\"}");

            assertThat(result.valid()).isTrue();
            assertThat(result.unevaluated()).containsExactly("pattern");
        }

        @Test
        @Timeout(5)
        void catastrophicBacktrackingIsAbandonedQuickly() {
            // Java memoizes simple nested quantifiers such as (a+)+; this one still explodes.
            String input = "\"" + "a".repeat(30) + "b\"";
            long started = System.nanoTime();

            SchemaValidator.Result result = validate(input, "{\"pattern\":\"^(.*a){12}$\"}");

            assertThat(result.unevaluated()).containsExactly("pattern");
            assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThan(Duration.ofSeconds(2));
        }

        @Test
        void formatIsAnAnnotationAndNotAsserted() {
            assertThat(validate("\"not-an-email\"", "{\"type\":\"string\",\"format\":\"email\"}").valid()).isTrue();
        }
    }

    @Nested
    class Arrays {

        @Test
        void itemCountsAndUniqueness() {
            String schema = "{\"type\":\"array\",\"minItems\":1,\"maxItems\":2,\"uniqueItems\":true}";
            assertThat(validate("[1]", schema).valid()).isTrue();
            assertThat(validate("[]", schema).violations()).extracting(SchemaValidator.Violation::keyword)
                    .containsExactly("minItems");
            assertThat(validate("[1,2,3]", schema).violations()).extracting(SchemaValidator.Violation::keyword)
                    .containsExactly("maxItems");
            assertThat(validate("[1,1.0]", schema).violations()).extracting(SchemaValidator.Violation::keyword)
                    .containsExactly("uniqueItems");
        }

        @Test
        void itemViolationsPointAtTheItem() {
            SchemaValidator.Result result = validate("[\"a\", 5]", "{\"items\":{\"type\":\"string\"}}");

            assertThat(result.violations()).singleElement().satisfies(violation -> {
                assertThat(violation.pointer()).isEqualTo("/1");
                assertThat(violation.keyword()).isEqualTo("type");
            });
        }

        @Test
        void prefixItemsIsReportedAsUnevaluated() {
            assertThat(validate("[1]", "{\"prefixItems\":[{\"type\":\"string\"}]}").unevaluated())
                    .containsExactly("prefixItems");
        }
    }

    @Nested
    class Objects {

        private static final String PET = """
                {"type":"object","required":["name","id"],"additionalProperties":false,
                 "properties":{"id":{"type":"integer","readOnly":true},"name":{"type":"string"},
                               "tag":{"type":"string"}}}""";

        @Test
        void requiredReadOnlyPropertiesAreNotExpectedInRequests() {
            assertThat(validate("{\"name\":\"Rex\"}", PET).valid()).isTrue();
        }

        @Test
        void sendingAReadOnlyPropertyIsAViolation() {
            assertThat(validate("{\"name\":\"Rex\",\"id\":1}", PET).violations())
                    .extracting(SchemaValidator.Violation::pointer, SchemaValidator.Violation::keyword)
                    .containsExactly(org.assertj.core.groups.Tuple.tuple("/id", "readOnly"));
        }

        @Test
        void missingRequiredPropertyPointsAtTheProperty() {
            assertThat(validate("{}", PET).violations())
                    .extracting(SchemaValidator.Violation::pointer, SchemaValidator.Violation::keyword)
                    .containsExactly(org.assertj.core.groups.Tuple.tuple("/name", "required"));
        }

        @Test
        void additionalPropertiesFalseAndSchema() {
            assertThat(validate("{\"name\":\"Rex\",\"color\":\"red\"}", PET).violations())
                    .extracting(SchemaValidator.Violation::pointer).containsExactly("/color");
            String map = "{\"type\":\"object\",\"additionalProperties\":{\"type\":\"integer\"}}";
            assertThat(validate("{\"a\":1,\"b\":2}", map).valid()).isTrue();
            assertThat(validate("{\"a\":\"x\"}", map).violations()).extracting(SchemaValidator.Violation::pointer)
                    .containsExactly("/a");
        }

        @Test
        void propertyCounts() {
            String schema = "{\"minProperties\":1,\"maxProperties\":2}";
            assertThat(validate("{}", schema).violations()).extracting(SchemaValidator.Violation::keyword)
                    .containsExactly("minProperties");
            assertThat(validate("{\"a\":1,\"b\":2,\"c\":3}", schema).violations())
                    .extracting(SchemaValidator.Violation::keyword).containsExactly("maxProperties");
        }

        @Test
        void propertyNamesWithSlashesAreEscapedInPointers() {
            assertThat(validate("{\"a/b\":1}", "{\"properties\":{\"a/b\":{\"type\":\"string\"}}}").violations())
                    .extracting(SchemaValidator.Violation::pointer).containsExactly("/a~1b");
        }
    }

    @Nested
    class Composition {

        private static final String COMPONENTS = """
                {"Base":{"type":"object","required":["id"],"properties":{"id":{"type":"integer"}}},
                 "Cat":{"type":"object","required":["meow"],"properties":{"meow":{"type":"boolean"}}},
                 "Dog":{"type":"object","required":["bark"],"properties":{"bark":{"type":"boolean"}}}}""";

        @Test
        void allOfAppliesEveryMember() {
            String schema = "{\"allOf\":[{\"$ref\":\"#/components/schemas/Base\"},{\"required\":[\"name\"]}]}";
            assertThat(validate("{\"id\":1,\"name\":\"x\"}", schema, COMPONENTS).valid()).isTrue();
            assertThat(validate("{\"name\":\"x\"}", schema, COMPONENTS).violations())
                    .extracting(SchemaValidator.Violation::pointer).containsExactly("/id");
        }

        @Test
        void oneOfRequiresExactlyOneMatch() {
            String schema = "{\"oneOf\":[{\"$ref\":\"#/components/schemas/Cat\"},{\"$ref\":\"#/components/schemas/Dog\"}]}";
            assertThat(validate("{\"meow\":true}", schema, COMPONENTS).valid()).isTrue();
            assertThat(validate("{\"meow\":true,\"bark\":true}", schema, COMPONENTS).violations())
                    .extracting(SchemaValidator.Violation::keyword).containsExactly("oneOf");
            assertThat(validate("{}", schema, COMPONENTS).violations())
                    .extracting(SchemaValidator.Violation::message)
                    .containsExactly("must match exactly one alternative (matches 0)");
        }

        @Test
        void anyOfAndNot() {
            assertThat(validate("5", "{\"anyOf\":[{\"type\":\"string\"},{\"minimum\":3}]}").valid()).isTrue();
            assertThat(validate("1", "{\"anyOf\":[{\"type\":\"string\"},{\"minimum\":3}]}").violations())
                    .extracting(SchemaValidator.Violation::keyword).containsExactly("anyOf");
            assertThat(validate("\"x\"", "{\"not\":{\"type\":\"string\"}}").violations())
                    .extracting(SchemaValidator.Violation::keyword).containsExactly("not");
        }

        @Test
        void referenceSiblingsApplyInAdditionToTheTarget() {
            // OpenAPI 3.1: keywords next to $ref are not ignored.
            String schema = "{\"$ref\":\"#/components/schemas/Base\",\"required\":[\"name\"]}";
            assertThat(validate("{\"id\":1}", schema, COMPONENTS).violations())
                    .extracting(SchemaValidator.Violation::pointer).containsExactly("/name");
        }
    }

    @Nested
    class Unknowns {

        @Test
        void unsupportedKeywordsAreListedNotIgnoredSilently() {
            SchemaValidator.Result result = validate("{}", "{\"if\":{},\"then\":{},\"patternProperties\":{}}");

            assertThat(result.valid()).isTrue();
            assertThat(result.unevaluated()).containsExactlyInAnyOrder("if", "then", "patternProperties");
        }

        @Test
        void unresolvableReferenceIsUnevaluated() {
            SchemaValidator.Result result = validate("1", "{\"$ref\":\"#/components/schemas/Missing\"}");

            assertThat(result.unevaluated()).singleElement().asString().contains("cannot be resolved");
        }

        @Test
        void circularReferenceChainIsUnevaluated() {
            SchemaValidator.Result result = validate("1", "{\"$ref\":\"#/components/schemas/A\"}",
                    "{\"A\":{\"$ref\":\"#/components/schemas/B\"},\"B\":{\"$ref\":\"#/components/schemas/A\"}}");

            assertThat(result.unevaluated()).isNotEmpty();
        }

        @Test
        void falseSchemaRejectsEverything() {
            assertThat(validate("1", "false").violations()).extracting(SchemaValidator.Violation::keyword)
                    .containsExactly("false");
            assertThat(validate("1", "true").valid()).isTrue();
        }
    }

    @Test
    void onlyViolatesRequiresEveryViolationAtTheTarget() {
        SchemaValidator.Result single = validate("{\"a\":\"x\"}", "{\"properties\":{\"a\":{\"type\":\"integer\"}}}");
        SchemaValidator.Result two = validate("{\"a\":\"x\",\"b\":\"y\"}",
                "{\"properties\":{\"a\":{\"type\":\"integer\"},\"b\":{\"type\":\"integer\"}}}");

        assertThat(single.onlyViolates("/a", Set.of("type"))).isTrue();
        assertThat(single.onlyViolates("/a", Set.of("enum"))).isFalse();
        assertThat(two.onlyViolates("/a", Set.of("type"))).isFalse();
        assertThat(validate("{}", "{}").onlyViolates("", Set.of("type"))).isFalse();
    }
}
