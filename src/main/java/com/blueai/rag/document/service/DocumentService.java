package com.blueai.rag.document.service;

import com.blueai.auth.exception.ApiException;
import com.blueai.auth.model.User;
import com.blueai.auth.model.UserStatus;
import com.blueai.auth.repository.UserRepository;
import com.blueai.rag.document.dto.DocumentUploadResponse;
import com.blueai.rag.document.dto.DocumentProcessingResultRequest;
import com.blueai.rag.document.dto.WorkerDocumentResponse;
import com.blueai.rag.document.model.DocumentStatus;
import com.blueai.rag.document.model.RagDocument;
import com.blueai.rag.document.repository.RagDocumentRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.util.StringUtils;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
public class DocumentService {

    private static final int DEFAULT_CHUNK_SIZE = 1_000;
    private static final int DEFAULT_CHUNK_OVERLAP = 150;
    private static final int MIN_CHUNK_SIZE = 200;
    private static final int MAX_CHUNK_SIZE = 4_000;
    private static final Map<String, String> SUPPORTED_CONTENT_TYPES = Map.of(
            ".pdf", "application/pdf",
            ".docx", "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
            ".txt", "text/plain",
            ".md", "text/markdown"
    );

    private final UserRepository userRepository;
    private final RagDocumentRepository documentRepository;
    private final Path storageRoot;
    private final long maxSizeBytes;

    public DocumentService(
            UserRepository userRepository,
            RagDocumentRepository documentRepository,
            @Value("${app.documents.storage-dir}") String storageDirectory,
            @Value("${app.documents.max-size-bytes}") long maxSizeBytes
    ) {
        this.userRepository = userRepository;
        this.documentRepository = documentRepository;
        this.storageRoot = Path.of(storageDirectory).toAbsolutePath().normalize();
        this.maxSizeBytes = maxSizeBytes;
    }

    @Transactional
    public DocumentUploadResponse upload(String ownerId, MultipartFile file, Integer chunkSize, Integer chunkOverlap) {
        User owner = userRepository.findById(ownerId)
                .orElseThrow(() -> new ApiException(HttpStatus.UNAUTHORIZED, "USER_NOT_FOUND", "User no longer exists."));
        if (owner.getStatus() != UserStatus.ACTIVE) {
            throw new ApiException(HttpStatus.FORBIDDEN, "USER_DISABLED", "User account is disabled.");
        }

        UploadDetails details = validateFile(file, chunkSize, chunkOverlap);
        String documentId = UUID.randomUUID().toString();
        String storageKey = owner.getId() + "/" + documentId + details.extension();
        Path destination = resolveStoragePath(storageKey);

        storeFile(file, destination);
        try {
            RagDocument document = new RagDocument(
                    owner,
                    details.filename(),
                    storageKey,
                    details.contentType(),
                    file.getSize(),
                    details.chunkSize(),
                    details.chunkOverlap()
            );
            documentRepository.saveAndFlush(document);
            return DocumentUploadResponse.from(document);
        } catch (RuntimeException exception) {
            deleteStoredFile(destination);
            throw exception;
        }
    }

    @Transactional
    public List<WorkerDocumentResponse> claimPendingDocuments(int limit) {
        if (limit < 1 || limit > 50) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_CLAIM_LIMIT", "limit must be between 1 and 50.");
        }

