package com.blueai.rag.document.controller;

import com.blueai.BlueAiAuthApplication;
import com.blueai.auth.model.User;
import com.blueai.auth.model.UserRole;
import com.blueai.auth.repository.RefreshTokenRepository;
import com.blueai.auth.repository.UserRepository;
import com.blueai.rag.document.model.DocumentStatus;
import com.blueai.rag.document.model.RagDocument;
import com.blueai.rag.document.repository.RagDocumentRepository;
import com.blueai.rag.document.service.DocumentService;
import com.blueai.rag.document.service.DocumentIngestionTrigger;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.mockito.Mockito.*;

@SpringBootTest(classes = BlueAiAuthApplication.class, properties = "app.documents.retention=0s")
@AutoConfigureMockMvc
@ActiveProfiles("test")
class WorkerDocumentIntegrationTest {

    private static final String WORKER_API_KEY = "test-worker-api-key-for-integration-tests";
    private static final Path TEST_STORAGE = Path.of("target/test-uploaded-documents");

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private RagDocumentRepository documentRepository;

    @Autowired
    private RefreshTokenRepository refreshTokenRepository;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Autowired
    private DocumentService documentService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @MockBean
    private DocumentIngestionTrigger ingestionTrigger;

    @BeforeEach
    @AfterEach
    void cleanState() throws Exception {
        documentRepository.deleteAll();
        refreshTokenRepository.deleteAll();
        userRepository.deleteAll();
        if (Files.exists(TEST_STORAGE)) {
            try (var paths = Files.walk(TEST_STORAGE)) {
                paths.sorted((left, right) -> right.compareTo(left)).forEach(path -> {
                    try {
                        Files.deleteIfExists(path);
                    } catch (Exception exception) {
                        throw new IllegalStateException(exception);
                    }
                });
            }
        }
    }

