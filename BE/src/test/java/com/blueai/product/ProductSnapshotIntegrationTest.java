package com.blueai.product;

import com.blueai.BlueAiAuthApplication;
import com.blueai.auth.exception.ApiException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.annotation.Transactional;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

@SpringBootTest(classes=BlueAiAuthApplication.class,properties={"app.products.api-key=synthetic-key","app.products.auto-sync-enabled=false"})
@ActiveProfiles("test")
@Transactional
class ProductSnapshotIntegrationTest {
    @Autowired ProductCatalogService catalog;
    @Autowired ProductRepository products;
    @Autowired ProductProperties properties;
    @Autowired ObjectMapper mapper;
    @MockitoBean BluecomProductClient client;
    @BeforeEach void setup() {
        properties.setBaseUrl("https://api.sandbox.bluesim.com.vn/api/open/v1");
        properties.setSourceEnvironment("AUTO"); properties.setApiKey("synthetic-key");
        properties.setProductionApproved(false); properties.setMaxAge(Duration.ofMinutes(30));
        properties.setSellingPriceCurrencyConfirmed(true);
        products.deleteAll();
    }
    @AfterEach void resetProperties() {
        properties.setBaseUrl("https://api.sandbox.bluesim.com.vn/api/open/v1"); properties.setSourceEnvironment("AUTO");
        properties.setApiKey("synthetic-key"); properties.setProductionApproved(false); properties.setMaxAge(Duration.ofMinutes(30));
        properties.setSellingPriceCurrencyConfirmed(false);
    }
    private ObjectNode plan(String sku,String countries,int days,String price,String currency) {
        var item = mapper.createObjectNode().put("sku",sku).put("name","Synthetic "+sku)
                .put("sellingPrice",new BigDecimal(price)).put("currencyCode",currency).put("price",12345);
        item.putObject("extraData").put("location",countries).put("duration",days).put("volume","1024");
        return item;
    }
    private void sync(JsonNode... incoming) { when(client.fetchAll()).thenReturn(List.of(incoming)); catalog.sync(); }
    private ProductCatalogService.AdviceSnapshot lookup(Set<String> countries,Integer days) {
        return catalog.adviceSnapshot(countries,days,null,null);
    }
    @Test void environmentSwitchCannotRelabelOrReuseTheStoredSandboxRows() {
        sync(plan("JP","JP",31,"19.95","USD"));
        var before = lookup(Set.of("JP"),31);
        assertThat(before.facts().getFirst().provenance().environment()).isEqualTo("SANDBOX");
        properties.setBaseUrl("https://production.example.invalid/api/open/v1");
        properties.setSourceEnvironment("PRODUCTION"); properties.setProductionApproved(true);
        assertThat(lookup(Set.of("JP"),31).availability()).isEqualTo(ProductCatalogService.Availability.UNAVAILABLE);
        assertThat(catalog.summary().available()).isZero();
        assertThat(catalog.summary().source()).isNull();
        assertThat(before.facts().getFirst().provenance().environment()).isEqualTo("SANDBOX");
        assertThat(before.facts().getFirst().provenance().sourceBaseUrl()).contains("sandbox");
    }
    @Test void unconfiguredStaleAndUnapprovedProductionAreNotCurrentAdvice() {
        sync(plan("JP","JP",31,"19.95","USD"));
        properties.setApiKey("");
        assertThat(lookup(Set.of("JP"),31).availability()).isEqualTo(ProductCatalogService.Availability.UNAVAILABLE);
        properties.setApiKey("synthetic-key");
        Product product = products.findById("JP").orElseThrow();
        ReflectionTestUtils.setField(product,"syncedAt",Instant.now().minus(Duration.ofHours(2)));
        products.saveAndFlush(product);
        assertThat(lookup(Set.of("JP"),31).availability()).isEqualTo(ProductCatalogService.Availability.STALE);
        properties.setBaseUrl("https://production.example.invalid"); properties.setSourceEnvironment("PRODUCTION");
        assertThat(lookup(Set.of("JP"),31).availability()).isEqualTo(ProductCatalogService.Availability.UNAVAILABLE);
    }
    @Test void allCoverageAndValidityConstraintsMustHoldAndCurrencyComparisonsStayInOneCurrency() {
        sync(plan("JP-30","JP",30,"10","USD"),plan("JP-SG-31","JP,SG",31,"15","USD"),plan("JP-SG-VND","JP,SG",31,"100","VND"));
        assertThat(lookup(Set.of("JP","SG"),31).facts()).extracting(ProductFacts::sku).containsExactly("JP-SG-31","JP-SG-VND");
        var usd = catalog.adviceSnapshot(Set.of("JP","SG"),31,new ProductCatalogService.Budget(new BigDecimal("20"),"USD"),null);
        assertThat(usd.facts()).extracting(ProductFacts::sku).containsExactly("JP-SG-31");
        assertThat(lookup(Set.of("JP","SG"),32).facts()).isEmpty();
    }
    @Test void documentedMbDailyTotalAndUnlimitedValuesRetainTheirDistinctMeaning() {
        var unknown = plan("UNKNOWN","JP",31,"0","USD");
        ((ObjectNode)unknown.get("extraData")).put("volume","unexpected");
        var daily = plan("DAILY","JP",31,"1.25","USD");
        ((ObjectNode)daily.get("extraData")).put("volume","1024.00").put("dataType",2).put("speed","5G/4G").put("supportTopUpType",1);
        var unlimited = plan("UNLIMITED","JP",31,"2","USD");
        ((ObjectNode)unlimited.get("extraData")).put("volume","unlimited").put("dataType",1);
        sync(unknown,daily,unlimited);
        var facts = lookup(Set.of("JP"),31).facts();
        assertThat(facts.stream().filter(item->item.sku().equals("UNKNOWN")).findFirst().orElseThrow().data()).isNull();
        var data = facts.stream().filter(item->item.sku().equals("DAILY")).findFirst().orElseThrow().data();
        assertThat(data.amount()).isEqualByComparingTo("1024.00");
        assertThat(data.unit()).isEqualTo("MB");
        assertThat(data.period()).isEqualTo(ProductFacts.DataAllowance.Period.DAILY);
        var unlimitedData = facts.stream().filter(item->item.sku().equals("UNLIMITED")).findFirst().orElseThrow().data();
        assertThat(unlimitedData.unlimited()).isTrue();
        assertThat(unlimitedData.amount()).isNull();
        assertThat(unlimitedData.period()).isEqualTo(ProductFacts.DataAllowance.Period.TOTAL);
        var conditions = facts.stream().filter(item->item.sku().equals("DAILY")).findFirst().orElseThrow().conditions();
        assertThat(conditions.supportedNetworks()).containsExactly("4G","5G");
        assertThat(conditions.topUpSupported()).isFalse();
    }
    @Test void retailCurrencyIsNotAssumedFromAFieldDocumentedOnlyForInputCost() {
        sync(plan("RETAIL","JP",31,"19.95","USD"));
        properties.setSellingPriceCurrencyConfirmed(false);
        var fact = lookup(Set.of("JP"),31).facts().getFirst();
        assertThat(fact.sellingPrice()).isEqualByComparingTo("19.95");
        assertThat(fact.currencyCode()).isNull();
        assertThat(catalog.detail("RETAIL",false).path("currencyCode").isMissingNode()).isTrue();
        assertThat(catalog.adviceSnapshot(Set.of("JP"),31,new ProductCatalogService.Budget(new BigDecimal("20"),"USD"),null).availability())
                .isEqualTo(ProductCatalogService.Availability.UNAVAILABLE);
    }
    @Test void malformedDuplicateAndCompleteEmptySyncsRetainTheLastGoodSnapshot() {
        sync(plan("GOOD","JP",31,"19.95","USD"));
        String snapshot = products.findById("GOOD").orElseThrow().getSnapshotId();
        for (List<JsonNode> invalid : List.of(List.<JsonNode>of(),List.<JsonNode>of(plan("DUP","JP",31,"1","USD"),plan("dup","JP",31,"2","USD")))) {
            when(client.fetchAll()).thenReturn(invalid);
            assertThatThrownBy(catalog::sync).isInstanceOf(ApiException.class);
            assertThat(lookup(Set.of("JP"),31).facts()).extracting(ProductFacts::sku).containsExactly("GOOD");
            assertThat(products.findById("GOOD").orElseThrow().getSnapshotId()).isEqualTo(snapshot);
        }
    }
    @Test void removedSkusAreWithdrawnBySnapshotIdAndLegacyRowsHaveNoAuthority() {
        sync(plan("OLD","JP",31,"1","USD"),plan("KEPT","JP",31,"2","USD"));
        sync(plan("KEPT","JP",31,"3","USD"));
        assertThat(lookup(Set.of("JP"),31).facts()).extracting(ProductFacts::sku).containsExactly("KEPT");
        Product kept = products.findById("KEPT").orElseThrow();
        kept.setProvenance(null,null,null); products.saveAndFlush(kept);
        assertThat(lookup(Set.of("JP"),31).availability()).isEqualTo(ProductCatalogService.Availability.UNAVAILABLE);
    }
    @Test void publicDetailAndEvidenceContainNoNestedWholesaleFields() {
        var item = plan("SAFE","JP",31,"19.95","USD");
        ((ObjectNode)item.get("extraData")).put("costPrice",12345).putObject("refundPolicy").put("price",12345);
        sync(item);
        assertThat(catalog.detail("SAFE",false).toString()).doesNotContain("12345","costPrice","refundPolicy");
        var advisor = new ProductAdvisor(catalog,mapper);
        assertThat(advisor.grounding("Recommend eSIM Japan 31 days",List.of()).context()).doesNotContain("12345","costPrice","refundPolicy");
        assertThat(catalog.detail("SAFE",true).path("price").asInt()).isEqualTo(12345);
    }
}
