package com.apiforge.environment.service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.apiforge.audit.domain.AuditEventType;
import com.apiforge.audit.service.AuditService;
import com.apiforge.common.error.ApiException;
import com.apiforge.common.error.ErrorCode;
import com.apiforge.common.error.Problems.FieldErrorItem;
import com.apiforge.common.http.HeaderRules;
import com.apiforge.environment.domain.Environment;
import com.apiforge.environment.domain.Environment.AuthType;
import com.apiforge.environment.domain.Environment.ExecutionPolicy;
import com.apiforge.environment.domain.EnvironmentVariable;
import com.apiforge.environment.repository.EnvironmentRepository;
import com.apiforge.environment.repository.EnvironmentVariableRepository;
import com.apiforge.environment.service.EnvironmentViews.EnvironmentView;
import com.apiforge.environment.service.EnvironmentViews.ExecutionEnvironment;
import com.apiforge.environment.service.EnvironmentViews.VariableView;
import com.apiforge.outbound.DestinationPolicy;
import com.apiforge.outbound.OutboundBlockedException;
import com.apiforge.outbound.OutboundProperties;
import com.apiforge.outbound.UrlGuard;
import com.apiforge.project.service.ProjectAccessService;
import com.apiforge.secrets.SecretCipher;

/**
 * Execution environments: target base URL, timeouts, redirect handling, execution policy,
 * authentication, default headers and variables. Secret variables are encrypted with
 * {@link SecretCipher} and are write-only through the API.
 */
@Service
public class EnvironmentService {

    /** Authentication settings; each field names a variable that holds the credential. */
    public record AuthInput(AuthType type, String tokenVariable, String usernameVariable, String passwordVariable,
            String headerName, String parameterName, String valueVariable) {
    }

    /**
     * @param authorizationConfirmed required when the policy allows mutating or destructive tests:
     *                               the user confirms the target is a non-production system they
     *                               are authorized to test
     */
    public record EnvironmentInput(String name, String baseUrl, Integer timeoutMs, Boolean followRedirects,
            ExecutionPolicy executionPolicy, Boolean authorizationConfirmed, AuthInput auth,
            Map<String, String> defaultHeaders) {
    }

    static final Pattern VARIABLE_NAME = Pattern.compile("^[A-Za-z_][A-Za-z0-9_]{0,63}$");
    private static final Pattern PARAMETER_NAME = Pattern.compile("^[A-Za-z0-9_.\\-\\[\\]]{1,100}$");
    static final int MAX_VARIABLE_LENGTH = 4096;
    static final int MAX_DEFAULT_HEADERS = 30;
    static final int MAX_VARIABLES = 100;

    private final ProjectAccessService access;
    private final EnvironmentRepository environments;
    private final EnvironmentVariableRepository variables;
    private final DestinationPolicy policy;
    private final SecretCipher cipher;
    private final AuditService audit;
    private final long maxTimeoutMs;

    public EnvironmentService(ProjectAccessService access, EnvironmentRepository environments,
            EnvironmentVariableRepository variables, DestinationPolicy policy, SecretCipher cipher, AuditService audit,
            OutboundProperties outbound) {
        this.access = access;
        this.environments = environments;
        this.variables = variables;
        this.policy = policy;
        this.cipher = cipher;
        this.audit = audit;
        this.maxTimeoutMs = Math.min(60_000, outbound.maxReadTimeout().toMillis());
    }

    @Transactional(readOnly = true)
    public List<EnvironmentView> list(UUID userId, UUID projectId) {
        access.requireOwned(projectId, userId);
        return environments.findByProjectIdOrderByNameAsc(projectId).stream()
                .map(environment -> EnvironmentView.from(environment,
                        variables.findByEnvironmentIdOrderByNameAsc(environment.getId())))
                .toList();
    }

    @Transactional(readOnly = true)
    public EnvironmentView get(UUID userId, UUID projectId, UUID environmentId) {
        access.requireOwned(projectId, userId);
        Environment environment = require(projectId, environmentId);
        return EnvironmentView.from(environment, variables.findByEnvironmentIdOrderByNameAsc(environmentId));
    }

