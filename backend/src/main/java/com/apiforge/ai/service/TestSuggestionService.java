package com.apiforge.ai.service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.apiforge.ai.AiProperties;
import com.apiforge.ai.client.AiFailure;
import com.apiforge.ai.service.AiViews.Discarded;
import com.apiforge.ai.service.AiViews.PossibleDuplicate;
import com.apiforge.ai.service.AiViews.SuggestionReport;
import com.apiforge.ai.service.StructuredOutput.Suggestion;
import com.apiforge.ai.service.StructuredOutput.SuggestionReply;
import com.apiforge.audit.domain.AuditEventType;
import com.apiforge.common.error.ApiException;
import com.apiforge.common.error.ErrorCode;
import com.apiforge.generation.engine.DeterministicTestGenerator;
import com.apiforge.project.domain.Project;
import com.apiforge.project.service.ProjectAccessService;
import com.apiforge.schema.MediaTypes;
import com.apiforge.spec.domain.ApiOperation;
import com.apiforge.spec.domain.SpecificationVersion;
import com.apiforge.spec.repository.ApiOperationRepository;
import com.apiforge.spec.repository.SpecificationVersionRepository;
import com.apiforge.testcase.domain.RequestTemplate.AuthMode;
import com.apiforge.testcase.domain.RequestTemplate.BodyMode;
import com.apiforge.testcase.domain.TestCase;
import com.apiforge.testcase.domain.TestFingerprint;
import com.apiforge.testcase.domain.TestSuite;
import com.apiforge.testcase.repository.TestCaseRepository;
import com.apiforge.testcase.repository.TestSuiteRepository;
import com.apiforge.testcase.service.TestCaseViews.TestCaseDetail;
import com.apiforge.testcase.service.TestDefinitionValidator;
import com.apiforge.testcase.service.TestDefinitionValidator.BodyInput;
import com.apiforge.testcase.service.TestDefinitionValidator.ExpectationsInput;
import com.apiforge.testcase.service.TestDefinitionValidator.RequestInput;

import tools.jackson.databind.json.JsonMapper;

/**
 * Asks the local model for test scenarios the existing tests do not cover. Every suggestion is
 * validated like a user's edit, compared with the existing tests, and stored as an AI suggestion
 * pending review: it cannot run until a person approves it, and nothing the model returns is
 * executed as code. The database is not touched while the model works.
 */
@Service
public class TestSuggestionService {

    static final String PROMPT_VERSION = "ai-suggest-1";
    static final String SUITE_NAME = "AI suggestions";
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final int SCHEMA_DEPTH = 3;
    private static final int MAX_EXISTING = 150;

    static final String SYSTEM = """
            You design additional API tests for one operation of an OpenAPI contract.
            Answer with JSON only, following the provided JSON schema exactly.

            Rules:
            - Propose at most %d scenarios that the existing tests do not already cover. Prefer the gaps listed under \
            generatorCouldNotBuild, combinations of constraints, edge cases of formats and patterns, and documented \
            responses that no existing test expects.
            - Each scenario checks one behaviour; explain why it matters in "rationale".
            - A request is data only: pathParams, query, headers, body and auth. Never write code, scripts or expressions.
            - Use the parameter and property names of the contract. Every path parameter must be present.
            - Only rely on constraints the schema states (required, type, minimum, maximum, minLength, maxLength, \
            pattern, enum, format). Do not invent limits the contract does not document.
            - Never put credentials in headers or values (no Authorization, Cookie or API keys); the environment adds \
            them. To test authentication use auth "NONE" (no credentials) or "INVALID" (wrong credentials); \
            otherwise use "ENVIRONMENT".
            - body.mode is "JSON" with a value when the operation takes a JSON body, otherwise "NONE".
            - expectedStatuses lists the status codes (e.g. "400") or classes (e.g. "4XX") the API should answer; \
            prefer documented statuses.
            - If a scenario overlaps an existing test, set duplicateOf to that test's name and explain in duplicateReason \
            why it is still useful; otherwise set both to null.
            - category is one of the allowed values; use CUSTOM if none fits.""";

