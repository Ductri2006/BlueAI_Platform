package com.blueai.ai.task;

import com.blueai.BlueAiAuthApplication;
import com.blueai.auth.model.User;
import com.blueai.auth.model.UserRole;
import com.blueai.auth.repository.UserRepository;
import com.blueai.rag.document.model.RagDocument;
import com.blueai.rag.document.repository.RagDocumentRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(classes=BlueAiAuthApplication.class)
@AutoConfigureMockMvc
@ActiveProfiles("test")
class AiTaskApiIntegrationTest {
    @DynamicPropertySource static void database(DynamicPropertyRegistry registry){registry.add("spring.datasource.url",()->"jdbc:h2:mem:ai-task-api-test;MODE=MySQL;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE");}
    @Autowired MockMvc mvc; @Autowired ObjectMapper mapper; @Autowired JdbcTemplate jdbc; @Autowired UserRepository users; @Autowired RagDocumentRepository documents;
    @MockitoBean ConfiguredAiProviders providers;
    private User admin;

    @BeforeEach void setup(){
        jdbc.update("delete from ai_task_evidence");jdbc.update("delete from ai_task_ledger");jdbc.update("delete from ai_integration_document_grants");jdbc.update("delete from ai_integration_capabilities");jdbc.update("delete from ai_integration_clients");
        documents.deleteAll();users.deleteAll();admin=users.save(new User("ai-owner@example.test","not-used","AI Owner",UserRole.ADMIN));reset(providers);
    }

