package com.ltc.rag.common;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SsrfGuardTest {

    @Test
    void 拒绝环回与内网与云元数据地址() {
        assertThatThrownBy(() -> SsrfGuard.validateCallbackUrl("http://127.0.0.1:8080/hook", false))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("安全限制");

        assertThatThrownBy(() -> SsrfGuard.validateCallbackUrl("http://localhost:8080/hook", false))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("安全限制");

        assertThatThrownBy(() -> SsrfGuard.validateCallbackUrl("http://qdrant:6333/collections", false))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("安全限制");

        assertThatThrownBy(() -> SsrfGuard.validateCallbackUrl("http://169.254.169.254/latest/meta-data/", false))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("安全限制");

        assertThatThrownBy(() -> SsrfGuard.validateCallbackUrl("http://192.168.1.10/hook", false))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("安全限制");

        assertThatThrownBy(() -> SsrfGuard.validateCallbackUrl("http://10.0.0.8/hook", false))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("安全限制");
    }

    @Test
    void 拒绝非Http协议与带账号密码的URL() {
        assertThatThrownBy(() -> SsrfGuard.validateCallbackUrl("file:///etc/passwd", true))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("http");

        assertThatThrownBy(() -> SsrfGuard.validateCallbackUrl("http://admin:123456@example.com/hook", true))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("用户信息");
    }

    @Test
    void 允许公网IP且开启内网开关时允许本地调试() {
        assertThat(SsrfGuard.validateCallbackUrl("https://8.8.8.8/webhook", false))
                .isEqualTo("https://8.8.8.8/webhook");

        assertThat(SsrfGuard.validateCallbackUrl("http://127.0.0.1:9000/webhook", true))
                .isEqualTo("http://127.0.0.1:9000/webhook");

        assertThat(SsrfGuard.validateCallbackUrl("   ", false)).isNull();
    }
}
