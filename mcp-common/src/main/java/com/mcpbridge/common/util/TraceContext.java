package com.mcpbridge.common.util;

import java.util.Locale;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * W3C Trace Context（OPS-02）。
 *
 * <p>平台此前只做了一半的链路追踪：把调用方给的 traceId 回写到响应头 {@code X-Trace-Id}，
 * 但<b>不往上游传任何东西</b>。结果是「桥接层之前的链路」与「桥接层到业务系统之间的链路」
 * 断成两截——上游日志里没有可以拿来对齐的标识，出了问题只能靠时间戳猜。
 *
 * <p>这个类负责把「调用方给的任意 traceId」转换成<b>合法的 W3C {@code traceparent}</b>，
 * 因为上游不会为你兜底：{@code traceparent} 格式不合法时，规范的实现应当<b>丢弃</b>整个头
 * （W3C Trace Context §3.2.2.3），于是你会以为传了、其实上游什么都没收到。
 *
 * <h2>入站信息的三种情况</h2>
 * <ol>
 *   <li>调用方给了合法 {@code traceparent} → <b>延续</b>它的 trace-id，本跳生成新的 span-id；</li>
 *   <li>调用方只给了 32 位十六进制的 trace-id（很多网关的约定）→ 当作 trace-id 使用；</li>
 *   <li>其它（任意字符串、UUID 带连字符等）→ 生成新的随机 trace-id，
 *       并通过响应头 {@code X-Trace-Id} 把<b>实际下发的那个 id</b> 告知调用方。
 *       刻意不去哈希调用方的原值：哈希后的 id 无法用人眼核对，而「回传真实 id」让两侧日志
 *       可以用同一个字符串直接搜到。</li>
 * </ol>
 *
 * @param traceId 32 位小写十六进制，全链路共享
 * @param spanId  16 位小写十六进制，只在「本跳发往下一跳」这一跳有效
 * @param sampled 是否采样（W3C flags 的最低位）
 */
public record TraceContext(String traceId, String spanId, boolean sampled) {

    /** W3C 标准头名。 */
    public static final String HEADER = "traceparent";

    /** 平台自己的 trace 头，回写给调用方用于跨系统检索。 */
    public static final String LEGACY_HEADER = "X-Trace-Id";

    /** 未采样的 traceparent 同样要传：它让上游明确知道「不要采」，比缺失更有信息量。 */
    private static final String FLAG_SAMPLED = "01";

    private static final String FLAG_NOT_SAMPLED = "00";

    /**
     * {@code version-traceId-parentId-flags}。
     *
     * <p>只接受 {@code 00} 版本：W3C 允许更高版本附带额外字段（向后兼容的解析规则），
     * 但平台既不产生它们也不理解它们，直接拒绝比"猜着解析"诚实。
     */
    private static final Pattern PATTERN = Pattern.compile(
            "^00-([0-9a-f]{32})-([0-9a-f]{16})-([0-9a-f]{2})$");

    /** 32 位十六进制的纯 trace-id 形态（第 2 种入站情况）。 */
    private static final Pattern TRACE_ID_ONLY = Pattern.compile("^[0-9a-f]{32}$");

    /** W3C 明确禁止全零值：trace-id 全零表示「无 trace」，parent-id 全零表示「无父 span」。 */
    private static final String ZERO_32 = "0".repeat(32);

    private static final String ZERO_16 = "0".repeat(16);

    /**
     * 解析一个 {@code traceparent} 头。
     *
     * @return 非法或缺省时返回空——调用方据此生成新的 trace，而不是拿着半截数据硬走
     */
    public static Optional<TraceContext> parse(String traceparent) {
        if (traceparent == null || traceparent.isBlank()) {
            return Optional.empty();
        }
        Matcher matcher = PATTERN.matcher(traceparent.trim().toLowerCase(Locale.ROOT));
        if (!matcher.matches()) {
            return Optional.empty();
        }
        String traceId = matcher.group(1);
        String parentId = matcher.group(2);
        if (ZERO_32.equals(traceId) || ZERO_16.equals(parentId)) {
            return Optional.empty();
        }
        // flags 的最低位是 sampled；高位保留给未来的语义，按 0 处理
        boolean sampled = (Integer.parseInt(matcher.group(3), 16) & 0x01) == 1;
        return Optional.of(new TraceContext(traceId, parentId, sampled));
    }

    /** 从一个纯 32 位十六进制 trace-id 构造（大小写不敏感）。 */
    public static Optional<TraceContext> ofTraceId(String traceId) {
        if (traceId == null) {
            return Optional.empty();
        }
        String normalized = traceId.trim().toLowerCase(Locale.ROOT);
        if (!TRACE_ID_ONLY.matcher(normalized).matches() || ZERO_32.equals(normalized)) {
            return Optional.empty();
        }
        return Optional.of(new TraceContext(normalized, randomHex(16), true));
    }

    /** 全新的 trace（采样开启）。 */
    public static TraceContext random() {
        return new TraceContext(randomHex(32), randomHex(16), true);
    }

    /**
     * 解析入站信息，<b>保证返回一个可用的 trace 上下文</b>。
     *
     * @param traceparent 调用方给的 {@code traceparent}，可为 null
     * @param traceId     调用方给的 traceId（非 W3C 形态时退化为「生成新的」），可为 null
     */
    public static TraceContext inbound(String traceparent, String traceId) {
        Optional<TraceContext> parsed = parse(traceparent);
        if (parsed.isPresent()) {
            return parsed.get();
        }
        return ofTraceId(traceId).orElseGet(TraceContext::random);
    }

    /**
     * 派生子上下文：trace-id 不变，span-id 换新。
     *
     * <p>发往上游的 {@code traceparent} 必须换 span-id——同一请求打多个上游（或重试多次）时，
     * 上游节点看到的是不同的 span，否则「哪一次调用失败了」在链路系统里会糊成一团。
     */
    public TraceContext child() {
        return new TraceContext(traceId, randomHex(16), sampled);
    }

    /** 序列化成 {@code traceparent} 头的值。 */
    public String header() {
        return "00-" + traceId + "-" + spanId + "-" + (sampled ? FLAG_SAMPLED : FLAG_NOT_SAMPLED);
    }

    private static final char[] HEX = "0123456789abcdef".toCharArray();

    private static String randomHex(int length) {
        // 用 SecureRandom：trace-id 会出现在上游日志与链路系统里，可预测的值等于把这些通道
        // 变成可被伪造的信道（有人可以构造出一个「看起来属于别人」的 trace）
        byte[] bytes = new byte[length / 2];
        StringBuilder builder = new StringBuilder(length);
        while (true) {
            Holder.RANDOM.nextBytes(bytes);
            builder.setLength(0);
            for (byte value : bytes) {
                builder.append(HEX[(value >> 4) & 0x0F]).append(HEX[value & 0x0F]);
            }
            // 全零在 W3C 里是非法值：概率约 2^-128，但规则明确这么写就按规则来，不留特例
            if (!ZERO_32.contentEquals(builder) && !ZERO_16.contentEquals(builder)) {
                return builder.toString();
            }
        }
    }

    /** 持有 SecureRandom：它的构造会读取系统熵源，不该每次调用都新建一个。 */
    private static final class Holder {
        private static final java.security.SecureRandom RANDOM = new java.security.SecureRandom();
    }
}
