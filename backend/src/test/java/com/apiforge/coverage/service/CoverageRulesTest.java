package com.apiforge.coverage.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import com.apiforge.coverage.service.CoverageViews.ChangeKind;
import com.apiforge.generation.engine.GenerationFixtures;
import com.apiforge.schema.Schema;
import com.apiforge.schema.Schemas;
import com.apiforge.testcase.domain.TestCase;
import com.apiforge.testcase.domain.TestFingerprint;

class CoverageRulesTest {

    @Nested
    class Kinds {

        @ParameterizedTest
        @CsvSource({
                "happy.minimal, VALID_REQUEST", "auth.invalid, AUTHENTICATION", "body.missing, MISSING_REQUIRED",
                "body.missing:/name, MISSING_REQUIRED", "query.missing:limit, MISSING_REQUIRED",
                "header.missing:X-Request-Id, MISSING_REQUIRED", "body.type:, WRONG_TYPE", "path.type:id, WRONG_TYPE",
                "query.enum:category, INVALID_VALUE", "body.below-minimum:/price, OUT_OF_RANGE",
                "body.too-long:/name, OUT_OF_RANGE", "body.too-many-items:/tags, OUT_OF_RANGE",
                "body.at-max-length:/name, ACCEPTED_BOUNDARY", "query.at-minimum:limit, ACCEPTED_BOUNDARY",
                "content-type.unsupported, UNSUPPORTED_CONTENT_TYPE", "custom.something:x, OTHER", "manual, OTHER"})
        void scenarioKeysMapToKinds(String key, ScenarioKind kind) {
            assertThat(ScenarioKind.of(key)).isEqualTo(kind);
        }

        /** Every key the generator produces for the fixture specifications has a kind that matches its category. */
        @ParameterizedTest
        @ValueSource(strings = {"warehouse-lab-3.0.yaml", "petstore3-official.yaml", "pets-3.0.json", "inventory-3.0.yaml",
                "library-3.1.json"})
        void everyGeneratedScenarioHasAKind(String fixture) {
            GenerationFixtures.Imported imported = GenerationFixtures.fixture(fixture);
            int checked = 0;
            for (String operation : imported.operations().keySet()) {
                for (TestCase.Definition test : imported.generate(operation).tests()) {
                    ScenarioKind kind = ScenarioKind.of(test.scenarioKey());
                    assertThat(kind).as(test.scenarioKey()).isNotEqualTo(ScenarioKind.OTHER);
                    switch (test.category()) {
                        case HAPPY_PATH -> assertThat(kind).as(test.scenarioKey()).isEqualTo(ScenarioKind.VALID_REQUEST);
                        case AUTH -> assertThat(kind).as(test.scenarioKey()).isEqualTo(ScenarioKind.AUTHENTICATION);
                        case MISSING_FIELD -> assertThat(kind).as(test.scenarioKey()).isEqualTo(ScenarioKind.MISSING_REQUIRED);
                        case INVALID_TYPE -> assertThat(kind).as(test.scenarioKey()).isEqualTo(ScenarioKind.WRONG_TYPE);
                        case ENUM_VALUE -> assertThat(kind).as(test.scenarioKey()).isEqualTo(ScenarioKind.INVALID_VALUE);
                        case CONTENT_TYPE -> assertThat(kind).as(test.scenarioKey()).isEqualTo(ScenarioKind.UNSUPPORTED_CONTENT_TYPE);
                        case BOUNDARY_VALUE, STRING_LENGTH, ARRAY_LENGTH -> assertThat(kind).as(test.scenarioKey())
                                .isIn(ScenarioKind.OUT_OF_RANGE, ScenarioKind.ACCEPTED_BOUNDARY);
                        default -> assertThat(kind.constraint() || kind == ScenarioKind.AUTHENTICATION).isTrue();
                    }
                    // Rejection scenarios expect a client error; accepted boundaries expect success.
                    if (kind.negative()) {
                        assertThat(test.expectations().statuses()).as(test.scenarioKey())
                                .allMatch(status -> status.startsWith("4") || status.equalsIgnoreCase("default"));
                    }
                    checked++;
                }
            }
            assertThat(checked).isPositive();
        }
    }

    @Nested
    class Branches {

        private static final String COMPONENTS = """
                {"Address":{"type":"object","required":["kind","city"],"properties":{"kind":{"enum":["address"]},"city":{"type":"string"}}},
                 "Pickup":{"type":"object","required":["kind","pointId"],"properties":{"kind":{"enum":["pickup"]},"pointId":{"type":"string","minLength":6}}}}""";
        private static final Schema ORDER = Schemas.schema("""
                {"type":"object","properties":{
                   "delivery":{"oneOf":[{"$ref":"#/components/schemas/Address"},{"$ref":"#/components/schemas/Pickup"}]},
                   "lines":{"type":"array","items":{"type":"object","properties":{
                       "price":{"anyOf":[{"type":"number"},{"type":"string","pattern":"^[0-9.]+$"}]}}}},
                   "self":{"$ref":"#/components/schemas/Address"}}}""", COMPONENTS);

