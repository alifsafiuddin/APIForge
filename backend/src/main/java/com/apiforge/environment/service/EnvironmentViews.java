package com.apiforge.environment.service;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.apiforge.environment.domain.Environment;
import com.apiforge.environment.domain.EnvironmentVariable;

/** API representations of environments. Secret values never appear in any of them. */
public final class EnvironmentViews {

    private EnvironmentViews() {
    }

    /** {@code value} is {@code null} for secrets; {@code hasValue} says a secret is set. */
    public record VariableView(String name, boolean secret, String value, boolean hasValue, Instant updatedAt) {

        static VariableView from(EnvironmentVariable variable) {
            return new VariableView(variable.getName(), variable.isSecret(),
                    variable.isSecret() ? null : variable.getPlainValue(),
                    variable.isSecret() ? variable.getEncryptedValue() != null : variable.getPlainValue() != null,
                    variable.getUpdatedAt());
        }
    }

    /** Authentication settings: the type plus the names of the variables (never the values). */
    public record AuthView(Environment.AuthType type, Map<String, Object> config) {
    }

    public record EnvironmentView(UUID id, String name, String baseUrl, int timeoutMs, boolean followRedirects,
            Environment.ExecutionPolicy executionPolicy, AuthView auth, Map<String, String> defaultHeaders,
            List<VariableView> variables, Instant createdAt, Instant updatedAt, long version) {

        static EnvironmentView from(Environment environment, List<EnvironmentVariable> variables) {
            return new EnvironmentView(environment.getId(), environment.getName(), environment.getBaseUrl(),
                    environment.getTimeoutMs(), environment.isFollowRedirects(), environment.getExecutionPolicy(),
                    new AuthView(environment.getAuthType(), environment.getAuthConfig()),
                    environment.getDefaultHeaders(), variables.stream().map(VariableView::from).toList(),
                    environment.getCreatedAt(), environment.getUpdatedAt(), environment.getVersion());
        }
    }

    /**
     * Everything the executor needs, with secrets decrypted in memory. Never serialized, logged or
     * returned by the API; {@code toString()} hides the values.
     */
    public record ExecutionEnvironment(UUID id, String name, String baseUrl, int timeoutMs, boolean followRedirects,
            Environment.ExecutionPolicy executionPolicy, Environment.AuthType authType, Map<String, Object> authConfig,
            Map<String, String> defaultHeaders, Map<String, String> plainVariables, Map<String, String> secretVariables) {

        public ExecutionEnvironment {
            authConfig = Map.copyOf(authConfig);
            defaultHeaders = Map.copyOf(defaultHeaders);
            plainVariables = Map.copyOf(plainVariables);
            secretVariables = Map.copyOf(secretVariables);
        }

        @Override
        public String toString() {
            return "ExecutionEnvironment[id=" + id + ", name=" + name + ", secrets=" + secretVariables.keySet() + "]";
        }
    }
}
