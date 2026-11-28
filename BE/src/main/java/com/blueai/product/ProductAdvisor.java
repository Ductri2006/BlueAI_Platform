package com.blueai.product;

import com.blueai.rag.chat.dto.RagSourceResponse;
import com.blueai.rag.chat.service.LlmChatMessage;
import com.blueai.rag.chat.service.ReplyLanguagePolicy;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;
import java.math.BigDecimal;
import java.util.*;
import java.util.regex.Pattern;

/** Product intent is resolved once. All customer wording uses this immutable, customer-safe snapshot. */
@Service
public class ProductAdvisor {
    public enum Status { NONE, NEEDS_DETAILS, MATCHES, NO_MATCHES, UNAVAILABLE, STALE }
    public enum Detail { COUNTRY, DAYS, CALENDAR_MONTH, BUDGET_CURRENCY, MULTIPLE_DURATIONS, SUPPORT_FOLLOWUP }
    public record Grounding(String context, List<RagSourceResponse> sources, Status status,
                            List<ProductFacts> facts, Set<Detail> details) {
        public Grounding {
            context = context == null ? "" : context;
            sources = List.copyOf(sources);
            facts = List.copyOf(facts);
            details = Set.copyOf(details);
        }
        /** Compatibility for callers which only supply the legacy prompt/evidence pair. */
        public Grounding(String context, List<RagSourceResponse> sources) {
            this(context, sources, Status.NONE, List.of(), Set.of());
        }
        public Optional<String> deterministicReply(ReplyLanguagePolicy.State language) {
            return ProductReplyRenderer.reply(this, language);
        }
        public Set<String> factNames() {
            Set<String> names = new LinkedHashSet<>();
            facts.forEach(fact -> { names.add(fact.sku()); names.add(fact.name()); });
            return Collections.unmodifiableSet(names);
        }
    }
    private record Query(boolean intent, Set<String> countries, Integer days, ProductCatalogService.Budget budget,
                         String sku, Set<Detail> details) { }
    private record CountryMatch(int start, int end, String code) { }
    private static final Pattern SKU = Pattern.compile("(?i)(?<![a-z0-9])(?:(?:SKU\\s*[:=]?\\s*)?BLU-ESIM-[a-z0-9 ._-]{1,140}-[a-f0-9]{8}(?![a-z0-9])|(?:BLU-ESIM|ESIM|SIM|TOPUP)-[a-z0-9-]+|SKU\\s*[:=]?\\s*[a-z0-9][a-z0-9_-]{1,159})");
    private static final Pattern DAYS = Pattern.compile("(?<![\\d.\\-])(\\d{1,4})\\s*(?:ngay|days?|天|日)(?![a-z])");
    private static final Pattern MONTH = Pattern.compile("(?:\\b(?:one|a|1)\\s+(?:calendar\\s+)?month\\b|\\b(?:mot|1)\\s*thang\\b|(?:一个|一個|一|1)个?月)");
    private static final Pattern SUPPORT = Pattern.compile("\\b(?:install(?:ation)?|activate|activation|troubleshoot(?:ing)?|refund|qr|signal|error|setup)\\b|cai dat|kich hoat|khong hoat dong|khong ket noi|mat song|hoan tien|安装|安裝|激活|啟用|故障|无法|無法|没信号|沒有信號|退款|排查|设置|設定");
    private static final Pattern PRODUCT = Pattern.compile("esim|e-sim|\\b(?:sim|plans?|packages?|products?|goi|san pham)\\b|套餐|产品|產品");
    private static final Pattern SALES = Pattern.compile("\\b(?:list|recommend|offer|provide|sell|which|what|price|prices|cost|budget|under|cheaper|cheapest|duration|validity|travel|suitable|plans|packages|products)\\b|how much|how long|tu van|danh sach|goi nao|nhung goi|cac goi|bao nhieu|bao lau|thoi han|ngan sach|duoi|phu hop|gia\\b|推荐|推薦|哪些|提供|套餐|价格|價格|价钱|價錢|预算|預算|有效期|旅行|旅游|旅遊");
    private static final Pattern RECOMMEND = Pattern.compile("\\b(?:recommend|travel|suitable|cheaper|cheapest)\\b|tu van|phu hop|du lich|推荐|推薦|适合|適合|旅行|旅游|旅遊");
    private static final Pattern BUDGET_MARKER = Pattern.compile("\\b(?:under|budget|maximum|max)\\b|duoi|toi da|ngan sach|预算|預算|低于|低於|不超过|不超過");
    private static final Pattern BUDGET = Pattern.compile("(?:under|budget|maximum|max|duoi|toi da|ngan sach|预算|預算|低于|低於|不超过|不超過)\\s*(?:(?:la|is)\\s*|[:：]\\s*)?(vnd|usd|eur|gbp|cny|jpy|\\$|€|¥)?\\s*(\\d+(?:[.,]\\d+)*)(?:\\s*(k|nghin|ngan|trieu|million))?\\s*(vnd|usd|eur|gbp|cny|jpy|dong|đ|dollars?)?");
    private static final Map<String,String> COUNTRIES = countries();
    private final ProductCatalogService catalog;
    private final ObjectMapper mapper;
    public ProductAdvisor(ProductCatalogService catalog, ObjectMapper mapper) { this.catalog = catalog; this.mapper = mapper; }

