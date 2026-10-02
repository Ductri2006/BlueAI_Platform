package com.blueai.auth.service;

import com.blueai.auth.dto.PurgeAuthRecordsResponse;
import com.blueai.auth.repository.PasswordResetTokenRepository;
import com.blueai.auth.repository.RefreshTokenRepository;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class AuthRecordRetentionOrderingTest {

    @Test
    void purgeAcquiresRefreshBeforeResetAndPreservesCutoffAndResponseCounts() {
        RefreshTokenRepository refreshTokens = mock(RefreshTokenRepository.class);
        PasswordResetTokenRepository resetTokens = mock(PasswordResetTokenRepository.class);
        AuthAuditLogService auditLogs = mock(AuthAuditLogService.class);
        when(refreshTokens.purgeBefore(any(Instant.class))).thenReturn(3);
        when(resetTokens.purgeBefore(any(Instant.class))).thenReturn(2);
        when(auditLogs.purgeBefore(any(Instant.class))).thenReturn(4);
        AuthRecordRetentionService service = new AuthRecordRetentionService(resetTokens, refreshTokens, auditLogs);

        PurgeAuthRecordsResponse result = service.purge(90, "retention-test-admin");

        ArgumentCaptor<Instant> cutoff = ArgumentCaptor.forClass(Instant.class);
        InOrder order = inOrder(refreshTokens, resetTokens, auditLogs);
        order.verify(refreshTokens).purgeBefore(cutoff.capture());
        order.verify(resetTokens).purgeBefore(cutoff.getValue());
        order.verify(auditLogs).purgeBefore(cutoff.getValue());
        order.verify(auditLogs).recordInTransaction("AUTH_RECORDS_PURGED", "SUCCESS", "retention-test-admin", null);
        order.verifyNoMoreInteractions();
        assertThat(result).isEqualTo(new PurgeAuthRecordsResponse(2, 3, 4));
    }
}
