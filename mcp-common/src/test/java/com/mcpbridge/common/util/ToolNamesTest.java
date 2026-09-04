package com.mcpbridge.common.util;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * BR-1 Tool 命名规则的单元测试（PRD 示例：{@code GET /users/{id}} → {@code get_users_id}）。
 */
class ToolNamesTest {

    @Test
    @DisplayName("operationId 存在时优先使用，并保留驼峰可读性")
    void prefersOperationId() {
        assertThat(ToolNames.derive("getUserById", "GET", "/users/{id}")).isEqualTo("getUserById");
        assertThat(ToolNames.derive("list orders", "GET", "/orders")).isEqualTo("list_orders");
    }

    @Test
    @DisplayName("operationId 缺省时按 HTTP方法_路径段 生成，路径参数拼入以保证唯一")
    void fallsBackToMethodAndPath() {
        assertThat(ToolNames.derive(null, "GET", "/users/{id}")).isEqualTo("get_users_id");
        assertThat(ToolNames.derive("  ", "POST", "/orders/{orderId}/items")).isEqualTo("post_orders_orderid_items");
        assertThat(ToolNames.derive(null, "DELETE", "/users/{id}")).isEqualTo("delete_users_id");
    }

    @Test
    @DisplayName("生成的名字始终满足 MCP tool 名字符约束")
    void alwaysValid() {
        assertThat(ToolNames.isValid(ToolNames.derive(null, "GET", "/a/b-c/d.e/{x}"))).isTrue();
        assertThat(ToolNames.isValid(ToolNames.derive("weird::operationId!!", "GET", "/x"))).isTrue();
    }

    @Test
    @DisplayName("超长名字被截断到 64 字符以内")
    void truncates() {
        String longPath = "/very/long/" + "segment/".repeat(20) + "{id}";
        String name = ToolNames.derive(null, "GET", longPath);
        assertThat(name).hasSizeLessThanOrEqualTo(64);
        assertThat(ToolNames.isValid(name)).isTrue();
    }

    @Test
    @DisplayName("同一 Server 内重名时追加 _2/_3 保证唯一")
    void uniqueOnCollision() {
        Set<String> used = new HashSet<>();
        String first = ToolNames.unique("get_users", used);
        used.add(first);
        String second = ToolNames.unique("get_users", used);
        used.add(second);
        String third = ToolNames.unique("get_users", used);

        assertThat(first).isEqualTo("get_users");
        assertThat(second).isEqualTo("get_users_2");
        assertThat(third).isEqualTo("get_users_3");
    }

    @Test
    @DisplayName("锚点格式固定为 METHOD + 空格 + path，供 overlay 定位与漂移检测")
    void anchorFormat() {
        assertThat(ToolNames.anchor("get", "/users/{id}")).isEqualTo("GET /users/{id}");
    }
}
