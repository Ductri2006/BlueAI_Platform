package com.blueai.rag.document.service;

import com.blueai.auth.exception.ApiException;
import com.blueai.auth.model.User;
import com.blueai.auth.model.UserStatus;
import com.blueai.auth.repository.UserRepository;
import com.blueai.rag.document.dto.DocumentUploadResponse;
import com.blueai.rag.document.dto.DocumentProcessingResultRequest;
import com.blueai.rag.document.dto.DocumentPageResponse;
import com.blueai.rag.document.dto.DocumentResponse;
import com.blueai.rag.document.dto.WorkerDocumentResponse;
import com.blueai.rag.document.model.DocumentStatus;
import com.blueai.rag.document.model.RagDocument;
import com.blueai.rag.document.repository.RagDocumentRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.util.StringUtils;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.Locale;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
public class DocumentService {

    private static final Logger LOG = LoggerFactory.getLogger(DocumentService.class);
    private static final int DEFAULT_CHUNK_SIZE = 1_000;
    private static final int DEFAULT_CHUNK_OVERLAP = 150;
    private static final int MIN_CHUNK_SIZE = 200;
    private static final int MAX_CHUNK_SIZE = 4_000;
    private static final Map<String, String> SUPPORTED_CONTENT_TYPES = Map.of(
            ".pdf", "application/pdf",
            ".docx", "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
            ".csv", "text/csv",
            ".txt", "text/plain",
            ".md", "text/markdown"
    );

    private final UserRepository userRepository;
    private final RagDocumentRepository documentRepository;
    private final Path storageRoot;
    private final long maxSizeBytes;
    private final Duration processingTimeout;
    private final int maxProcessingAttempts;
    private final Duration retryBaseDelay;

