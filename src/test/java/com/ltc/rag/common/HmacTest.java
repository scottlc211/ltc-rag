package com.ltc.rag.common;

import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

class HmacTest {

    @Test
    void 相同输入必须得到相同签名() {
        String signature = Hmac.hex("secret", "1699999999.{\"taskId\":\"abc\"}");
        assertThat(signature).hasSize(64).isEqualTo(Hmac.hex("secret", "1699999999.{\"taskId\":\"abc\"}"));
    }

    @Test
    void 换密钥或换正文都会改变签名() {
        String base = Hmac.hex("secret-a", "payload");
        assertThat(Hmac.hex("secret-b", "payload")).isNotEqualTo(base);
        assertThat(Hmac.hex("secret-a", "payload2")).isNotEqualTo(base);
    }

    @Test
    void 时间戳参与签名可以防重放() {
        long now = Instant.now().getEpochSecond();
        String body = "{\"answer\":\"ok\"}";
        assertThat(Hmac.hex("s", now + "." + body))
                .isNotEqualTo(Hmac.hex("s", (now + 1) + "." + body));
    }
}