    @Transactional
    public EnvironmentView create(UUID userId, UUID projectId, EnvironmentInput input) {
        access.requireOwned(projectId, userId);
        Validated validated = validate(input);
        if (environments.existsByProjectIdAndNameIgnoreCase(projectId, validated.name())) {
            throw duplicate(validated.name());
        }
        Environment environment = new Environment(projectId, validated.name(), validated.baseUrl());
        apply(environment, validated);
        environment = save(environment);
        audit.record(AuditEventType.ENVIRONMENT_CHANGED, userId, null, projectId,
                Map.of("action", "created", "environmentId", environment.getId().toString()));
        return EnvironmentView.from(environment, List.of());
    }

    @Transactional
    public EnvironmentView update(UUID userId, UUID projectId, UUID environmentId, EnvironmentInput input,
            long expectedVersion) {
        access.requireOwned(projectId, userId);
        Environment environment = require(projectId, environmentId);
        if (environment.getVersion() != expectedVersion) {
            throw new ApiException(ErrorCode.STALE_VERSION,
                    "The environment was modified since you loaded it. Reload it and try again.");
        }
        Validated validated = validate(input);
        if (!environment.getName().equalsIgnoreCase(validated.name())
                && environments.existsByProjectIdAndNameIgnoreCase(projectId, validated.name())) {
            throw duplicate(validated.name());
        }
        apply(environment, validated);
        environment = save(environment);
        audit.record(AuditEventType.ENVIRONMENT_CHANGED, userId, null, projectId,
                Map.of("action", "updated", "environmentId", environmentId.toString()));
        return EnvironmentView.from(environment, variables.findByEnvironmentIdOrderByNameAsc(environmentId));
    }

    /** Runs keep their environment snapshots (name and base URL) after a delete. */
    @Transactional
    public void delete(UUID userId, UUID projectId, UUID environmentId) {
        access.requireOwned(projectId, userId);
        Environment environment = require(projectId, environmentId);
        environments.delete(environment);
        audit.record(AuditEventType.ENVIRONMENT_CHANGED, userId, null, projectId,
                Map.of("action", "deleted", "environmentId", environmentId.toString()));
    }

    /** Creates or replaces a variable. A secret is encrypted; its value is never returned again. */
    @Transactional
    public VariableView putVariable(UUID userId, UUID projectId, UUID environmentId, String name, String value,
            boolean secret) {
        access.requireOwned(projectId, userId);
        require(projectId, environmentId);
        if (name == null || !VARIABLE_NAME.matcher(name).matches()) {
            throw new ApiException(ErrorCode.VALIDATION_ERROR,
                    "Variable names start with a letter or underscore and contain only letters, digits and underscores (max 64).");
        }
        if (value == null || value.length() > MAX_VARIABLE_LENGTH || value.chars().anyMatch(c -> c == '\r' || c == '\n' || c == 0)) {
            throw new ApiException(ErrorCode.VALIDATION_ERROR,
                    "Variable values must be at most %d characters, without line breaks.".formatted(MAX_VARIABLE_LENGTH));
        }
        EnvironmentVariable variable = variables.findByEnvironmentIdAndName(environmentId, name).orElse(null);
        if (variable == null && variables.findByEnvironmentIdOrderByNameAsc(environmentId).size() >= MAX_VARIABLES) {
            throw new ApiException(ErrorCode.VALIDATION_ERROR, "An environment can have at most %d variables."
                    .formatted(MAX_VARIABLES));
        }
        if (secret) {
            byte[] ciphertext = cipher.encrypt(value, context(environmentId, name));
            if (variable == null) {
                variable = EnvironmentVariable.secret(environmentId, name, ciphertext, cipher.keyId());
            } else {
                variable.setSecret(ciphertext, cipher.keyId());
            }
            audit.record(AuditEventType.SECRET_CHANGED, userId, null, projectId,
                    Map.of("environmentId", environmentId.toString(), "variable", name));
        } else if (variable == null) {
            variable = EnvironmentVariable.plain(environmentId, name, value);
        } else {
            variable.setPlain(value);
        }
        return VariableView.from(variables.saveAndFlush(variable));
    }

    @Transactional
    public void deleteVariable(UUID userId, UUID projectId, UUID environmentId, String name) {
        access.requireOwned(projectId, userId);
        require(projectId, environmentId);
        EnvironmentVariable variable = variables.findByEnvironmentIdAndName(environmentId, name)
                .orElseThrow(() -> ApiException.notFound("Variable"));
        variables.delete(variable);
        if (variable.isSecret()) {
            audit.record(AuditEventType.SECRET_CHANGED, userId, null, projectId,
                    Map.of("environmentId", environmentId.toString(), "variable", name, "action", "deleted"));
        }
    }

