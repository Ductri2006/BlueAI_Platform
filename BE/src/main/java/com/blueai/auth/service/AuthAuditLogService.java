package com.blueai.auth.service;

import com.blueai.auth.dto.AuthAuditLogResponse;
import com.blueai.auth.model.AuthAuditLog;
import com.blueai.auth.repository.AuthAuditLogRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.Instant;

@Service
public class AuthAuditLogService {

    private final AuthAuditLogRepository repository;
    private final TransactionTemplate requiresNewTransaction;

    public AuthAuditLogService(
            AuthAuditLogRepository repository,
            PlatformTransactionManager transactionManager
    ) {
        this.repository = repository;
        this.requiresNewTransaction = new TransactionTemplate(transactionManager);
        this.requiresNewTransaction.setPropagationBehavior(TransactionTemplate.PROPAGATION_REQUIRES_NEW);
    }

    public void record(String action, String outcome, String actorUserId, String targetUserId) {
        requiresNewTransaction.executeWithoutResult(status ->
                repository.save(new AuthAuditLog(action, outcome, actorUserId, targetUserId))
        );
    }

    /** Security mutations and their audit must share one connection and one commit. */
    @Transactional(propagation = Propagation.MANDATORY)
    public void recordInTransaction(String action, String outcome, String actorUserId, String targetUserId) {
        repository.save(new AuthAuditLog(action, outcome, actorUserId, targetUserId));
    }

    public void recordAfterCommit(String action, String outcome, String actorUserId, String targetUserId) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            record(action, outcome, actorUserId, targetUserId);
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                record(action, outcome, actorUserId, targetUserId);
            }
        });
    }

    @Transactional(readOnly = true)
    public Page<AuthAuditLogResponse> search(String action, String actorUserId, String targetUserId,
                                             Instant from, Instant to, int page, int size) {
        if (page < 0 || size < 1 || size > 100) {
            throw new com.blueai.auth.exception.ApiException(
                    org.springframework.http.HttpStatus.BAD_REQUEST,
                    "INVALID_PAGE_REQUEST",
                    "Page must be non-negative and size must be between 1 and 100."
            );
        }
        return repository.search(action, actorUserId, targetUserId, from, to,
                        PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "createdAt")))
                .map(AuthAuditLogResponse::from);
    }

    @Transactional
    public int purgeBefore(Instant cutoff) {
        return repository.purgeBefore(cutoff);
    }
}
