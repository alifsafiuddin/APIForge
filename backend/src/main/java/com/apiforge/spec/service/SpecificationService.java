package com.apiforge.spec.service;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.apiforge.audit.domain.AuditEventType;
import com.apiforge.audit.service.AuditService;
import com.apiforge.common.error.ApiException;
import com.apiforge.common.error.ErrorCode;
import com.apiforge.config.ApiForgeProperties;
import com.apiforge.project.domain.Project;
import com.apiforge.project.service.ProjectAccessService;
import com.apiforge.spec.domain.AnalyzedSpecification;
import com.apiforge.spec.domain.ApiOperation;
import com.apiforge.spec.domain.ImportMessage;
import com.apiforge.spec.domain.SpecificationRejectedException;
import com.apiforge.spec.domain.SpecificationVersion;
import com.apiforge.spec.internal.SpecificationAnalyzer;
import com.apiforge.spec.repository.ApiOperationRepository;
import com.apiforge.spec.repository.SpecificationVersionRepository;
import com.apiforge.spec.service.SpecificationViews.ImportResult;
import com.apiforge.spec.service.SpecificationViews.OperationDetail;
import com.apiforge.spec.service.SpecificationViews.OperationSummary;
import com.apiforge.spec.service.SpecificationViews.RawSpecification;
import com.apiforge.spec.service.SpecificationViews.ValidationResult;
import com.apiforge.spec.service.SpecificationViews.VersionDetail;
import com.apiforge.spec.service.SpecificationViews.VersionSummary;
import com.apiforge.testcase.repository.TestCaseRepository;

/**
 * Imports OpenAPI documents into a project as immutable, numbered versions and serves them to
 * the explorer. All access goes through the owned project.
 */
@Service
public class SpecificationService {

    /** Uploaded or pasted document. {@code sourceName} is informational (file name or label). */
    public record SpecificationUpload(byte[] content, String sourceName) {
    }

    private final ApplicationEventPublisher events;
    private final ProjectAccessService access;
    private final SpecificationVersionRepository versions;
    private final ApiOperationRepository operations;
    private final TestCaseRepository testCases;
    private final SpecificationAnalyzer analyzer;
    private final AuditService audit;
    private final Clock clock;
    private final long maxSizeBytes;

    public SpecificationService(ProjectAccessService access, SpecificationVersionRepository versions,
            ApiOperationRepository operations, TestCaseRepository testCases, SpecificationAnalyzer analyzer,
            AuditService audit, Clock clock, ApiForgeProperties properties, ApplicationEventPublisher events) {
        this.access = access;
        this.events = events;
        this.versions = versions;
        this.operations = operations;
        this.testCases = testCases;
        this.analyzer = analyzer;
        this.audit = audit;
        this.clock = clock;
        this.maxSizeBytes = properties.specification().maxSize().toBytes();
    }

    /** Dry run: reports what an import would do. Never persists anything. */
    @Transactional(readOnly = true)
    public ValidationResult validate(UUID userId, UUID projectId, SpecificationUpload upload) {
        access.requireOwned(projectId, userId);
        checkSize(upload);
        try {
            AnalyzedSpecification analyzed = analyzer.analyze(upload.content());
            return new ValidationResult(true, analyzed.openapiVersion(), analyzed.title(), analyzed.apiVersion(),
                    analyzed.operations().size(), toMaps(analyzed.messages()));
        } catch (SpecificationRejectedException ex) {
            return new ValidationResult(false, null, null, null, null, toMaps(ex.messages()));
        }
    }

    @Transactional
    public ImportResult importSpecification(UUID userId, UUID projectId, SpecificationUpload upload, boolean activate) {
        // Locked so concurrent imports into one project receive consecutive version numbers.
        Project project = access.requireOwnedForUpdate(projectId, userId);
        checkSize(upload);
        AnalyzedSpecification analyzed;
        try {
            analyzed = analyzer.analyze(upload.content());
        } catch (SpecificationRejectedException ex) {
            long errors = ex.messages().stream().filter(ImportMessage::isError).count();
            throw new ApiException(ErrorCode.SPEC_INVALID,
                    "The specification cannot be imported: %d problem%s found.".formatted(errors, errors == 1 ? "" : "s"),
                    List.of(), Map.of("messages", toMaps(ex.messages())));
        }

        List<ImportMessage> messages = new ArrayList<>(analyzed.messages());
        versions.findFirstByProjectIdAndContentSha256OrderByVersionNumberDesc(projectId, analyzed.contentSha256())
                .ifPresent(identical -> messages.add(ImportMessage.info(ImportMessage.Code.IDENTICAL_CONTENT,
                        "The content is identical to version %d.".formatted(identical.getVersionNumber()), null)));

        int versionNumber = versions.findHighestVersionNumber(projectId) + 1;
        Instant now = Instant.now(clock);
        SpecificationVersion version = versions.saveAndFlush(new SpecificationVersion(projectId, versionNumber,
                analyzed.toVersionContents(sanitizeSourceName(upload.sourceName()), messages), userId, now));
        List<ApiOperation> saved = new ArrayList<>();
        for (int index = 0; index < analyzed.operations().size(); index++) {
            saved.add(new ApiOperation(version.getId(), index, analyzed.operations().get(index)));
        }
        operations.saveAll(saved);

        if (activate || project.getActiveSpecificationVersionId() == null) {
            activate(project, version);
        }
        audit.record(AuditEventType.SPECIFICATION_IMPORTED, userId, null, projectId,
                Map.of("versionNumber", versionNumber, "operationCount", saved.size()));
        return new ImportResult(VersionSummary.from(version, project.getActiveSpecificationVersionId()),
                toMaps(messages));
    }

