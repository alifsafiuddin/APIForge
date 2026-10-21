package com.apiforge.generation.engine;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.InstanceOfAssertFactories.MAP;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.IntStream;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import com.apiforge.generation.engine.DeterministicTestGenerator.Operation;
import com.apiforge.generation.engine.DeterministicTestGenerator.Output;
import com.apiforge.generation.engine.DeterministicTestGenerator.Skipped;
import com.apiforge.testcase.domain.RequestTemplate.AuthMode;
import com.apiforge.testcase.domain.RequestTemplate.BodyMode;
import com.apiforge.testcase.domain.TestCase.Category;
import com.apiforge.testcase.domain.TestCase.Definition;
import com.apiforge.testcase.domain.TestCase.RiskLevel;

class DeterministicTestGeneratorTest {

    private static final GenerationFixtures.Imported WAREHOUSE = GenerationFixtures.fixture("warehouse-lab-3.0.yaml");

    private static Definition test(Output output, String scenarioKey) {
        return output.tests().stream().filter(test -> test.scenarioKey().equals(scenarioKey)).findFirst()
                .orElseThrow(() -> new AssertionError("no scenario " + scenarioKey + " in "
                        + output.tests().stream().map(Definition::scenarioKey).toList()));
    }

    private static List<String> keys(Output output) {
        return output.tests().stream().map(Definition::scenarioKey).toList();
    }

    private static Skipped skipped(Output output, String scenarioPrefix) {
        return output.skipped().stream().filter(skip -> skip.scenario().startsWith(scenarioPrefix)).findFirst()
                .orElseThrow(() -> new AssertionError("nothing skipped as '" + scenarioPrefix + "': " + output.skipped()));
    }

    @Nested
    class Repeatability {

        @Test
        void sameDocumentGivesIdenticalTestsInTheSameOrder() {
            GenerationFixtures.Imported again = GenerationFixtures.fixture("warehouse-lab-3.0.yaml");
            for (String key : WAREHOUSE.operations().keySet()) {
                assertThat(again.generate(key)).as(key).isEqualTo(WAREHOUSE.generate(key));
            }
        }

        @Test
        void scenarioKeysOfCreateProductAreStable() {
            assertThat(keys(WAREHOUSE.generate("POST /products"))).containsExactly(
                    "happy.minimal", "happy.complete", "auth.none", "auth.invalid", "body.missing",
                    "content-type.unsupported", "body.type:",
                    "body.type:/active",
                    "body.missing:/category", "body.type:/category", "body.enum:/category",
                    "body.type:/dimensions",
                    "body.missing:/dimensions/heightCm", "body.type:/dimensions/heightCm",
                    "body.below-minimum:/dimensions/heightCm", "body.above-maximum:/dimensions/heightCm",
                    "body.at-maximum:/dimensions/heightCm",
                    "body.missing:/dimensions/widthCm", "body.type:/dimensions/widthCm",
                    "body.below-minimum:/dimensions/widthCm", "body.at-minimum:/dimensions/widthCm",
                    "body.above-maximum:/dimensions/widthCm", "body.at-maximum:/dimensions/widthCm",
                    "body.type:/discontinuedAt",
                    "body.missing:/name", "body.type:/name", "body.too-short:/name", "body.at-min-length:/name",
                    "body.too-long:/name", "body.at-max-length:/name",
                    "body.missing:/price", "body.type:/price", "body.below-minimum:/price", "body.above-maximum:/price",
                    "body.at-maximum:/price",
                    "body.missing:/sku", "body.type:/sku",
                    "body.type:/stock", "body.below-minimum:/stock", "body.above-maximum:/stock",
                    "body.at-maximum:/stock",
                    "body.type:/tags", "body.too-many-items:/tags", "body.at-max-items:/tags",
                    "body.type:/tags/0", "body.too-short:/tags/0", "body.at-min-length:/tags/0", "body.too-long:/tags/0",
                    "body.at-max-length:/tags/0");
        }

