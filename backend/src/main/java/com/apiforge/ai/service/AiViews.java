package com.apiforge.ai.service;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import com.apiforge.testcase.service.TestCaseViews.TestCaseDetail;

/** API views of the AI features. */
public final class AiViews {

    private AiViews() {
    }

    public enum State {
        /** Ollama reachable and the configured model installed. */
        READY,
        /** Ollama reachable, but the configured model is not installed. */
        MODEL_MISSING,
        /** Ollama not reachable. */
        UNAVAILABLE,
        /** AI turned off by configuration. */
        DISABLED
    }

    /**
     * @param server          scheme://host:port of Ollama (no credentials)
     * @param installedModels models Ollama reports (when reachable)
     * @param latencyMs       of the availability check
     */
    public record AiStatus(boolean enabled, State state, String model, String server, List<String> installedModels,
            String message, Long latencyMs, Instant checkedAt) {
    }

    // --- Suggestions ---

    public record Discarded(String name, String reason) {
    }

    /**
     * A stored suggestion that differs from an existing test in at most one value (same expected
     * statuses, authentication and inputs). Kept, but flagged for the reviewer.
     */
    public record PossibleDuplicate(String name, String similarTo, String difference) {
    }

    /** @param created stored as AI suggestions pending review; nothing runs until a person approves */
    public record SuggestionReport(String operationKey, String model, long latencyMs, int attempts,
            List<TestCaseDetail> created, List<Discarded> discarded, List<PossibleDuplicate> possibleDuplicates) {
    }

    // --- Explanations ---

    /**
     * Whether a statement could be checked against the data the model was given: VERIFIED when every
     * concrete value it cites (JSON paths, status codes, quoted values) occurs in that data,
     * UNVERIFIED when one does not, NOT_CHECKABLE when it cites nothing concrete.
     */
    public enum Grounding { VERIFIED, UNVERIFIED, NOT_CHECKABLE }

    public record Fact(String statement, String source, Grounding grounding) {
    }

    public record Evidence(String text, Grounding grounding) {
    }

    public record Cause(String hypothesis, String confidence, List<Evidence> evidence) {
    }

    public record Change(String target, String change) {
    }

    /**
     * @param inputs             what the model was given (names, not contents)
     * @param sourceCodeProvided always {@code false}: APIForge never sends the target's source code
     * @param warnings           problems found in the answer, e.g. a claim to have read source code
     */
    public record Explanation(UUID id, UUID resultId, String model, long latencyMs, Instant createdAt, String summary,
            List<Fact> observedFacts, List<Cause> likelyCauses, List<String> debuggingSteps, List<Change> suggestedChanges,
            List<String> inputs, boolean sourceCodeProvided, List<String> warnings) {
    }
}
