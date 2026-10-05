package com.blueai.rag.document.controller;

import com.blueai.rag.document.dto.DocumentUploadResponse;
import com.blueai.rag.document.dto.DocumentResponse;
import com.blueai.rag.document.dto.DocumentPageResponse;
import com.blueai.rag.document.model.DocumentStatus;
import com.blueai.rag.document.service.DocumentIngestionTrigger;
import com.blueai.rag.document.service.DocumentService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;

@RestController
@RequestMapping("/api/v1/documents")
public class DocumentController {

    private final DocumentService documentService;
    private final DocumentIngestionTrigger ingestionTrigger;

    public DocumentController(DocumentService documentService, DocumentIngestionTrigger ingestionTrigger) {
        this.documentService = documentService;
        this.ingestionTrigger = ingestionTrigger;
    }

    @PostMapping(consumes = "multipart/form-data")
    ResponseEntity<DocumentUploadResponse> upload(
            @AuthenticationPrincipal Jwt jwt,
            @RequestParam("file") MultipartFile file,
            @RequestParam(required = false) Integer chunkSize,
            @RequestParam(required = false) Integer chunkOverlap
    ) {
        DocumentUploadResponse response = documentService.upload(jwt.getSubject(), file, chunkSize, chunkOverlap);
        // The upload transaction has committed, so the worker can already claim the row.
        boolean triggered = ingestionTrigger.requestIngestion();
        return ResponseEntity.status(HttpStatus.CREATED).body(response.withIngestionTriggered(triggered));
    }

    @GetMapping
    List<DocumentResponse> list(
            @AuthenticationPrincipal Jwt jwt,
            @RequestParam(required = false) DocumentStatus status
    ) {
        return documentService.listDocuments(jwt.getSubject(), status);
    }

    @GetMapping("/search")
    DocumentPageResponse search(
            @AuthenticationPrincipal Jwt jwt,
            @RequestParam(required = false) String q,
            @RequestParam(required = false) DocumentStatus status,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size
    ) {
        return documentService.searchDocuments(jwt.getSubject(), q, status, page, size);
    }

    @GetMapping("/{documentId}")
    DocumentResponse get(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable String documentId
    ) {
        return documentService.getDocument(jwt.getSubject(), documentId);
    }

    @DeleteMapping("/{documentId}")
    ResponseEntity<Void> delete(@AuthenticationPrincipal Jwt jwt, @PathVariable String documentId) {
        documentService.softDelete(jwt.getSubject(), documentId);
        // The Java tombstone is authoritative immediately. The best-effort nudge
        // removes vectors and is retried by the retention/purge integration.
        ingestionTrigger.requestDocumentPurge(documentId);
        return ResponseEntity.noContent().build();
    }
}
