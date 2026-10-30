package com.apiforge.validation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

import java.util.Collections;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import com.apiforge.schema.Schemas;
import com.apiforge.validation.Assertion.Result;
import com.apiforge.validation.ContractValidator.ActualResponse;
import com.apiforge.validation.ContractValidator.DocumentedOperation;

class ContractValidatorTest {

    private static final Map<String, Object> COMPONENTS = Schemas.object("""
            {"Product":{"type":"object","required":["id","name","price","createdAt"],"additionalProperties":false,
               "properties":{"id":{"type":"integer","format":"int64","readOnly":true},
                             "name":{"type":"string","minLength":3},
                             "price":{"type":"number","exclusiveMinimum":0,"multipleOf":0.01},
                             "status":{"type":"string","enum":["available","sold"]},
                             "tags":{"type":"array","maxItems":2,"items":{"type":"string"}},
                             "dimensions":{"$ref":"#/components/schemas/Dimensions"},
                             "createdAt":{"type":"string","format":"date-time"},
                             "secret":{"type":"string","writeOnly":true},
                             "note":{"type":["string","null"]}}},
             "Dimensions":{"type":"object","required":["widthCm"],"properties":{"widthCm":{"type":"number","minimum":0.1}}},
             "Error":{"type":"object","required":["code","message"],"properties":{"code":{"type":"string"},"message":{"type":"string"}}}}""");

    private static final DocumentedOperation GET_PRODUCT = new DocumentedOperation("GET", "/products/{id}", List.of(
            Map.of("status", "200", "content", Map.of("application/json", Map.of("schema",
                    Map.of("$ref", "#/components/schemas/Product")))),
            Map.of("status", "4XX", "content", Map.of("application/problem+json", Map.of("schema",
                    Map.of("$ref", "#/components/schemas/Error")))),
            Map.of("status", "204", "description", "No content"),
            Map.of("status", "202", "content", Map.of("text/plain", Map.of("schema", Map.of("type", "string")))),
            Map.of("status", "203", "content", Map.of("application/json", Map.of()))));

    private static final String VALID = """
            {"id":7,"name":"Drill","price":129.99,"status":"available","tags":["a"],"dimensions":{"widthCm":10},
             "createdAt":"2026-10-21T10:00:00Z","note":null}""";

    private final ContractValidator validator = new ContractValidator(COMPONENTS);

    private List<Assertion> validate(int status, String contentType, String body) {
        return validator.validate(GET_PRODUCT, new ActualResponse(status, contentType, body,
                body == null ? 0 : body.length(), false), true);
    }

    private static List<String> summary(List<Assertion> assertions) {
        return assertions.stream().map(assertion -> assertion.check() + "=" + assertion.result()).toList();
    }

    private static List<Assertion> failures(List<Assertion> assertions) {
        return assertions.stream().filter(assertion -> assertion.result() == Result.FAIL).toList();
    }

    @Nested
    class StatusCodes {

        @Test
        void documentedStatusWithValidBodyPassesEveryCheck() {
            assertThat(summary(validate(200, "application/json; charset=utf-8", VALID))).containsExactly(
                    "STATUS_DOCUMENTED=PASS", "CONTENT_TYPE=PASS", "JSON_SYNTAX=PASS", "BODY_SCHEMA=PASS");
        }

        @ParameterizedTest
        @CsvSource({"404, 4XX", "422, 4XX"})
        void statusClassesCoverCodes(int status, String key) {
            List<Assertion> assertions = validate(status, "application/problem+json", "{\"code\":\"X\",\"message\":\"m\"}");

            assertThat(assertions.getFirst().message()).contains("covered by the documented '" + key + "' response");
            assertThat(summary(assertions)).contains("BODY_SCHEMA=PASS");
        }

        @Test
        void undocumentedStatusFailsAndTheBodyIsNotValidated() {
            List<Assertion> assertions = validate(503, "application/json", "{\"oops\":true}");

            assertThat(summary(assertions)).containsExactly("STATUS_DOCUMENTED=FAIL", "JSON_SYNTAX=PASS", "BODY_SCHEMA=SKIPPED");
            assertThat(assertions.getFirst().expected()).isEqualTo("one of 200, 4XX, 204, 202, 203");
            assertThat(assertions.getFirst().actual()).isEqualTo("503");
            assertThat(assertions.getLast().message()).contains("no documented response for status 503");
        }

        @Test
        void defaultCoversEverythingElse() {
            DocumentedOperation withDefault = new DocumentedOperation("GET", "/x", List.of(Map.of("status", "default")));

            List<Assertion> assertions = validator.validate(withDefault, new ActualResponse(500, null, null, 0, false), true);

            assertThat(assertions.getFirst().result()).isEqualTo(Result.PASS);
            assertThat(assertions.getFirst().message()).contains("'default'");
        }