        @Test
        void oneOfAndAnyOfAlternativesAreFoundWithTheirLocation() {
            List<SchemaBranches.Branch> branches = SchemaBranches.find(ORDER);

            assertThat(branches).extracting(SchemaBranches.Branch::id, SchemaBranches.Branch::path, SchemaBranches.Branch::label)
                    .containsExactly(
                            tuple("delivery#oneOf/0", "$.delivery", "delivery: one of Address"),
                            tuple("delivery#oneOf/1", "$.delivery", "delivery: one of Pickup"),
                            tuple("lines/*/price#anyOf/0", "$.lines[*].price", "price: any of alternative 1"),
                            tuple("lines/*/price#anyOf/1", "$.lines[*].price", "price: any of alternative 2"));
        }

        @Test
        void aBranchIsExercisedWhenTheValueAtItsLocationValidatesAgainstIt() {
            List<SchemaBranches.Branch> branches = SchemaBranches.find(ORDER);
            Object pickup = Schemas.value("""
                    {"delivery":{"kind":"pickup","pointId":"ABC123"},"lines":[{"price":"9.50"},{"price":3}]}""");
            Object invalidPickup = Schemas.value("""
                    {"delivery":{"kind":"pickup","pointId":"A"},"lines":[]}""");

            assertThat(SchemaBranches.exercised(pickup, branches, Schemas.validator()))
                    .containsExactly("delivery#oneOf/1", "lines/*/price#anyOf/0", "lines/*/price#anyOf/1");
            assertThat(SchemaBranches.exercised(invalidPickup, branches, Schemas.validator())).isEmpty();
            assertThat(SchemaBranches.exercised(null, branches, Schemas.validator())).isEmpty();
        }

        @Test
        void recursiveSchemasTerminate() {
            Schema tree = Schemas.schema("{\"$ref\":\"#/components/schemas/Node\"}", """
                    {"Node":{"type":"object","properties":{"children":{"type":"array","items":{"$ref":"#/components/schemas/Node"}},
                     "value":{"oneOf":[{"type":"string"},{"type":"integer"}]}}}}""");

            List<SchemaBranches.Branch> branches = SchemaBranches.find(tree);

            assertThat(branches).extracting(SchemaBranches.Branch::path).containsExactly("$.value", "$.value");
        }

        @Test
        void theWarehouseOrderDeliveryHasTwoBranches() {
            GenerationFixtures.Imported warehouse = GenerationFixtures.fixture("warehouse-lab-3.0.yaml");
            Map<String, Object> body = warehouse.operation("POST /orders").requestBody();
            Object schemaNode = ((Map<?, ?>) ((Map<?, ?>) ((Map<?, ?>) body.get("content")).get("application/json")).get("schema"));
            Schema schema = new com.apiforge.schema.SchemaResolver(warehouse.specification().componentSchemas())
                    .resolve(schemaNode, "#/orders");

            assertThat(SchemaBranches.find(schema)).extracting(SchemaBranches.Branch::label)
                    .containsExactly("delivery: one of Address", "delivery: one of PickupPoint");
        }
    }

    @Nested
    class Diff {

        private static SpecificationDiff.Contract contract(String key, String parameters, String body, String responses) {
            return new SpecificationDiff.Contract(key, list(parameters), body == null ? null : Schemas.object(body),
                    list(responses), List.of());
        }

        @SuppressWarnings("unchecked")
        private static List<Map<String, Object>> list(String json) {
            return (List<Map<String, Object>>) Schemas.value(json);
        }