    /**
     * The environment with its secrets decrypted, for the executor only. A secret that cannot be
     * decrypted (for example after a key change) is left out, so requests using it fail with an
     * unresolved-variable error instead of sending a wrong value.
     */
    @Transactional(readOnly = true)
    public ExecutionEnvironment forExecution(UUID projectId, UUID environmentId) {
        Environment environment = require(projectId, environmentId);
        Map<String, String> plain = new HashMap<>();
        Map<String, String> secrets = new HashMap<>();
        for (EnvironmentVariable variable : variables.findByEnvironmentIdOrderByNameAsc(environmentId)) {
            if (!variable.isSecret()) {
                plain.put(variable.getName(), variable.getPlainValue());
                continue;
            }
            try {
                secrets.put(variable.getName(), cipher.decrypt(variable.getEncryptedValue(), variable.getKeyId(),
                        context(environmentId, variable.getName())));
            } catch (SecretCipher.SecretDecryptionException ex) {
                // reported as unresolved when used
            }
        }
        return new ExecutionEnvironment(environment.getId(), environment.getName(), environment.getBaseUrl(),
                environment.getTimeoutMs(), environment.isFollowRedirects(), environment.getExecutionPolicy(),
                environment.getAuthType(), environment.getAuthConfig(), environment.getDefaultHeaders(), plain, secrets);
    }

    private static String context(UUID environmentId, String name) {
        return "environment-variable:" + environmentId + ":" + name;
    }

    private record Validated(String name, String baseUrl, int timeoutMs, boolean followRedirects,
            ExecutionPolicy policy, AuthType authType, Map<String, Object> authConfig, Map<String, String> headers) {
    }

    private Validated validate(EnvironmentInput input) {
        List<FieldErrorItem> errors = new ArrayList<>();
        String name = input.name() == null ? "" : input.name().strip();
        if (name.isEmpty() || name.length() > 100 || name.chars().anyMatch(Character::isISOControl)) {
            errors.add(new FieldErrorItem("name", "must be 1–100 characters without control characters"));
        }
        String baseUrl = input.baseUrl() == null ? "" : input.baseUrl().strip();
        while (baseUrl.endsWith("/")) {
            baseUrl = baseUrl.substring(0, baseUrl.length() - 1);
        }
        try {
            UrlGuard.ParsedUrl parsed = UrlGuard.parse(baseUrl);
            if (parsed.uri().getRawQuery() != null || parsed.uri().getRawFragment() != null) {
                errors.add(new FieldErrorItem("baseUrl", "must not contain a query string or fragment"));
            }
            policy.checkWithoutLookup(parsed);
        } catch (OutboundBlockedException ex) {
            errors.add(new FieldErrorItem("baseUrl", ex.getMessage()));
        }
        int timeoutMs = input.timeoutMs() == null ? 15_000 : input.timeoutMs();
        if (timeoutMs < 100 || timeoutMs > maxTimeoutMs) {
            errors.add(new FieldErrorItem("timeoutMs", "must be between 100 and %d".formatted(maxTimeoutMs)));
        }
        ExecutionPolicy executionPolicy = input.executionPolicy() == null ? ExecutionPolicy.READ_ONLY
                : input.executionPolicy();
        if (executionPolicy != ExecutionPolicy.READ_ONLY && !Boolean.TRUE.equals(input.authorizationConfirmed())) {
            errors.add(new FieldErrorItem("authorizationConfirmed", "confirm that the target is a non-production "
                    + "system you are authorized to test before allowing mutating or destructive requests"));
        }
        AuthInput auth = input.auth() == null ? new AuthInput(AuthType.NONE, null, null, null, null, null, null)
                : input.auth();
        Map<String, Object> authConfig = authConfig(auth, errors);
        Map<String, String> headers = defaultHeaders(input.defaultHeaders(), errors);
        if (!errors.isEmpty()) {
            throw new ApiException(ErrorCode.VALIDATION_ERROR, "The environment settings are invalid.", errors);
        }
        return new Validated(name, baseUrl, timeoutMs, Boolean.TRUE.equals(input.followRedirects()), executionPolicy,
                auth.type() == null ? AuthType.NONE : auth.type(), authConfig, headers);
    }

