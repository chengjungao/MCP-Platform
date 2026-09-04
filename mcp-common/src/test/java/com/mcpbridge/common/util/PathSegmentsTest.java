package com.mcpbridge.common.util;

import com.mcpbridge.common.error.ErrorCode;
import com.mcpbridge.common.error.PlatformException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * BR-3 PATH 末段规则的单元测试。
 */
class PathSegmentsTest {

    @ParameterizedTest
    @ValueSource(strings = {"crm-order", "a", "order_v2", "abc123", "a-b_c-1"})
    @DisplayName("合法末段：小写字母数字开头结尾，中间允许 - 与 _")
    void validSegments(String segment) {
        assertThat(PathSegments.isValid(segment)).isTrue();
        assertThat(PathSegments.requireValid(segment)).isEqualTo(segment);
    }

    @ParameterizedTest
    @ValueSource(strings = {"-lead", "lead-", "has space", "a.b", "中文", ""})
    @DisplayName("非法末段被拒绍并给出结构化原因")
    void invalidSegments(String segment) {
        assertThat(PathSegments.isValid(segment)).isFalse();
        assertThatThrownBy(() -> PathSegments.requireValid(segment))
                .isInstanceOf(PlatformException.class)
                .extracting(e -> ((PlatformException) e).errorCode())
                .isEqualTo(ErrorCode.VALIDATION_FAILED);
    }

    @Test
    @DisplayName("超过 64 字符被拒绝")
    void tooLong() {
        String segment = "a".repeat(65);
        assertThatThrownBy(() -> PathSegments.requireValid(segment))
                .isInstanceOf(PlatformException.class)
                .hasMessageContaining("64");
    }

    @Test
    @DisplayName("normalize 容错：去空白 + 转小写（大写输入本身不合规则，但规范化后可接受）")
    void normalizeIsLenient() {
        assertThat(PathSegments.normalize("  CRM-Order ")).isEqualTo("crm-order");
        assertThat(PathSegments.requireValid("  CRM-Order ")).isEqualTo("crm-order");
        assertThat(PathSegments.isValid("CRM")).isFalse();
        assertThat(PathSegments.requireValid("CRM")).isEqualTo("crm");
    }

    @Test
    @DisplayName("端点拼接：{集群入口}/{保留前缀}/{末段}，且末段非法时拒绝")
    void endpointComposition() {
        assertThat(PathSegments.endpoint("https://mcp.example.com/", "/mcp/", "crm-order"))
                .isEqualTo("https://mcp.example.com/mcp/crm-order");
        assertThat(PathSegments.endpoint("https://mcp.example.com", null, "crm-order"))
                .isEqualTo("https://mcp.example.com/mcp/crm-order");
        assertThatThrownBy(() -> PathSegments.endpoint("https://mcp.example.com", "mcp", "Bad Segment"))
                .isInstanceOf(PlatformException.class);
    }
}