    @Transactional
    public VersionSummary activate(UUID userId, UUID projectId, UUID versionId) {
        Project project = access.requireOwnedForUpdate(projectId, userId);
        SpecificationVersion version = requireVersion(projectId, versionId);
        activate(project, version);
        audit.record(AuditEventType.SPECIFICATION_ACTIVATED, userId, null, projectId,
                Map.of("versionNumber", version.getVersionNumber()));
        return VersionSummary.from(version, version.getId());
    }

    @Transactional(readOnly = true)
    public Page<VersionSummary> listVersions(UUID userId, UUID projectId, Pageable pageable) {
        Project project = access.requireOwned(projectId, userId);
        List<VersionSummary> all = versions.findByProjectIdOrderByVersionNumberDesc(projectId).stream()
                .map(version -> VersionSummary.from(version, project.getActiveSpecificationVersionId()))
                .toList();
        int from = (int) Math.min(pageable.getOffset(), all.size());
        int to = Math.min(from + pageable.getPageSize(), all.size());
        return new PageImpl<>(all.subList(from, to), pageable, all.size());
    }

    @Transactional(readOnly = true)
    public VersionDetail getVersion(UUID userId, UUID projectId, UUID versionId) {
        Project project = access.requireOwned(projectId, userId);
        return VersionDetail.from(requireVersion(projectId, versionId), project.getActiveSpecificationVersionId());
    }

    @Transactional(readOnly = true)
    public RawSpecification rawContent(UUID userId, UUID projectId, UUID versionId) {
        access.requireOwned(projectId, userId);
        SpecificationVersion version = requireVersion(projectId, versionId);
        return new RawSpecification(version.getRawContent(), version.getSourceFormat(), version.getVersionNumber());
    }

    @Transactional(readOnly = true)
    public Map<String, Object> componentSchemas(UUID userId, UUID projectId, UUID versionId) {
        access.requireOwned(projectId, userId);
        return requireVersion(projectId, versionId).getComponentSchemas();
    }

    /** The complete operation index of a version (bounded by the import limit on operations). */
    @Transactional(readOnly = true)
    public List<OperationSummary> listOperations(UUID userId, UUID projectId, UUID versionId) {
        access.requireOwned(projectId, userId);
        requireVersion(projectId, versionId);
        return operations.findBySpecificationVersionIdOrderBySortOrderAsc(versionId).stream()
                .map(OperationSummary::from)
                .toList();
    }

    @Transactional(readOnly = true)
    public OperationDetail getOperation(UUID userId, UUID projectId, UUID versionId, UUID operationId) {
        access.requireOwned(projectId, userId);
        requireVersion(projectId, versionId);
        return operations.findByIdAndSpecificationVersionId(operationId, versionId)
                .map(OperationDetail::from)
                .orElseThrow(() -> ApiException.notFound("Operation"));
    }

    /**
     * Makes {@code version} active and updates test lifecycles: tests for operations that no longer
     * exist become STALE (kept with their history); stale tests whose operation is back become ACTIVE.
     */
    private void activate(Project project, SpecificationVersion version) {
        project.activateSpecification(version.getId());
        List<String> keys = operations.findOperationKeys(version.getId());
        // "NOT IN ()" is not valid SQL; an impossible key keeps the query well-formed for empty specs.
        List<String> keysOrSentinel = keys.isEmpty() ? List.of("") : keys;
        testCases.markStaleExcept(project.getId(), keysOrSentinel);
        testCases.reactivate(project.getId(), keysOrSentinel);
        events.publishEvent(new SpecificationActivated(project.getId(), version.getId()));
    }

    private SpecificationVersion requireVersion(UUID projectId, UUID versionId) {
        return versions.findByIdAndProjectId(versionId, projectId)
                .orElseThrow(() -> ApiException.notFound("Specification version"));
    }

    private void checkSize(SpecificationUpload upload) {
        if (upload.content().length > maxSizeBytes) {
            throw new ApiException(ErrorCode.PAYLOAD_TOO_LARGE,
                    "The specification is %s; the maximum is %s.".formatted(humanSize(upload.content().length),
                            humanSize(maxSizeBytes)));
        }
    }

    /** Keeps only the file name, without directories or control characters. */
    static String sanitizeSourceName(String sourceName) {
        if (sourceName == null) {
            return null;
        }
        String name = sourceName.replace('\\', '/');
        name = name.substring(name.lastIndexOf('/') + 1).replaceAll("\\p{Cntrl}", "").strip();
        if (name.isEmpty()) {
            return null;
        }
        return name.length() > 255 ? name.substring(0, 255) : name;
    }

    private static String humanSize(long bytes) {
        return bytes >= 1024 * 1024 ? "%.1f MB".formatted(bytes / (1024.0 * 1024)) : "%d KB".formatted(bytes / 1024);
    }

    private static List<Map<String, Object>> toMaps(List<ImportMessage> messages) {
        return messages.stream().map(ImportMessage::toMap).toList();
    }
}