        @Test
        void sortOrderFollowsGenerationOrderAndMetadataIsComplete() {
            for (String key : WAREHOUSE.operations().keySet()) {
                Output output = WAREHOUSE.generate(key);
                assertThat(output.tests()).extracting(Definition::sortOrder)
                        .containsExactlyElementsOf(IntStream.range(0, output.tests().size()).boxed().toList());
                assertThat(output.tests()).allSatisfy(test -> {
                    assertThat(test.operationKey()).isEqualTo(key);
                    assertThat(test.generatorVersion()).isEqualTo(DeterministicTestGenerator.VERSION);
                    assertThat(test.rationale()).isNotBlank();
                    assertThat(test.specLocation()).isNotBlank();
                    assertThat(test.expectations().statuses()).isNotEmpty();
                    assertThat(test.expectations().summary()).isNotBlank();
                    assertThat(test.dedupeHash()).hasSize(64);
                });
                assertThat(output.tests()).extracting(Definition::scenarioKey).doesNotHaveDuplicates();
            }
        }
    }

    @Nested
    class RequiredFieldsAndLocations {

        @Test
        void onlyRequiredPropertiesGetMissingFieldTests() {
            List<String> keys = keys(WAREHOUSE.generate("POST /products"));

            assertThat(keys).contains("body.missing:/name", "body.missing:/sku", "body.missing:/price",
                    "body.missing:/category", "body.missing:/dimensions/widthCm");
            assertThat(keys).doesNotContain("body.missing:/stock", "body.missing:/tags", "body.missing:/active",
                    "body.missing:/dimensions");
        }

        @Test
        void minimalRequestSendsRequiredFieldsOnly() {
            Definition minimal = test(WAREHOUSE.generate("POST /products"), "happy.minimal");

            assertThat(minimal.request().body().mode()).isEqualTo(BodyMode.JSON);
            assertThat(minimal.request().body().value()).asInstanceOf(MAP).containsOnlyKeys("name", "sku", "price",
                    "category");
            assertThat(minimal.name()).isEqualTo("Valid request (required fields only)");
        }

        @Test
        void requiredHeaderIsTestedOptionalQueryParametersAreNot() {
            Output output = WAREHOUSE.generate("GET /products");

            Definition missing = test(output, "header.missing:X-Request-Id");
            assertThat(missing.category()).isEqualTo(Category.HEADER);
            assertThat(missing.request().headers()).doesNotContainKey("X-Request-Id");
            assertThat(keys(output)).noneMatch(key -> key.startsWith("query.missing:"));
            assertThat(test(output, "happy.minimal").request().query()).isEmpty();
            assertThat(test(output, "happy.minimal").request().headers()).containsOnlyKeys("X-Request-Id");
            assertThat(test(output, "happy.complete").request().query()).containsOnlyKeys("limit", "category", "q");
        }

        @Test
        void pathParametersAreNeverOmittedAndTheReasonIsRecorded() {
            Output output = WAREHOUSE.generate("GET /products/{productId}");

            assertThat(keys(output)).noneMatch(key -> key.contains("missing"));
            assertThat(output.tests()).allSatisfy(test -> assertThat(test.request().pathParams()).containsKey("productId"));
            assertThat(skipped(output, "Missing path parameter 'productId'").reason())
                    .contains("changes the URL to a different route");
            assertThat(test(output, "happy.minimal").request().pathParams()).containsEntry("productId", 42);
        }

        @Test
        void parameterValuesUseTheirSchemaTypes() {
            Output output = WAREHOUSE.generate("GET /products");

            assertThat(test(output, "query.type:limit").request().query()).containsEntry("limit", "not-a-number");
            assertThat(test(output, "query.below-minimum:limit").request().query()).containsEntry("limit", 0L);
            assertThat(test(output, "query.at-maximum:limit").request().query()).containsEntry("limit", 100L);
            // A string parameter cannot receive a "wrong type" value: everything in a URL is text.
            assertThat(keys(output)).doesNotContain("query.type:q", "header.type:X-Request-Id");
        }

        @Test
        void riskLevelFollowsTheMethod() {
            assertThat(WAREHOUSE.generate("GET /products").tests()).extracting(Definition::riskLevel)
                    .containsOnly(RiskLevel.READ_ONLY);
            assertThat(WAREHOUSE.generate("POST /orders").tests()).extracting(Definition::riskLevel)
                    .containsOnly(RiskLevel.MUTATING);
            assertThat(WAREHOUSE.generate("DELETE /products/{productId}").tests()).extracting(Definition::riskLevel)
                    .containsOnly(RiskLevel.DESTRUCTIVE);
        }
    }

    @Nested
    class Bodies {

