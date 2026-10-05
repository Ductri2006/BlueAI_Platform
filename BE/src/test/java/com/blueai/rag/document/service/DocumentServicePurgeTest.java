package com.blueai.rag.document.service;

import com.blueai.auth.repository.UserRepository;
import com.blueai.rag.document.model.RagDocument;
import com.blueai.rag.document.repository.RagDocumentRepository;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

class DocumentServicePurgeTest {

    @Test
    void storageFailureKeepsTombstoneForRetryAndDoesNotDeleteRow() throws Exception {
        Path root = Files.createTempDirectory("document-purge-test");
        try {
            Path nonEmptyDirectory = root.resolve("owner/document.txt");
            Files.createDirectories(nonEmptyDirectory);
            Files.writeString(nonEmptyDirectory.resolve("still-secret"), "secret");

            RagDocumentRepository documents = mock(RagDocumentRepository.class);
            RagDocument document = new RagDocument(null, "document.txt", "owner/document.txt", "text/plain", 1, 800, 100);
            document.softDelete();
            when(documents.findByIdIncludingDeleted("doc-1")).thenReturn(Optional.of(document));
            DocumentService service = new DocumentService(
                    mock(UserRepository.class), documents, root.toString(), 100, Duration.ofMinutes(1), 3, Duration.ZERO
            );

            assertThatThrownBy(() -> service.purgeDeletedDocument("doc-1", Instant.now().plusSeconds(1)))
                    .hasMessageContaining("storage could not be purged");
            verify(documents, never()).delete(document);
            assertThat(Files.exists(nonEmptyDirectory.resolve("still-secret"))).isTrue();
        } finally {
            try (var paths = Files.walk(root)) {
                paths.sorted((left, right) -> right.compareTo(left)).forEach(path -> {
                    try { Files.deleteIfExists(path); } catch (Exception ignored) { }
                });
            }
        }
    }

    @Test
    void databaseFinalizeFailureLeavesTombstoneRetryableAfterBytesAreGone() throws Exception {
        Path root = Files.createTempDirectory("document-purge-db-failure");
        try {
            Path storedFile = root.resolve("owner/document.txt");
            Files.createDirectories(storedFile.getParent());
            Files.writeString(storedFile, "secret");

            RagDocumentRepository documents = mock(RagDocumentRepository.class);
            RagDocument document = new RagDocument(null, "document.txt", "owner/document.txt", "text/plain", 1, 800, 100);
            document.softDelete();
            when(documents.findByIdIncludingDeleted("doc-1")).thenReturn(Optional.of(document));
            doThrow(new IllegalStateException("db unavailable")).when(documents).delete(document);
            DocumentService service = new DocumentService(
                    mock(UserRepository.class), documents, root.toString(), 100, Duration.ofMinutes(1), 3, Duration.ZERO
            );

            assertThatThrownBy(() -> service.purgeDeletedDocument("doc-1", Instant.now().plusSeconds(1)))
                    .isInstanceOf(IllegalStateException.class);
            assertThat(Files.exists(storedFile)).isFalse();
            assertThat(document.getDeletedAt()).isNotNull();
            verify(documents).delete(document);
        } finally {
            try (var paths = Files.walk(root)) {
                paths.sorted((left, right) -> right.compareTo(left)).forEach(path -> {
                    try { Files.deleteIfExists(path); } catch (Exception ignored) { }
                });
            }
        }
    }
}
