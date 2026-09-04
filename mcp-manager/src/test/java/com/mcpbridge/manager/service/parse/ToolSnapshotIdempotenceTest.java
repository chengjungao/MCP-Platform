package com.mcpbridge.manager.service.parse;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 幂等判定（EXE-03 的前提）：决定 Executor 是否可以对一次失败的上游调用自动重试。
 *
 * <p>把 POST 判成幂等会导致重复下单，把 GET 判成非幂等会导致无谓的失败率上升，
 * 两个方向的代价都很实在，因此单独钉一个测试。
 */
class ToolSnapshotIdempotenceTest {

    @Test
    @DisplayName("GET/HEAD/OPTIONS/PUT/DELETE 视为幂等")
    void idempotentMethods() {
        assertThat(ToolSnapshotIdempotence.of("GET")).isTrue();
        assertThat(ToolSnapshotIdempotence.of("HEAD")).isTrue();
        assertThat(ToolSnapshotIdempotence.of("OPTIONS")).isTrue();
        assertThat(ToolSnapshotIdempotence.of("PUT")).isTrue();
        assertThat(ToolSnapshotIdempotence.of("DELETE")).isTrue();
    }

    @Test
    @DisplayName("POST/PATCH 不幂等：重发可能创建两笔业务数据")
    void nonIdempotentMethods() {
        assertThat(ToolSnapshotIdempotence.of("POST")).isFalse();
        assertThat(ToolSnapshotIdempotence.of("PATCH")).isFalse();
    }

    @Test
    @DisplayName("大小写不敏感，且 null/未知方法一律按不幂等处理")
    void caseInsensitiveAndFailSafe() {
        assertThat(ToolSnapshotIdempotence.of("get")).isTrue();
        assertThat(ToolSnapshotIdempotence.of("Get")).isTrue();
        assertThat(ToolSnapshotIdempotence.of(null)).isFalse();
        assertThat(ToolSnapshotIdempotence.of("")).isFalse();
        assertThat(ToolSnapshotIdempotence.of("TRACE")).isFalse();
    }
}