        @Test
        void noMatchingOperationSkipsTheContractButStillChecksJson() {
            List<Assertion> assertions = validator.validate(null, new ActualResponse(200, "application/json", "{bad", 4, false),
                    true);

            assertThat(summary(assertions)).containsExactly("STATUS_DOCUMENTED=SKIPPED", "JSON_SYNTAX=FAIL");
        }
    }

    @Nested
    class MediaTypesAndBodies {

        @Test
        void undocumentedContentTypeFails() {
            List<Assertion> assertions = validate(200, "text/html", "<html>");

            assertThat(summary(assertions)).containsExactly("STATUS_DOCUMENTED=PASS", "CONTENT_TYPE=FAIL", "BODY_SCHEMA=SKIPPED");
            assertThat(assertions.get(1).expected()).isEqualTo("application/json");
            assertThat(assertions.get(1).actual()).isEqualTo("text/html");
        }

        @Test
        void missingContentTypeFails() {
            assertThat(failures(validate(200, null, VALID))).singleElement()
                    .satisfies(failure -> assertThat(failure.actual()).isEqualTo("no Content-Type"));
        }

        @Test
        void mediaTypeRangesMatch() {
            DocumentedOperation ranged = new DocumentedOperation("GET", "/x", List.of(Map.of("status", "200", "content",
                    Map.of("application/*", Map.of("schema", Map.of("type", "object"))))));

            assertThat(summary(validator.validate(ranged, new ActualResponse(200, "application/hal+json", "{}", 2, false), true)))
                    .containsExactly("STATUS_DOCUMENTED=PASS", "CONTENT_TYPE=PASS", "JSON_SYNTAX=PASS", "BODY_SCHEMA=PASS");
        }

        @Test
        void responsesDocumentedWithoutBodyOrSchemaAreSkippedExplicitly() {
            assertThat(summary(validate(204, null, null))).containsExactly("STATUS_DOCUMENTED=PASS", "BODY_SCHEMA=SKIPPED");
            List<Assertion> unexpectedBody = validate(204, "application/json", "{}");
            assertThat(summary(unexpectedBody)).containsExactly("STATUS_DOCUMENTED=PASS", "CONTENT_TYPE=SKIPPED",
                    "JSON_SYNTAX=PASS", "BODY_SCHEMA=SKIPPED");
            assertThat(unexpectedBody.getLast().message()).contains("no body or schema").contains("application/json, 2 bytes");
            List<Assertion> noSchema = validate(203, "application/json", "{}");
            assertThat(noSchema.getLast().result()).isEqualTo(Result.SKIPPED);
            assertThat(noSchema.getLast().message()).contains("has no schema");
        }

        @Test
        void aMissingDocumentedBodyFails() {
            List<Assertion> assertions = validate(200, null, null);

            assertThat(summary(assertions)).containsExactly("STATUS_DOCUMENTED=PASS", "BODY_SCHEMA=FAIL");
            assertThat(assertions.getLast().actual()).isEqualTo("empty body");
        }

        @Test
        void malformedTruncatedAndNonJsonBodiesAreNeverPasses() {
            assertThat(summary(validate(200, "application/json", "{\"id\": 1,"))).containsExactly("STATUS_DOCUMENTED=PASS",
                    "CONTENT_TYPE=PASS", "JSON_SYNTAX=FAIL", "BODY_SCHEMA=SKIPPED");
            List<Assertion> truncated = validator.validate(GET_PRODUCT, new ActualResponse(200, "application/json", "{\"id\":",
                    999_999, true), true);
            assertThat(summary(truncated)).containsExactly("STATUS_DOCUMENTED=PASS", "CONTENT_TYPE=PASS",
                    "JSON_SYNTAX=INCONCLUSIVE", "BODY_SCHEMA=INCONCLUSIVE");
            assertThat(summary(validate(202, "text/plain", "hello"))).containsExactly("STATUS_DOCUMENTED=PASS",
                    "CONTENT_TYPE=PASS", "BODY_SCHEMA=INCONCLUSIVE");
        }

        @Test
        void schemaValidationCanBeTurnedOffPerTest() {
            List<Assertion> assertions = validator.validate(GET_PRODUCT, new ActualResponse(200, "application/json", "{}", 2,
                    false), false);

            assertThat(assertions.getLast().result()).isEqualTo(Result.SKIPPED);
            assertThat(assertions.getLast().message()).isEqualTo("Schema validation is turned off for this test.");
        }
    }

    @Nested
    class BodySchemas {