    public String context(String question, List<LlmChatMessage> history) { return grounding(question, history).context(); }
    public Grounding grounding(String question, List<LlmChatMessage> history) { return resolve(question, history, false); }
    public Grounding groundingForPublic(String question, List<LlmChatMessage> history) { return resolve(question, history, true); }

    private Grounding resolve(String question, List<LlmChatMessage> history, boolean publicAccess) {
        Query query = query(question == null ? "" : question, history == null ? List.of() : history);
        if (!query.intent()) return result(Status.NONE, List.of(), Set.of());
        // Installing the optional catalogue must not disable existing approved-KB answers.
        if (!catalog.integrationConfigured() || (publicAccess && !catalog.publicAdviceEnabled()))
            return result(catalog.requiresVerifiedCatalog() ? Status.UNAVAILABLE : Status.NONE, List.of(), Set.of());
        ProductCatalogService.AdviceSnapshot snapshot;
        try { snapshot = catalog.adviceSnapshot(query.countries(), query.days(), query.budget(), query.sku()); }
        catch (DataAccessException | com.blueai.auth.exception.ApiException unavailable) {
            return result(Status.UNAVAILABLE, List.of(), Set.of());
        }
        if (snapshot.availability() == ProductCatalogService.Availability.UNAVAILABLE)
            return result(Status.UNAVAILABLE, List.of(), Set.of());
        if (snapshot.availability() == ProductCatalogService.Availability.STALE)
            return result(Status.STALE, List.of(), Set.of());
        if (query.details().stream().anyMatch(detail -> detail != Detail.SUPPORT_FOLLOWUP)) return result(Status.NEEDS_DETAILS, List.of(), query.details());
        List<ProductFacts> verified = snapshot.facts().stream().filter(ProductQuality::eligible).toList();
        return result(verified.isEmpty() ? Status.NO_MATCHES : Status.MATCHES, verified, query.details());
    }

    private Grounding result(Status status, List<ProductFacts> facts, Set<Detail> details) {
        List<ProductFacts> bounded = List.copyOf(facts.stream().filter(ProductQuality::eligible).limit(6).toList());
        if (status == Status.NONE) return new Grounding("", List.of(), status, bounded, details);
        StringBuilder context = new StringBuilder("PRODUCT CATALOGUE: ").append(status).append(".\n");
        List<RagSourceResponse> sources = new ArrayList<>();
        for (ProductFacts fact : bounded) {
            String excerpt = mapper.valueToTree(fact).toString();
            context.append(excerpt).append('\n');
            sources.add(new RagSourceResponse("product:" + fact.sku(), fact.name(), fact.sku(), null, excerpt, "PRODUCT_CATALOG", "SYNCED"));
        }
        return new Grounding(context.toString(), sources, status, bounded, details);
    }

