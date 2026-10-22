package com.apiforge.ai.client;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.ConnectException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;

import org.junit.jupiter.api.Test;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClientException;

import com.apiforge.ai.client.AiFailure.Kind;

class OllamaGatewayTest {

    private static Kind kind(RuntimeException ex) {
        return OllamaGateway.classify(ex).kind();
    }

    @Test
    void ollamaErrorsAreClassifiedByStatusAndMessage() {
        assertThat(kind(new OllamaGateway.OllamaHttpError(404, "model \"x\" not found, try pulling it first")))
                .isEqualTo(Kind.MODEL_NOT_FOUND);
        assertThat(kind(new OllamaGateway.OllamaHttpError(503, "server busy, please try again"))).isEqualTo(Kind.OVERLOADED);
        assertThat(kind(new OllamaGateway.OllamaHttpError(429, "too many requests"))).isEqualTo(Kind.OVERLOADED);
        assertThat(kind(new OllamaGateway.OllamaHttpError(500, "model requires more system memory than is available")))
                .isEqualTo(Kind.MODEL_ERROR);
        assertThat(kind(new RestClientException("wrapped", new OllamaGateway.OllamaHttpError(404, "model not found"))))
                .isEqualTo(Kind.MODEL_NOT_FOUND);
    }

    @Test
    void transportFailuresAreFoundAnywhereInTheCauseChain() {
        assertThat(kind(new ResourceAccessException("x", new ConnectException("Connection refused")))).isEqualTo(Kind.UNAVAILABLE);
        assertThat(kind(new ResourceAccessException("x", new UnknownHostException("ollama")))).isEqualTo(Kind.UNAVAILABLE);
        assertThat(kind(new ResourceAccessException("x", new SocketTimeoutException("Read timed out")))).isEqualTo(Kind.TIMEOUT);
        assertThat(kind(new ResourceAccessException("x", new SocketTimeoutException("Connect timed out"))))
                .isEqualTo(Kind.UNAVAILABLE);
        assertThat(kind(new RestClientException("outer", new RuntimeException(new SocketTimeoutException("Read timed out")))))
                .isEqualTo(Kind.TIMEOUT);
        assertThat(kind(new ResourceAccessException("I/O error"))).isEqualTo(Kind.UNAVAILABLE);
        assertThat(kind(new IllegalStateException("?"))).isEqualTo(Kind.MODEL_ERROR);
    }

    @Test
    void onlyOverloadAndMalformedOutputAreRetryable() {
        assertThat(Kind.values()).filteredOn(Kind::retryable).containsExactlyInAnyOrder(Kind.OVERLOADED, Kind.INVALID_OUTPUT);
    }
}
