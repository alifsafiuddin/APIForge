package com.apiforge.ai.client;

import java.io.IOException;
import java.io.InputStream;
import java.net.ConnectException;
import java.net.NoRouteToHostException;
import java.net.Proxy;
import java.net.SocketTimeoutException;
import java.net.URI;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import org.springframework.ai.ollama.api.OllamaApi;
import org.springframework.ai.ollama.api.OllamaApi.ChatRequest;
import org.springframework.ai.ollama.api.OllamaApi.ChatResponse;
import org.springframework.ai.ollama.api.OllamaApi.Message;
import org.springframework.ai.ollama.api.OllamaApi.Message.Role;
import org.springframework.http.HttpMethod;
import org.springframework.http.client.ClientHttpResponse;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.ResponseErrorHandler;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import com.apiforge.ai.AiProperties;
import com.apiforge.ai.client.AiFailure.Kind;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * The only path to the model: Spring AI's {@link OllamaApi} with explicit timeouts, no proxy, no
 * automatic retries and an error handler that classifies Ollama's answers. Spring AI types do not
 * leave this package (ArchitectureTest).
 */
@Component
public class OllamaGateway {

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final int MAX_ERROR_BODY = 2000;

    /** One completed call; {@code content} is the model's raw answer. */
    public record Completion(String content, String model, String doneReason, long latencyMs, Integer promptTokens,
            Integer outputTokens) {
    }

    private final AiProperties properties;
    private final OllamaApi api;
    private final OllamaApi statusApi;

    public OllamaGateway(AiProperties properties) {
        this.properties = properties;
        this.api = build(properties, properties.requestTimeout());
        this.statusApi = build(properties, properties.statusTimeout());
    }

    private static OllamaApi build(AiProperties properties, Duration readTimeout) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(properties.connectTimeout());
        factory.setReadTimeout(readTimeout);
        factory.setProxy(Proxy.NO_PROXY);
        return OllamaApi.builder()
                .baseUrl(properties.baseUrl())
                .restClientBuilder(RestClient.builder().requestFactory(factory))
                .responseErrorHandler(new ErrorHandler())
                .build();
    }

    /** Names of the installed models (the availability check). */
    public List<String> models() {
        try {
            List<String> names = new ArrayList<>();
            OllamaApi.ListModelResponse response = statusApi.listModels();
            if (response != null && response.models() != null) {
                response.models().forEach(model -> names.add(model.name()));
            }
            return names;
        } catch (RuntimeException ex) {
            throw classify(ex);
        }
    }

    /**
     * One non-streamed chat call whose answer must follow {@code schema} (Ollama structured
     * outputs). Not retried here; see {@code AiCallRunner}.
     */
    public Completion chat(String system, String user, Map<String, Object> schema) {
        Map<String, Object> options = new LinkedHashMap<>();
        options.put("temperature", properties.temperature());
        options.put("num_ctx", properties.contextTokens());
        options.put("num_predict", properties.maxOutputTokens());
        ChatRequest request = ChatRequest.builder(properties.model())
                .messages(List.of(Message.builder(Role.SYSTEM).content(system).build(),
                        Message.builder(Role.USER).content(user).build()))
                .stream(false)
                .format(schema)
                .keepAlive(properties.keepAlive())
                .options(options)
                .build();
        long started = System.nanoTime();
        ChatResponse response;
        try {
            response = api.chat(request);
        } catch (RuntimeException ex) {
            throw classify(ex);
        }
        long latency = Duration.ofNanos(System.nanoTime() - started).toMillis();
        if (response == null || response.message() == null) {
            throw new AiFailure(Kind.INVALID_OUTPUT, "The model returned no message.");
        }
        return new Completion(response.message().content(), response.model(), response.doneReason(), latency,
                response.promptEvalCount(), response.evalCount());
    }

    /** Maps client exceptions to failure kinds without quoting request content. */
    static AiFailure classify(RuntimeException ex) {
        if (ex instanceof AiFailure failure) {
            return failure;
        }
        OllamaHttpError error = null;
        for (Throwable cause = ex; cause != null && error == null; cause = cause.getCause()) {
            if (cause instanceof OllamaHttpError httpError) {
                error = httpError;
            }
        }
        if (error != null) {
            String message = error.getMessage().toLowerCase(Locale.ROOT);
            if (error.status == 404 && message.contains("not found")) {
                return new AiFailure(Kind.MODEL_NOT_FOUND, "The model is not installed in Ollama (" + error.getMessage() + ").");
            }
            if (error.status == 503 || error.status == 429 || message.contains("busy") || message.contains("maximum pending")) {
                return new AiFailure(Kind.OVERLOADED, "Ollama is busy (" + error.getMessage() + ").");
            }
            return new AiFailure(Kind.MODEL_ERROR, "Ollama answered " + error.status + ": " + error.getMessage());
        }
        // The transport failure may be wrapped by RestClient or Spring AI: look at the whole chain.
        for (Throwable cause = ex; cause != null; cause = cause.getCause()) {
            if (cause instanceof SocketTimeoutException) {
                return cause.getMessage() != null && cause.getMessage().toLowerCase(Locale.ROOT).contains("connect")
                        ? new AiFailure(Kind.UNAVAILABLE, "Ollama did not accept the connection in time.")
                        : new AiFailure(Kind.TIMEOUT, "The model did not answer within the request timeout.");
            }
            if (cause instanceof ConnectException || cause instanceof UnknownHostException
                    || cause instanceof NoRouteToHostException) {
                return new AiFailure(Kind.UNAVAILABLE, "Ollama is not reachable (" + cause.getClass().getSimpleName() + ").");
            }
        }
        if (ex instanceof ResourceAccessException) {
            return new AiFailure(Kind.UNAVAILABLE, "Ollama is not reachable.");
        }
        if (ex instanceof RestClientException) {
            return new AiFailure(Kind.MODEL_ERROR, "Ollama's answer could not be read.");
        }
        return new AiFailure(Kind.MODEL_ERROR, "Unexpected error calling Ollama (" + ex.getClass().getSimpleName() + ").");
    }

    /** An HTTP error answer of Ollama, with its {@code error} message (bounded). */
    static final class OllamaHttpError extends RuntimeException {

        private static final long serialVersionUID = 1L;
        final int status;

        OllamaHttpError(int status, String message) {
            super(message);
            this.status = status;
        }
    }

    private static final class ErrorHandler implements ResponseErrorHandler {

        @Override
        public boolean hasError(ClientHttpResponse response) throws IOException {
            return response.getStatusCode().isError();
        }

        @Override
        public void handleError(URI url, HttpMethod method, ClientHttpResponse response) throws IOException {
            String text;
            try (InputStream body = response.getBody()) {
                text = new String(body.readNBytes(MAX_ERROR_BODY), StandardCharsets.UTF_8);
            }
            String message = text;
            try {
                JsonNode node = JSON.readTree(text);
                if (node.hasNonNull("error")) {
                    message = node.get("error").asString();
                }
            } catch (RuntimeException ignored) {
                // Not JSON: keep the (bounded) text.
            }
            message = message.replaceAll("\\s+", " ").strip();
            throw new OllamaHttpError(response.getStatusCode().value(),
                    message.length() > 300 ? message.substring(0, 300) + "…" : message.isEmpty() ? "no details" : message);
        }
    }
}
