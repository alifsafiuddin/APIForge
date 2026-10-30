package com.apiforge.testcase.web;

import java.net.URI;
import java.util.List;
import java.util.UUID;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.apiforge.auth.service.AuthenticatedUser;
import com.apiforge.testcase.service.TestCaseViews.SuiteView;
import com.apiforge.testcase.service.TestSuiteService;

@RestController
@Validated
@RequestMapping("/api/v1/projects/{projectId}/test-suites")
public class TestSuiteController {

    private static final String NO_CONTROL_CHARACTERS = "^[^\\p{Cntrl}]*$";

    private final TestSuiteService suites;

    public TestSuiteController(TestSuiteService suites) {
        this.suites = suites;
    }

    public record CreateSuiteRequest(
            @NotBlank @Size(max = 100) @Pattern(regexp = NO_CONTROL_CHARACTERS,
                    message = "must not contain control characters") String name,
            @Size(max = 2000) String description) {
    }

    public record UpdateSuiteRequest(
            @Size(min = 1, max = 100) @Pattern(regexp = NO_CONTROL_CHARACTERS,
                    message = "must not contain control characters") String name,
            @Size(max = 2000) String description,
            @NotNull @PositiveOrZero Long version) {
    }

    @GetMapping
    public List<SuiteView> list(@AuthenticationPrincipal AuthenticatedUser user, @PathVariable UUID projectId) {
        return suites.list(user.id(), projectId);
    }

    @PostMapping
    public ResponseEntity<SuiteView> create(@AuthenticationPrincipal AuthenticatedUser user,
            @PathVariable UUID projectId, @Valid @RequestBody CreateSuiteRequest body) {
        SuiteView suite = suites.create(user.id(), projectId, body.name(), body.description());
        return ResponseEntity.created(URI.create("/api/v1/projects/" + projectId + "/test-suites/" + suite.id()))
                .body(suite);
    }

    @PatchMapping("/{suiteId}")
    public SuiteView update(@AuthenticationPrincipal AuthenticatedUser user, @PathVariable UUID projectId,
            @PathVariable UUID suiteId, @Valid @RequestBody UpdateSuiteRequest body) {
        return suites.update(user.id(), projectId, suiteId, body.name(), body.description(), body.version());
    }

    @DeleteMapping("/{suiteId}")
    public ResponseEntity<Void> delete(@AuthenticationPrincipal AuthenticatedUser user, @PathVariable UUID projectId,
            @PathVariable UUID suiteId) {
        suites.delete(user.id(), projectId, suiteId);
        return ResponseEntity.noContent().build();
    }
}
