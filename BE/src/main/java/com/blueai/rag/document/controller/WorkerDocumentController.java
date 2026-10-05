package com.blueai.rag.document.controller;

import com.blueai.auth.exception.ApiException;
import com.blueai.rag.document.dto.DocumentProcessingResultRequest;
import com.blueai.rag.document.dto.WorkerDocumentResponse;
import com.blueai.rag.document.service.DocumentService;
import jakarta.validation.Valid;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.List;
import java.time.Duration;
import java.time.Instant;

@RestController
@RequestMapping("/api/v1/internal/documents")
public class WorkerDocumentController {

    private static final Logger LOG = LoggerFactory.getLogger(WorkerDocumentController.class);

    private static final String WORKER_API_KEY_HEADER = "X-Worker-Api-Key";
    private static final byte[] DEVELOPMENT_WORKER_API_KEY =
            "local-worker-api-key-change-before-production".getBytes(StandardCharsets.UTF_8);
    private static final String WORKER_ATTEMPT_HEADER = "X-Worker-Attempt-Id";

    private final DocumentService documentService;
    private final com.blueai.rag.document.service.DocumentIngestionTrigger ingestionTrigger;
    private final byte[] workerApiKey;
    private final Duration retention;

    public WorkerDocumentController(
            DocumentService documentService,
            com.blueai.rag.document.service.DocumentIngestionTrigger ingestionTrigger,
            @Value("${app.worker.api-key}") String workerApiKey,
            @Value("${app.documents.retention:30d}") Duration retention
    ) {
        this.documentService = documentService;
        this.ingestionTrigger = ingestionTrigger;
        this.workerApiKey = workerApiKey.getBytes(StandardCharsets.UTF_8);
        this.retention = retention.isNegative() ? Duration.ZERO : retention;
    }

    @PostMapping("/claim")
    List<WorkerDocumentResponse> claim(
            @RequestHeader(value = WORKER_API_KEY_HEADER, required = false) String apiKey,
            @RequestParam(defaultValue = "10") int limit
    ) {
        requireWorkerApiKey(apiKey);
        return documentService.claimPendingDocuments(limit);
    }

    @GetMapping("/{documentId}/content")
    ResponseEntity<Resource> downloadContent(
            @RequestHeader(value = WORKER_API_KEY_HEADER, required = false) String apiKey,
            @RequestHeader(value = WORKER_ATTEMPT_HEADER, required = false) String attemptId,
            @PathVariable String documentId
    ) {
        requireWorkerApiKey(apiKey);
        DocumentService.StoredDocumentFile document = documentService.getClaimedDocumentFile(documentId, attemptId);
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(document.contentType()))
                .contentLength(document.sizeBytes())
                .header(
                        HttpHeaders.CONTENT_DISPOSITION,
                        ContentDisposition.attachment()
                                .filename(document.originalFilename(), StandardCharsets.UTF_8)
                                .build()
                                .toString()
                )
                .body(new FileSystemResource(document.path()));
    }

    @PatchMapping("/{documentId}/result")
    ResponseEntity<Void> recordResult(
            @RequestHeader(value = WORKER_API_KEY_HEADER, required = false) String apiKey,
            @RequestHeader(value = WORKER_ATTEMPT_HEADER, required = false) String attemptId,
            @PathVariable String documentId,
            @Valid @RequestBody DocumentProcessingResultRequest request
    ) {
        requireWorkerApiKey(apiKey);
        if (!request.attemptId().equals(attemptId)) {
            throw new ApiException(HttpStatus.CONFLICT, "STALE_PROCESSING_ATTEMPT", "Attempt header and body do not match.");
        }
        documentService.recordProcessingResult(documentId, request);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/purge")
    ResponseEntity<Integer> purge(
            @RequestHeader(value = WORKER_API_KEY_HEADER, required = false) String apiKey,
            @RequestParam(defaultValue = "100") int limit
    ) {
        requireWorkerApiKey(apiKey);
        Instant cutoff = Instant.now().minus(retention);
        int purged = 0;
        for (String documentId : documentService.findExpiredDeletedDocumentIds(cutoff, limit)) {
            try {
                if (!ingestionTrigger.requestDocumentPurge(documentId)) {
                    continue;
                }
                documentService.purgeDeletedDocument(documentId, cutoff);
                purged++;
            } catch (ApiException exception) {
                // Persisted attempt timestamps rotate failed tombstones behind
                // untried rows; continue so one broken object cannot starve a batch.
                LOG.warn("Document retention purge failed for tombstone {} with {}; it will be retried",
                        documentId, exception.getCode());
            }
        }
        return ResponseEntity.ok(purged);
    }

    private void requireWorkerApiKey(String providedApiKey) {
        if (workerApiKey.length == 0 || MessageDigest.isEqual(workerApiKey, DEVELOPMENT_WORKER_API_KEY)) {
            throw new ApiException(
                    HttpStatus.SERVICE_UNAVAILABLE,
                    "WORKER_API_KEY_NOT_CONFIGURED",
                    "Document worker authentication is not configured."
            );
        }
        byte[] candidate = providedApiKey == null ? new byte[0] : providedApiKey.getBytes(StandardCharsets.UTF_8);
        if (!MessageDigest.isEqual(workerApiKey, candidate)) {
            throw new ApiException(HttpStatus.UNAUTHORIZED, "INVALID_WORKER_API_KEY", "Worker API key is invalid.");
        }
    }
}
