package com.blueai.auth.controller;

import com.blueai.auth.dto.CreateManagedUserRequest;
import com.blueai.auth.dto.AuthAuditLogResponse;
import com.blueai.auth.dto.PageResponse;
import com.blueai.auth.dto.TemporaryPasswordRequest;
import com.blueai.auth.dto.TemporaryPasswordResponse;
import org.springframework.http.CacheControl;
import com.blueai.auth.dto.PurgeAuthRecordsRequest;
import com.blueai.auth.dto.PurgeAuthRecordsResponse;
import com.blueai.auth.dto.UpdateUserStatusRequest;
import com.blueai.auth.dto.UserResponse;
import com.blueai.auth.service.AuthAuditLogService;
import com.blueai.auth.service.AuthRecordRetentionService;
import com.blueai.auth.service.AdminUserService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;

@RestController
@RequestMapping("/api/v1/admin")
public class AdminUserController {

    private final AdminUserService adminUserService;
    private final AuthAuditLogService auditLogService;
    private final AuthRecordRetentionService retentionService;

    public AdminUserController(
            AdminUserService adminUserService,
            AuthAuditLogService auditLogService,
            AuthRecordRetentionService retentionService
    ) {
        this.adminUserService = adminUserService;
        this.auditLogService = auditLogService;
        this.retentionService = retentionService;
    }

    @GetMapping("/users")
    ResponseEntity<?> listUsers(
            @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer size,
            @RequestParam(required = false) String search,
            @RequestParam(required = false) com.blueai.auth.model.UserStatus status,
            @RequestParam(defaultValue = "false") boolean includeDeleted
    ) {
        if (page == null && size == null && search == null && status == null && !includeDeleted) {
            return ResponseEntity.ok(adminUserService.listUsers());
        }
        return ResponseEntity.ok(adminUserService.searchUsers(
                search,
                status,
                includeDeleted,
                page == null ? 0 : page,
                size == null ? 20 : size
        ));
    }

    @PostMapping("/users")
    ResponseEntity<UserResponse> createUser(
            @AuthenticationPrincipal Jwt jwt,
            @Valid @RequestBody CreateManagedUserRequest request
    ) {
        return ResponseEntity.status(HttpStatus.CREATED).body(adminUserService.createUser(jwt.getSubject(), request));
    }

    @PatchMapping("/users/{userId}/status")
    UserResponse updateStatus(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable String userId,
            @Valid @RequestBody UpdateUserStatusRequest request
    ) {
        return adminUserService.updateStatus(jwt.getSubject(), userId, request.status());
    }

    @PostMapping("/users/{userId}/password")
    ResponseEntity<TemporaryPasswordResponse> setPassword(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable String userId,
            @Valid @RequestBody TemporaryPasswordRequest request
    ) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(adminUserService.resetTemporaryPassword(jwt.getSubject(), userId, request.currentPassword()));
    }

    @DeleteMapping("/users/{userId}")
    UserResponse deleteUser(@AuthenticationPrincipal Jwt jwt, @PathVariable String userId) {
        return adminUserService.softDelete(jwt.getSubject(), userId);
    }

    @GetMapping("/audit-logs")
    PageResponse<AuthAuditLogResponse> auditLogs(
            @RequestParam(required = false) String action,
            @RequestParam(required = false) String actorUserId,
            @RequestParam(required = false) String targetUserId,
            @RequestParam(required = false) Instant from,
            @RequestParam(required = false) Instant to,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size
    ) {
        return PageResponse.from(auditLogService.search(action, actorUserId, targetUserId, from, to, page, size));
    }

    @PostMapping("/auth-records/purge")
    PurgeAuthRecordsResponse purgeAuthRecords(
            @AuthenticationPrincipal Jwt jwt,
            @Valid @RequestBody PurgeAuthRecordsRequest request
    ) {
        return retentionService.purge(request.retentionDays(), jwt.getSubject());
    }
}
