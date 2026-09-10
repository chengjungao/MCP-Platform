package com.mcpbridge.common.util;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Locale;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * W3C Trace Context 的转换规则（OPS-02）。
 *
 * <p>这个类的正确性标准很硬：产出的 {@code traceparent} 一旦不合法，上游规范的实现会
 * <b>整条丢弃</b>这个头，于是「平台以为传了、上游什么都没收到」——没有报错，只有断掉的链路。
 * 所以这里重点钉三件事：非法输入不产生非法输出、trace-id 跨跳稳定、span-id 每跳必换。
 */
class TraceContextTest {

    @Test
    @DisplayName("解析合法 traceparent：继承 trace-id，保留采样位")
    void parsesWellFormedHeader() {
        TraceContext parsed = TraceContext.parse(
                "00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01").orElseThrow();

        assertThat(parsed.traceId()).isEqualTo("4bf92f3577b34da6a3ce929d0e0e4736");
        assertThat(parsed.spanId()).isEqualTo("00f067aa0ba902b7");
        assertThat(parsed.sampled()).isTrue();
    }

    @Test
    @DisplayName("flags 最低位为 0 表示不采样，不能擅自改成采样")
    void respectsUnsampledFlag() {
        assertThat(TraceContext.parse("00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-00")
                .orElseThrow().sampled()).isFalse();
    }

    @Test
    @DisplayName("W3C 禁止的形态一律拒绝：全零、长度错、非十六进制、非 00 版本")
    void rejectsInvalidHeaders() {
        assertThat(TraceContext.parse(null)).isEmpty();
        assertThat(TraceContext.parse("")).isEmpty();
        assertThat(TraceContext.parse("   ")).isEmpty();
        // trace-id 全零 = 「无 trace」
        assertThat(TraceContext.parse("00-" + "0".repeat(32) + "-00f067aa0ba902b7-01")).isEmpty();
        // parent-id 全零 = 「无父 span」
        assertThat(TraceContext.parse("00-4bf92f3577b34da6a3ce929d0e0e4736-" + "0".repeat(16) + "-01")).isEmpty();
        assertThat(TraceContext.parse("00-4bf92f3577b34da6a3ce929d0e0e473-00f067aa0ba902b7-01")).isEmpty();
        assertThat(TraceContext.parse("00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b-01")).isEmpty();
        assertThat(TraceContext.parse("00-zzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzz-00f067aa0ba902b7-01")).isEmpty();
        // 只接受 00 版本：更高版本会附带额外字段，本平台既不产生也不理解
        assertThat(TraceContext.parse("01-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01")).isEmpty();
    }

    @Test
    @DisplayName("32 位十六进制 trace-id 直接沿用；大小写不敏感，统一转小写")
    void acceptsBareTraceId() {
        TraceContext context = TraceContext.ofTraceId("4BF92F3577B34DA6A3CE929D0E0E4736").orElseThrow();

        assertThat(context.traceId()).isEqualTo("4bf92f3577b34da6a3ce929d0e0e4736");
        assertThat(context.sampled()).isTrue();
    }

    @Test
    @DisplayName("非法 traceId 不猜、不截断，交给调用方另起一个 trace")
    void rejectsUnusableTraceId() {
        assertThat(TraceContext.ofTraceId(null)).isEmpty();
        // UUID 形态：长度对但含连字符，不是十六进制
        assertThat(TraceContext.ofTraceId("4bf92f35-77b3-4da6-a3ce-929d0e0e4736")).isEmpty();
        assertThat(TraceContext.ofTraceId("order-123")).isEmpty();
        assertThat(TraceContext.ofTraceId("0".repeat(32))).isEmpty();
    }

    @Test
    @DisplayName("子上下文换 span-id 但 trace-id 不变——这是同一链路能被串起来的前提")
    void childKeepsTraceIdAndRotatesSpanId() {
        TraceContext parent = TraceContext.parse(
                "00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01").orElseThrow();
        TraceContext child = parent.child();

        assertThat(child.traceId()).isEqualTo(parent.traceId());
        assertThat(child.spanId()).isNotEqualTo(parent.spanId()).hasSize(16);
        assertThat(child.sampled()).isEqualTo(parent.sampled());
    }

    @Test
    @DisplayName("生成的 id 稳定符合格式：全小写十六进制且非全零")
    void generatesWellFormedIdentifiers() {
        for (int i = 0; i < 50; i++) {
            TraceContext context = TraceContext.random();
            assertThat(context.traceId()).hasSize(32).matches("[0-9a-f]{32}");
            assertThat(context.spanId()).hasSize(16).matches("[0-9a-f]{16}");
            assertThat(context.traceId()).isNotEqualTo("0".repeat(32));
        }
    }

    @Test
    @DisplayName("header() 的输出必须能被自己解析回来（往返一致）")
    void headerRoundTrips() {
        TraceContext context = TraceContext.random();
        TraceContext reparsed = TraceContext.parse(context.header()).orElseThrow();

        assertThat(reparsed.traceId()).isEqualTo(context.traceId());
        assertThat(reparsed.spanId()).isEqualTo(context.spanId());
        assertThat(reparsed.sampled()).isTrue();
        assertThat(context.header()).startsWith("00-").endsWith("-01");
        assertThat(context.header().toLowerCase(Locale.ROOT)).isEqualTo(context.header());
    }

    @Test
    @DisplayName("inbound 永不失败：三种入站情况分别走延续 / 沿用 / 新建")
    void inboundAlwaysYieldsUsableContext() {
        // ① 有合法 traceparent → 延续它的 trace
        assertThat(TraceContext.inbound("00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01", null).traceId())
                .isEqualTo("4bf92f3577b34da6a3ce929d0e0e4736");
        // ② 只有裸 traceId → 沿用它
        assertThat(TraceContext.inbound(null, "4bf92f3577b34da6a3ce929d0e0e4736").traceId())
                .isEqualTo("4bf92f3577b34da6a3ce929d0e0e4736");
        assertThat(TraceContext.inbound("garbage", "4bf92f3577b34da6a3ce929d0e0e4736").traceId())
                .isEqualTo("4bf92f3577b34da6a3ce929d0e0e4736");
        // ③ 什么都没有 / 无法用 → 新建，且两次调用互不相同（不是固定兜底值）
        String first = TraceContext.inbound(null, null).traceId();
        String second = TraceContext.inbound(null, "order-123").traceId();
        assertThat(first).matches("[0-9a-f]{32}");
        assertThat(second).matches("[0-9a-f]{32}");
        assertThat(first).isNotEqualTo(second);
    }
}