    private Query query(String question, List<LlmChatMessage> history) {
        String current = ProductCatalogService.normalize(question);
        String prior = history.stream().filter(item -> "user".equals(item.role())).map(LlmChatMessage::content).reduce((a,b)->b).orElse("");
        String normalizedPrior = ProductCatalogService.normalize(prior);
        var skuMatch = SKU.matcher(question);
        String sku = skuMatch.find() ? skuMatch.group().replaceFirst("(?i)^SKU\\s*[:=]?\\s*", "") : null;
        boolean priorProduct = PRODUCT.matcher(normalizedPrior).find()
                && (SALES.matcher(normalizedPrior).find() || DAYS.matcher(normalizedPrior).find() || MONTH.matcher(normalizedPrior).find())
                && !SUPPORT.matcher(normalizedPrior).find();
        boolean followup = priorProduct && (DAYS.matcher(current).find() || MONTH.matcher(current).find()
                || BUDGET_MARKER.matcher(current).find() || SALES.matcher(current).find() || !countryCodes(question).isEmpty());
        boolean mixed = SUPPORT.matcher(current).find() && PRODUCT.matcher(current).find()
                && Pattern.compile("recommend|advise|purchase|buy|available|list|tu van|danh sach|muon mua|cac goi|推荐|推薦|购买|購買|哪些").matcher(current).find();
        boolean intent = (!SUPPORT.matcher(current).find() || mixed) && (sku != null
                || (PRODUCT.matcher(current).find() && (SALES.matcher(current).find() || DAYS.matcher(current).find() || MONTH.matcher(current).find()))
                || followup);
        if (!intent) return new Query(false, Set.of(), null, null, null, Set.of());
        Set<Detail> details = EnumSet.noneOf(Detail.class);
        if (mixed) details.add(Detail.SUPPORT_FOLLOWUP);
        // A country-looking token inside a SKU is an identifier, not a requested destination.
        String destinationText = sku == null ? question : question.substring(0,skuMatch.start()) + " " + question.substring(skuMatch.end());
        Set<String> countries = countryCodes(destinationText);
        if (countries.isEmpty() && priorProduct && sku == null) countries = countryCodes(prior);
        if (countries.isEmpty() && sku == null) details.add(Detail.COUNTRY);
        List<Integer> durations = durationValues(current);
        if (durations.isEmpty() && priorProduct && sku == null && !MONTH.matcher(current).find()) durations = durationValues(normalizedPrior);
        Integer days = durations.size() == 1 ? durations.getFirst() : null;
        if (durations.size() > 1) details.add(Detail.MULTIPLE_DURATIONS);
        boolean month = MONTH.matcher(current).find() || (priorProduct && sku == null && durations.isEmpty() && MONTH.matcher(normalizedPrior).find());
        if (month && days == null) details.add(Detail.CALENDAR_MONTH);
        else if (days == null && sku == null && RECOMMEND.matcher(current).find()) details.add(Detail.DAYS);
        if (days != null && (days < 1 || days > 3650)) { details.add(Detail.DAYS); days = null; }
        ProductCatalogService.Budget budget = null;
        var budgetMatch = BUDGET.matcher(current);
        if (BUDGET_MARKER.matcher(current).find()) {
            if (!budgetMatch.find()) details.add(Detail.BUDGET_CURRENCY);
            else {
                String prefix = budgetMatch.group(1), suffix = budgetMatch.group(4);
                String currency = suffix == null ? prefix : suffix;
                if ("dong".equals(currency) || "đ".equals(currency)) currency = "vnd";
                if (currency == null || !currency.matches("vnd|usd|eur|gbp|cny|jpy")
                        || (prefix != null && !prefix.equals(currency))) details.add(Detail.BUDGET_CURRENCY);
                else try {
                    String number = budgetMatch.group(2);
                    if (number.matches("\\d{1,3}(,\\d{3})+(\\.\\d{1,4})?")) number = number.replace(",", "");
                    else if (number.matches("\\d{1,3}(\\.\\d{3})+(,\\d{1,4})?")) number = number.replace(".", "").replace(',', '.');
                    else number = number.replace(',', '.');
                    BigDecimal amount = new BigDecimal(number);
                    String multiplier = budgetMatch.group(3);
                    if (multiplier != null) amount = amount.multiply(BigDecimal.valueOf(Set.of("trieu","million").contains(multiplier) ? 1_000_000 : 1000));
                    budget = new ProductCatalogService.Budget(amount, currency.toUpperCase(Locale.ROOT));
                } catch (NumberFormatException invalid) { details.add(Detail.BUDGET_CURRENCY); }
            }
        }
        return new Query(true, Set.copyOf(countries), days, budget, sku, Set.copyOf(details));
    }

