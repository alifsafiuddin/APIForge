package com.apiforge.testcase.web;

import java.util.List;
import java.util.UUID;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.apiforge.auth.service.AuthenticatedUser;
import com.apiforge.common.web.PageResponse;
import com.apiforge.testcase.domain.TestCase;
import com.apiforge.testcase.service.TestCaseService;
import com.apiforge.testcase.service.TestCaseService.BulkChange;
import com.apiforge.testcase.service.TestCaseService.ChangeTest;
import com.apiforge.testcase.service.TestCaseService.EditTest;
import com.apiforge.testcase.service.TestCaseService.ReviewDecision;
import com.apiforge.testcase.service.TestCaseService.TestFilter;
import com.apiforge.testcase.service.TestCaseViews.TestCaseDetail;
import com.apiforge.testcase.service.TestCaseViews.TestCaseSummary;
import com.apiforge.testcase.service.TestCaseViews.TestSummary;
import com.apiforge.testcase.service.TestDefinitionValidator.ExpectationsInput;
import com.apiforge.testcase.service.TestDefinitionValidator.RequestInput;

@RestController
@Validated
@RequestMapping("/api/v1/projects/{projectId}/tests")
public class TestCaseController {

    private final TestCaseService tests;

    public TestCaseController(TestCaseService tests) {
        this.tests = tests;
    }

    public record EditTestRequest(@NotBlank @Size(max = 200) String name, @NotNull RequestInput request,
            @NotNull ExpectationsInput expectations, @Size(max = 4000) String rationale,
            @NotNull @PositiveOrZero Long version) {
    }

    public record ChangeTestRequest(Boolean enabled, UUID suiteId, @NotNull @PositiveOrZero Long version) {
    }

    public record BulkChangeRequest(@NotEmpty @Size(max = 500) List<@NotNull UUID> testIds, Boolean enabled,
            UUID suiteId) {
    }

    public record BulkChangeResult(int updated) {
    }

    public record ReviewRequest(@NotNull ReviewDecision decision) {
    }

    @GetMapping
    public PageResponse<TestCaseSummary> list(@AuthenticationPrincipal AuthenticatedUser user,
            @PathVariable UUID projectId,
            @RequestParam(required = false) @Size(max = 600) String operationKey,
            @RequestParam(required = false) UUID suiteId,
            @RequestParam(required = false) TestCase.Category category,
            @RequestParam(required = false) TestCase.Origin origin,
            @RequestParam(required = false) Boolean enabled,
            @RequestParam(required = false) TestCase.ReviewStatus reviewStatus,
            @RequestParam(required = false) TestCase.LifecycleStatus lifecycleStatus,
            @RequestParam(required = false) @Size(max = 100) String q,
            @PageableDefault(size = 50) Pageable pageable) {
        TestFilter filter = new TestFilter(operationKey, suiteId, category, origin, enabled, reviewStatus,
                lifecycleStatus, q);
        return PageResponse.of(tests.list(user.id(), projectId, filter, pageable));
    }

    /** Test counts per operation, for the workspace's endpoint index. */
    @GetMapping("/summary")
    public TestSummary summary(@AuthenticationPrincipal AuthenticatedUser user, @PathVariable UUID projectId) {
        return tests.summary(user.id(), projectId);
    }

    @GetMapping("/{testId}")
    public TestCaseDetail get(@AuthenticationPrincipal AuthenticatedUser user, @PathVariable UUID projectId,
            @PathVariable UUID testId) {
        return tests.get(user.id(), projectId, testId);
    }

    @PutMapping("/{testId}")
    public TestCaseDetail edit(@AuthenticationPrincipal AuthenticatedUser user, @PathVariable UUID projectId,
            @PathVariable UUID testId, @Valid @RequestBody EditTestRequest body) {
        return tests.edit(user.id(), projectId, testId,
                new EditTest(body.name(), body.request(), body.expectations(), body.rationale(), body.version()));
    }

    /** Enable, disable or move one test. */
    @PatchMapping("/{testId}")
    public TestCaseDetail change(@AuthenticationPrincipal AuthenticatedUser user, @PathVariable UUID projectId,
            @PathVariable UUID testId, @Valid @RequestBody ChangeTestRequest body) {
        return tests.change(user.id(), projectId, testId, new ChangeTest(body.enabled(), body.suiteId(), body.version()));
    }

    @PostMapping("/bulk")
    public BulkChangeResult bulk(@AuthenticationPrincipal AuthenticatedUser user, @PathVariable UUID projectId,
            @Valid @RequestBody BulkChangeRequest body) {
        return new BulkChangeResult(tests.bulkChange(user.id(), projectId,
                new BulkChange(body.testIds(), body.enabled(), body.suiteId())));
    }

    @PostMapping("/{testId}/review")
    public TestCaseDetail review(@AuthenticationPrincipal AuthenticatedUser user, @PathVariable UUID projectId,
            @PathVariable UUID testId, @Valid @RequestBody ReviewRequest body) {
        return tests.review(user.id(), projectId, testId, body.decision());
    }
}
