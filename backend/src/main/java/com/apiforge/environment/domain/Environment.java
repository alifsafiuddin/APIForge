package com.apiforge.environment.domain;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;

import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import com.apiforge.common.persistence.AuditableEntity;

/**
 * Where and how tests are executed. Credentials are never stored here: {@code authConfig}
 * only names the variables that hold them (see {@link EnvironmentVariable}).
 */
@Entity
@Table(name = "environments")
public class Environment extends AuditableEntity {

    public enum ExecutionPolicy {
        READ_ONLY, ALLOW_MUTATING, ALLOW_DESTRUCTIVE
    }

    public enum AuthType {
        NONE, BEARER, BASIC, API_KEY_HEADER, API_KEY_QUERY
    }

    @Column(name = "project_id", nullable = false, updatable = false)
    private UUID projectId;

    @Column(name = "name", nullable = false, length = 100)
    private String name;

    @Column(name = "base_url", nullable = false, length = 2048)
    private String baseUrl;

    @Column(name = "timeout_ms", nullable = false)
    private int timeoutMs = 15_000;

    @Column(name = "follow_redirects", nullable = false)
    private boolean followRedirects;

    @Enumerated(EnumType.STRING)
    @Column(name = "execution_policy", nullable = false, length = 20)
    private ExecutionPolicy executionPolicy = ExecutionPolicy.READ_ONLY;

    @Enumerated(EnumType.STRING)
    @Column(name = "auth_type", nullable = false, length = 20)
    private AuthType authType = AuthType.NONE;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "auth_config", nullable = false)
    private Map<String, Object> authConfig = Map.of();

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "default_headers", nullable = false)
    private Map<String, String> defaultHeaders = Map.of();

    protected Environment() {
    }

    public Environment(UUID projectId, String name, String baseUrl) {
        this.projectId = projectId;
        this.name = name;
        this.baseUrl = baseUrl;
    }

    /** Replaces every setting; values were validated by the service. */
    public void configure(String newName, String newBaseUrl, int newTimeoutMs, boolean newFollowRedirects,
            ExecutionPolicy newPolicy, AuthType newAuthType, Map<String, Object> newAuthConfig,
            Map<String, String> newDefaultHeaders) {
        this.name = newName;
        this.baseUrl = newBaseUrl;
        this.timeoutMs = newTimeoutMs;
        this.followRedirects = newFollowRedirects;
        this.executionPolicy = newPolicy;
        this.authType = newAuthType;
        this.authConfig = new LinkedHashMap<>(newAuthConfig);
        this.defaultHeaders = new LinkedHashMap<>(newDefaultHeaders);
    }

    public Map<String, String> getDefaultHeaders() {
        return defaultHeaders;
    }

    public UUID getProjectId() {
        return projectId;
    }

    public String getName() {
        return name;
    }

    public String getBaseUrl() {
        return baseUrl;
    }

    public int getTimeoutMs() {
        return timeoutMs;
    }

    public boolean isFollowRedirects() {
        return followRedirects;
    }

    public ExecutionPolicy getExecutionPolicy() {
        return executionPolicy;
    }

    public AuthType getAuthType() {
        return authType;
    }

    public Map<String, Object> getAuthConfig() {
        return authConfig;
    }
}
