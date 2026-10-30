package com.apiforge.ai.service;

import java.net.URI;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.apiforge.ai.client.AiFailure;
import com.apiforge.ai.domain.AiExplanation;
import com.apiforge.ai.repository.AiExplanationRepository;
import com.apiforge.ai.service.AiViews.Cause;
import com.apiforge.ai.service.AiViews.Change;
import com.apiforge.ai.service.AiViews.Evidence;
import com.apiforge.ai.service.AiViews.Explanation;
import com.apiforge.ai.service.AiViews.Fact;
import com.apiforge.ai.service.AiViews.Grounding;
import com.apiforge.ai.service.StructuredOutput.ExplanationReply;
import com.apiforge.audit.domain.AuditEventType;
import com.apiforge.common.error.ApiException;
import com.apiforge.common.error.ErrorCode;
import com.apiforge.execution.domain.TestResult;
import com.apiforge.execution.domain.TestRun;
import com.apiforge.execution.repository.TestResultRepository;
import com.apiforge.execution.repository.TestRunRepository;
import com.apiforge.project.service.ProjectAccessService;
import com.apiforge.spec.domain.ApiOperation;
import com.apiforge.spec.domain.SpecificationVersion;
import com.apiforge.spec.repository.ApiOperationRepository;
import com.apiforge.spec.repository.SpecificationVersionRepository;

import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

/**
 * Asks the local model why a result failed. The model sees the failed assertions, the test, the
 * redacted request (path only, no host), the response status, safe headers and a sanitized body
 * excerpt, and the documented responses of the operation; never source code, environment values or
 * credentials. Its observed facts are checked against that data, claims of source-code access are
 * flagged, and nothing it suggests is applied.
 */
@Service
public class FailureExplanationService {

    static final String PROMPT_VERSION = "ai-explain-1";
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final Set<TestResult.Outcome> EXPLAINABLE = Set.of(TestResult.Outcome.FAILED, TestResult.Outcome.ERROR,
            TestResult.Outcome.INCONCLUSIVE);
    private static final int MAX_ASSERTIONS = 25;
    private static final int REQUEST_BODY = 1500;
    private static final int RESPONSE_BODY = 3000;

    static final List<String> INPUTS = List.of("failed and inconclusive assertions with expected and actual values",
            "the test's name, rationale and expected statuses", "the request: method, path, test headers and body (redacted, no host)",
            "the response: status, content type, safe headers and a sanitized body excerpt",
            "the operation's documented responses and their schemas");

    static final String SYSTEM = """
            You explain why one automated API test did not pass, for the developer who owns the API.
            Answer with JSON only, following the provided JSON schema exactly.

            Rules:
            - "observedFacts" only restate what the provided data shows (assertions, request, response, schema, test). \
            Give the source of each fact. Do not interpret there.
            - Causes are hypotheses: put them in "likelyCauses" with a confidence (LOW, MEDIUM or HIGH) and cite concrete \
            evidence from the data, such as JSON paths, status codes and values.
            - You have not seen the API's source code, logs, configuration or database. Never claim or imply that you have; \
            speak about the implementation only as a possibility.
            - "debuggingSteps" are concrete checks a developer can do.
            - "suggestedChanges" are proposals for a person to review, aimed at the TEST, the SPECIFICATION or the \
            API_IMPLEMENTATION. Nothing is applied automatically.
            - Values shown as «redacted» were removed on purpose; do not guess them.
            - Be concise.""";

    private static final Pattern JSON_PATH = Pattern.compile("\\$(?:\\.[A-Za-z_][A-Za-z0-9_]*|\\[\\d+]|\\[\\*]|\\['[^']*'])+");
    private static final Pattern STATUS = Pattern.compile("\\b[1-5]\\d\\d\\b");
    private static final Pattern QUOTED = Pattern.compile("\"([^\"]{2,80})\"|`([^`]{2,80})`|“([^”]{2,80})”");
    private static final List<Pattern> CODE_CLAIMS = List.of(
            Pattern.compile("(?i)\\b(i|we)\\s+(have\\s+)?(reviewed|inspected|looked at|examined|analy[sz]ed|read|checked)\\s+"
                    + "(the\\s+|your\\s+)?(source|code|codebase|implementation|repository|controller)"),
            Pattern.compile("(?i)\\b(in|at)\\s+line\\s+\\d+"),
            Pattern.compile("(?i)\\b[\\w/-]+\\.(java|kt|py|js|ts|go|cs|rb|php):\\d+"));