    /** What is read before the model is asked, so no transaction stays open during the call. */
    private record Context(SpecificationVersion version, ApiOperation operation, List<TestCase> tests,
            Map<String, Object> prompt) {
    }

    private final ProjectAccessService access;
    private final SpecificationVersionRepository versions;
    private final ApiOperationRepository operations;
    private final TestCaseRepository testCases;
    private final TestSuiteRepository suites;
    private final AiCallRunner runner;
    private final AiProperties properties;
    private final TransactionTemplate readOnly;
    private final TransactionTemplate writable;

    public TestSuggestionService(ProjectAccessService access, SpecificationVersionRepository versions,
            ApiOperationRepository operations, TestCaseRepository testCases, TestSuiteRepository suites, AiCallRunner runner,
            AiProperties properties, PlatformTransactionManager transactions) {
        this.access = access;
        this.versions = versions;
        this.operations = operations;
        this.testCases = testCases;
        this.suites = suites;
        this.runner = runner;
        this.properties = properties;
        this.readOnly = new TransactionTemplate(transactions);
        this.readOnly.setReadOnly(true);
        this.writable = new TransactionTemplate(transactions);
    }

    public SuggestionReport suggest(UUID userId, UUID projectId, String operationKey) {
        Context context = readOnly.execute(status -> context(userId, projectId, operationKey));
        AiCallRunner.Metrics metrics = new AiCallRunner.Metrics();
        Map<String, Object> audit = new LinkedHashMap<>();
        audit.put("operationKey", operationKey);
        SuggestionReply reply;
        try {
            String user = "Operation contract, existing tests and gaps (JSON):\n" + JSON.writeValueAsString(context.prompt());
            reply = runner.call(userId, "suggest-tests", SYSTEM.formatted(properties.maxSuggestions()), user,
                    StructuredOutput.SUGGESTIONS, content -> StructuredOutput.read(content, StructuredOutput.SUGGESTIONS,
                            SuggestionReply.class), metrics);
        } catch (AiFailure failure) {
            runner.audit(AuditEventType.AI_SUGGESTIONS_REQUESTED, userId, projectId, metrics, failure.kind().name(), audit);
            throw AiCallRunner.toApi(failure);
        }
        List<Discarded> discarded = new ArrayList<>();
        List<PossibleDuplicate> similar = new ArrayList<>();
        List<TestCase> accepted = review(context, reply, metrics.model(), projectId, discarded, similar);
        List<TestCaseDetail> created = writable.execute(status -> store(projectId, accepted));
        audit.put("suggested", reply.suggestions().size());
        audit.put("created", created.size());
        audit.put("discarded", discarded.size());
        audit.put("possibleDuplicates", similar.size());
        runner.audit(AuditEventType.AI_SUGGESTIONS_REQUESTED, userId, projectId, metrics, "OK", audit);
        return new SuggestionReport(operationKey, metrics.model(), metrics.latencyMs(), metrics.attempts(), created,
                discarded, similar);
    }

    private Context context(UUID userId, UUID projectId, String operationKey) {
        Project project = access.requireOwned(projectId, userId);
        if (project.getActiveSpecificationVersionId() == null) {
            throw new ApiException(ErrorCode.NO_ACTIVE_SPECIFICATION, "Import a specification before asking for suggestions.");
        }
        SpecificationVersion version = versions.findByIdAndProjectId(project.getActiveSpecificationVersionId(), projectId)
                .orElseThrow(() -> new ApiException(ErrorCode.NO_ACTIVE_SPECIFICATION, "The project has no active version."));
        ApiOperation operation = operations.findBySpecificationVersionIdOrderBySortOrderAsc(version.getId()).stream()
                .filter(candidate -> candidate.getOperationKey().equals(operationKey)).findFirst()
                .orElseThrow(() -> ApiException.notFound("Operation"));
        List<TestCase> tests = testCases.findByProjectIdAndLifecycleStatus(projectId, TestCase.LifecycleStatus.ACTIVE)
                .stream().filter(test -> test.getOperationKey().equals(operationKey)).toList();
        return new Context(version, operation, tests, prompt(version, operation, tests));
    }

