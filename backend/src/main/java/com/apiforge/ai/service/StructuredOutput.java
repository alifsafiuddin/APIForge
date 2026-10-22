package com.apiforge.ai.service;

import java.util.List;
import java.util.Map;

import com.apiforge.ai.client.AiFailure;
import com.apiforge.ai.client.AiFailure.Kind;
import com.apiforge.schema.GuardedPattern;
import com.apiforge.schema.SchemaResolver;
import com.apiforge.schema.SchemaValidator;

import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.json.JsonMapper;

/**
 * The strict output contracts of the model. The same JSON schema is sent to Ollama (structured
 * outputs) and used to validate the answer, before the answer is bound to records with unknown
 * properties rejected. Nothing in an answer is executed: suggestions are request data that still
 * goes through {@code TestDefinitionValidator} and human review.
 */
final class StructuredOutput {

    private static final JsonMapper JSON = JsonMapper.builder()
            .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
            .build();

    static final String CATEGORIES = "\"HAPPY_PATH\",\"MISSING_FIELD\",\"INVALID_TYPE\",\"BOUNDARY_VALUE\",\"ENUM_VALUE\","
            + "\"STRING_LENGTH\",\"ARRAY_LENGTH\",\"QUERY_PARAMETER\",\"PATH_PARAMETER\",\"HEADER\",\"CONTENT_TYPE\","
            + "\"RESPONSE_VALIDATION\",\"AUTH\",\"CUSTOM\"";

    static final Map<String, Object> SUGGESTIONS = schema("""
            {"type":"object","additionalProperties":false,"required":["suggestions"],
             "properties":{"suggestions":{"type":"array","maxItems":10,"items":{
               "type":"object","additionalProperties":false,
               "required":["name","category","rationale","request","expectedStatuses","duplicateOf","duplicateReason"],
               "properties":{
                 "name":{"type":"string","minLength":5,"maxLength":200},
                 "category":{"type":"string","enum":[%s]},
                 "rationale":{"type":"string","minLength":10,"maxLength":1500},
                 "request":{"type":"object","additionalProperties":false,
                   "required":["pathParams","query","headers","body","auth"],
                   "properties":{
                     "pathParams":{"type":"object"},
                     "query":{"type":"object"},
                     "headers":{"type":"object","additionalProperties":{"type":"string"}},
                     "body":{"type":"object","additionalProperties":false,"required":["mode"],
                             "properties":{"mode":{"type":"string","enum":["NONE","JSON"]},"value":{}}},
                     "auth":{"type":"string","enum":["ENVIRONMENT","NONE","INVALID"]}}},
                 "expectedStatuses":{"type":"array","minItems":1,"maxItems":4,
                   "items":{"type":"string","pattern":"^[1-5]([0-9]{2}|XX)$"}},
                 "duplicateOf":{"type":["string","null"],"maxLength":300},
                 "duplicateReason":{"type":["string","null"],"maxLength":800}}}}}}""".formatted(CATEGORIES));

    static final Map<String, Object> EXPLANATION = schema("""
            {"type":"object","additionalProperties":false,
             "required":["summary","observedFacts","likelyCauses","debuggingSteps","suggestedChanges"],
             "properties":{
               "summary":{"type":"string","minLength":10,"maxLength":800},
               "observedFacts":{"type":"array","minItems":1,"maxItems":10,"items":{
                 "type":"object","additionalProperties":false,"required":["statement","source"],
                 "properties":{"statement":{"type":"string","minLength":3,"maxLength":500},
                               "source":{"type":"string","enum":["ASSERTION","REQUEST","RESPONSE","SCHEMA","TEST"]}}}},
               "likelyCauses":{"type":"array","minItems":1,"maxItems":5,"items":{
                 "type":"object","additionalProperties":false,"required":["hypothesis","confidence","evidence"],
                 "properties":{"hypothesis":{"type":"string","minLength":3,"maxLength":600},
                               "confidence":{"type":"string","enum":["LOW","MEDIUM","HIGH"]},
                               "evidence":{"type":"array","maxItems":6,"items":{"type":"string","maxLength":400}}}}},
               "debuggingSteps":{"type":"array","minItems":1,"maxItems":8,"items":{"type":"string","minLength":3,"maxLength":400}},
               "suggestedChanges":{"type":"array","maxItems":6,"items":{
                 "type":"object","additionalProperties":false,"required":["target","change"],
                 "properties":{"target":{"type":"string","enum":["TEST","SPECIFICATION","API_IMPLEMENTATION"]},
                               "change":{"type":"string","minLength":3,"maxLength":600}}}}}}""");

    // --- Records the answers are bound to ---

    record SuggestionReply(List<Suggestion> suggestions) {
    }

    record Suggestion(String name, String category, String rationale, SuggestedRequest request,
            List<String> expectedStatuses, String duplicateOf, String duplicateReason) {
    }

    record SuggestedRequest(Map<String, Object> pathParams, Map<String, Object> query, Map<String, String> headers,
            SuggestedBody body, String auth) {
    }

    record SuggestedBody(String mode, Object value) {
    }

    record ExplanationReply(String summary, List<Fact> observedFacts, List<Cause> likelyCauses, List<String> debuggingSteps,
            List<Change> suggestedChanges) {
    }

    record Fact(String statement, String source) {
    }

    record Cause(String hypothesis, String confidence, List<String> evidence) {
    }

    record Change(String target, String change) {
    }

    private StructuredOutput() {
    }

    private static Map<String, Object> schema(String json) {
        return JSON.readValue(json, new TypeReference<>() {
        });
    }

    /**
     * The answer as {@code type}, or INVALID_OUTPUT naming the first problems (never the content).
     * A single surrounding Markdown code fence is tolerated; anything else must be the JSON object.
     */
    static <T> T read(String content, Map<String, Object> schema, Class<T> type) {
        if (content == null || content.isBlank()) {
            throw new AiFailure(Kind.INVALID_OUTPUT, "The model returned an empty answer.");
        }
        String text = content.strip();
        if (text.startsWith("```") && text.endsWith("```") && text.length() > 6) {
            text = text.substring(text.indexOf('\n') + 1, text.length() - 3).strip();
        }
        Object value;
        try {
            value = JSON.readValue(text, Object.class);
        } catch (RuntimeException ex) {
            throw new AiFailure(Kind.INVALID_OUTPUT, "The answer is not valid JSON.");
        }
        SchemaValidator.Result result = new SchemaValidator(new GuardedPattern())
                .validate(value, new SchemaResolver(Map.of()).resolve(schema, "#/answer"), SchemaValidator.Direction.RESPONSE);
        if (!result.valid()) {
            List<String> problems = result.violations().stream().limit(3)
                    .map(violation -> violation.jsonPath() + " " + violation.message()).toList();
            throw new AiFailure(Kind.INVALID_OUTPUT, "The answer does not match the required structure: "
                    + String.join("; ", problems) + (result.violations().size() > 3 ? "; …" : "") + ".");
        }
        try {
            return JSON.convertValue(value, type);
        } catch (RuntimeException ex) {
            throw new AiFailure(Kind.INVALID_OUTPUT, "The answer does not match the required structure.");
        }
    }
}
