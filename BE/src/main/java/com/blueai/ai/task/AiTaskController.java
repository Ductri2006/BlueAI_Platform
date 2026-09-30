package com.blueai.ai.task;

import com.blueai.auth.model.User;
import com.blueai.auth.model.UserRole;
import com.blueai.auth.model.UserStatus;
import com.blueai.auth.repository.UserRepository;
import com.blueai.rag.document.model.DocumentStatus;
import com.blueai.rag.document.repository.RagDocumentRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.validation.constraints.NotBlank;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.security.oauth2.jwt.Jwt;

import java.security.SecureRandom;
import java.time.Instant;
import java.util.*;

@RestController
public class AiTaskController {
    private final JdbcTemplate jdbc;
    private final UserRepository users;
    private final RagDocumentRepository documents;
    private final AiTaskService tasks;
    private final ObjectMapper mapper;
    private final SecureRandom random = new SecureRandom();

    public AiTaskController(JdbcTemplate jdbc, UserRepository users, RagDocumentRepository documents,
                            AiTaskService tasks, ObjectMapper mapper) {
        this.jdbc=jdbc; this.users=users; this.documents=documents; this.tasks=tasks; this.mapper=mapper;
    }

    @PostMapping("/api/v1/admin/integration-clients")
    @Transactional
    ResponseEntity<?> createClient(@AuthenticationPrincipal Jwt jwt, @RequestBody JsonNode body) {
        requireKeys(body, Set.of("name", "capabilities", "documentIds"));
        String name = requiredText(body, "name", 100);
        Set<String> caps = stringSet(body.path("capabilities"), Set.of("CLASSIFY","EXTRACT","RAG_ANSWER"), 3);
        if (caps.isEmpty()) throw bad("INVALID_CAPABILITIES", "At least one capability is required.");
        Set<String> docIds = stringSet(body.path("documentIds"), null, 100);
        User owner = users.findById(jwt.getSubject()).orElseThrow(() -> bad("OWNER_NOT_FOUND", "Admin account is unavailable."));
        if(owner.getRole()!= UserRole.ADMIN || owner.getStatus()!= UserStatus.ACTIVE || owner.isDeleted()) throw bad("OWNER_NOT_ACTIVE_ADMIN", "An active administrator is required.");
        for(String id:docIds) {
            var doc=documents.findByIdAndOwner_Id(id, owner.getId()).orElseThrow(() -> bad("INVALID_DOCUMENT_GRANT", "Every document grant must belong to the administrator."));
            if(doc.getStatus()!= DocumentStatus.READY) throw bad("INVALID_DOCUMENT_GRANT", "Only READY documents can be granted.");
        }
        String id=UUID.randomUUID().toString();
        byte[] bytes=new byte[32]; random.nextBytes(bytes);
        String token="bai_"+Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        jdbc.update("insert into ai_integration_clients(id,owner_id,name,credential_hash,active,created_at,rate_count) values(?,?,?,?,true,?,0)",
                id, owner.getId(), name, HexFormat.of().formatHex(AiTaskCredentialFilter.sha256(token)), Instant.now());
        for(String cap:caps) jdbc.update("insert into ai_integration_capabilities(client_id,capability) values(?,?)",id,cap);
        for(String doc:docIds) jdbc.update("insert into ai_integration_document_grants(client_id,document_id) values(?,?)",id,doc);
        return ResponseEntity.status(HttpStatus.CREATED).body(Map.of("id",id,"name",name,"credential",token,
                "capabilities",caps,"documentIds",docIds,"credentialShownOnce",true));
    }

    @GetMapping("/api/v1/admin/integration-clients")
    List<Map<String,Object>> listClients(@AuthenticationPrincipal Jwt jwt,@RequestParam(defaultValue="0") int page, @RequestParam(defaultValue="50") int size) {
        requireAdmin(jwt.getSubject());
        if(page<0 || page>100000 || size<1 || size>100) throw bad("INVALID_PAGE", "Use page 0..100000 and size 1..100.");
        return jdbc.queryForList("select id,name,active,created_at,revoked_at from ai_integration_clients where owner_id=? order by created_at desc limit ? offset ?",jwt.getSubject(),size,(long)page*size);
    }

    @DeleteMapping("/api/v1/admin/integration-clients/{id}")
    @Transactional
    ResponseEntity<Void> revoke(@AuthenticationPrincipal Jwt jwt,@PathVariable String id) {
        requireAdmin(jwt.getSubject());
        int changed=jdbc.update("update ai_integration_clients set active=false,revoked_at=? where id=? and owner_id=? and revoked_at is null",Instant.now(),id,jwt.getSubject());
        if(changed==0) throw new AiTaskException(HttpStatus.NOT_FOUND,"CLIENT_NOT_FOUND", "Integration client does not exist or is already revoked.");
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/api/v1/ai/tasks")
    ResponseEntity<?> createTask(@AuthenticationPrincipal AiClientPrincipal client,
                                 @RequestHeader(value="Idempotency-Key",required=false) String key, @RequestBody JsonNode body) {
        if(key==null||key.isBlank() || key.length()>128) throw bad("INVALID_IDEMPOTENCY_KEY", "Idempotency-Key must contain 1..128 characters.");
        requireKeys(body, Set.of("capability","input","configuration","history","metadata"));
        return tasks.submit(client, key, body);
    }

    @GetMapping("/api/v1/ai/tasks/{id}")
    Map<String,Object> getTask(@AuthenticationPrincipal AiClientPrincipal client,@PathVariable String id) {
        return tasks.get(client,id);
    }

    static void requireKeys(JsonNode node,Set<String> allowed) {
        if(node==null || !node.isObject()) throw bad("INVALID_REQUEST","Request must be a JSON object.");
        node.fieldNames().forEachRemaining(name->{if(!allowed.contains(name)) throw bad("UNKNOWN_FIELD","Unknown request field: "+name);});
    }
    static String requiredText(JsonNode node,String name,int max) {
        JsonNode v=node.get(name); if(v==null || !v.isTextual() || v.asText().isBlank() || v.asText().length()>max) throw bad("INVALID_REQUEST","Field "+name+" must be non-empty and at most "+max+" characters."); return v.asText();
    }
    private void requireAdmin(String id) {
        User u=users.findById(id).orElseThrow(()->new AiTaskException(HttpStatus.FORBIDDEN,"ADMIN_REQUIRED","Active administrator required."));
        if(u.getRole()!=UserRole.ADMIN||u.getStatus()!=UserStatus.ACTIVE||u.isDeleted())throw new AiTaskException(HttpStatus.FORBIDDEN,"ADMIN_REQUIRED","Active administrator required.");
    }
    static Set<String> stringSet(JsonNode n,Set<String> allowed,int max) {
        if(n==null || n.isMissingNode() || n.isNull()) return Set.of();
        if(!n.isArray() || n.size()>max) throw bad("INVALID_REQUEST","Expected a bounded array.");
        LinkedHashSet<String> values=new LinkedHashSet<>(); for(JsonNode v:n) {if(!v.isTextual() || v.asText().isBlank()) throw bad("INVALID_REQUEST","Array values must be text."); if(allowed!=null&&!allowed.contains(v.asText())) throw bad("INVALID_REQUEST","Unsupported value."); values.add(v.asText());}
        if(values.size()!=n.size()) throw bad("INVALID_REQUEST","Duplicate array values are not allowed."); return values;
    }
    static AiTaskException bad(String code,String message) { return new AiTaskException(HttpStatus.BAD_REQUEST,code,message); }
}
