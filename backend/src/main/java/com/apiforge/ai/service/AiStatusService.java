package com.apiforge.ai.service;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;

import org.springframework.stereotype.Service;

import com.apiforge.ai.AiProperties;
import com.apiforge.ai.client.AiFailure;
import com.apiforge.ai.client.OllamaGateway;
import com.apiforge.ai.service.AiViews.AiStatus;
import com.apiforge.ai.service.AiViews.State;

/**
 * Whether AI can be used right now: Ollama reachable and the configured model installed. Checked
 * by listing Ollama's models (cheap; no model is loaded) and cached briefly.
 */
@Service
public class AiStatusService {

    static final Duration CACHE = Duration.ofSeconds(10);

    private final OllamaGateway gateway;
    private final AiProperties properties;
    private final Clock clock;
    private volatile AiStatus cached;

    public AiStatusService(OllamaGateway gateway, AiProperties properties, Clock clock) {
        this.gateway = gateway;
        this.properties = properties;
        this.clock = clock;
    }

    public AiStatus status() {
        AiStatus current = cached;
        if (current != null && current.checkedAt().plus(CACHE).isAfter(Instant.now(clock))) {
            return current;
        }
        current = check();
        cached = current;
        return current;
    }

    /** Forget the cached state (after a call found Ollama or the model missing). */
    public void invalidate() {
        cached = null;
    }

    private AiStatus check() {
        Instant now = Instant.now(clock);
        if (!properties.enabled()) {
            return new AiStatus(false, State.DISABLED, properties.model(), properties.origin(), List.of(),
                    "AI is turned off (APIFORGE_AI_ENABLED=false). Everything else works as usual.", null, now);
        }
        long started = System.nanoTime();
        try {
            List<String> models = gateway.models();
            long latency = Duration.ofNanos(System.nanoTime() - started).toMillis();
            List<String> shown = models.stream().sorted().limit(30).toList();
            if (installed(models, properties.model())) {
                return new AiStatus(true, State.READY, properties.model(), properties.origin(), shown,
                        "Ollama is reachable and the model is installed.", latency, now);
            }
            return new AiStatus(true, State.MODEL_MISSING, properties.model(), properties.origin(), shown,
                    "The model %s is not installed. Run: ollama pull %s".formatted(properties.model(), properties.model()),
                    latency, now);
        } catch (AiFailure failure) {
            return new AiStatus(true, State.UNAVAILABLE, properties.model(), properties.origin(), List.of(),
                    "%s Server: %s. AI features are unavailable; everything else works."
                            .formatted(failure.getMessage(), properties.origin()),
                    null, now);
        }
    }

    /** Ollama lists "name:tag"; a configured name without a tag means ":latest". */
    static boolean installed(List<String> models, String model) {
        String wanted = model.contains(":") ? model : model + ":latest";
        return models.stream().anyMatch(name -> name.equals(wanted) || name.equals(model));
    }
}