        @Test
        void operationsWithoutABodyGetNoBodyScenarios() {
            for (String key : List.of("GET /products", "GET /products/{productId}", "DELETE /products/{productId}")) {
                Output output = WAREHOUSE.generate(key);
                assertThat(keys(output)).as(key).noneMatch(scenario -> scenario.startsWith("body.")
                        || scenario.startsWith("content-type"));
                assertThat(output.tests()).allSatisfy(test -> assertThat(test.request().body().mode())
                        .isEqualTo(BodyMode.NONE));
            }
        }

        @Test
        void requiredBodyCanBeMissingAndContentTypeCanBeWrong() {
            Output output = WAREHOUSE.generate("POST /products");

            Definition missing = test(output, "body.missing");
            assertThat(missing.request().body().mode()).isEqualTo(BodyMode.NONE);
            Definition foreign = test(output, "content-type.unsupported");
            assertThat(foreign.request().body().mode()).isEqualTo(BodyMode.RAW);
            assertThat(foreign.request().body().contentType()).isEqualTo("text/plain");
            assertThat((String) foreign.request().body().value()).startsWith("{").contains("\"name\"");
            assertThat(foreign.category()).isEqualTo(Category.CONTENT_TYPE);
        }

        @Test
        void structuredSyntaxSuffixIsAJsonBody() {
            Definition patch = test(WAREHOUSE.generate("PATCH /products/{productId}"), "happy.minimal");

            assertThat(patch.request().body().contentType()).isEqualTo("application/merge-patch+json");
            // minProperties 1 with no required property: one optional property is added.
            assertThat((Map<?, ?>) patch.request().body().value()).hasSize(1);
        }

        @Test
        void oneOfFieldsAreNotMutatedAndTheReasonIsRecorded() {
            Output output = WAREHOUSE.generate("POST /orders");

            assertThat(keys(output)).noneMatch(key -> key.startsWith("body.") && key.contains("/delivery/"));
            assertThat(skipped(output, "Field scenarios inside field 'delivery'").reason()).contains("oneOf/anyOf");
            Map<?, ?> body = (Map<?, ?>) test(output, "happy.minimal").request().body().value();
            assertThat(body.get("delivery")).asInstanceOf(MAP).containsEntry("kind", "address");
        }

        @Test
        void patternConstrainedValuesMatchTheirPattern() {
            Map<?, ?> order = (Map<?, ?>) test(WAREHOUSE.generate("POST /orders"), "happy.complete").request().body().value();
            Map<?, ?> product = (Map<?, ?>) test(WAREHOUSE.generate("POST /products"), "happy.minimal").request().body().value();

            assertThat((String) order.get("couponCode")).matches("[A-Z]{4}-[0-9]{4}");
            assertThat((String) ((Map<?, ?>) order.get("delivery")).get("postalCode")).matches("[0-9]{5}");
            assertThat((String) product.get("sku")).matches("[A-Z]{3}-[0-9]{4}");
        }
    }

    @Nested
    class Expectations {

        @Test
        void documentedStatusesAreExpected() {
            Output create = WAREHOUSE.generate("POST /products");

            assertThat(test(create, "happy.minimal").expectations().statuses()).containsExactly("201");
            assertThat(test(create, "auth.none").expectations().statuses()).containsExactly("401");
            assertThat(test(create, "content-type.unsupported").expectations().statuses()).containsExactly("415");
            assertThat(test(create, "body.missing:/name").expectations().statuses()).containsExactly("400", "422");
            assertThat(test(create, "body.at-max-length:/name").expectations().statuses()).containsExactly("201");
        }

        @Test
        void pathParameterErrorsMayBeNotFound() {
            assertThat(test(WAREHOUSE.generate("GET /products/{productId}"), "path.type:productId")
                    .expectations().statuses()).containsExactly("404");
            assertThat(test(WAREHOUSE.generate("PATCH /products/{productId}"), "path.type:productId")
                    .expectations().statuses()).containsExactly("400", "404");
        }

        @Test
        void undocumentedAuthenticationFailureFallsBackToHttpSemantics() {
            var expectations = test(WAREHOUSE.generate("POST /orders"), "auth.none").expectations();

            assertThat(expectations.statuses()).containsExactly("401", "403");
            assertThat(expectations.summary()).contains("documents neither");
        }

