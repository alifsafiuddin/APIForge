package com.apiforge.spec.web;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import com.apiforge.auth.service.AuthenticatedUser;
import com.apiforge.common.web.PageResponse;
import com.apiforge.spec.service.SpecificationService;
import com.apiforge.spec.service.SpecificationService.SpecificationUpload;
import com.apiforge.spec.service.SpecificationViews.ImportResult;
import com.apiforge.spec.service.SpecificationViews.OperationDetail;
import com.apiforge.spec.service.SpecificationViews.OperationSummary;
import com.apiforge.spec.service.SpecificationViews.RawSpecification;
import com.apiforge.spec.service.SpecificationViews.ValidationResult;
import com.apiforge.spec.service.SpecificationViews.VersionDetail;
import com.apiforge.spec.service.SpecificationViews.VersionSummary;

/**
 * Import (file upload or pasted text), dry-run validation and exploration of OpenAPI
 * specification versions. Size limits are enforced by the multipart configuration, the request
 * size filter and the service.
 */
@RestController
@RequestMapping("/api/v1/projects/{projectId}/specs")
public class SpecificationController {

    private final SpecificationService specifications;

    public SpecificationController(SpecificationService specifications) {
        this.specifications = specifications;
    }

    /** Pasted specification text. The size cap matches the 5 MB document limit with headroom for JSON escaping. */
    public record SpecificationTextRequest(@NotNull @Size(max = 6_000_000) String content,
            @Size(max = 255) String sourceName) {

        SpecificationUpload toUpload() {
            return new SpecificationUpload(content.getBytes(StandardCharsets.UTF_8), sourceName);
        }
    }

    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<ImportResult> importFile(@AuthenticationPrincipal AuthenticatedUser user,
            @PathVariable UUID projectId, @RequestPart("file") MultipartFile file,
            @RequestParam(defaultValue = "true") boolean activate) {
        return created(projectId, specifications.importSpecification(user.id(), projectId, upload(file), activate));
    }

    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<ImportResult> importText(@AuthenticationPrincipal AuthenticatedUser user,
            @PathVariable UUID projectId, @Valid @RequestBody SpecificationTextRequest body,
            @RequestParam(defaultValue = "true") boolean activate) {
        return created(projectId, specifications.importSpecification(user.id(), projectId, body.toUpload(), activate));
    }

    @PostMapping(path = "/validate", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ValidationResult validateFile(@AuthenticationPrincipal AuthenticatedUser user,
            @PathVariable UUID projectId, @RequestPart("file") MultipartFile file) {
        return specifications.validate(user.id(), projectId, upload(file));
    }

    @PostMapping(path = "/validate", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ValidationResult validateText(@AuthenticationPrincipal AuthenticatedUser user,
            @PathVariable UUID projectId, @Valid @RequestBody SpecificationTextRequest body) {
        return specifications.validate(user.id(), projectId, body.toUpload());
    }

    @GetMapping
    public PageResponse<VersionSummary> listVersions(@AuthenticationPrincipal AuthenticatedUser user,
            @PathVariable UUID projectId, @PageableDefault(size = 20) Pageable pageable) {
        return PageResponse.of(specifications.listVersions(user.id(), projectId, pageable));
    }

    @GetMapping("/{versionId}")
    public VersionDetail getVersion(@AuthenticationPrincipal AuthenticatedUser user, @PathVariable UUID projectId,
            @PathVariable UUID versionId) {
        return specifications.getVersion(user.id(), projectId, versionId);
    }

    @PostMapping("/{versionId}/activate")
    public VersionSummary activate(@AuthenticationPrincipal AuthenticatedUser user, @PathVariable UUID projectId,
            @PathVariable UUID versionId) {
        return specifications.activate(user.id(), projectId, versionId);
    }

    /** The original document, byte for byte as imported (as an attachment, never rendered). */
    @GetMapping("/{versionId}/raw")
    public ResponseEntity<String> raw(@AuthenticationPrincipal AuthenticatedUser user, @PathVariable UUID projectId,
            @PathVariable UUID versionId) {
        RawSpecification raw = specifications.rawContent(user.id(), projectId, versionId);
        String extension = raw.format().name().toLowerCase(Locale.ROOT);
        return ResponseEntity.ok()
                .contentType(new MediaType("text", "plain", StandardCharsets.UTF_8))
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment()
                        .filename("specification-v%d.%s".formatted(raw.versionNumber(), extension))
                        .build()
                        .toString())
                .body(raw.content());
    }

    @GetMapping("/{versionId}/schemas")
    public Map<String, Object> componentSchemas(@AuthenticationPrincipal AuthenticatedUser user,
            @PathVariable UUID projectId, @PathVariable UUID versionId) {
        return specifications.componentSchemas(user.id(), projectId, versionId);
    }

    /** All operations of the version; bounded by the import limit, so not paged. */
    @GetMapping("/{versionId}/operations")
    public List<OperationSummary> listOperations(@AuthenticationPrincipal AuthenticatedUser user,
            @PathVariable UUID projectId, @PathVariable UUID versionId) {
        return specifications.listOperations(user.id(), projectId, versionId);
    }

    @GetMapping("/{versionId}/operations/{operationId}")
    public OperationDetail getOperation(@AuthenticationPrincipal AuthenticatedUser user,
            @PathVariable UUID projectId, @PathVariable UUID versionId, @PathVariable UUID operationId) {
        return specifications.getOperation(user.id(), projectId, versionId, operationId);
    }

    private static ResponseEntity<ImportResult> created(UUID projectId, ImportResult result) {
        return ResponseEntity.created(URI.create("/api/v1/projects/%s/specs/%s".formatted(projectId,
                result.version().id()))).body(result);
    }

    private static SpecificationUpload upload(MultipartFile file) {
        try {
            return new SpecificationUpload(file.getBytes(), file.getOriginalFilename());
        } catch (IOException ex) {
            throw new UncheckedIOException("Could not read the uploaded file", ex);
        }
    }
}