    /** The model's view of the operation: contract, existing tests (no request values) and generator gaps. */
    private static Map<String, Object> prompt(SpecificationVersion version, ApiOperation operation, List<TestCase> tests) {
        Map<String, Object> components = version.getComponentSchemas();
        Map<String, Object> op = new LinkedHashMap<>();
        op.put("method", operation.getMethod());
        op.put("path", operation.getPath());
        op.put("summary", PromptSanitizer.text(operation.getSummary(), 300));
        op.put("description", PromptSanitizer.text(operation.getDescription(), 1000));
        op.put("tags", operation.getTags());
        op.put("deprecated", operation.isDeprecated());
        op.put("authentication", operation.getSecurity().isEmpty() ? "none"
                : "required; the environment supplies credentials (test with auth NONE or INVALID)");

        List<Map<String, Object>> parameters = new ArrayList<>();
        for (Map<String, Object> parameter : operation.getParameters()) {
            Map<String, Object> view = new LinkedHashMap<>();
            view.put("name", parameter.get("name"));
            view.put("in", parameter.get("in"));
            view.put("required", "path".equals(parameter.get("in")) || Boolean.TRUE.equals(parameter.get("required")));
            view.put("description", PromptSanitizer.text(String.valueOf(parameter.getOrDefault("description", "")), 200));
            view.put("schema", PromptSanitizer.schema(parameter.get("schema"), components, SCHEMA_DEPTH));
            parameters.add(view);
        }

        Map<String, Object> body = null;
        if (operation.getRequestBody() != null && operation.getRequestBody().get("content") instanceof Map<?, ?> content) {
            body = new LinkedHashMap<>();
            body.put("required", Boolean.TRUE.equals(operation.getRequestBody().get("required")));
            body.put("mediaTypes", content.keySet().stream().map(String::valueOf).toList());
            String json = jsonMediaType(operation);
            if (json != null && content.get(json) instanceof Map<?, ?> media) {
                body.put("schema", PromptSanitizer.schema(media.get("schema"), components, SCHEMA_DEPTH));
            }
        }

        List<Map<String, Object>> responses = new ArrayList<>();
        for (Map<String, Object> response : operation.getResponses()) {
            Map<String, Object> view = new LinkedHashMap<>();
            view.put("status", response.get("status"));
            view.put("description", PromptSanitizer.text(String.valueOf(response.getOrDefault("description", "")), 200));
            if (response.get("content") instanceof Map<?, ?> content) {
                content.values().stream().filter(media -> media instanceof Map<?, ?> map && map.get("schema") != null)
                        .findFirst().ifPresent(media -> view.put("schema",
                                PromptSanitizer.schema(((Map<?, ?>) media).get("schema"), components, 1)));
            }
            responses.add(view);
        }

        List<Map<String, Object>> existing = new ArrayList<>();
        for (TestCase test : tests.stream().limit(MAX_EXISTING).toList()) {
            Map<String, Object> view = new LinkedHashMap<>();
            view.put("name", test.getName());
            view.put("category", test.getCategory().name());
            if (test.getScenarioKey() != null) {
                view.put("scenario", test.getScenarioKey());
            }
            view.put("expectedStatuses", test.getExpectationsValue().statuses());
            view.put("auth", test.getRequest().auth().name());
            existing.add(view);
        }

        List<Map<String, Object>> gaps = new DeterministicTestGenerator().generate(
                new DeterministicTestGenerator.Specification(components, version.getSecuritySchemes()),
                new DeterministicTestGenerator.Operation(operation.getMethod(), operation.getPath(), operation.getParameters(),
                        operation.getRequestBody(), operation.getResponses(), operation.getSecurity()))
                .skipped().stream().map(skipped -> {
                    Map<String, Object> gap = new LinkedHashMap<>();
                    gap.put("scenario", skipped.scenario());
                    gap.put("reason", skipped.reason());
                    return gap;
                }).toList();

        Map<String, Object> prompt = new LinkedHashMap<>();
        prompt.put("operation", op);
        prompt.put("parameters", parameters);
        prompt.put("requestBody", body);
        prompt.put("responses", responses);
        prompt.put("existingTests", existing);
        prompt.put("generatorCouldNotBuild", gaps);
        return prompt;
    }