        @Test
        void responseSchemaChecksComeFromTheDocumentedResponses() {
            var success = test(WAREHOUSE.generate("POST /products"), "happy.minimal").expectations();
            var error = test(WAREHOUSE.generate("POST /products"), "body.missing:/name").expectations();
            var deleted = test(WAREHOUSE.generate("DELETE /products/{productId}"), "happy.minimal").expectations();

            assertThat(success.validateResponseSchema()).isTrue();
            assertThat(success.responseChecks()).extracting(check -> check.status() + " " + check.schemaName())
                    .containsExactly("201 Product");
            assertThat(success.summary()).contains("schema Product (201, application/json)");
            assertThat(error.responseChecks()).extracting(check -> check.status() + " " + check.schemaName())
                    .containsExactly("400 Error", "422 Error");
            assertThat(deleted.statuses()).containsExactly("204");
            assertThat(deleted.validateResponseSchema()).isFalse();
        }

        @Test
        void publicOperationsHaveNoAuthenticationTests() {
            Output output = WAREHOUSE.generate("GET /health");

            assertThat(keys(output)).containsExactly("happy.minimal");
            assertThat(skipped(output, "Authentication scenarios").reason()).contains("no security requirement");
        }
    }

    @Nested
    class EdgeCases {

        private Output generate(String document, String operationKey) {
            return GenerationFixtures.document(document).generate(operationKey);
        }

        private static String api(String paths, String components) {
            return """
                    {"openapi":"3.0.3","info":{"title":"Edge","version":"1"},"paths":%s,"components":%s}"""
                    .formatted(paths, components);
        }

        @Test
        void cookieAndReservedHeaderParametersAreSkippedWithReasons() {
            Output output = generate(api("""
                    {"/x":{"get":{"parameters":[
                       {"name":"session","in":"cookie","schema":{"type":"string"}},
                       {"name":"Accept","in":"header","required":true,"schema":{"type":"string"}}],
                     "responses":{"200":{"description":"ok"}}}}}""", "{}"), "GET /x");

            assertThat(skipped(output, "Scenarios for cookie parameter 'session'").reason()).contains("cookies");
            assertThat(skipped(output, "Scenarios for header parameter 'Accept'").reason()).contains("ignores");
            assertThat(test(output, "happy.minimal").request().headers()).isEmpty();
        }

        @Test
        void requiredNonJsonBodyOnlyGetsTheMissingBodyScenario() {
            Output output = generate(api("""
                    {"/upload":{"post":{"requestBody":{"required":true,
                       "content":{"application/octet-stream":{"schema":{"type":"string","format":"binary"}}}},
                     "responses":{"201":{"description":"stored"}}}}}""", "{}"), "POST /upload");

            assertThat(keys(output)).containsExactly("body.missing");
            assertThat(skipped(output, "All other scenarios").reason()).contains("JSON bodies only");
        }

        @Test
        void optionalBodyIsLeftOutOfTheMinimalRequest() {
            Output output = generate(api("""
                    {"/notes":{"post":{"requestBody":{"content":{"application/json":{"schema":
                       {"type":"object","properties":{"text":{"type":"string"}}}}}},
                     "responses":{"200":{"description":"ok"}}}}}""", "{}"), "POST /notes");

            assertThat(test(output, "happy.minimal").request().body().mode()).isEqualTo(BodyMode.NONE);
            assertThat(test(output, "happy.minimal").rationale()).contains("no request body (it is optional)");
            assertThat(test(output, "happy.complete").request().body().value()).isEqualTo(Map.of("text", "sample"));
            assertThat(keys(output)).doesNotContain("body.missing");
        }

        @Test
        void anyMediaTypeSkipsTheContentTypeScenario() {
            Output output = generate(api("""
                    {"/any":{"put":{"requestBody":{"required":true,"content":{"*/*":{"schema":{"type":"object"}}}},
                     "responses":{"204":{"description":"ok"}}}}}""", "{}"), "PUT /any");

            assertThat(test(output, "happy.minimal").request().body().contentType()).isEqualTo("application/json");
            assertThat(skipped(output, "Unsupported content type").reason()).contains("*/*");
        }