        List<RagDocument> documents = documentRepository.findForClaim(
                DocumentStatus.PENDING_CHUNKING,
                PageRequest.of(0, limit)
        );
        documents.forEach(RagDocument::markProcessing);
        return documents.stream().map(WorkerDocumentResponse::from).toList();
    }

    @Transactional
    public void recordProcessingResult(String documentId, DocumentProcessingResultRequest request) {
        RagDocument document = documentRepository.findById(documentId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "DOCUMENT_NOT_FOUND", "Document was not found."));
        if (document.getStatus() != DocumentStatus.PROCESSING) {
            throw new ApiException(
                    HttpStatus.CONFLICT,
                    "DOCUMENT_NOT_PROCESSING",
                    "Only a claimed document can receive a processing result."
            );
        }

        if (request.status() == DocumentStatus.READY) {
            if (request.producedChunkCount() == null) {
                throw new ApiException(
                        HttpStatus.BAD_REQUEST,
                        "MISSING_CHUNK_COUNT",
                        "producedChunkCount is required when status is READY."
                );
            }
            document.markReady(request.producedChunkCount());
            return;
        }

        if (request.status() == DocumentStatus.FAILED) {
            if (!StringUtils.hasText(request.failureReason())) {
                throw new ApiException(
                        HttpStatus.BAD_REQUEST,
                        "MISSING_FAILURE_REASON",
                        "failureReason is required when status is FAILED."
                );
            }
            document.markFailed(request.failureReason().trim());
            return;
        }

        throw new ApiException(
                HttpStatus.BAD_REQUEST,
                "INVALID_PROCESSING_STATUS",
                "Worker may report only READY or FAILED."
        );
    }

    private UploadDetails validateFile(MultipartFile file, Integer requestedChunkSize, Integer requestedChunkOverlap) {
        if (file == null || file.isEmpty()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "EMPTY_FILE", "A non-empty document file is required.");
        }
        if (file.getSize() > maxSizeBytes) {
            throw new ApiException(HttpStatus.PAYLOAD_TOO_LARGE, "FILE_TOO_LARGE", "Document exceeds the 20 MB upload limit.");
        }

        String filename = extractFilename(file.getOriginalFilename());
        String extension = extensionOf(filename);
        String contentType = SUPPORTED_CONTENT_TYPES.get(extension);
        if (contentType == null) {
            throw new ApiException(
                    HttpStatus.BAD_REQUEST,
                    "UNSUPPORTED_FILE_TYPE",
                    "Supported file types are PDF, DOCX, TXT, and Markdown."
            );
        }

        int chunkSize = requestedChunkSize == null ? DEFAULT_CHUNK_SIZE : requestedChunkSize;
        int chunkOverlap = requestedChunkOverlap == null ? DEFAULT_CHUNK_OVERLAP : requestedChunkOverlap;
        if (chunkSize < MIN_CHUNK_SIZE || chunkSize > MAX_CHUNK_SIZE) {
            throw new ApiException(
                    HttpStatus.BAD_REQUEST,
                    "INVALID_CHUNK_SIZE",
                    "chunkSize must be between 200 and 4000 characters."
            );
        }
        if (chunkOverlap < 0 || chunkOverlap >= chunkSize) {
            throw new ApiException(
                    HttpStatus.BAD_REQUEST,
                    "INVALID_CHUNK_OVERLAP",
                    "chunkOverlap must be at least 0 and smaller than chunkSize."
            );
        }
        return new UploadDetails(filename, extension, contentType, chunkSize, chunkOverlap);
    }

    private String extractFilename(String rawFilename) {
        String cleaned = StringUtils.cleanPath(rawFilename == null ? "" : rawFilename).replace('\\', '/');
        String filename = cleaned.substring(cleaned.lastIndexOf('/') + 1);
        if (!StringUtils.hasText(filename) || cleaned.contains("..") || filename.length() > 255) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_FILENAME", "Document filename is invalid.");
        }
        return filename;
    }

    private String extensionOf(String filename) {
        int extensionStart = filename.lastIndexOf('.');
        return extensionStart < 1 ? "" : filename.substring(extensionStart).toLowerCase(Locale.ROOT);
    }

    private Path resolveStoragePath(String storageKey) {
        Path destination = storageRoot.resolve(storageKey).normalize();
        if (!destination.startsWith(storageRoot)) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_STORAGE_PATH", "Document storage path is invalid.");
        }
        return destination;
    }

    private void storeFile(MultipartFile file, Path destination) {
        try {
            Files.createDirectories(destination.getParent());
            try (InputStream input = file.getInputStream()) {
                Files.copy(input, destination);
            }
        } catch (IOException exception) {
            deleteStoredFile(destination);
            throw new ApiException(HttpStatus.INTERNAL_SERVER_ERROR, "DOCUMENT_STORAGE_FAILED", "Unable to store document.");
        }
    }

    private void deleteStoredFile(Path destination) {
        try {
            Files.deleteIfExists(destination);
        } catch (IOException ignored) {
            // A failed cleanup must not hide the original persistence error.
        }
    }

    private record UploadDetails(String filename, String extension, String contentType, int chunkSize, int chunkOverlap) {
    }
}
