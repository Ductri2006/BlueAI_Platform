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

@RestController
@RequestMapping("/api/v1/internal/documents")
public class WorkerDocumentController {

    private static final String WORKER_API_KEY_HEADER = "X-Worker-Api-Key";
    private static final byte[] DEVELOPMENT_WORKER_API_KEY =
            "local-worker-api-key-change-before-production".getBytes(StandardCharsets.UTF_8);

    private final DocumentService documentService;
    private final byte[] workerApiKey;

    public WorkerDocumentController(
            DocumentService documentService,
            @Value("${app.worker.api-key}") String workerApiKey
    ) {
        this.documentService = documentService;
        this.workerApiKey = workerApiKey.getBytes(StandardCharsets.UTF_8);
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
            @PathVariable String documentId
    ) {
        requireWorkerApiKey(apiKey);
        DocumentService.StoredDocumentFile document = documentService.getClaimedDocumentFile(documentId);
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
            @PathVariable String documentId,
            @Valid @RequestBody DocumentProcessingResultRequest request
    ) {
        requireWorkerApiKey(apiKey);
        documentService.recordProcessingResult(documentId, request);
        return ResponseEntity.noContent().build();
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