    @Test void machineCredentialIsScopedAndIdempotencyReplaysOneValidatedCompletion()throws Exception{
        String jwt="owner-jwt";
        String provision=mvc.perform(post("/api/v1/admin/integration-clients").with(jwt().jwt(t->t.subject(admin.getId())).authorities(new SimpleGrantedAuthority("ROLE_ADMIN")))
                .contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"workflow\",\"capabilities\":[\"CLASSIFY\"],\"documentIds\":[]}"))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.credentialShownOnce").value(true)).andReturn().getResponse().getContentAsString();
        JsonNode client=mapper.readTree(provision);String secret=client.path("credential").asText();String clientId=client.path("id").asText();
        assertThat(jdbc.queryForObject("select credential_hash from ai_integration_clients where id=?",String.class,clientId)).doesNotContain(secret);
        String body="{\"capability\":\"CLASSIFY\",\"input\":{\"text\":\"hello\"},\"configuration\":{\"allowedLabels\":[\"sales\",\"support\"]}}";
        mvc.perform(post("/api/v1/ai/tasks").header("Idempotency-Key","execution:node:1").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isUnauthorized());
        mvc.perform(post("/api/v1/ai/tasks").with(jwt().jwt(t->t.subject(admin.getId())).authorities(new SimpleGrantedAuthority("ROLE_ADMIN")))
                        .header("Idempotency-Key","execution:node:1").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isUnauthorized());
        when(providers.complete(anyString(),anyString(),anyList(),any())).thenReturn("{\"label\":\"sales\",\"abstain\":false,\"reason\":\"explicit\"}");
        String first=mvc.perform(post("/api/v1/ai/tasks").header("Authorization","Bearer "+secret).header("Idempotency-Key","execution:node:1")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("COMPLETED")).andExpect(jsonPath("$.result.label").value("sales"))
                .andExpect(jsonPath("$.confidenceStatus").value("UNAVAILABLE")).andReturn().getResponse().getContentAsString();
        String taskId=mapper.readTree(first).path("taskId").asText();
        mvc.perform(post("/api/v1/ai/tasks").header("Authorization","Bearer "+secret).header("Idempotency-Key","execution:node:1")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk()).andExpect(jsonPath("$.taskId").value(taskId));
        verify(providers,times(1)).complete(anyString(),anyString(),anyList(),any());
        mvc.perform(get("/api/v1/ai/tasks/"+taskId).header("Authorization","Bearer "+secret)).andExpect(status().isOk()).andExpect(jsonPath("$.taskId").value(taskId));
        mvc.perform(post("/api/v1/ai/tasks").header("Authorization","Bearer "+secret).header("Idempotency-Key","execution:node:1")
                        .contentType(MediaType.APPLICATION_JSON).content(body.replace("hello","different")))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.error.code").value("IDEMPOTENCY_CONFLICT"));
        mvc.perform(get("/api/v1/admin/integration-clients").header("Authorization","Bearer "+secret)).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/v1/admin/integration-clients").with(jwt().jwt(t->t.subject(admin.getId())).authorities(new SimpleGrantedAuthority("ROLE_ADMIN"))))
                .andExpect(status().isOk()).andExpect(jsonPath("$[0].credential").doesNotExist()).andExpect(jsonPath("$[0].credential_hash").doesNotExist());
    }

    @Test void invalidConfigurationsAndUnknownFieldsFailBeforeProviderCall()throws Exception{
        String provision=mvc.perform(post("/api/v1/admin/integration-clients").with(jwt().jwt(t->t.subject(admin.getId())).authorities(new SimpleGrantedAuthority("ROLE_ADMIN")))
                .contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"workflow\",\"capabilities\":[\"CLASSIFY\"],\"documentIds\":[]}"))
                .andReturn().getResponse().getContentAsString();String key=mapper.readTree(provision).path("credential").asText();
        mvc.perform(post("/api/v1/ai/tasks").header("Authorization","Bearer "+key).header("Idempotency-Key","bad")
                .contentType(MediaType.APPLICATION_JSON).content("{\"capability\":\"CLASSIFY\",\"input\":{\"text\":\"x\"}}"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.error.code").value("INVALID_CONFIGURATION"));
        mvc.perform(post("/api/v1/ai/tasks").header("Authorization","Bearer "+key)
                .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.error.code").value("INVALID_IDEMPOTENCY_KEY"));
        mvc.perform(post("/api/v1/ai/tasks").header("Authorization","Bearer "+key).header("Idempotency-Key","unknown")
                .contentType(MediaType.APPLICATION_JSON).content("{\"capability\":\"CLASSIFY\",\"input\":{\"text\":\"x\"},\"configuration\":{\"allowedLabels\":[\"x\"]},\"ownerId\":\"forged\"}"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.error.code").value("UNKNOWN_FIELD"));
        verifyNoInteractions(providers);
    }

    @Test void extractionUsesTypedOutputAndMissingRequiredFieldsNeedReview()throws Exception{
        String secret=createClient(List.of("EXTRACT"),List.of());
        when(providers.complete(anyString(),anyString(),anyList(),any())).thenReturn("{\"phone\":null,\"quantity\":3}");
        String request="{\"capability\":\"EXTRACT\",\"input\":{\"text\":\"quantity 3\"},\"configuration\":{\"fields\":[{\"name\":\"phone\",\"type\":\"STRING\",\"required\":true},{\"name\":\"quantity\",\"type\":\"NUMBER\",\"required\":false}]}}";
        mvc.perform(post("/api/v1/ai/tasks").header("Authorization","Bearer "+secret).header("Idempotency-Key","extract:1").contentType(MediaType.APPLICATION_JSON).content(request))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("NEEDS_REVIEW"))
                .andExpect(jsonPath("$.result.values.quantity").value(3)).andExpect(jsonPath("$.result.missingFields[0]").value("phone"));
    }

    @Test void extractionRejectsWrongModelFieldTypes()throws Exception{
        String secret=createClient(List.of("EXTRACT"),List.of());
        when(providers.complete(anyString(),anyString(),anyList(),any())).thenReturn("{\"quantity\":\"three\"}");
        String request="{\"capability\":\"EXTRACT\",\"input\":{\"text\":\"quantity three\"},\"configuration\":{\"fields\":[{\"name\":\"quantity\",\"type\":\"NUMBER\",\"required\":false}]}}";
        mvc.perform(post("/api/v1/ai/tasks").header("Authorization","Bearer "+secret).header("Idempotency-Key","extract:wrong-type")
                        .contentType(MediaType.APPLICATION_JSON).content(request))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("FAILED"))
                .andExpect(jsonPath("$.error.code").value("AI_INVALID_OUTPUT"));
    }

    @Test void malformedModelOutputIsPersistedAsFailedAndReplayedWithoutAnotherCompletion()throws Exception{
        String secret=createClient(List.of("CLASSIFY"),List.of());
        when(providers.complete(anyString(),anyString(),anyList(),any())).thenReturn("{\"label\":\"admin\",\"abstain\":false,\"reason\":\"not allowed\"}");
        String request="{\"capability\":\"CLASSIFY\",\"input\":{\"text\":\"hello\"},\"configuration\":{\"allowedLabels\":[\"sales\"]}}";
        String task=mvc.perform(post("/api/v1/ai/tasks").header("Authorization","Bearer "+secret).header("Idempotency-Key","classify:invalid")
                .contentType(MediaType.APPLICATION_JSON).content(request)).andExpect(status().isOk()).andExpect(jsonPath("$.status").value("FAILED"))
                .andExpect(jsonPath("$.error.code").value("AI_INVALID_OUTPUT")).andReturn().getResponse().getContentAsString();
        String taskId=mapper.readTree(task).path("taskId").asText();
        mvc.perform(post("/api/v1/ai/tasks").header("Authorization","Bearer "+secret).header("Idempotency-Key","classify:invalid")
                .contentType(MediaType.APPLICATION_JSON).content(request)).andExpect(status().isOk()).andExpect(jsonPath("$.taskId").value(taskId)).andExpect(jsonPath("$.status").value("FAILED"));
        verify(providers,times(1)).complete(anyString(),anyString(),anyList(),any());
    }

    @Test void clientsAreOwnerScopedAndDemotionInvalidatesExistingMachineCredential()throws Exception{
        User secondAdmin=users.save(new User("second-admin@example.test","unused","Second Admin",UserRole.ADMIN));
        String ownerSecret=createClient(List.of("CLASSIFY"),List.of());
        String otherSecret=createClientAs(secondAdmin,List.of("CLASSIFY"),List.of());
        when(providers.complete(anyString(),anyString(),anyList(),any())).thenReturn("{\"label\":\"sales\",\"abstain\":false,\"reason\":\"explicit\"}");
        String request="{\"capability\":\"CLASSIFY\",\"input\":{\"text\":\"hello\"},\"configuration\":{\"allowedLabels\":[\"sales\"]}}";
        String task=mvc.perform(post("/api/v1/ai/tasks").header("Authorization","Bearer "+ownerSecret).header("Idempotency-Key","owner:task")
                .contentType(MediaType.APPLICATION_JSON).content(request)).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        String taskId=mapper.readTree(task).path("taskId").asText();
        mvc.perform(get("/api/v1/ai/tasks/"+taskId).header("Authorization","Bearer "+otherSecret)).andExpect(status().isNotFound());
        mvc.perform(get("/api/v1/admin/integration-clients").with(jwt().jwt(t->t.subject(secondAdmin.getId())).authorities(new SimpleGrantedAuthority("ROLE_ADMIN"))))
                .andExpect(status().isOk()).andExpect(jsonPath("$[0].name").value("workflow"))
                .andExpect(jsonPath("$[1]").doesNotExist());
        admin.changeRole(UserRole.USER);users.save(admin);
        mvc.perform(get("/api/v1/ai/tasks/"+taskId).header("Authorization","Bearer "+ownerSecret)).andExpect(status().isUnauthorized());
    }

    @Test void concurrentDuplicateClaimsOneProviderCallOutsideDatabaseTransaction()throws Exception{
        String secret=createClient(List.of("CLASSIFY"),List.of());
        String request="{\"capability\":\"CLASSIFY\",\"input\":{\"text\":\"hello\"},\"configuration\":{\"allowedLabels\":[\"sales\"]}}";
        CountDownLatch enteredProvider=new CountDownLatch(1),releaseProvider=new CountDownLatch(1);
        AtomicBoolean providerOutsideTransaction=new AtomicBoolean(false);
        when(providers.complete(anyString(),anyString(),anyList(),any())).thenAnswer(invocation->{
            providerOutsideTransaction.set(!TransactionSynchronizationManager.isActualTransactionActive());
            enteredProvider.countDown();
            if(!releaseProvider.await(5,TimeUnit.SECONDS))throw new IllegalStateException("test provider was not released");
            return "{\"label\":\"sales\",\"abstain\":false,\"reason\":\"explicit\"}";
        });
        var executor=Executors.newSingleThreadExecutor();
        try{
            var first=executor.submit(()->mvc.perform(post("/api/v1/ai/tasks").header("Authorization","Bearer "+secret).header("Idempotency-Key","same:invoke")
                    .contentType(MediaType.APPLICATION_JSON).content(request)).andReturn());
            assertThat(enteredProvider.await(3,TimeUnit.SECONDS)).isTrue();
            mvc.perform(post("/api/v1/ai/tasks").header("Authorization","Bearer "+secret).header("Idempotency-Key","same:invoke")
                    .contentType(MediaType.APPLICATION_JSON).content(request))
                    .andExpect(status().isAccepted()).andExpect(jsonPath("$.status").value("PROCESSING"));
            releaseProvider.countDown();
            var firstResponse=first.get(6,TimeUnit.SECONDS).getResponse();
            assertThat(firstResponse.getStatus()).isEqualTo(200);
            String taskId=mapper.readTree(firstResponse.getContentAsString()).path("taskId").asText();
            mvc.perform(get("/api/v1/ai/tasks/"+taskId).header("Authorization","Bearer "+secret))
                    .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("COMPLETED"));
            assertThat(providerOutsideTransaction).isTrue();
            verify(providers,times(1)).complete(anyString(),anyString(),anyList(),any());
        }finally{releaseProvider.countDown();executor.shutdownNow();}
    }

    @Test void quotaRejectsNewWorkButAllowsCompletedIdempotentReplay()throws Exception{
        String secret=createClient(List.of("CLASSIFY"),List.of());
        when(providers.complete(anyString(),anyString(),anyList(),any())).thenReturn("{\"label\":\"sales\",\"abstain\":false,\"reason\":\"explicit\"}");
        String body="{\"capability\":\"CLASSIFY\",\"input\":{\"text\":\"hello\"},\"configuration\":{\"allowedLabels\":[\"sales\"]}}";
        mvc.perform(post("/api/v1/ai/tasks").header("Authorization","Bearer "+secret).header("Idempotency-Key","quota:original").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk());
        String clientId=jdbc.queryForObject("select id from ai_integration_clients where owner_id=?",String.class,admin.getId());
        jdbc.update("update ai_integration_clients set rate_count=30,rate_window_start=? where id=?",java.time.Instant.now(),clientId);
        mvc.perform(post("/api/v1/ai/tasks").header("Authorization","Bearer "+secret).header("Idempotency-Key","quota:original").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk());
        mvc.perform(post("/api/v1/ai/tasks").header("Authorization","Bearer "+secret).header("Idempotency-Key","quota:new").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isTooManyRequests()).andExpect(header().string("Retry-After","60"));
        verify(providers,times(1)).complete(anyString(),anyString(),anyList(),any());
    }

    @Test void ragUsesOnlySelectedEvidenceAndNoEvidenceNeverCallsCompletion()throws Exception{
        RagDocument ready=saveReadyDocument(admin,"owned.pdf");
        String secret=createClient(List.of("RAG_ANSWER"),List.of(ready.getId()));
        var noEvidence=mapper.createArrayNode();
        when(providers.search(eq(admin.getId()),anyString(),anyList(),any())).thenReturn(noEvidence);
        String request="{\"capability\":\"RAG_ANSWER\",\"input\":{\"text\":\"policy?\"},\"configuration\":{\"documentIds\":[\""+ready.getId()+"\"]}}";
        mvc.perform(post("/api/v1/ai/tasks").header("Authorization","Bearer "+secret).header("Idempotency-Key","rag:none").contentType(MediaType.APPLICATION_JSON).content(request))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("NO_MATCH")).andExpect(jsonPath("$.result.answer").value(""));
        verify(providers,never()).complete(anyString(),anyString(),anyList(),any());

        var forged=mapper.createArrayNode();forged.addObject().put("documentId","different-admin-document").put("chunkId","chunk-x")
                .put("content","private unrelated content").put("filename","private.pdf");
        when(providers.search(eq(admin.getId()),contains("other"),anyList(),any())).thenReturn(forged);
        String forgedReq=request.replace("policy?","other question");
        mvc.perform(post("/api/v1/ai/tasks").header("Authorization","Bearer "+secret).header("Idempotency-Key","rag:forged").contentType(MediaType.APPLICATION_JSON).content(forgedReq))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("NO_MATCH"));
        verify(providers,never()).complete(anyString(),anyString(),anyList(),any());
    }

    @Test void ragSavedAnswerIsBlockedAfterDocumentHardDeleteAndForeignClientCannotPoll()throws Exception{
        RagDocument ready=saveReadyDocument(admin,"policy.pdf");
        String ownerSecret=createClient(List.of("RAG_ANSWER"),List.of(ready.getId()));
        String otherSecret=createClient(List.of("RAG_ANSWER"),List.of(ready.getId()));
        var evidence=mapper.createArrayNode();evidence.addObject().put("documentId",ready.getId()).put("chunkId","chunk-1").put("content","Returns in 30 days").put("filename","policy.pdf");
        when(providers.search(eq(admin.getId()),anyString(),anyList(),any())).thenReturn(evidence);
        when(providers.complete(anyString(),anyString(),anyList(),any())).thenReturn("{\"answer\":\"30 days\",\"abstain\":false,\"citations\":[\"chunk-1\"]}");
        String request="{\"capability\":\"RAG_ANSWER\",\"input\":{\"text\":\"return window?\"},\"configuration\":{\"documentIds\":[\""+ready.getId()+"\"]}}";
        String response=mvc.perform(post("/api/v1/ai/tasks").header("Authorization","Bearer "+ownerSecret).header("Idempotency-Key","rag:success").contentType(MediaType.APPLICATION_JSON).content(request))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("COMPLETED")).andExpect(jsonPath("$.citations[0].chunkId").value("chunk-1"))
                .andReturn().getResponse().getContentAsString();
        String taskId=mapper.readTree(response).path("taskId").asText();
        mvc.perform(get("/api/v1/ai/tasks/"+taskId).header("Authorization","Bearer "+otherSecret)).andExpect(status().isNotFound());
        jdbc.update("delete from rag_documents where id=?",ready.getId());
        mvc.perform(get("/api/v1/ai/tasks/"+taskId).header("Authorization","Bearer "+ownerSecret)).andExpect(status().isGone())
                .andExpect(jsonPath("$.error.code").value("AI_TASK_EVIDENCE_REVOKED"));
        mvc.perform(post("/api/v1/ai/tasks").header("Authorization","Bearer "+ownerSecret).header("Idempotency-Key","rag:success").contentType(MediaType.APPLICATION_JSON).content(request))
                .andExpect(status().isGone());
    }

    @Test void ragRejectsInventedCitationInsteadOfFilteringIt()throws Exception{
        RagDocument ready=saveReadyDocument(admin,"policy.pdf");
        String secret=createClient(List.of("RAG_ANSWER"),List.of(ready.getId()));
        var evidence=mapper.createArrayNode();evidence.addObject().put("documentId",ready.getId()).put("chunkId","chunk-valid").put("content","Returns in 30 days").put("filename","policy.pdf");
        when(providers.search(eq(admin.getId()),anyString(),anyList(),any())).thenReturn(evidence);
        when(providers.complete(anyString(),anyString(),anyList(),any())).thenReturn("{\"answer\":\"30 days\",\"abstain\":false,\"citations\":[\"chunk-valid\",\"invented\"]}");
        String request="{\"capability\":\"RAG_ANSWER\",\"input\":{\"text\":\"return window?\"},\"configuration\":{\"documentIds\":[\""+ready.getId()+"\"]}}";
        mvc.perform(post("/api/v1/ai/tasks").header("Authorization","Bearer "+secret).header("Idempotency-Key","rag:invented-citation")
                        .contentType(MediaType.APPLICATION_JSON).content(request))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("FAILED"))
                .andExpect(jsonPath("$.error.code").value("AI_INVALID_OUTPUT"));
    }

    @Test void revokingClientImmediatelyRejectsItsSecretAndAdminProvisioningCannotGrantForeignDocuments()throws Exception{
        User other=users.save(new User("foreign-owner@example.test","unused","Foreign Owner",UserRole.ADMIN));
        RagDocument foreign=saveReadyDocument(other,"foreign.pdf");
        mvc.perform(post("/api/v1/admin/integration-clients").with(jwt().jwt(t->t.subject(admin.getId())).authorities(new SimpleGrantedAuthority("ROLE_ADMIN")))
                .contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"invalid\",\"capabilities\":[\"RAG_ANSWER\"],\"documentIds\":[\""+foreign.getId()+"\"]}"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.error.code").value("INVALID_DOCUMENT_GRANT"));
        String secret=createClient(List.of("CLASSIFY"),List.of());
        String id=jdbc.queryForObject("select id from ai_integration_clients where owner_id=?",String.class,admin.getId());
        mvc.perform(delete("/api/v1/admin/integration-clients/"+id).with(jwt().jwt(t->t.subject(admin.getId())).authorities(new SimpleGrantedAuthority("ROLE_ADMIN"))))
                .andExpect(status().isNoContent());
        mvc.perform(get("/api/v1/ai/tasks/anything").header("Authorization","Bearer "+secret)).andExpect(status().isUnauthorized());
    }

    private String createClient(List<String> capabilities,List<String> documentIds)throws Exception{
        return createClientAs(admin,capabilities,documentIds);
    }
    private String createClientAs(User creator,List<String> capabilities,List<String> documentIds)throws Exception{
        String capJson=mapper.writeValueAsString(capabilities),docsJson=mapper.writeValueAsString(documentIds);
        String json=mvc.perform(post("/api/v1/admin/integration-clients").with(jwt().jwt(t->t.subject(creator.getId())).authorities(new SimpleGrantedAuthority("ROLE_ADMIN")))
                .contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"workflow\",\"capabilities\":"+capJson+",\"documentIds\":"+docsJson+"}"))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        return mapper.readTree(json).path("credential").asText();
    }
    private RagDocument saveReadyDocument(User owner,String filename){RagDocument document=new RagDocument(owner,filename,"test/"+UUID.randomUUID(),"text/plain",100,1000,100);document.markReady(1);return documents.save(document);}
}