    @Test
    void workerClaimsPendingDocumentThenMarksItReady() throws Exception {
        String documentId = uploadDocument();

        String claimResponse = mockMvc.perform(post("/api/v1/internal/documents/claim")
                        .header("X-Worker-Api-Key", WORKER_API_KEY)
                        .param("limit", "5"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].id").value(documentId))
                .andExpect(jsonPath("$[0].storageKey").isNotEmpty())
                .andExpect(jsonPath("$[0].requestedChunkSize").value(900))
                .andReturn()
                .getResponse()
                .getContentAsString();

        assertThat(objectMapper.readTree(claimResponse)).hasSize(1);
        String attemptId = objectMapper.readTree(claimResponse).get(0).get("attemptId").asText();
        assertThat(documentRepository.findById(documentId).orElseThrow().getStatus()).isEqualTo(DocumentStatus.PROCESSING);

        mockMvc.perform(post("/api/v1/internal/documents/claim")
                        .header("X-Worker-Api-Key", WORKER_API_KEY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isEmpty());

        mockMvc.perform(patch("/api/v1/internal/documents/{documentId}/result", documentId)
                        .header("X-Worker-Api-Key", WORKER_API_KEY)
                        .header("X-Worker-Attempt-Id", attemptId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "attemptId": "%s",
                                  "status": "READY",
                                  "producedChunkCount": 7
                                }
                                """.formatted(attemptId)))
                .andExpect(status().isNoContent());

        RagDocument document = documentRepository.findById(documentId).orElseThrow();
        assertThat(document.getStatus()).isEqualTo(DocumentStatus.READY);
        assertThat(document.getProducedChunkCount()).isEqualTo(7);
        assertThat(document.getProcessedAt()).isNotNull();
    }

    @Test
    void workerEndpointRejectsInvalidApiKey() throws Exception {
        mockMvc.perform(post("/api/v1/internal/documents/claim")
                        .header("X-Worker-Api-Key", "incorrect-key"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("INVALID_WORKER_API_KEY"));
    }

    @Test
    void workerCanDownloadOnlyAClaimedDocument() throws Exception {
        String documentId = uploadDocument();

        mockMvc.perform(get("/api/v1/internal/documents/{documentId}/content", documentId)
                        .header("X-Worker-Api-Key", WORKER_API_KEY))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("STALE_PROCESSING_ATTEMPT"));

        String claimResponse = mockMvc.perform(post("/api/v1/internal/documents/claim")
                        .header("X-Worker-Api-Key", WORKER_API_KEY))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        String attemptId = objectMapper.readTree(claimResponse).get(0).get("attemptId").asText();

        mockMvc.perform(get("/api/v1/internal/documents/{documentId}/content", documentId)
                        .header("X-Worker-Api-Key", WORKER_API_KEY)
                        .header("X-Worker-Attempt-Id", attemptId))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.TEXT_PLAIN))
                .andExpect(content().string("This is the document that the worker will process."));
    }

    @Test
    void failedVectorPurgeRotatesBehindUntriedTombstonesAndRetries() throws Exception {
        User owner = userRepository.saveAndFlush(new User(
                "purge-owner@example.com", passwordEncoder.encode("StrongPassword123!"), "Purge Owner", UserRole.ADMIN
        ));
        Files.createDirectories(TEST_STORAGE);
        Path failedFile = TEST_STORAGE.resolve("purge-failed.txt");
        Path healthyFile = TEST_STORAGE.resolve("purge-healthy.txt");
        Files.writeString(failedFile, "failed vector document");
        Files.writeString(healthyFile, "healthy vector document");

        RagDocument failed = new RagDocument(owner, "failed.txt", "purge-failed.txt", "text/plain", 22, 900, 120);
        documentRepository.saveAndFlush(failed);
        failed.softDelete();
        documentRepository.saveAndFlush(failed);
        RagDocument healthy = new RagDocument(owner, "healthy.txt", "purge-healthy.txt", "text/plain", 23, 900, 120);
        documentRepository.saveAndFlush(healthy);
        healthy.softDelete();
        documentRepository.saveAndFlush(healthy);

        // Both tombstones must be strictly expired even on a coarse system clock.
        Instant deletedBefore = Instant.now().minusSeconds(60);
        jdbcTemplate.update("update rag_documents set deleted_at = ? where id = ?",
                java.sql.Timestamp.from(deletedBefore.minusSeconds(1)), failed.getId());
        jdbcTemplate.update("update rag_documents set deleted_at = ? where id = ?",
                java.sql.Timestamp.from(deletedBefore), healthy.getId());

        when(ingestionTrigger.requestDocumentPurge(failed.getId())).thenReturn(false);
        when(ingestionTrigger.requestDocumentPurge(healthy.getId())).thenReturn(true);

        mockMvc.perform(post("/api/v1/internal/documents/purge")
                        .header("X-Worker-Api-Key", WORKER_API_KEY)
                        .param("limit", "1"))
                .andExpect(status().isOk())
                .andExpect(content().string("0"));
        assertThat(documentRepository.findByIdIncludingDeleted(failed.getId())).isPresent();
        assertThat(documentRepository.findByIdIncludingDeleted(healthy.getId())).isPresent();

        mockMvc.perform(post("/api/v1/internal/documents/purge")
                        .header("X-Worker-Api-Key", WORKER_API_KEY)
                        .param("limit", "1"))
                .andExpect(status().isOk())
                .andExpect(content().string("1"));
        assertThat(documentRepository.findByIdIncludingDeleted(healthy.getId())).isEmpty();
        assertThat(documentRepository.findByIdIncludingDeleted(failed.getId())).isPresent();
        verify(ingestionTrigger).requestDocumentPurge(failed.getId());
        verify(ingestionTrigger).requestDocumentPurge(healthy.getId());

        when(ingestionTrigger.requestDocumentPurge(failed.getId())).thenReturn(true);
        mockMvc.perform(post("/api/v1/internal/documents/purge")
                        .header("X-Worker-Api-Key", WORKER_API_KEY)
                        .param("limit", "1"))
                .andExpect(status().isOk())
                .andExpect(content().string("1"));
        assertThat(documentRepository.findByIdIncludingDeleted(failed.getId())).isEmpty();
    }

    @Test
    void expiredDocumentLookupUsesExclusiveCutoffAndEnforcesBatchBound() {
        User owner = userRepository.saveAndFlush(new User(
                "cutoff-owner@example.com", passwordEncoder.encode("StrongPassword123!"), "Cutoff Owner", UserRole.ADMIN
        ));
        RagDocument older = new RagDocument(owner, "older.txt", "older.txt", "text/plain", 5, 900, 120);
        RagDocument atBoundary = new RagDocument(owner, "boundary.txt", "boundary.txt", "text/plain", 5, 900, 120);
        documentRepository.saveAndFlush(older);
        older.softDelete();
        documentRepository.saveAndFlush(older);
        documentRepository.saveAndFlush(atBoundary);
        atBoundary.softDelete();
        documentRepository.saveAndFlush(atBoundary);

        Instant cutoff = Instant.parse("2026-09-24T00:00:00Z");
        jdbcTemplate.update("update rag_documents set deleted_at = ? where id = ?",
                java.sql.Timestamp.from(cutoff.minusSeconds(1)), older.getId());
        jdbcTemplate.update("update rag_documents set deleted_at = ? where id = ?",
                java.sql.Timestamp.from(cutoff), atBoundary.getId());

        assertThat(documentService.findExpiredDeletedDocumentIds(cutoff, 1)).containsExactly(older.getId());
        assertThatThrownBy(() -> documentService.findExpiredDeletedDocumentIds(cutoff, 101))
                .isInstanceOf(com.blueai.auth.exception.ApiException.class)
                .hasMessageContaining("between 1 and 100");
    }

    private String uploadDocument() throws Exception {
        String accessToken = registerAndGetAccessToken();
        MockMultipartFile file = new MockMultipartFile(
                "file",
                "knowledge-base.txt",
                "text/plain",
                "This is the document that the worker will process.".getBytes()
        );

        String response = mockMvc.perform(multipart("/api/v1/documents")
                        .file(file)
                        .param("chunkSize", "900")
                        .param("chunkOverlap", "120")
                        .header("Authorization", "Bearer " + accessToken))
                .andExpect(status().isCreated())
                .andReturn()
                .getResponse()
                .getContentAsString();
        return objectMapper.readTree(response).get("id").asText();
    }

    @Test
    void expiredClaimCannotDownloadOrOverwriteANewerAttempt() throws Exception {
        String id = uploadDocument();
        String oldAttempt = claimAttempt(id);
        jdbcTemplate.update("update rag_documents set processing_started_at = ? where id = ?",
                java.sql.Timestamp.from(Instant.now().minusSeconds(3600)), id);
        mockMvc.perform(post("/api/v1/internal/documents/claim").header("X-Worker-Api-Key", WORKER_API_KEY))
                .andExpect(status().isOk()).andExpect(jsonPath("$").isEmpty());
        jdbcTemplate.update("update rag_documents set next_attempt_at = ? where id = ?",
                java.sql.Timestamp.from(Instant.now().minusSeconds(1)), id);
        String newAttempt = claimAttempt(id);
        assertThat(newAttempt).isNotEqualTo(oldAttempt);
        mockMvc.perform(get("/api/v1/internal/documents/{id}/content", id)
                        .header("X-Worker-Api-Key", WORKER_API_KEY).header("X-Worker-Attempt-Id", oldAttempt))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("STALE_PROCESSING_ATTEMPT"));
        reportResult(id, oldAttempt, "READY", "\"producedChunkCount\":1", 409);
        assertThat(documentRepository.findById(id).orElseThrow().getProcessingAttemptId()).isEqualTo(newAttempt);
        reportResult(id, newAttempt, "READY", "\"producedChunkCount\":2", 204);
        assertThat(documentRepository.findById(id).orElseThrow().getProducedChunkCount()).isEqualTo(2);
    }

    @Test
    void failedProcessingRetriesAtMostThreeTimesAndRejectsAllOldResults() throws Exception {
        String id = uploadDocument();
        String firstAttempt = null;
        for (int attempt = 1; attempt <= 3; attempt++) {
            jdbcTemplate.update("update rag_documents set next_attempt_at = ? where id = ?",
                    java.sql.Timestamp.from(Instant.now().minusSeconds(1)), id);
            String currentAttempt = claimAttempt(id);
            if (firstAttempt == null) { firstAttempt = currentAttempt; }
            reportResult(id, currentAttempt, "FAILED", "\"failureReason\":\"Document processing failed.\"", 204);
            RagDocument document = documentRepository.findById(id).orElseThrow();
            assertThat(document.getAttemptCount()).isEqualTo(attempt);
            assertThat(document.getStatus()).isEqualTo(attempt < 3 ? DocumentStatus.PENDING_CHUNKING : DocumentStatus.FAILED);
            assertThat(document.getProcessingAttemptId()).isNull();
            assertThat(document.getProducedChunkCount()).isNull();
        }
        mockMvc.perform(post("/api/v1/internal/documents/claim").header("X-Worker-Api-Key", WORKER_API_KEY))
                .andExpect(status().isOk()).andExpect(jsonPath("$").isEmpty());
        reportResult(id, firstAttempt, "READY", "\"producedChunkCount\":99", 409);
        assertThat(documentRepository.findById(id).orElseThrow().getStatus()).isEqualTo(DocumentStatus.FAILED);
    }

    @Test
    void deletingClaimedDocumentInvalidatesDownloadAndLateResult() throws Exception {
        String id = uploadDocument();
        String attempt = claimAttempt(id);
        String ownerId = documentRepository.findById(id).orElseThrow().getOwnerId();
        documentService.softDelete(ownerId, id);
        mockMvc.perform(get("/api/v1/internal/documents/{id}/content", id)
                        .header("X-Worker-Api-Key", WORKER_API_KEY).header("X-Worker-Attempt-Id", attempt))
                .andExpect(status().isConflict());
        reportResult(id, attempt, "READY", "\"producedChunkCount\":99", 409);
        assertThat(documentRepository.findByIdIncludingDeleted(id)).isPresent().get().extracting("deletedAt").isNotNull();
    }

    private String claimAttempt(String id) throws Exception {
        String body = mockMvc.perform(post("/api/v1/internal/documents/claim").header("X-Worker-Api-Key", WORKER_API_KEY))
                .andExpect(status().isOk()).andExpect(jsonPath("$[0].id").value(id))
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(body).get(0).get("attemptId").asText();
    }

    private void reportResult(String id, String attempt, String result, String fields, int expectedStatus) throws Exception {
        mockMvc.perform(patch("/api/v1/internal/documents/{id}/result", id)
                        .header("X-Worker-Api-Key", WORKER_API_KEY).header("X-Worker-Attempt-Id", attempt)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"attemptId\":\"" + attempt + "\",\"status\":\"" + result + "\"," + fields + "}"))
                .andExpect(status().is(expectedStatus));
    }

    private String registerAndGetAccessToken() throws Exception {
        userRepository.saveAndFlush(new User(
                "worker-owner@example.com",
                passwordEncoder.encode("StrongPassword123!"),
                "Worker Owner",
                UserRole.ADMIN
        ));
        String response = mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "email": "worker-owner@example.com",
                                  "password": "StrongPassword123!"
                                }
                                """))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();
        JsonNode body = objectMapper.readTree(response);
        return body.get("accessToken").asText();
    }
}