    private static String jsonMediaType(ApiOperation operation) {
        if (operation.getRequestBody() != null && operation.getRequestBody().get("content") instanceof Map<?, ?> content) {
            return content.keySet().stream().map(String::valueOf).filter(MediaTypes::isJson).findFirst().orElse(null);
        }
        return null;
    }

    /** Valid, non-duplicate suggestions as new tests; everything else is reported with the reason. */
    private List<TestCase> review(Context context, SuggestionReply reply, String model, UUID projectId,
            List<Discarded> discarded, List<PossibleDuplicate> similar) {
        String operationKey = context.operation().getOperationKey();
        Map<String, String> existing = new HashMap<>();
        context.tests().forEach(test -> existing.putIfAbsent(TestFingerprint.of(test), test.getName()));
        Map<String, String> batch = new HashMap<>();
        List<TestCase> accepted = new ArrayList<>();
        String contentType = jsonMediaType(context.operation());
        for (Suggestion suggestion : reply.suggestions()) {
            String name = suggestion.name().strip();
            if (accepted.size() >= properties.maxSuggestions()) {
                discarded.add(new Discarded(name, "More than the " + properties.maxSuggestions() + " suggestions asked for."));
                continue;
            }
            TestDefinitionValidator.Validated validated;
            try {
                boolean json = "JSON".equals(suggestion.request().body().mode());
                RequestInput request = new RequestInput(orEmpty(suggestion.request().pathParams()),
                        orEmpty(suggestion.request().query()),
                        suggestion.request().headers() == null ? Map.of() : suggestion.request().headers(),
                        json ? new BodyInput(BodyMode.JSON, contentType == null ? "application/json" : contentType,
                                suggestion.request().body().value()) : new BodyInput(BodyMode.NONE, null, null),
                        AuthMode.valueOf(suggestion.request().auth()));
                validated = TestDefinitionValidator.validate(operationKey, request, new ExpectationsInput(
                        suggestion.expectedStatuses(), firstSentence(suggestion.rationale()), true), List.of());
            } catch (ApiException ex) {
                String fields = ex.fieldErrors().stream().map(error -> error.field() + ": " + error.message()).limit(3)
                        .reduce((left, right) -> left + "; " + right).map(text -> " (" + text + ")").orElse("");
                discarded.add(new Discarded(name, "Not a valid test: " + ex.getMessage() + fields));
                continue;
            }
            String fingerprint = TestFingerprint.of(operationKey, validated.request().toMap(), validated.expectations().statuses());
            String rationale = suggestion.rationale().strip();
            String same = existing.get(fingerprint);
            if (same != null) {
                String reason = suggestion.duplicateReason() == null ? "" : suggestion.duplicateReason().strip();
                if (reason.length() < 10) {
                    discarded.add(new Discarded(name, "Sends the same request with the same expected statuses as the "
                            + "existing test “" + same + "”, and no reason was given."));
                    continue;
                }
                rationale += "\n\nOverlaps with “" + same + "”: " + reason;
            }
            if (batch.containsKey(fingerprint)) {
                discarded.add(new Discarded(name, "Same as the suggestion “" + batch.get(fingerprint) + "”."));
                continue;
            }
            batch.put(fingerprint, name);
            if (same == null) {
                for (TestCase test : context.tests()) {
                    String difference = nearDuplicate(test, validated.request().toMap(), validated.expectations().statuses());
                    if (difference != null) {
                        similar.add(new PossibleDuplicate(name, test.getName(), difference));
                        rationale += "\n\nPossibly a duplicate of “" + test.getName() + "”: it differs only in "
                                + difference + ". Check before approving.";
                        break;
                    }
                }
            }
            accepted.add(TestCase.suggested(projectId, null, context.version().getId(), operationKey,
                    name.length() > 200 ? name.substring(0, 200) : name, TestCase.Category.valueOf(suggestion.category()),
                    validated.request(), validated.expectations(), "Suggested by " + model + ". " + rationale, model,
                    PROMPT_VERSION, dedupeHash(fingerprint), 10_000 + accepted.size()));
        }
        return accepted;
    }

