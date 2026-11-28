package com.blueai.product;

import com.blueai.rag.chat.service.ReplyLanguagePolicy;
import java.math.BigDecimal;
import java.util.*;

/** Deterministic VI/EN/ZH prose; source names and numeric facts never pass through a model. */
final class ProductReplyRenderer {
    private ProductReplyRenderer() { }
    private static String tr(String language, String vi, String en, String zh) {
        return switch (language) { case "en" -> en; case "zh" -> zh; default -> vi; };
    }
    static Optional<String> reply(ProductAdvisor.Grounding grounding, ReplyLanguagePolicy.State state) {
        String code = state == null ? "vi" : state.code().toLowerCase(Locale.ROOT);
        String language = code.startsWith("zh") ? "zh" : code.startsWith("en") ? "en" : "vi";
        return switch (grounding.status()) {
            case NONE -> Optional.empty();
            case UNAVAILABLE -> Optional.of(tr(language,
                    "Hiện mình chưa thể xác minh danh mục sản phẩm và giá hiện tại. Điều này không có nghĩa là không có gói cước. Vui lòng thử lại sau.",
                    "I cannot currently verify the product catalogue or current prices. This does not mean there are no plans. Please try again later.",
                    "目前无法核实产品目录及当前价格。这并不代表没有套餐，请稍后重试。"));
            case STALE -> Optional.of(tr(language,
                    "Bản danh mục hiện có đã quá hạn cập nhật nên mình chưa thể xác nhận gói và giá hiện tại. Vui lòng thử lại sau khi danh mục được cập nhật.",
                    "The cached catalogue is out of date, so I cannot confirm current plans or prices. Please try again after it is refreshed.",
                    "缓存目录已过期，暂时无法确认当前套餐或价格。请在目录更新后重试。"));
            case NEEDS_DETAILS -> Optional.of(details(language, grounding.details()));
            case NO_MATCHES -> Optional.of(tr(language,
                    "Mình chưa xác minh được gói đáp ứng đầy đủ yêu cầu trong bản danh mục hiện tại. Bạn có muốn điều chỉnh quốc gia, số ngày hoặc ngân sách không?",
                    "I could not verify a plan meeting all your constraints in the current catalogue. Would you like to adjust the destinations, number of days or budget?",
                    "当前目录中暂未核实到满足全部条件的套餐。是否需要调整目的地、旅行天数或预算？"));
            case MATCHES -> Optional.of(matches(language, grounding.facts()));
        };
    }
    private static String details(String language, Set<ProductAdvisor.Detail> details) {
        List<String> prompts = new ArrayList<>();
        if (details.contains(ProductAdvisor.Detail.COUNTRY)) prompts.add(tr(language,
                "Bạn sẽ đến quốc gia nào?", "Which countries will you visit?", "您将前往哪些国家或地区？"));
        if (details.contains(ProductAdvisor.Detail.CALENDAR_MONTH)) prompts.add(tr(language,
                "Một tháng có thể là 30 hoặc 31 ngày; vui lòng cho biết ngày đi, ngày về hoặc số ngày chính xác để kiểm tra thời hạn gói.",
                "A month may mean 30 or 31 days; please provide your travel dates or exact number of days so I can check plan validity.",
                "一个月可能是30天或31天；请提供出发和返回日期，或准确的旅行天数，以便核实套餐有效期。"));
        else if (details.contains(ProductAdvisor.Detail.DAYS) || details.contains(ProductAdvisor.Detail.MULTIPLE_DURATIONS))
            prompts.add(tr(language, "Vui lòng cho biết số ngày chính xác hoặc ngày đi và ngày về.",
                    "Please provide the exact number of days or your travel dates.", "请提供准确的旅行天数，或出发和返回日期。"));
        if (details.contains(ProductAdvisor.Detail.BUDGET_CURRENCY)) prompts.add(tr(language,
                "Vui lòng ghi rõ số tiền và mã tiền tệ của ngân sách, ví dụ VND hoặc USD; mình không tự quy đổi tiền tệ.",
                "Please specify the budget amount and currency code, such as VND or USD; I do not assume an exchange rate.",
                "请注明预算金额及货币代码，例如VND或USD；我不会自行换算货币。"));
        return String.join(" ", prompts);
    }
    private static String matches(String language, List<ProductFacts> facts) {
        StringBuilder text = new StringBuilder(tr(language,
                "Các gói trong bản danh mục đã đồng bộ (tối đa 6 gói):",
                "Plans in the synchronized catalogue (up to 6):", "已同步目录中的套餐（最多6个）："));
        for (ProductFacts fact : facts) {
            text.append("\n- ").append(fact.sku()).append(" — ").append(fact.name());
            text.append("; ").append(tr(language, "phủ sóng: ", "coverage: ", "覆盖："));
            text.append(fact.countryCodes().isEmpty() ? tr(language, "chưa xác định", "not verified", "尚未核实") : String.join(", ", fact.countryCodes()));
            text.append("; ").append(tr(language, "thời hạn: ", "validity: ", "有效期："));
            text.append(fact.validityDays() == null ? tr(language, "chưa xác định", "not verified", "尚未核实")
                    : fact.validityDays() + tr(language, " ngày", " days", "天"));
            text.append("; ").append(tr(language, "dữ liệu: ", "data: ", "流量："));
            ProductFacts.DataAllowance data = fact.data();
            if (data == null) text.append(tr(language, "chưa xác minh dung lượng và đơn vị", "amount and unit not verified", "流量及单位尚未核实"));
            else {
                if (data.unlimited()) text.append(tr(language,"không giới hạn","unlimited","不限量"));
                else text.append(number(data.amount())).append(' ').append(data.unit());
                text.append(switch (data.period()) {
                    case TOTAL -> tr(language, " tổng cộng", " total", "（总流量）");
                    case DAILY -> tr(language, "/ngày", "/day", "/天");
                    case UNKNOWN -> tr(language, " (chưa xác định tổng hay mỗi ngày)", " (total/daily basis not verified)", "（总量或每日用量尚未核实）");
                });
            }
            text.append("; ").append(tr(language, "giá bán lẻ đề xuất: ", "suggested retail price: ", "建议零售价："));
            if (fact.sellingPrice() == null)
                text.append(tr(language, "chưa xác minh giá và tiền tệ", "amount and currency not verified", "金额及货币尚未核实"));
            else if (fact.currencyCode() == null)
                text.append(number(fact.sellingPrice())).append(tr(language," (chưa xác minh tiền tệ)"," (currency not verified)","（货币尚未核实）"));
            else text.append(number(fact.sellingPrice())).append(' ').append(fact.currencyCode());
            if (fact.conditions() != null) {
                if (!fact.conditions().supportedNetworks().isEmpty())
                    text.append("; ").append(tr(language,"công nghệ mạng hỗ trợ: ","supported network technology: ","支持网络制式："))
                            .append(String.join("/",fact.conditions().supportedNetworks()));
                if (Boolean.FALSE.equals(fact.conditions().topUpSupported()))
                    text.append("; ").append(tr(language,"không hỗ trợ nạp thêm","top-up not supported","不支持充值"));
            }
        }
        boolean sandbox = facts.stream().anyMatch(fact -> "SANDBOX".equals(fact.provenance().environment()));
        if (sandbox) text.append('\n').append(tr(language,
                "Đây là dữ liệu SANDBOX, không phải báo giá production đã xác minh.",
                "This is SANDBOX data, not a verified production quote.", "以上为SANDBOX测试数据，并非已核实的正式报价。"));
        text.append('\n').append(tr(language,
                "Thông tin phản ánh lần đồng bộ gần nhất, không phải kiểm tra trực tiếp tại thời điểm trò chuyện.",
                "Information reflects the last synchronization, rather than a live check during this chat.",
                "信息来自最近一次同步，并非本次对话时实时查询。"));
        return text.toString();
    }
    private static String number(BigDecimal value) { return value.stripTrailingZeros().toPlainString(); }
}
