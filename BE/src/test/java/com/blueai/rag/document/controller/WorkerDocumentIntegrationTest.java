package com.blueai.rag.document.controller;

import com.blueai.auth.BlueAiAuthApplication;
import com.blueai.auth.model.User;
import com.blueai.auth.model.UserRole;
import com.blueai.auth.repository.RefreshTokenRepository;
import com.blueai.auth.repository.UserRepository;
import com.blueai.rag.document.model.DocumentStatus;
import com.blueai.rag.document.model.RagDocument;
import com.blueai.rag.document.repository.RagDocumentRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(classes = BlueAiAuthApplication.class)
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
        assertThat(documentRepository.findById(documentId).orElseThrow().getStatus()).isEqualTo(DocumentStatus.PROCESSING);

        mockMvc.perform(post("/api/v1/internal/documents/claim")
                        .header("X-Worker-Api-Key", WORKER_API_KEY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isEmpty());

        mockMvc.perform(patch("/api/v1/internal/documents/{documentId}/result", documentId)
                        .header("X-Worker-Api-Key", WORKER_API_KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "status": "READY",
                                  "producedChunkCount": 7
                                }
                                """))
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
                .andExpect(jsonPath("$.code").value("DOCUMENT_NOT_PROCESSING"));

        mockMvc.perform(post("/api/v1/internal/documents/claim")
                        .header("X-Worker-Api-Key", WORKER_API_KEY))
                .andExpect(status().isOk());

        mockMvc.perform(get("/api/v1/internal/documents/{documentId}/content", documentId)
                        .header("X-Worker-Api-Key", WORKER_API_KEY))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.TEXT_PLAIN))
                .andExpect(content().string("This is the document that the worker will process."));
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