    private static List<Integer> durationValues(String text) {
        List<Integer> result = new ArrayList<>();
        var matcher = DAYS.matcher(text);
        while (matcher.find()) { int days = Integer.parseInt(matcher.group(1)); if (!result.contains(days)) result.add(days); }
        if (text.contains("三十一天")) result.add(31);
        else if (text.contains("三十天")) result.add(30);
        return List.copyOf(new LinkedHashSet<>(result));
    }

    private Set<String> countryCodes(String text) {
        String normalized = ProductCatalogService.normalize(text);
        List<CountryMatch> matches = new ArrayList<>();
        COUNTRIES.forEach((name,code) -> {
            String left = name.codePointAt(0) < 128 ? "(?<![a-z])" : "";
            String right = name.codePointBefore(name.length()) < 128 ? "(?![a-z])" : "";
            var matcher = Pattern.compile(left + Pattern.quote(name) + right).matcher(normalized);
            while (matcher.find()) matches.add(new CountryMatch(matcher.start(), matcher.end(), code));
        });
        var codes = Pattern.compile("(?<![A-Za-z])[A-Z]{2}(?![A-Za-z])").matcher(text);
        Set<String> valid = Set.of(Locale.getISOCountries());
        while (codes.find()) if (!"AI".equals(codes.group()) && valid.contains(codes.group())
                && !("GB".equals(codes.group()) && text.substring(0,codes.start()).matches("(?s).*\\d\\s*$")))
            matches.add(new CountryMatch(codes.start(), codes.end(), codes.group()));
        matches.sort(Comparator.comparingInt((CountryMatch match) -> match.end() - match.start()).reversed());
        List<CountryMatch> accepted = new ArrayList<>();
        Set<String> result = new TreeSet<>();
        for (CountryMatch match : matches) if (accepted.stream().noneMatch(other -> match.start() < other.end() && other.start() < match.end())) {
            accepted.add(match); result.add(match.code());
        }
        return Collections.unmodifiableSet(result);
    }
    private static Map<String,String> countries() {
        Map<String,String> countries = new LinkedHashMap<>();
        for (String code : Locale.getISOCountries()) {
            Locale country = new Locale.Builder().setRegion(code).build();
            for (Locale language : List.of(Locale.ENGLISH, Locale.forLanguageTag("vi"), Locale.SIMPLIFIED_CHINESE, Locale.TRADITIONAL_CHINESE))
                countries.put(ProductCatalogService.normalize(country.getDisplayCountry(language)), code);
        }
        countries.putAll(Map.of("di nhat","JP","nhat ban","JP","han quoc","KR","trung quoc","CN","dai loan","TW",
                "thai lan","TH","hong kong","HK","viet nam","VN","vietnam","VN"));
        countries.put("台湾","TW"); countries.put("香港","HK"); countries.put("韩国","KR");
        return Map.copyOf(countries);
    }
}