        @Test
        void everyViolationIsReportedWithItsPathExpectedAndActualValue() {
            String body = """
                    {"id":7,"name":"Dr","price":"129.99","status":"lost","tags":["a","b","c"],
                     "dimensions":{"widthCm":-1},"createdAt":"yesterday","secret":"s","extra":1}""";

            List<Assertion> failures = failures(validate(200, "application/json", body));

            assertThat(failures).extracting(Assertion::path, Assertion::keyword, Assertion::expected, Assertion::actual)
                    .containsExactlyInAnyOrder(
                            tuple("$.name", "minLength", "at least 3 characters", "2 characters"),
                            tuple("$.price", "type", "number", "string \"129.99\""),
                            tuple("$.status", "enum", "one of available, sold", "string \"lost\""),
                            tuple("$.tags", "maxItems", "at most 2 items", "3 items"),
                            tuple("$.dimensions.widthCm", "minimum", "≥ 0.1", "-1"),
                            tuple("$.createdAt", "format", "format date-time", "string \"yesterday\""),
                            tuple("$.secret", "writeOnly", "not returned (writeOnly)", "string \"s\""),
                            tuple("$.extra", "additionalProperties", "only declared properties",
                                    "property 'extra'"));
            assertThat(failures).allSatisfy(failure -> assertThat(failure.check()).isEqualTo("BODY_SCHEMA"));
            assertThat(failures.stream().filter(failure -> "$.price".equals(failure.path())).findFirst().orElseThrow().message())
                    .isEqualTo("$.price expected number but found string.");
        }

        @Test
        void readOnlyPropertiesAreRequiredInResponsesAndArraysReportItemPaths() {
            DocumentedOperation list = new DocumentedOperation("GET", "/products", List.of(Map.of("status", "200", "content",
                    Map.of("application/json", Map.of("schema", Map.of("type", "array", "items",
                            Map.of("$ref", "#/components/schemas/Product")))))));

            List<Assertion> failures = failures(validator.validate(list, new ActualResponse(200, "application/json",
                    "[" + VALID + ", {\"name\":\"Saw\",\"price\":5,\"createdAt\":\"2026-10-21T10:00:00Z\"}]", 300, false), true));

            assertThat(failures).extracting(Assertion::path, Assertion::keyword)
                    .containsExactly(tuple("$[1].id", "required"));
            assertThat(failures.getFirst().actual()).isEqualTo("missing");
        }

        @Test
        void exactDecimalsAreUsedForMultipleOf() {
            assertThat(failures(validate(200, "application/json", VALID.replace("129.99", "0.3")))).isEmpty();
            assertThat(failures(validate(200, "application/json", VALID.replace("129.99", "10.005"))))
                    .extracting(Assertion::keyword).containsExactly("multipleOf");
        }

        @Test
        void unsupportedKeywordsMakeTheResultInconclusive() {
            DocumentedOperation conditional = new DocumentedOperation("GET", "/x", List.of(Map.of("status", "200", "content",
                    Map.of("application/json", Map.of("schema", Map.of("type", "object", "if", Map.of(), "then", Map.of()))))));

            List<Assertion> assertions = validator.validate(conditional, new ActualResponse(200, "application/json", "{}", 2,
                    false), true);

            assertThat(assertions.getLast().result()).isEqualTo(Result.INCONCLUSIVE);
            assertThat(assertions.getLast().message()).isEqualTo("Could not evaluate: if, then.");
        }

        @Test
        void findingsAreCapped() {
            DocumentedOperation many = new DocumentedOperation("GET", "/x", List.of(Map.of("status", "200", "content",
                    Map.of("application/json", Map.of("schema", Map.of("type", "array", "items", Map.of("type", "string")))))));
            String body = "[" + String.join(",", Collections.nCopies(60, "1")) + "]";

            List<Assertion> failures = failures(validator.validate(many, new ActualResponse(200, "application/json", body,
                    body.length(), false), true));

            assertThat(failures).hasSize(ContractValidator.MAX_BODY_FINDINGS + 1);
            assertThat(failures.getLast().message()).isEqualTo("10 further violations were not listed.");
        }
    }

    @Nested
    class OperationMatching {

        private final List<DocumentedOperation> operations = List.of(
                new DocumentedOperation("GET", "/products/{id}", List.of()),
                new DocumentedOperation("GET", "/products/featured", List.of()),
                new DocumentedOperation("DELETE", "/products/{id}", List.of()),
                new DocumentedOperation("GET", "/orders/{orderId}/lines/{line}", List.of()));

        @ParameterizedTest
        @CsvSource(textBlock = """
                GET,    /products/42,             GET /products/{id}
                GET,    /products/featured,       GET /products/featured
                DELETE, /products/7,              DELETE /products/{id}
                GET,    /orders/a1/lines/3,       GET /orders/{orderId}/lines/{line}
                """)
        void concretePathsMatchTheirTemplates(String method, String path, String expected) {
            assertThat(ContractValidator.match(method, path, operations)).map(DocumentedOperation::operationKey).hasValue(expected);
        }

        @ParameterizedTest
        @CsvSource({"GET, /products", "GET, /products/", "POST, /products/42", "GET, /products/42/extra"})
        void otherRequestsMatchNothing(String method, String path) {
            assertThat(ContractValidator.match(method, path, operations)).isEmpty();
        }
    }
}