    private static Map<String, Object> authConfig(AuthInput auth, List<FieldErrorItem> errors) {
        Map<String, Object> config = new LinkedHashMap<>();
        AuthType type = auth.type() == null ? AuthType.NONE : auth.type();
        switch (type) {
            case NONE -> {
                // nothing to configure
            }
            case BEARER -> config.put("tokenVariable", variable("auth.tokenVariable", auth.tokenVariable(), errors));
            case BASIC -> {
                config.put("usernameVariable", variable("auth.usernameVariable", auth.usernameVariable(), errors));
                config.put("passwordVariable", variable("auth.passwordVariable", auth.passwordVariable(), errors));
            }
            case API_KEY_HEADER -> {
                String header = auth.headerName() == null ? "" : auth.headerName().strip();
                if (!HeaderRules.isValidName(header) || HeaderRules.isForbidden(header)) {
                    errors.add(new FieldErrorItem("auth.headerName", "must be a header name other than transport, "
                            + "Content-Type, Authorization or Cookie headers"));
                }
                config.put("headerName", header);
                config.put("valueVariable", variable("auth.valueVariable", auth.valueVariable(), errors));
            }
            case API_KEY_QUERY -> {
                String parameter = auth.parameterName() == null ? "" : auth.parameterName().strip();
                if (!PARAMETER_NAME.matcher(parameter).matches()) {
                    errors.add(new FieldErrorItem("auth.parameterName", "must be 1–100 letters, digits or _ . - [ ]"));
                }
                config.put("parameterName", parameter);
                config.put("valueVariable", variable("auth.valueVariable", auth.valueVariable(), errors));
            }
        }
        return config;
    }

    private static String variable(String field, String name, List<FieldErrorItem> errors) {
        if (name == null || !VARIABLE_NAME.matcher(name).matches()) {
            errors.add(new FieldErrorItem(field, "must name a variable (letters, digits and underscores)"));
            return "";
        }
        return name;
    }

    private static Map<String, String> defaultHeaders(Map<String, String> headers, List<FieldErrorItem> errors) {
        Map<String, String> result = new LinkedHashMap<>();
        if (headers == null) {
            return result;
        }
        if (headers.size() > MAX_DEFAULT_HEADERS) {
            errors.add(new FieldErrorItem("defaultHeaders", "at most %d headers".formatted(MAX_DEFAULT_HEADERS)));
            return result;
        }
        Set<String> seen = new HashSet<>();
        headers.forEach((name, value) -> {
            String field = "defaultHeaders." + name;
            if (!HeaderRules.isValidName(name)) {
                errors.add(new FieldErrorItem("defaultHeaders", "header names must be HTTP tokens of 1–100 characters"));
            } else if (HeaderRules.isForbidden(name)) {
                errors.add(new FieldErrorItem(field, "cannot be set here (transport, Content-Type and credentials "
                        + "headers are managed by the client and the authentication settings)"));
            } else if (!seen.add(name.toLowerCase(Locale.ROOT))) {
                errors.add(new FieldErrorItem(field, "is set more than once"));
            } else if (!HeaderRules.isValidValue(value)) {
                errors.add(new FieldErrorItem(field, "must be at most %d characters without line breaks"
                        .formatted(HeaderRules.MAX_VALUE_LENGTH)));
            } else {
                result.put(name, value);
            }
        });
        return result;
    }

    private static void apply(Environment environment, Validated validated) {
        environment.configure(validated.name(), validated.baseUrl(), validated.timeoutMs(), validated.followRedirects(),
                validated.policy(), validated.authType(), validated.authConfig(), validated.headers());
    }

    private Environment require(UUID projectId, UUID environmentId) {
        return environments.findByIdAndProjectId(environmentId, projectId)
                .orElseThrow(() -> ApiException.notFound("Environment"));
    }

    private Environment save(Environment environment) {
        try {
            return environments.saveAndFlush(environment);
        } catch (DataIntegrityViolationException ex) {
            throw duplicate(environment.getName());
        }
    }

    private static ApiException duplicate(String name) {
        return new ApiException(ErrorCode.DUPLICATE_NAME, "An environment named '%s' already exists.".formatted(name));
    }
}