    /**
     * Where a suggestion differs from {@code test} when both expect the same statuses, use the same
     * authentication mode and send values at the same locations, and at most one value differs;
     * otherwise {@code null}.
     */
    static String nearDuplicate(TestCase test, Map<String, Object> request, List<String> statuses) {
        if (!new java.util.TreeSet<>(test.getExpectationsValue().statuses()).equals(new java.util.TreeSet<>(statuses))) {
            return null;
        }
        Map<String, String> mine = flatten(request);
        Map<String, String> theirs = flatten(test.getRequestTemplate());
        if (!mine.keySet().equals(theirs.keySet())) {
            return null;
        }
        List<String> differing = mine.keySet().stream().filter(key -> !mine.get(key).equals(theirs.get(key))).toList();
        return differing.size() == 1 ? differing.getFirst() : null;
    }

    /** Request values by location ("query.limit", "body/name", "auth"); path and query values as text. */
    private static Map<String, String> flatten(Map<String, Object> request) {
        Map<String, String> flat = new java.util.TreeMap<>();
        for (String section : List.of("pathParams", "query", "headers")) {
            if (request.get(section) instanceof Map<?, ?> values) {
                values.forEach((key, value) -> flat.put(section + "." + key, String.valueOf(value)));
            }
        }
        flat.put("auth", String.valueOf(request.get("auth")));
        if (request.get("body") instanceof Map<?, ?> body) {
            flat.put("body mode", String.valueOf(body.get("mode")));
            flattenBody(body.get("value"), "body", flat);
        }
        return flat;
    }

    private static void flattenBody(Object value, String location, Map<String, String> flat) {
        if (value instanceof Map<?, ?> object && !object.isEmpty()) {
            object.forEach((key, child) -> flattenBody(child, location + "/" + key, flat));
        } else if (value instanceof List<?> list && !list.isEmpty()) {
            for (int index = 0; index < list.size(); index++) {
                flattenBody(list.get(index), location + "/" + index, flat);
            }
        } else {
            flat.put(location, JSON.writeValueAsString(value));
        }
    }

    private List<TestCaseDetail> store(UUID projectId, List<TestCase> accepted) {
        if (accepted.isEmpty()) {
            return List.of();
        }
        TestSuite suite = suites.findFirstByProjectIdAndKind(projectId, TestSuite.Kind.AI_SUGGESTIONS).orElseGet(() -> {
            String name = SUITE_NAME;
            for (int attempt = 2; suites.existsByProjectIdAndNameIgnoreCase(projectId, name); attempt++) {
                name = SUITE_NAME + " (" + attempt + ")";
            }
            return suites.saveAndFlush(new TestSuite(projectId, name,
                    "Tests suggested by the local model. Each needs review before it can run.", TestSuite.Kind.AI_SUGGESTIONS));
        });
        List<TestCaseDetail> created = new ArrayList<>();
        for (TestCase test : accepted) {
            if (testCases.existsByProjectIdAndDedupeHashAndLifecycleStatusNot(projectId, test.getDedupeHash(),
                    TestCase.LifecycleStatus.ARCHIVED)) {
                continue;
            }
            test.moveTo(suite.getId());
            created.add(TestCaseDetail.from(testCases.saveAndFlush(test)));
        }
        return created;
    }

    private static Map<String, Object> orEmpty(Map<String, Object> map) {
        return map == null ? Map.of() : map;
    }

    private static String firstSentence(String text) {
        String trimmed = text.strip();
        int end = trimmed.indexOf(". ");
        String sentence = end > 0 ? trimmed.substring(0, end + 1) : trimmed;
        return sentence.length() > 300 ? sentence.substring(0, 300) : sentence;
    }

    private static String dedupeHash(String fingerprint) {
        try {
            return java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(("ai-suggestion\n" + fingerprint).getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 is required by the Java platform", ex);
        }
    }
}