        @Test
        void optionalAuthenticationAndUnimitableSchemes() {
            String components = """
                    {"securitySchemes":{"key":{"type":"apiKey","in":"header","name":"X-Key"},
                                        "mtls":{"type":"mutualTLS"}}}""";
            // mutualTLS exists since OpenAPI 3.1.
            Output optional = generate(api("""
                    {"/x":{"get":{"security":[{"key":[]},{}],"responses":{"200":{"description":"ok"}}}}}""", components)
                    .replace("3.0.3", "3.1.0"), "GET /x");
            Output mtls = generate(api("""
                    {"/x":{"get":{"security":[{"mtls":[]}],"responses":{"200":{"description":"ok"}}}}}""", components)
                    .replace("3.0.3", "3.1.0"), "GET /x");

            assertThat(keys(optional)).containsExactly("happy.minimal");
            assertThat(skipped(optional, "Authentication scenarios").reason()).contains("optional");
            assertThat(keys(mtls)).containsExactly("happy.minimal", "auth.none");
            assertThat(test(mtls, "auth.none").request().auth()).isEqualTo(AuthMode.NONE);
            assertThat(skipped(mtls, "Request with invalid credentials").reason()).contains("cannot be imitated");
        }

        @Test
        void mutationsThatWouldBreakTwoConstraintsAreSkipped() {
            Output output = generate(api("""
                    {"/codes":{"post":{"requestBody":{"required":true,"content":{"application/json":{"schema":
                       {"type":"object","required":["code"],"properties":{"code":
                         {"type":"string","pattern":"^[A-Z]{3}$","minLength":3,"maxLength":3}}}}}},
                     "responses":{"201":{"description":"ok"},"400":{"description":"bad"}}}}}""", "{}"), "POST /codes");

            assertThat(keys(output)).contains("body.missing:/code", "body.type:/code");
            assertThat(keys(output)).doesNotContain("body.too-short:/code", "body.too-long:/code");
            assertThat(skipped(output, "Field 'code' shorter than minLength 3").reason())
                    .contains("also break another constraint").contains("pattern");
        }

        @Test
        void hugeLimitsAreNotTestedWithOversizedValues() {
            Output output = generate(api("""
                    {"/x":{"post":{"requestBody":{"required":true,"content":{"application/json":{"schema":
                       {"type":"object","properties":{
                          "text":{"type":"string","maxLength":20000},
                          "list":{"type":"array","maxItems":500,"items":{"type":"integer"}}}}}}},
                     "responses":{"200":{"description":"ok"}}}}}""", "{}"), "POST /x");

            assertThat(skipped(output, "Field 'text' longer than maxLength 20000").reason()).contains("10,000 characters");
            assertThat(skipped(output, "Field 'list' with more than maxItems 500").reason()).contains("100 items");
        }

        @Test
        void unsatisfiableRequiredParameterSkipsTheOperation() {
            Output output = generate(api("""
                    {"/x":{"get":{"parameters":[{"name":"n","in":"query","required":true,
                       "schema":{"type":"integer","minimum":10,"maximum":1}}],
                     "responses":{"200":{"description":"ok"}}}}}""", "{}"), "GET /x");

            assertThat(output.tests()).isEmpty();
            assertThat(skipped(output, "All scenarios").reason()).contains("required query parameter 'n'");
        }

        @Test
        void undocumentedOutcomesFallBackToStatusClasses() {
            Output output = generate(api("""
                    {"/x":{"post":{"requestBody":{"required":true,"content":{"application/json":{"schema":
                       {"type":"object","required":["a"],"properties":{"a":{"type":"integer"}}}}}},
                     "responses":{"default":{"description":"anything"}}}}}""", "{}"), "POST /x");

            assertThat(test(output, "happy.minimal").expectations().statuses()).containsExactly("2XX");
            assertThat(test(output, "body.missing:/a").expectations().statuses()).containsExactly("4XX");
        }

        @Test
        void testsPerOperationAreCappedAndTheOverflowIsReported() {
            StringBuilder properties = new StringBuilder();
            for (int index = 0; index < 40; index++) {
                properties.append(index == 0 ? "" : ",")
                        .append("\"f%02d\":{\"type\":\"integer\",\"minimum\":0,\"maximum\":9}".formatted(index));
            }
            Output output = generate(api("""
                    {"/big":{"post":{"requestBody":{"required":true,"content":{"application/json":{"schema":
                       {"type":"object","properties":{%s}}}}},
                     "responses":{"200":{"description":"ok"}}}}}""".formatted(properties), "{}"), "POST /big");

            assertThat(output.tests()).hasSize(DeterministicTestGenerator.MAX_TESTS_PER_OPERATION);
            assertThat(output.skipped()).last().satisfies(skip -> {
                assertThat(skip.scenario()).endsWith("further scenarios");
                assertThat(skip.reason()).contains("limit of 100 tests");
            });
        }