    private record Context(UUID resultId, Map<String, Object> prompt, String text) {
    }

    private final ProjectAccessService access;
    private final TestRunRepository runs;
    private final TestResultRepository results;
    private final SpecificationVersionRepository versions;
    private final ApiOperationRepository operations;
    private final AiExplanationRepository explanations;
    private final AiCallRunner runner;
    private final Clock clock;
    private final TransactionTemplate readOnly;
    private final TransactionTemplate writable;

    public FailureExplanationService(ProjectAccessService access, TestRunRepository runs, TestResultRepository results,
            SpecificationVersionRepository versions, ApiOperationRepository operations, AiExplanationRepository explanations,
            AiCallRunner runner, Clock clock, PlatformTransactionManager transactions) {
        this.access = access;
        this.runs = runs;
        this.results = results;
        this.versions = versions;
        this.operations = operations;
        this.explanations = explanations;
        this.runner = runner;
        this.clock = clock;
        this.readOnly = new TransactionTemplate(transactions);
        this.readOnly.setReadOnly(true);
        this.writable = new TransactionTemplate(transactions);
    }

    /** The latest stored explanation of a result. */
    public Explanation latest(UUID userId, UUID projectId, UUID runId, UUID resultId) {
        return readOnly.execute(status -> {
            require(userId, projectId, runId, resultId);
            return explanations.findFirstByTestResultIdAndProjectIdOrderByCreatedAtDesc(resultId, projectId)
                    .map(FailureExplanationService::view)
                    .orElseThrow(() -> ApiException.notFound("Explanation"));
        });
    }

    public Explanation explain(UUID userId, UUID projectId, UUID runId, UUID resultId) {
        Context context = readOnly.execute(status -> context(userId, projectId, runId, resultId));
        AiCallRunner.Metrics metrics = new AiCallRunner.Metrics();
        Map<String, Object> audit = Map.of("resultId", resultId.toString());
        ExplanationReply reply;
        try {
            reply = runner.call(userId, "explain-failure", SYSTEM, "Failed test result (JSON):\n" + context.text(),
                    StructuredOutput.EXPLANATION,
                    content -> StructuredOutput.read(content, StructuredOutput.EXPLANATION, ExplanationReply.class), metrics);
        } catch (AiFailure failure) {
            runner.audit(AuditEventType.AI_EXPLANATION_REQUESTED, userId, projectId, metrics, failure.kind().name(), audit);
            throw AiCallRunner.toApi(failure);
        }
        Map<String, Object> stored = annotate(reply, context.text());
        AiExplanation saved = writable.execute(status -> explanations.saveAndFlush(new AiExplanation(projectId, resultId,
                metrics.model(), stored, (int) Math.min(Integer.MAX_VALUE, metrics.latencyMs()), userId, Instant.now(clock))));
        runner.audit(AuditEventType.AI_EXPLANATION_REQUESTED, userId, projectId, metrics, "OK", audit);
        return view(saved);
    }

    private TestResult require(UUID userId, UUID projectId, UUID runId, UUID resultId) {
        access.requireOwned(projectId, userId);
        TestRun run = runs.findByIdAndProjectId(runId, projectId).orElseThrow(() -> ApiException.notFound("Test run"));
        return results.findById(resultId).filter(result -> result.getTestRunId().equals(run.getId()))
                .orElseThrow(() -> ApiException.notFound("Test result"));
    }

