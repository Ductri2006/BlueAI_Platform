package com.blueai.rag.document.controller;

import com.blueai.rag.document.dto.DocumentUploadResponse;
import com.blueai.rag.document.dto.DocumentResponse;
import com.blueai.rag.document.model.DocumentStatus;
import com.blueai.rag.document.service.DocumentService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
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

    public DocumentController(DocumentService documentService) {
        this.documentService = documentService;
    }

    @PostMapping(consumes = "multipart/form-data")
    ResponseEntity<DocumentUploadResponse> upload(
            @AuthenticationPrincipal Jwt jwt,
            @RequestParam("file") MultipartFile file,
            @RequestParam(required = false) Integer chunkSize,
            @RequestParam(required = false) Integer chunkOverlap
    ) {
        DocumentUploadResponse response = documentService.upload(jwt.getSubject(), file, chunkSize, chunkOverlap);
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    @GetMapping
    List<DocumentResponse> list(
            @AuthenticationPrincipal Jwt jwt,
            @RequestParam(required = false) DocumentStatus status
    ) {
        return documentService.listDocuments(jwt.getSubject(), status);
    }

    @GetMapping("/{documentId}")
    DocumentResponse get(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable String documentId
    ) {
        return documentService.getDocument(jwt.getSubject(), documentId);
    }
}
