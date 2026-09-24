package com.blueai.rag.document.controller;

import com.blueai.BlueAiAuthApplication;
import com.blueai.auth.model.User;
import com.blueai.auth.model.UserRole;
import com.blueai.auth.repository.RefreshTokenRepository;
import com.blueai.auth.repository.UserRepository;
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
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(classes = BlueAiAuthApplication.class)
@AutoConfigureMockMvc
@ActiveProfiles("test")
class DocumentUploadIntegrationTest {

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
    void authenticatedUserCanUploadDocumentAndRequestChunkSettings() throws Exception {
        String accessToken = registerAndGetAccessToken();
        MockMultipartFile file = new MockMultipartFile(
                "file",
                "product-guide.md",
                "text/markdown",
                "# Product guide\nThis document will be chunked by the Python worker.\n".getBytes()
        );

        mockMvc.perform(multipart("/api/v1/documents")
                        .file(file)
                        .param("chunkSize", "900")
                        .param("chunkOverlap", "120")
                        .header("Authorization", "Bearer " + accessToken))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.originalFilename").value("product-guide.md"))
                .andExpect(jsonPath("$.requestedChunkSize").value(900))
                .andExpect(jsonPath("$.requestedChunkOverlap").value(120))
                .andExpect(jsonPath("$.status").value("PENDING_CHUNKING"));

        RagDocument saved = documentRepository.findAll().getFirst();
        assertThat(saved.getStorageKey()).startsWith(userRepository.findAll().getFirst().getId() + "/");
        assertThat(Files.readString(TEST_STORAGE.resolve(saved.getStorageKey()))).contains("Product guide");
    }

