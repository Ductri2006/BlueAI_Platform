package com.blueai.product;

import com.blueai.BlueAiAuthApplication;
import com.blueai.auth.exception.ApiException;
import com.blueai.auth.model.*;
import com.blueai.auth.repository.UserRepository;
import com.blueai.rag.chat.service.*;
import com.blueai.studio.service.BotSnapshot;
import com.blueai.studio.dto.BotStudioDtos.CustomizationInput;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.*;
import org.springframework.test.context.*;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.client.RestClient;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.*;

@SpringBootTest(classes=BlueAiAuthApplication.class)
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class ProductCatalogIntegrationTest {
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper mapper;
    @Autowired ProductCatalogService catalog;
    @Autowired ProductRepository products;
    @Autowired ProductAdvisor advisor;
    @Autowired UserRepository users;
    @Autowired RagChatService chat;
    @MockitoBean BluecomProductClient upstream;
    @MockitoBean RagRestClientFactory restFactory;
    MockRestServiceServer llm;
    @DynamicPropertySource static void config(DynamicPropertyRegistry registry) {
        registry.add("app.products.api-key",()->"test-partner-key");
        registry.add("app.products.public-advice-enabled",()->"true");
        registry.add("app.products.selling-price-currency-confirmed",()->"true");
        registry.add("app.rag.llm-base-url",()->"http://product-llm.test");
        registry.add("app.rag.llm-api-key",()->"test-llm-key");
        registry.add("app.rag.llm-model",()->"test-model");
    }
    @BeforeEach void setup() {
        products.deleteAll();
        RestClient.Builder builder = RestClient.builder();
        llm = MockRestServiceServer.bindTo(builder).build();
        when(restFactory.create()).thenAnswer(call->builder.clone());
    }
    private JsonNode plan(String sku,String country,int days,int price) {
        ObjectNode node = mapper.createObjectNode().put("sku",sku).put("name",country+" "+days+" days")
                .put("regional",country).put("description","Data only. No calls or SMS.").put("price",12345)
                .put("sellingPrice",price).put("currencyCode","VND");
        ObjectNode extra = node.putObject("extraData").put("duration",days).put("volume","1024").put("location",country).put("speed","4G/5G");
        extra.putObject("operator").putArray("coverages").addObject().put("locationCode",country).put("locationName",country);
        return node;
    }
    private void sync(JsonNode... plans) { doReturn(List.of(plans)).when(upstream).fetchAll();catalog.sync(); }
    private String token(boolean admin) throws Exception {
        String email = UUID.randomUUID()+"@example.com";
        String body = "{\"email\":\""+email+"\",\"password\":\"StrongPassword123!\",\"fullName\":\"Product Tester\"}";
        mvc.perform(post("/api/v1/auth/register").contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isCreated());
        if(admin) { User user=users.findByEmail(email).orElseThrow();user.changeRole(UserRole.ADMIN);users.saveAndFlush(user); }
        return mapper.readTree(mvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString()).path("accessToken").asText();
    }
    @Test void onlyAdminCanReadLiveSandboxAndPaginationIsValidated() throws Exception {
        var response = mapper.createObjectNode().put("count",1).put("page",1).put("size",200).put("totalPages",1);
        response.putArray("packageList").add(plan("BLU-ESIM-JP-7D","JP",7,150000));
        when(upstream.fetchPage(1,200)).thenReturn(response);
        mvc.perform(get("/api/v1/products/sandbox")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/v1/products/sandbox").header("Authorization","Bearer "+token(false)))
                .andExpect(status().isForbidden());
        verify(upstream,never()).fetchPage(anyInt(),anyInt());
        String admin = token(true);
        mvc.perform(get("/api/v1/products/sandbox").header("Authorization","Bearer "+admin))
                .andExpect(status().isOk()).andExpect(jsonPath("$.count").value(1))
                .andExpect(jsonPath("$.packageList[0].sku").value("BLU-ESIM-JP-7D"));
        mvc.perform(get("/api/v1/products/sandbox?page=0").header("Authorization","Bearer "+admin))
                .andExpect(status().isBadRequest());
        mvc.perform(get("/api/v1/products/sandbox?size=201").header("Authorization","Bearer "+admin))
                .andExpect(status().isBadRequest());
        verify(upstream,times(1)).fetchPage(1,200);
    }
    @Test void authenticatedUsersCanBrowseButOnlyAdminCanSyncAndSeeCostPrice() throws Exception {
        sync(plan("BLU-ESIM-JP-7D","JP",7,150000));
        mvc.perform(get("/api/v1/products")).andExpect(status().isUnauthorized());
        String user=token(false), admin=token(true);
        mvc.perform(get("/api/v1/products").header("Authorization","Bearer "+user))
                .andExpect(status().isOk()).andExpect(jsonPath("$.total").value(1))
                .andExpect(jsonPath("$.items[0].sellingPrice").value(150000)).andExpect(jsonPath("$.items[0].price").doesNotExist());
        mvc.perform(get("/api/v1/products/BLU-ESIM-JP-7D").header("Authorization","Bearer "+user))
                .andExpect(status().isOk()).andExpect(jsonPath("$.price").doesNotExist());
        mvc.perform(post("/api/v1/products/sync").header("Authorization","Bearer "+user)).andExpect(status().isForbidden());
        mvc.perform(get("/api/v1/products").header("Authorization","Bearer "+admin))
                .andExpect(status().isOk()).andExpect(jsonPath("$.items[0].price").value(12345));
        mvc.perform(post("/api/v1/products/sync").header("Authorization","Bearer "+admin))
                .andExpect(status().isOk()).andExpect(jsonPath("$.available").value(1));
    }
    @Test void filtersAndSyncPreserveCatalogueOnFailureAndDeactivateRemovedSkus() throws Exception {
        sync(plan("BLU-ESIM-JP-7D","JP",7,150000),plan("BLU-ESIM-SG-1D","SG",1,30000));
        assertThat(catalog.search("","JP",7,new java.math.BigDecimal(200000),0,20,false).items()).hasSize(1);
        assertThat(catalog.search("","JP",10,null,0,20,false).items()).isEmpty();
        when(upstream.fetchAll()).thenThrow(new ApiException(HttpStatus.BAD_GATEWAY,"PRODUCT_SYNC_FAILED","Upstream failed"));
        assertThatThrownBy(catalog::sync).isInstanceOf(ApiException.class);
        assertThat(catalog.summary().available()).isEqualTo(2);
        sync(plan("BLU-ESIM-JP-7D","JP",7,160000));
        assertThat(catalog.summary().available()).isEqualTo(1);
        assertThat(catalog.detail("BLU-ESIM-JP-7D",false).path("sellingPrice").asInt()).isEqualTo(160000);
        assertThatThrownBy(()->catalog.detail("BLU-ESIM-SG-1D",false)).isInstanceOf(ApiException.class);
    }
    @Test void adviceUsesDestinationDurationBudgetAndNeverWholesale() {
        assertThat(advisor.grounding("eSIM Japan 7 days", List.of()).status()).isEqualTo(ProductAdvisor.Status.UNAVAILABLE);
        sync(plan("BLU-ESIM-JP-7D","JP",7,150000),plan("BLU-ESIM-JP-1D","JP",1,30000),plan("BLU-ESIM-JP-30D","JP",30,300000),plan("BLU-ESIM-SG-7D","SG",7,50000));
        String result=advisor.context("Tư vấn eSIM Nhật Bản 7 ngày dưới 200000 VND",List.of());
        assertThat(result).contains("BLU-ESIM-JP-7D").doesNotContain("12345","costPrice","BLU-ESIM-JP-1D","BLU-ESIM-SG-7D","BLU-ESIM-JP-30D");
        assertThat(advisor.grounding("Tư vấn eSIM Nhật Bản 7 ngày dưới 200000 VND",List.of()).facts().getFirst().sellingPrice())
                .isEqualByComparingTo("150000");
        assertThat(advisor.context("hello how are you",List.of())).isEmpty();
        assertThat(advisor.context("AI tư vấn eSIM Nhật Bản 7 ngày 3 GB",List.of())).contains("BLU-ESIM-JP-7D");
        assertThat(advisor.context("Có gói nào rẻ nhất không?",List.of(new LlmChatMessage("user","eSIM Singapore 7 ngày"))))
                .contains("BLU-ESIM-SG-7D").doesNotContain("BLU-ESIM-JP-7D");
    }
    @Test void internalChatCanUseProductsWithoutTheDocumentRetriever() throws Exception {
        sync(plan("BLU-ESIM-JP-7D","JP",7,150000));
        String access=token(false);
        String conversation=mapper.readTree(mvc.perform(post("/api/v1/conversations").header("Authorization","Bearer "+access)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"title\":\"Product advice\"}"))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString()).path("id").asText();
        mvc.perform(post("/api/v1/conversations/"+conversation+"/ask").header("Authorization","Bearer "+access)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"question\":\"eSIM Nhật Bản 7 ngày\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.sources[0].sourceType").value("PRODUCT_CATALOG"))
                .andExpect(jsonPath("$.sources[0].documentId").value("product:BLU-ESIM-JP-7D"));
        verifyNoInteractions(restFactory);
    }
    @Test void widgetWithNoDocumentsCanAdviseFromCustomerSafeCatalogue() {
        sync(plan("BLU-ESIM-JP-7D","JP",7,150000));
        User owner=users.saveAndFlush(new User(UUID.randomUUID()+"@example.com","unused","Widget owner",UserRole.ADMIN));
        BotSnapshot snapshot=mock(BotSnapshot.class);
        when(snapshot.ownerId()).thenReturn(owner.getId());when(snapshot.publicDocumentIds()).thenReturn(List.of());
        when(snapshot.customization()).thenReturn(CustomizationInput.defaults());when(snapshot.name()).thenReturn("Sales");
        assertThat(chat.answerForWidget(snapshot,"eSIM Japan 7 days",List.of())).contains("BLU-ESIM-JP-7D");
        verifyNoInteractions(restFactory);
    }
    private void expectProductCompletion() {
        llm.expect(requestTo("http://product-llm.test/v1/chat/completions"))
                .andExpect(org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath("$.messages[0].content").value(org.hamcrest.Matchers.allOf(
                        org.hamcrest.Matchers.containsString("PRODUCT CATALOGUE"),org.hamcrest.Matchers.containsString("BLU-ESIM-JP-7D"),
                        org.hamcrest.Matchers.containsString("150000"),org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("12345")))))
                .andRespond(withSuccess("{\"choices\":[{\"message\":{\"role\":\"assistant\",\"content\":\"BLU-ESIM-JP-7D: giá đề xuất 150.000 VND\"}}]}",MediaType.APPLICATION_JSON));
    }
}