    private Context context(UUID userId, UUID projectId, UUID runId, UUID resultId) {
        TestResult result = require(userId, projectId, runId, resultId);
        if (!EXPLAINABLE.contains(result.getOutcome())) {
            throw new ApiException(ErrorCode.CONFLICT, "Only failed, errored or inconclusive results can be explained.");
        }
        TestRun run = runs.findById(result.getTestRunId()).orElseThrow();
        Map<String, Object> prompt = prompt(result, operation(run, result.getOperationKey()), version(run));
        return new Context(resultId, prompt, JSON.writeValueAsString(prompt));
    }

    private ApiOperation operation(TestRun run, String operationKey) {
        if (run.getSpecificationVersionId() == null) {
            return null;
        }
        return operations.findBySpecificationVersionIdOrderBySortOrderAsc(run.getSpecificationVersionId()).stream()
                .filter(operation -> operation.getOperationKey().equals(operationKey)).findFirst().orElse(null);
    }

    private SpecificationVersion version(TestRun run) {
        return run.getSpecificationVersionId() == null ? null
                : versions.findById(run.getSpecificationVersionId()).orElse(null);
    }

    static Map<String, Object> prompt(TestResult result, ApiOperation operation, SpecificationVersion version) {
        Map<String, Object> prompt = new LinkedHashMap<>();
        Map<String, Object> snapshot = result.getTestCaseSnapshot();
        Map<String, Object> test = new LinkedHashMap<>();
        test.put("name", PromptSanitizer.text(String.valueOf(snapshot.getOrDefault("name", "")), 200));
        test.put("category", snapshot.get("category"));
        test.put("scenario", snapshot.get("scenarioKey"));
        if (snapshot.get("expectations") instanceof Map<?, ?> expectations) {
            test.put("expectedStatuses", expectations.get("statuses"));
            test.put("expectation", PromptSanitizer.text(String.valueOf(expectations.get("summary")), 400));
        }
        prompt.put("test", test);
        prompt.put("outcome", result.getOutcome().name());
        if (result.getErrorKind() != null) {
            prompt.put("transportError", Map.of("kind", result.getErrorKind().name(),
                    "message", PromptSanitizer.text(String.valueOf(result.getErrorMessage()), 400)));
        }

        List<Map<String, Object>> failing = new ArrayList<>();
        Map<String, Integer> others = new LinkedHashMap<>();
        for (Map<String, Object> check : result.getChecks()) {
            String outcome = String.valueOf(check.get("result"));
            if (("FAIL".equals(outcome) || "INCONCLUSIVE".equals(outcome)) && failing.size() < MAX_ASSERTIONS) {
                Map<String, Object> view = new LinkedHashMap<>();
                for (String key : List.of("name", "result", "path", "keyword", "expected", "actual", "message")) {
                    if (check.get(key) != null) {
                        view.put(key, PromptSanitizer.text(String.valueOf(check.get(key)), 300));
                    }
                }
                failing.add(view);
            } else {
                others.merge(outcome, 1, Integer::sum);
            }
        }
        prompt.put("failedAssertions", failing);
        prompt.put("otherAssertions", others);

        Map<String, Object> request = new LinkedHashMap<>();
        Map<String, Object> sent = result.getRequestSnapshot();
        request.put("method", sent.get("method"));
        request.put("path", pathOnly(String.valueOf(sent.getOrDefault("url", sent.getOrDefault("path", "")))));
        if (sent.get("headers") instanceof Map<?, ?> headers) {
            request.put("headers", PromptSanitizer.requestHeaders(headers, Set.of("x-apiforge-request-id")));
        }
        request.put("auth", sent.get("auth"));
        if (sent.get("body") instanceof Map<?, ?> body && body.get("value") != null) {
            Object value = body.get("value");
            request.put("body", value instanceof String text ? PromptSanitizer.text(text, REQUEST_BODY)
                    : PromptSanitizer.body(JSON.writeValueAsString(value), "application/json", REQUEST_BODY));
            request.put("contentType", body.get("contentType"));
        }
        prompt.put("request", request);

        if (result.getResponseStatus() != null) {
            Map<String, Object> response = new LinkedHashMap<>();
            response.put("status", result.getResponseStatus());
            response.put("contentType", result.getResponseContentType());
            response.put("headers", PromptSanitizer.headers(result.getResponseHeaders()));
            response.put("body", PromptSanitizer.body(result.getResponseBody(), result.getResponseContentType(), RESPONSE_BODY));
            response.put("sizeBytes", result.getResponseSizeBytes());
            response.put("durationMs", result.getDurationMs());
            prompt.put("response", response);
        }

        if (operation != null && version != null) {
            Map<String, Object> contract = new LinkedHashMap<>();
            contract.put("operation", operation.getOperationKey());
            List<Map<String, Object>> documented = new ArrayList<>();
            for (Map<String, Object> response : operation.getResponses()) {
                Map<String, Object> view = new LinkedHashMap<>();
                view.put("status", response.get("status"));
                view.put("description", PromptSanitizer.text(String.valueOf(response.getOrDefault("description", "")), 200));
                if (response.get("content") instanceof Map<?, ?> content) {
                    view.put("mediaTypes", content.keySet().stream().map(String::valueOf).toList());
                    content.values().stream().filter(media -> media instanceof Map<?, ?> map && map.get("schema") != null)
                            .findFirst().ifPresent(media -> view.put("schema", PromptSanitizer.schema(
                                    ((Map<?, ?>) media).get("schema"), version.getComponentSchemas(), 2)));
                }
                documented.add(view);
            }
            contract.put("documentedResponses", documented);
            prompt.put("contract", contract);
        }
        return prompt;
    }

