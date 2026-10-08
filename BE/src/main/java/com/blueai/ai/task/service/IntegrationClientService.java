package com.blueai.ai.task.service;

import static com.blueai.ai.task.dto.AiTaskRequestValidator.*;

import com.blueai.ai.task.exception.AiTaskException;
import com.blueai.ai.task.repository.IntegrationClientRepository;
import com.blueai.ai.task.security.CredentialHasher;
import com.blueai.auth.model.User;
import com.blueai.auth.model.UserRole;
import com.blueai.auth.model.UserStatus;
import com.blueai.auth.repository.UserRepository;
import com.blueai.rag.document.model.DocumentStatus;
import com.blueai.rag.document.repository.RagDocumentRepository;
import com.fasterxml.jackson.databind.JsonNode;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.time.Instant;
import java.util.*;

/** Manage administrator-owned integrations and their capability/document grants. */
@Service
public class IntegrationClientService {
    private final IntegrationClientRepository clients;
    private final UserRepository users;
    private final RagDocumentRepository documents;
    private final SecureRandom random = new SecureRandom();

    public IntegrationClientService(
            IntegrationClientRepository clients,
            UserRepository users,
            RagDocumentRepository documents) {
        this.clients = clients;
        this.users = users;
        this.documents = documents;
    }

    @Transactional
    public Map<String, Object> createClient(String ownerId, JsonNode body) {
        requireKeys(body, Set.of("name", "capabilities", "documentIds"));
        String name = requiredText(body, "name", 100);
        Set<String> caps =
                stringSet(
                        body.path("capabilities"), Set.of("CLASSIFY", "EXTRACT", "RAG_ANSWER"), 3);
        if (caps.isEmpty())
            throw bad("INVALID_CAPABILITIES", "At least one capability is required.");
        Set<String> docIds = stringSet(body.path("documentIds"), null, 100);
        User owner =
                users.findById(ownerId)
                        .orElseThrow(() -> bad("OWNER_NOT_FOUND", "Admin account is unavailable."));
        if (owner.getRole() != UserRole.ADMIN
                || owner.getStatus() != UserStatus.ACTIVE
                || owner.isDeleted())
            throw bad("OWNER_NOT_ACTIVE_ADMIN", "An active administrator is required.");
        for (String id : docIds) {
            var doc =
                    documents
                            .findByIdAndOwner_Id(id, owner.getId())
                            .orElseThrow(
                                    () ->
                                            bad(
                                                    "INVALID_DOCUMENT_GRANT",
                                                    "Every document grant must belong to the"
                                                        + " administrator."));
            if (doc.getStatus() != DocumentStatus.READY)
                throw bad("INVALID_DOCUMENT_GRANT", "Only READY documents can be granted.");
        }
        String id = UUID.randomUUID().toString();
        byte[] bytes = new byte[32];
        random.nextBytes(bytes);
        String token = "bai_" + Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        clients.create(
                id,
                owner.getId(),
                name,
                HexFormat.of().formatHex(CredentialHasher.sha256(token)),
                Instant.now());
        for (String cap : caps) clients.grantCapability(id, cap);
        for (String doc : docIds) clients.grantDocument(id, doc);
        return Map.of(
                "id",
                id,
                "name",
                name,
                "credential",
                token,
                "capabilities",
                caps,
                "documentIds",
                docIds,
                "credentialShownOnce",
                true);
    }

    public List<Map<String, Object>> listClients(String ownerId, int page, int size) {
        requireAdmin(ownerId);
        if (page < 0 || page > 100000 || size < 1 || size > 100)
            throw bad("INVALID_PAGE", "Use page 0..100000 and size 1..100.");
        return clients.listOwned(ownerId, size, (long) page * size);
    }

    @Transactional
    public void revoke(String ownerId, String id) {
        requireAdmin(ownerId);
        int changed = clients.revokeOwned(Instant.now(), id, ownerId);
        if (changed == 0)
            throw new AiTaskException(
                    HttpStatus.NOT_FOUND,
                    "CLIENT_NOT_FOUND",
                    "Integration client does not exist or is already revoked.");
    }

    private void requireAdmin(String id) {
        User u =
                users.findById(id)
                        .orElseThrow(
                                () ->
                                        new AiTaskException(
                                                HttpStatus.FORBIDDEN,
                                                "ADMIN_REQUIRED",
                                                "Active administrator required."));
        if (u.getRole() != UserRole.ADMIN || u.getStatus() != UserStatus.ACTIVE || u.isDeleted())
            throw new AiTaskException(
                    HttpStatus.FORBIDDEN, "ADMIN_REQUIRED", "Active administrator required.");
    }
}
