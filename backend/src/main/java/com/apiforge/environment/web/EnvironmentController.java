package com.apiforge.environment.web;

import java.net.URI;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.apiforge.auth.service.AuthenticatedUser;
import com.apiforge.common.error.ApiException;
import com.apiforge.common.error.ErrorCode;
import com.apiforge.environment.domain.Environment.ExecutionPolicy;
import com.apiforge.environment.service.EnvironmentService;
import com.apiforge.environment.service.EnvironmentService.AuthInput;
import com.apiforge.environment.service.EnvironmentService.EnvironmentInput;
import com.apiforge.environment.service.EnvironmentViews.EnvironmentView;
import com.apiforge.environment.service.EnvironmentViews.VariableView;

@RestController
@Validated
@RequestMapping("/api/v1/projects/{projectId}/environments")
public class EnvironmentController {

    private final EnvironmentService environments;

    public EnvironmentController(EnvironmentService environments) {
        this.environments = environments;
    }

    public record EnvironmentRequest(@Size(max = 100) String name, @Size(max = 2048) String baseUrl, Integer timeoutMs,
            Boolean followRedirects, ExecutionPolicy executionPolicy, Boolean authorizationConfirmed, AuthInput auth,
            Map<String, String> defaultHeaders, @PositiveOrZero Long version) {

        EnvironmentInput toInput() {
            return new EnvironmentInput(name, baseUrl, timeoutMs, followRedirects, executionPolicy,
                    authorizationConfirmed, auth, defaultHeaders);
        }
    }

    /** {@code value} is write-only when {@code secret} is true. */
    public record VariableRequest(@NotNull @Size(max = 4096) String value, boolean secret) {
    }

    @GetMapping
    public List<EnvironmentView> list(@AuthenticationPrincipal AuthenticatedUser user, @PathVariable UUID projectId) {
        return environments.list(user.id(), projectId);
    }

    @PostMapping
    public ResponseEntity<EnvironmentView> create(@AuthenticationPrincipal AuthenticatedUser user,
            @PathVariable UUID projectId, @Valid @RequestBody EnvironmentRequest body) {
        EnvironmentView created = environments.create(user.id(), projectId, body.toInput());
        return ResponseEntity.created(URI.create("/api/v1/projects/" + projectId + "/environments/" + created.id()))
                .body(created);
    }

    @GetMapping("/{environmentId}")
    public EnvironmentView get(@AuthenticationPrincipal AuthenticatedUser user, @PathVariable UUID projectId,
            @PathVariable UUID environmentId) {
        return environments.get(user.id(), projectId, environmentId);
    }

    @PutMapping("/{environmentId}")
    public EnvironmentView update(@AuthenticationPrincipal AuthenticatedUser user, @PathVariable UUID projectId,
            @PathVariable UUID environmentId, @Valid @RequestBody EnvironmentRequest body) {
        if (body.version() == null) {
            throw new ApiException(ErrorCode.VALIDATION_ERROR,
                    "version is required to update an environment.");
        }
        return environments.update(user.id(), projectId, environmentId, body.toInput(), body.version());
    }

    @DeleteMapping("/{environmentId}")
    public ResponseEntity<Void> delete(@AuthenticationPrincipal AuthenticatedUser user, @PathVariable UUID projectId,
            @PathVariable UUID environmentId) {
        environments.delete(user.id(), projectId, environmentId);
        return ResponseEntity.noContent().build();
    }

    @PutMapping("/{environmentId}/variables/{name}")
    public VariableView putVariable(@AuthenticationPrincipal AuthenticatedUser user, @PathVariable UUID projectId,
            @PathVariable UUID environmentId, @PathVariable @Size(max = 64) String name,
            @Valid @RequestBody VariableRequest body) {
        return environments.putVariable(user.id(), projectId, environmentId, name, body.value(), body.secret());
    }

    @DeleteMapping("/{environmentId}/variables/{name}")
    public ResponseEntity<Void> deleteVariable(@AuthenticationPrincipal AuthenticatedUser user,
            @PathVariable UUID projectId, @PathVariable UUID environmentId, @PathVariable @Size(max = 64) String name) {
        environments.deleteVariable(user.id(), projectId, environmentId, name);
        return ResponseEntity.noContent().build();
    }
}