    private static String pathOnly(String url) {
        try {
            URI uri = URI.create(url);
            String path = uri.getRawPath() == null ? url : uri.getRawPath();
            return uri.getRawQuery() == null ? path : path + "?" + PromptSanitizer.text(uri.getRawQuery(), 300);
        } catch (IllegalArgumentException ex) {
            return PromptSanitizer.text(url, 300);
        }
    }

    // --- Checking the answer ---

    static Map<String, Object> annotate(ExplanationReply reply, String context) {
        String haystack = normalize(context);
        List<Map<String, Object>> facts = new ArrayList<>();
        int unverified = 0;
        for (StructuredOutput.Fact fact : reply.observedFacts()) {
            Grounding grounding = grounding(fact.statement(), haystack);
            if (grounding == Grounding.UNVERIFIED) {
                unverified++;
            }
            facts.add(Map.of("statement", fact.statement().strip(), "source", fact.source(), "grounding", grounding.name()));
        }
        List<Map<String, Object>> causes = new ArrayList<>();
        for (StructuredOutput.Cause cause : reply.likelyCauses()) {
            List<Map<String, Object>> evidence = new ArrayList<>();
            for (String item : cause.evidence() == null ? List.<String>of() : cause.evidence()) {
                evidence.add(Map.of("text", item.strip(), "grounding", grounding(item, haystack).name()));
            }
            causes.add(Map.of("hypothesis", cause.hypothesis().strip(), "confidence", cause.confidence(), "evidence", evidence));
        }
        List<String> warnings = new ArrayList<>();
        StringBuilder all = new StringBuilder(reply.summary());
        reply.observedFacts().forEach(fact -> all.append('\n').append(fact.statement()));
        reply.likelyCauses().forEach(cause -> all.append('\n').append(cause.hypothesis()));
        reply.debuggingSteps().forEach(step -> all.append('\n').append(step));
        reply.suggestedChanges().forEach(change -> all.append('\n').append(change.change()));
        if (claimsSourceCode(all.toString(), haystack)) {
            warnings.add("The answer claims or implies access to source code, which the model was never given. "
                    + "Treat such statements as guesses.");
        }
        if (unverified > 0) {
            warnings.add(unverified + " observed fact" + (unverified == 1 ? " cites a value" : "s cite values")
                    + " not found in the data the model was given.");
        }
        Map<String, Object> stored = new LinkedHashMap<>();
        stored.put("summary", reply.summary().strip());
        stored.put("observedFacts", facts);
        stored.put("likelyCauses", causes);
        stored.put("debuggingSteps", reply.debuggingSteps().stream().map(String::strip).toList());
        stored.put("suggestedChanges", reply.suggestedChanges().stream()
                .map(change -> Map.of("target", change.target(), "change", change.change().strip())).toList());
        stored.put("inputs", INPUTS);
        stored.put("sourceCodeProvided", false);
        stored.put("warnings", warnings);
        stored.put("promptVersion", PROMPT_VERSION);
        return stored;
    }