    public DocumentService(
            UserRepository userRepository,
            RagDocumentRepository documentRepository,
            @Value("${app.documents.storage-dir}") String storageDirectory,
            @Value("${app.documents.max-size-bytes}") long maxSizeBytes,
            @Value("${app.documents.processing-timeout:10m}") Duration processingTimeout,
            @Value("${app.documents.processing-max-attempts:3}") int maxProcessingAttempts,
            @Value("${app.documents.processing-retry-base-delay:30s}") Duration retryBaseDelay
    ) {
        this.userRepository = userRepository;
        this.documentRepository = documentRepository;
        this.storageRoot = Path.of(storageDirectory).toAbsolutePath().normalize();
        this.maxSizeBytes = maxSizeBytes;
        this.processingTimeout = processingTimeout;
        this.maxProcessingAttempts = Math.max(1, maxProcessingAttempts);
        this.retryBaseDelay = retryBaseDelay.isNegative() ? Duration.ZERO : retryBaseDelay;
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

    @Transactional(readOnly = true)
    public List<DocumentResponse> listDocuments(String ownerId, DocumentStatus status) {
        List<RagDocument> documents = status == null
                ? documentRepository.findByOwner_IdOrderByCreatedAtDesc(ownerId)
                : documentRepository.findByOwner_IdAndStatusOrderByCreatedAtDesc(ownerId, status);
        return documents.stream().map(DocumentResponse::from).toList();
    }

    @Transactional(readOnly = true)
    public DocumentPageResponse searchDocuments(
            String ownerId,
            String search,
            DocumentStatus status,
            int page,
            int size
    ) {
        validatePage(page, size);
        String normalizedSearch = search == null ? "" : search.trim();
        Page<RagDocument> result = documentRepository.searchActive(
                ownerId,
                status,
                escapeLike(normalizedSearch),
                PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "createdAt").and(Sort.by("id")))
        );
        return new DocumentPageResponse(
                result.getContent().stream().map(DocumentResponse::from).toList(),
                result.getNumber(),
                result.getSize(),
                result.getTotalElements(),
                result.getTotalPages(),
                result.hasNext(),
                result.hasPrevious()
        );
    }

    @Transactional(readOnly = true)
    public DocumentResponse getDocument(String ownerId, String documentId) {
        RagDocument document = documentRepository.findByIdAndOwner_Id(documentId, ownerId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "DOCUMENT_NOT_FOUND", "Document was not found."));
        return DocumentResponse.from(document);
    }

    @Transactional
    public List<WorkerDocumentResponse> claimPendingDocuments(int limit) {
        if (limit < 1 || limit > 50) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_CLAIM_LIMIT", "limit must be between 1 and 50.");
        }

        requeueStaleProcessingDocuments();
        List<RagDocument> documents = documentRepository.findForClaim(
                DocumentStatus.PENDING_CHUNKING,
                Instant.now(),
                PageRequest.of(0, limit)
        );
        documents.forEach(RagDocument::markProcessing);
        return documents.stream().map(WorkerDocumentResponse::from).toList();
    }

    /**
     * A worker that crashes between claiming a document and reporting its result would
     * otherwise leave the row in PROCESSING forever, invisible to both the queue and the
     * uploader.
     */
    private void requeueStaleProcessingDocuments() {
        List<RagDocument> stale = documentRepository.findByStatusAndProcessingStartedAtBefore(
                DocumentStatus.PROCESSING,
                Instant.now().minus(processingTimeout)
        );
        for (RagDocument document : stale) {
            LOG.warn("Document {} has been PROCESSING since {}; requeueing it for chunking.",
                    document.getId(), document.getProcessingStartedAt());
            document.markPendingAgain(
                    "Quá thời gian xử lý, đã đưa lại vào hàng đợi.",
                    maxProcessingAttempts,
                    retryDelayFor(document.getAttemptCount())
            );
        }
    }

    @Transactional(readOnly = true)
    public StoredDocumentFile getClaimedDocumentFile(String documentId, String attemptId) {
        RagDocument document = documentRepository.findClaimedByIdAndAttempt(
                        documentId, DocumentStatus.PROCESSING, attemptId)
                .orElseThrow(() -> new ApiException(
                        HttpStatus.CONFLICT,
                        "STALE_PROCESSING_ATTEMPT",
                        "The worker claim is no longer current."
                ));
        if (document.isDeleted()) {
            throw new ApiException(
                    HttpStatus.CONFLICT,
                    "DOCUMENT_NOT_PROCESSING",
                    "Only a claimed document can be downloaded by a worker."
            );
        }

        Path storedFile = resolveStoragePath(document.getStorageKey());
        if (!Files.isRegularFile(storedFile)) {
            throw new ApiException(HttpStatus.NOT_FOUND, "DOCUMENT_FILE_NOT_FOUND", "Document file was not found in storage.");
        }
        try {
            return new StoredDocumentFile(
                    storedFile,
                    document.getOriginalFilename(),
                    document.getContentType(),
                    Files.size(storedFile)
            );
        } catch (IOException exception) {
            throw new ApiException(HttpStatus.INTERNAL_SERVER_ERROR, "DOCUMENT_STORAGE_READ_FAILED", "Unable to read document.");
        }
    }

    @Transactional
    public void recordProcessingResult(String documentId, DocumentProcessingResultRequest request) {
        RagDocument document = documentRepository.findClaimedByIdAndAttempt(
                        documentId, DocumentStatus.PROCESSING, request.attemptId())
                .orElseThrow(() -> new ApiException(
                        HttpStatus.CONFLICT,
                        "STALE_PROCESSING_ATTEMPT",
                        "The worker claim is no longer current."
                ));

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
            document.markFailed(
                    request.failureReason().trim(),
                    maxProcessingAttempts,
                    retryDelayFor(document.getAttemptCount())
            );
            return;
        }

        throw new ApiException(
                HttpStatus.BAD_REQUEST,
                "INVALID_PROCESSING_STATUS",
                "Worker may report only READY or FAILED."
        );
    }

    @Transactional
    public void softDelete(String ownerId, String documentId) {
        RagDocument document = documentRepository.findByIdAndOwnerIncludingDeleted(documentId, ownerId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "DOCUMENT_NOT_FOUND", "Document was not found."));
        document.softDelete();
    }

    @Transactional
    public List<String> findExpiredDeletedDocumentIds(Instant cutoff, int limit) {
        if (limit < 1 || limit > 100) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_PURGE_LIMIT", "limit must be between 1 and 100.");
        }
        List<RagDocument> expired = documentRepository.findDeletedBefore(cutoff, PageRequest.of(0, limit));
        Instant attemptedAt = Instant.now();
        expired.forEach(document -> document.markPurgeAttempted(attemptedAt));
        return expired.stream().map(RagDocument::getId).toList();
    }

    @Transactional
    public void purgeDeletedDocument(String documentId, Instant cutoff) {
        RagDocument document = documentRepository.findByIdIncludingDeleted(documentId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "DOCUMENT_NOT_FOUND", "Document was not found."));
        if (document.getDeletedAt() == null || document.getDeletedAt().isAfter(cutoff)) {
            return;
        }
        if (!deleteStoredFile(resolveStoragePath(document.getStorageKey()))) {
            throw new ApiException(
                    HttpStatus.INTERNAL_SERVER_ERROR,
                    "DOCUMENT_STORAGE_PURGE_FAILED",
                    "Document storage could not be purged; the tombstone will be retried."
            );
        }
        // File deletion is intentionally one-way before the DB finalize. If the
        // transaction rolls back, the tombstone remains and a retry treats the
        // already-missing file as success; sensitive bytes are never restored.
        documentRepository.delete(document);
    }

    private Duration retryDelayFor(int attemptCount) {
        long multiplier = 1L << Math.min(Math.max(0, attemptCount - 1), 10);
        try {
            return retryBaseDelay.multipliedBy(multiplier);
        } catch (ArithmeticException exception) {
            return retryBaseDelay;
        }
    }

    private void validatePage(int page, int size) {
        if (page < 0 || size < 1 || size > 100) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_PAGE", "page must be >= 0 and size must be between 1 and 100.");
        }
    }

    private String escapeLike(String value) {
        return value.replace("\\", "\\\\")
                .replace("%", "\\%")
                .replace("_", "\\_");
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
                    "Supported file types are PDF, DOCX, CSV, TXT, and Markdown."
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
        String raw = rawFilename == null ? "" : rawFilename.replace('\\', '/');
        if (raw.chars().anyMatch(Character::isISOControl)
                || java.util.Arrays.stream(raw.split("/")).anyMatch(segment -> segment.equals(".."))) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_FILENAME", "Document filename is invalid.");
        }
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
            long written = 0;
            try (InputStream input = file.getInputStream(); var output = Files.newOutputStream(
                    destination, java.nio.file.StandardOpenOption.CREATE_NEW, java.nio.file.StandardOpenOption.WRITE)) {
                byte[] buffer = new byte[8192];
                int read;
                while ((read = input.read(buffer)) != -1) {
                    written += read;
                    if (written > maxSizeBytes) {
                        throw new ApiException(HttpStatus.PAYLOAD_TOO_LARGE, "FILE_TOO_LARGE", "Document exceeds the upload limit.");
                    }
                    output.write(buffer, 0, read);
                }
            }
            if (written == 0 || written != file.getSize()) {
                throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_FILE_CONTENT", "Document length is invalid.");
            }
            DocumentContentValidator.validate(destination, extensionOf(destination.getFileName().toString()));
        } catch (ApiException exception) {
            deleteStoredFile(destination);
            throw exception;
        } catch (IOException exception) {
            deleteStoredFile(destination);
            throw new ApiException(HttpStatus.INTERNAL_SERVER_ERROR, "DOCUMENT_STORAGE_FAILED", "Unable to store document.");
        }
    }

    private boolean deleteStoredFile(Path destination) {
        try {
            Files.deleteIfExists(destination);
            return true;
        } catch (IOException ignored) {
            return false;
        }
    }

    private record UploadDetails(String filename, String extension, String contentType, int chunkSize, int chunkOverlap) {
    }

    public record StoredDocumentFile(Path path, String originalFilename, String contentType, long sizeBytes) {
    }
}
