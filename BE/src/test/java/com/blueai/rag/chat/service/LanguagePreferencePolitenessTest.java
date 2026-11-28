package com.blueai.rag.chat.service;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.assertj.core.api.Assertions.assertThat;

class LanguagePreferencePolitenessTest {
    @ParameterizedTest
    @CsvSource(delimiter='|',value={
        "Vui lòng trả lời bằng tiếng Việt. Tư vấn eSIM Nhật Bản 31 ngày|vi",
        "Làm ơn trả lời bằng tiếng Việt|vi",
        "Xin hãy trả lời bằng tiếng Việt|vi",
        "Vui lòng trả lời bằng tiếng Anh|en",
        "Xin vui lòng giải thích bằng tiếng Trung|zh-CN",
        "Please answer in English.|en"
    })
    void directPoliteRequestOverridesAnEarlierExplicitPreference(String question,String expected) {
        var before=new ReplyLanguagePolicy.State(expected.equals("en")?"zh-CN":"en",true);
        var after=ReplyLanguagePolicy.resolve(question,before,List.of());
        assertThat(after.code()).isEqualTo(expected);
        assertThat(after.explicit()).isTrue();
    }
    @Test void quotedPoliteRequestRemainsDataRatherThanChangingPreference() {
        var before=new ReplyLanguagePolicy.State("en",true);
        assertThat(ReplyLanguagePolicy.resolve("Translate this quote: \"Vui lòng trả lời bằng tiếng Việt\"",before,List.of()))
                .isEqualTo(before);
    }
}