    /** VERIFIED when every concrete value the text cites appears in the data; NOT_CHECKABLE when it cites none. */
    static Grounding grounding(String text, String normalizedContext) {
        List<String> anchors = new ArrayList<>();
        Matcher path = JSON_PATH.matcher(text);
        while (path.find()) {
            anchors.add(path.group());
        }
        Matcher status = STATUS.matcher(text);
        while (status.find()) {
            anchors.add(status.group());
        }
        Matcher quoted = QUOTED.matcher(text);
        while (quoted.find()) {
            for (int group = 1; group <= 3; group++) {
                if (quoted.group(group) != null) {
                    anchors.add(quoted.group(group));
                }
            }
        }
        if (anchors.isEmpty()) {
            return Grounding.NOT_CHECKABLE;
        }
        for (String anchor : anchors) {
            if (!normalizedContext.contains(normalize(anchor))) {
                return Grounding.UNVERIFIED;
            }
        }
        return Grounding.VERIFIED;
    }

    /**
     * Whether the text claims or implies having read source code. A phrase quoted from the data the
     * model was given (e.g. a JSON parser's "at line 1, column 17") is not a claim.
     */
    static boolean claimsSourceCode(String text, String normalizedContext) {
        for (Pattern pattern : CODE_CLAIMS) {
            Matcher matcher = pattern.matcher(text);
            while (matcher.find()) {
                if (!normalizedContext.contains(normalize(matcher.group()))) {
                    return true;
                }
            }
        }
        return false;
    }

    /** Lower case, JSON escapes of quotes removed, so quoted values match their JSON-encoded form. */
    private static String normalize(String text) {
        return text.toLowerCase(Locale.ROOT).replace("\\\"", "\"").replace("\\u00ab", "«").replace("\\u00bb", "»");
    }

    private static Explanation view(AiExplanation entity) {
        Map<String, Object> data = entity.getExplanation();
        List<Fact> facts = list(data.get("observedFacts")).stream().map(fact -> new Fact(string(fact.get("statement")),
                string(fact.get("source")), Grounding.valueOf(string(fact.get("grounding"))))).toList();
        List<Cause> causes = list(data.get("likelyCauses")).stream().map(cause -> new Cause(string(cause.get("hypothesis")),
                string(cause.get("confidence")), list(cause.get("evidence")).stream()
                        .map(item -> new Evidence(string(item.get("text")), Grounding.valueOf(string(item.get("grounding")))))
                        .toList())).toList();
        List<Change> changes = list(data.get("suggestedChanges")).stream()
                .map(change -> new Change(string(change.get("target")), string(change.get("change")))).toList();
        return new Explanation(entity.getId(), entity.getTestResultId(), entity.getModel(), entity.getLatencyMs(),
                entity.getCreatedAt(), string(data.get("summary")), facts, causes, strings(data.get("debuggingSteps")), changes,
                strings(data.get("inputs")), Boolean.TRUE.equals(data.get("sourceCodeProvided")), strings(data.get("warnings")));
    }

    private static List<Map<String, Object>> list(Object value) {
        return value == null ? List.of() : JSON.convertValue(value, new TypeReference<List<Map<String, Object>>>() {
        });
    }

    private static List<String> strings(Object value) {
        return value == null ? List.of() : JSON.convertValue(value, new TypeReference<List<String>>() {
        });
    }

    private static String string(Object value) {
        return value == null ? "" : String.valueOf(value);
    }
}