        @Test
        void openApi31TypeArraysAndNumericExclusiveBounds() {
            Output output = generate("""
                    {"openapi":"3.1.0","info":{"title":"E","version":"1"},"paths":{"/m":{"post":{
                      "requestBody":{"required":true,"content":{"application/json":{"schema":{"type":"object",
                        "required":["rate"],"properties":{
                          "rate":{"type":["number","null"],"exclusiveMinimum":0,"maximum":1},
                          "mode":{"const":"fast"}}}}}},
                      "responses":{"200":{"description":"ok"}}}}}}""", "POST /m");

            Map<?, ?> body = (Map<?, ?>) test(output, "happy.complete").request().body().value();
            assertThat(body.get("mode")).isEqualTo("fast");
            assertThat(test(output, "body.below-minimum:/rate").request().body().value()).isEqualTo(Map.of("rate", 0L,
                    "mode", "fast"));
            assertThat(test(output, "body.below-minimum:/rate").name()).isEqualTo("Field 'rate' equal to exclusive minimum 0");
            assertThat(keys(output)).contains("body.type:/rate").doesNotContain("body.at-minimum:/rate");
        }
    }

    /** Every generated request is what its test claims: valid when success is expected, broken once otherwise. */
    @ParameterizedTest
    @ValueSource(strings = {"warehouse-lab-3.0.yaml", "petstore3-official.yaml", "pets-3.0.json", "inventory-3.0.yaml",
            "library-3.1.json"})
    void everyGeneratedRequestMatchesItsClaim(String fixture) {
        GenerationFixtures.Imported imported = GenerationFixtures.fixture(fixture);
        SchemaResolver resolver = new SchemaResolver(imported.specification().componentSchemas());
        SchemaValidator validator = Schemas.validator();
        for (Map.Entry<String, Operation> entry : imported.operations().entrySet()) {
            Operation operation = entry.getValue();
            Schema bodySchema = jsonBodySchema(operation, resolver);
            for (Definition test : imported.generate(entry.getKey()).tests()) {
                boolean success = test.expectations().statuses().stream().allMatch(status -> status.startsWith("2"));
                String scope = test.scenarioKey().substring(0, test.scenarioKey().indexOf('.'));
                String description = entry.getKey() + " " + test.scenarioKey();
                if (test.request().body().mode() == BodyMode.JSON && bodySchema != null
                        && (success || scope.equals("body"))) {
                    SchemaValidator.Result result = validator.validate(test.request().body().value(), bodySchema);
                    if (success) {
                        assertThat(result.violations()).as(description).isEmpty();
                    } else {
                        assertThat(result.violations()).as(description).isNotEmpty();
                        assertThat(result.violations()).as(description).extracting(SchemaValidator.Violation::pointer)
                                .containsOnly(result.violations().getFirst().pointer());
                    }
                }
                if (Set.of("query", "path").contains(scope) && !test.scenarioKey().contains(".missing:")) {
                    String name = test.scenarioKey().substring(test.scenarioKey().indexOf(':') + 1);
                    Object value = scope.equals("query") ? test.request().query().get(name)
                            : test.request().pathParams().get(name);
                    Schema parameterSchema = parameterSchema(operation, scope, name, resolver);
                    assertThat(validator.validate(value, parameterSchema).valid()).as(description).isEqualTo(success);
                }
            }
        }
    }

    private static Schema jsonBodySchema(Operation operation, SchemaResolver resolver) {
        if (operation.requestBody() == null || !(operation.requestBody().get("content") instanceof Map<?, ?> content)) {
            return null;
        }
        for (Map.Entry<?, ?> media : content.entrySet()) {
            if (MediaTypes.isJson(String.valueOf(media.getKey())) && media.getValue() instanceof Map<?, ?> mediaObject) {
                return resolver.resolve(mediaObject.get("schema"), "#/body");
            }
        }
        return null;
    }

    private static Schema parameterSchema(Operation operation, String in, String name, SchemaResolver resolver) {
        return operation.parameters().stream()
                .filter(parameter -> in.equals(parameter.get("in")) && name.equals(parameter.get("name")))
                .map(parameter -> resolver.resolve(parameter.get("schema"), "#/parameter"))
                .findFirst().orElseThrow();
    }
}