    @Test
    void rejectsUnsupportedFileType() throws Exception {
        String accessToken = registerAndGetAccessToken();
        MockMultipartFile file = new MockMultipartFile("file", "malware.exe", "application/octet-stream", new byte[]{1});

        mockMvc.perform(multipart("/api/v1/documents")
                        .file(file)
                        .header("Authorization", "Bearer " + accessToken))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("UNSUPPORTED_FILE_TYPE"));
    }

    @Test
    void authenticatedUserCanUploadCsvKnowledgeBase() throws Exception {
        String accessToken = registerAndGetAccessToken();
        MockMultipartFile file = new MockMultipartFile(
                "file",
                "products.csv",
                "text/csv",
                "sku,name,refund_hours\nSB47,Sao Bang 47,24\n".getBytes()
        );

        mockMvc.perform(multipart("/api/v1/documents")
                        .file(file)
                        .header("Authorization", "Bearer " + accessToken))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.originalFilename").value("products.csv"))
                .andExpect(jsonPath("$.contentType").value("text/csv"))
                .andExpect(jsonPath("$.status").value("PENDING_CHUNKING"));

        RagDocument saved = documentRepository.findAll().getFirst();
        assertThat(Files.readString(TEST_STORAGE.resolve(saved.getStorageKey())))
                .contains("SB47,Sao Bang 47,24");
    }

    @Test
    void ownerCanListAndReadOnlyTheirOwnDocuments() throws Exception {
        String ownerToken = registerAndGetAccessToken("owner@example.com");
        String otherUserToken = registerAndGetAccessToken("other@example.com");
        MockMultipartFile file = new MockMultipartFile("file", "plan.txt", "text/plain", "RAG plan".getBytes());

        String uploadResponse = mockMvc.perform(multipart("/api/v1/documents")
                        .file(file)
                        .header("Authorization", "Bearer " + ownerToken))
                .andExpect(status().isCreated())
                .andReturn()
                .getResponse()
                .getContentAsString();
        String documentId = objectMapper.readTree(uploadResponse).get("id").asText();

        mockMvc.perform(get("/api/v1/documents")
                        .param("status", "PENDING_CHUNKING")
                        .header("Authorization", "Bearer " + ownerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].id").value(documentId))
                .andExpect(jsonPath("$[0].status").value("PENDING_CHUNKING"))
                .andExpect(jsonPath("$[0].storageKey").doesNotExist());

        mockMvc.perform(get("/api/v1/documents/{documentId}", documentId)
                        .header("Authorization", "Bearer " + ownerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.originalFilename").value("plan.txt"));

        mockMvc.perform(get("/api/v1/documents/{documentId}", documentId)
                        .header("Authorization", "Bearer " + otherUserToken))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("DOCUMENT_NOT_FOUND"));
    }

    @Test
    void documentSearchPaginatesWithExactTotalsOwnerIsolationAndLiteralLikeCharacters() throws Exception {
        String ownerToken = registerAndGetAccessToken("search-doc-owner@example.com");
        String otherToken = registerAndGetAccessToken("search-doc-other@example.com");
        uploadDocument(ownerToken, "100% plan.txt", "100% plan");
        uploadDocument(ownerToken, "1000 plan.txt", "1000 plan");
        uploadDocument(otherToken, "100% plan.txt", "other plan");

        mockMvc.perform(get("/api/v1/documents/search")
                        .header("Authorization", "Bearer " + ownerToken)
                        .param("q", "100%")
                        .param("page", "0")
                        .param("size", "1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(1))
                .andExpect(jsonPath("$.totalItems").value(1))
                .andExpect(jsonPath("$.items[0].originalFilename").value("100% plan.txt"));
    }

    @Test
    void documentSearchPagesAcrossStatusFilteredResultsAndRejectsInvalidBounds() throws Exception {
        String ownerToken = registerAndGetAccessToken("document-page-owner@example.com");
        String otherToken = registerAndGetAccessToken("document-page-other@example.com");
        String first = uploadDocument(ownerToken, "paging-alpha.txt", "alpha content");
        String second = uploadDocument(ownerToken, "paging-beta.txt", "beta content");
        uploadDocument(otherToken, "paging-outsider.txt", "outsider content");

        mockMvc.perform(get("/api/v1/documents/search")
                        .header("Authorization", "Bearer " + ownerToken)
                        .param("q", "paging")
                        .param("status", "PENDING_CHUNKING")
                        .param("page", "0")
                        .param("size", "1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(1))
                .andExpect(jsonPath("$.totalItems").value(2))
                .andExpect(jsonPath("$.totalPages").value(2))
                .andExpect(jsonPath("$.hasNext").value(true));
        mockMvc.perform(get("/api/v1/documents/search")
                        .header("Authorization", "Bearer " + ownerToken)
                        .param("q", "paging")
                        .param("status", "PENDING_CHUNKING")
                        .param("page", "1")
                        .param("size", "1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(1))
                .andExpect(jsonPath("$.totalItems").value(2))
                .andExpect(jsonPath("$.hasPrevious").value(true));
        assertThat(objectMapper.readTree(first).get("id").asText())
                .isNotEqualTo(objectMapper.readTree(second).get("id").asText());

        mockMvc.perform(get("/api/v1/documents/search")
                        .header("Authorization", "Bearer " + ownerToken)
                        .param("status", "READY"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalItems").value(0));
        mockMvc.perform(get("/api/v1/documents/search")
                        .header("Authorization", "Bearer " + ownerToken)
                        .param("page", "-1"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_PAGE"));
        mockMvc.perform(get("/api/v1/documents/search")
                        .header("Authorization", "Bearer " + ownerToken)
                        .param("size", "101"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_PAGE"));
    }

    @Test
    void deleteHidesDocumentFromAllOwnedReadsAndLeavesRetentionTombstone() throws Exception {
        String ownerToken = registerAndGetAccessToken("delete-doc-owner@example.com");
        String response = uploadDocument(ownerToken, "secret.txt", "secret document");
        String documentId = objectMapper.readTree(response).get("id").asText();

        mockMvc.perform(delete("/api/v1/documents/{documentId}", documentId)
                        .header("Authorization", "Bearer " + ownerToken))
                .andExpect(status().isNoContent());
        mockMvc.perform(get("/api/v1/documents/{documentId}", documentId)
                        .header("Authorization", "Bearer " + ownerToken))
                .andExpect(status().isNotFound());
        mockMvc.perform(get("/api/v1/documents/search")
                        .header("Authorization", "Bearer " + ownerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalItems").value(0));

        assertThat(documentRepository.findByIdIncludingDeleted(documentId)).isPresent().get()
                .extracting("deletedAt").isNotNull();
        assertThat(documentRepository.countByStatus(com.blueai.rag.document.model.DocumentStatus.PENDING_CHUNKING))
                .isZero();
        assertThat(documentRepository.countByStatusIn(List.of(
                com.blueai.rag.document.model.DocumentStatus.PENDING_CHUNKING)))
                .isZero();
    }

    @Test
    void uploadWithoutJwtIsRejected() throws Exception {
        MockMultipartFile file = new MockMultipartFile("file", "guide.txt", "text/plain", "text".getBytes());

        mockMvc.perform(multipart("/api/v1/documents").file(file))
                .andExpect(status().isUnauthorized());
    }

    private String registerAndGetAccessToken() throws Exception {
        return registerAndGetAccessToken("owner@example.com");
    }

    private String uploadDocument(String accessToken, String filename, String content) throws Exception {
        return mockMvc.perform(multipart("/api/v1/documents")
                        .file(new MockMultipartFile("file", filename, "text/plain", content.getBytes()))
                        .header("Authorization", "Bearer " + accessToken))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
    }

    private String registerAndGetAccessToken(String email) throws Exception {
        userRepository.saveAndFlush(new User(email, passwordEncoder.encode("StrongPassword123!"), "Owner", UserRole.ADMIN));
        String response = mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "email": "%s",
                                  "password": "StrongPassword123!"
                                }
                                """.formatted(email)))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();
        JsonNode body = objectMapper.readTree(response);
        return body.get("accessToken").asText();
    }
}