        @Test
        void addedRemovedChangedAndUnchangedOperations() {
            Map<String, Object> v1 = Schemas.object("""
                    {"Item":{"type":"object","properties":{"name":{"$ref":"#/components/schemas/Name"}}},
                     "Name":{"type":"string","maxLength":20}}""");
            Map<String, Object> v2 = Schemas.object("""
                    {"Item":{"type":"object","properties":{"name":{"$ref":"#/components/schemas/Name"}}},
                     "Name":{"type":"string","maxLength":40}}""");
            String itemResponse = "[{\"status\":\"200\",\"content\":{\"application/json\":{\"schema\":{\"$ref\":\"#/components/schemas/Item\"}}}}]";
            List<SpecificationDiff.Contract> from = List.of(
                    contract("GET /items", "[{\"name\":\"a\",\"in\":\"query\"},{\"name\":\"b\",\"in\":\"query\"}]", null, "[{\"status\":\"200\"}]"),
                    contract("GET /items/{id}", "[]", null, itemResponse),
                    contract("POST /items", "[]", "{\"required\":true}", "[{\"status\":\"201\"},{\"status\":\"400\"}]"),
                    contract("DELETE /items/{id}", "[]", null, "[{\"status\":\"204\"}]"));
            List<SpecificationDiff.Contract> to = List.of(
                    // Parameters and responses reordered only: unchanged.
                    contract("GET /items", "[{\"name\":\"b\",\"in\":\"query\"},{\"name\":\"a\",\"in\":\"query\"}]", null, "[{\"status\":\"200\"}]"),
                    contract("GET /items/{id}", "[]", null, itemResponse),
                    contract("POST /items", "[]", "{\"required\":false}", "[{\"status\":\"400\"},{\"status\":\"201\"},{\"status\":\"409\"}]"),
                    contract("PUT /items/{id}", "[]", null, "[{\"status\":\"200\"}]"));

            SpecificationDiff.Result diff = SpecificationDiff.diff(v1, from, v2, to);

            assertThat(diff.changes()).extracting(SpecificationDiff.Change::operationKey, SpecificationDiff.Change::kind,
                    SpecificationDiff.Change::aspects).containsExactly(
                            tuple("PUT /items/{id}", ChangeKind.ADDED, List.of()),
                            tuple("DELETE /items/{id}", ChangeKind.REMOVED, List.of()),
                            tuple("GET /items/{id}", ChangeKind.CHANGED, List.of("referenced schemas")),
                            tuple("POST /items", ChangeKind.CHANGED, List.of("request body", "responses")));
            assertThat(diff.unchanged()).isEqualTo(1);
        }

        @Test
        void referencesAreFollowedTransitivelyAndCyclesEnd() {
            Map<String, Object> components = Schemas.object("""
                    {"A":{"properties":{"b":{"$ref":"#/components/schemas/B"}}},"B":{"items":{"$ref":"#/components/schemas/A"}},
                     "Unused":{}}""");

            assertThat(SpecificationDiff.referenced(contract("GET /a", "[]", null,
                    "[{\"status\":\"200\",\"content\":{\"application/json\":{\"schema\":{\"$ref\":\"#/components/schemas/A\"}}}}]"),
                    components)).containsExactlyInAnyOrder("A", "B");
        }
    }

    @Nested
    class Fingerprints {

        @Test
        void sameRequestAndExpectationsGiveTheSameFingerprint() {
            Map<String, Object> request = Schemas.object("""
                    {"pathParams":{"id":1},"query":{"a":1,"b":[1,2]},"headers":{},"body":{"mode":"JSON","value":{"x":1,"y":2}},"auth":"ENVIRONMENT"}""");
            Map<String, Object> reordered = Schemas.object("""
                    {"auth":"ENVIRONMENT","body":{"value":{"y":2,"x":1},"mode":"JSON"},"headers":{},"query":{"b":[1,2],"a":1},"pathParams":{"id":1}}""");

            String fingerprint = TestFingerprint.of("GET /x", request, List.of("200", "4xx"));

            assertThat(TestFingerprint.of("GET /x", reordered, List.of("4XX", "200"))).isEqualTo(fingerprint);
            assertThat(TestFingerprint.of("GET /x", request, List.of("200"))).isNotEqualTo(fingerprint);
            assertThat(TestFingerprint.of("GET /y", request, List.of("200", "4XX"))).isNotEqualTo(fingerprint);
            Map<String, Object> otherOrder = Schemas.object("""
                    {"pathParams":{"id":1},"query":{"a":1,"b":[2,1]},"headers":{},"body":{"mode":"JSON","value":{"x":1,"y":2}},"auth":"ENVIRONMENT"}""");
            assertThat(TestFingerprint.of("GET /x", otherOrder, List.of("200", "4XX"))).as("array order matters")
                    .isNotEqualTo(fingerprint);
            Map<String, Object> asText = Schemas.object("""
                    {"pathParams":{"id":"1"},"query":{"a":"1","b":["1","2"]},"headers":{},"body":{"mode":"JSON","value":{"x":1,"y":2}},"auth":"ENVIRONMENT"}""");
            assertThat(TestFingerprint.of("GET /x", asText, List.of("200", "4XX"))).as("path and query values travel as text")
                    .isEqualTo(fingerprint);
            Map<String, Object> bodyAsText = Schemas.object("""
                    {"pathParams":{"id":1},"query":{"a":1,"b":[1,2]},"headers":{},"body":{"mode":"JSON","value":{"x":"1","y":2}},"auth":"ENVIRONMENT"}""");
            assertThat(TestFingerprint.of("GET /x", bodyAsText, List.of("200", "4XX"))).as("JSON body types matter")
                    .isNotEqualTo(fingerprint);
        }
    }
}
