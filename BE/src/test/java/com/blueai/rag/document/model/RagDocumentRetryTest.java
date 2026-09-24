package com.blueai.rag.document.model;

import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

class RagDocumentRetryTest {

    @Test
    void staleAttemptTokenChangesOnReclaimAndFailureBecomesTerminalAtBound() {
        RagDocument document = new RagDocument(null, "guide.txt", "owner/guide.txt", "text/plain", 10, 800, 100);

        document.markProcessing();
        String firstAttempt = document.getProcessingAttemptId();
        document.markPendingAgain("worker timeout", 3, Duration.ZERO);
        document.markProcessing();
        String secondAttempt = document.getProcessingAttemptId();
        document.markPendingAgain("worker timeout", 3, Duration.ZERO);
        document.markProcessing();
        document.markFailed("permanent parser failure", 3, Duration.ZERO);

        assertThat(firstAttempt).isNotEqualTo(secondAttempt);
        assertThat(document.getAttemptCount()).isEqualTo(3);
        assertThat(document.getProcessingAttemptId()).isNull();
        assertThat(document.getStatus()).isEqualTo(DocumentStatus.FAILED);
    }
}
