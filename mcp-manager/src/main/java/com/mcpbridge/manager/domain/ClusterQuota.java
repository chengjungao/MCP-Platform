package com.mcpbridge.manager.domain;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.mcpbridge.common.error.PlatformException;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 集群发布配额（PUB-01）。
 *
 * <p><b>为什么会有这个类</b>：{@code executor_cluster} 从 V1 起就带着一个 {@code scopes jsonb}
 * 列（注释写「可发布范围与配额」），但全仓只有写入方、没有读取方——值存进去了，从不参与任何校验。
 * 这类"半成品"比没有更糟：它让人以为已经有配额控制了。本类把该列收敛成一组<b>可执行</b>的容量上限。
 *
 * <p>三个维度都可以缺省，缺省表示该维度不限；三者全为 null 等价于"整体不限"，是默认值。
 * 语义上都是"上限"，比较时用 {@code >}（等于上限是允许的）。
 *
 * <p>为什么是"发布时校验"而不是运行时限流：配额关心的是"这个集群能承载多少东西"，
 * 属于容量控制，在发布这个低频、可回滚的动作上拦住最经济，也最容易解释给使用者听。
 * 运行时的请求速率限制是另一件事（需要令牌桶 + 拒绝路径），明确不在本类范围内。
 *
 * <p>它刻意不进入 {@code ServerSnapshot}：Executor 不需要知道配额，改了配额也不必让所有节点重载快照。
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ClusterQuota(Integer maxServers,
                           Integer maxToolsPerServer,
                           Integer maxCatalogItemsPerServer) {

    public static final String KEY_MAX_SERVERS = "maxServers";
    public static final String KEY_MAX_TOOLS = "maxToolsPerServer";
    public static final String KEY_MAX_CATALOG = "maxCatalogItemsPerServer";

    public static final ClusterQuota UNLIMITED = new ClusterQuota(null, null, null);

    private static final Map<String, String> LABELS = Map.of(
            KEY_MAX_SERVERS, "本集群已发布 Server 数",
            KEY_MAX_TOOLS, "单个 Server 的启用 tool 数",
            KEY_MAX_CATALOG, "单个 Server 的 Resource + Prompt 数");

    /**
     * 从请求体（或数据库 JSONB）解析配额。
     *
     * <p>对非法输入一律<b>抛出校验异常</b>而不是静默忽略：把 {@code maxServers: -1} 当成"不限"
     * 只会让配置者以为已经生效。空值、空串、"键不存在"三者等价处理为"该维度不限"。
     */
    public static ClusterQuota of(Map<String, Object> raw) {
        if (raw == null || raw.isEmpty()) {
            return UNLIMITED;
        }
        return new ClusterQuota(
                intOrNull(raw, KEY_MAX_SERVERS),
                intOrNull(raw, KEY_MAX_TOOLS),
                intOrNull(raw, KEY_MAX_CATALOG));
    }

    /** 是否所有维度都不限。 */
    public boolean isUnlimited() {
        return maxServers == null && maxToolsPerServer == null && maxCatalogItemsPerServer == null;
    }

    /** 只输出已设置的维度，便于存回 JSONB 时保持精简（而不是塞三个 null）。 */
    public Map<String, Object> asMap() {
        Map<String, Object> map = new LinkedHashMap<>();
        if (maxServers != null) {
            map.put(KEY_MAX_SERVERS, maxServers);
        }
        if (maxToolsPerServer != null) {
            map.put(KEY_MAX_TOOLS, maxToolsPerServer);
        }
        if (maxCatalogItemsPerServer != null) {
            map.put(KEY_MAX_CATALOG, maxCatalogItemsPerServer);
        }
        return map;
    }

    /**
     * 发布前用量校验。返回空 map 表示全部通过；非空时 key 为 {@code quota.<维度>}、value 为可读原因，
     * 直接可以塞进 {@code PlatformException.details} 让前端逐项展示。
     *
     * <p>做成纯函数（四个数字进、问题清单出）而不依赖任何仓储，是为了让判定规则可被单测穷尽——
     * 这类"边界差一"的逻辑一旦埋在 Service 里就只能靠集成测试碰运气。
     *
     * @param publishedServers 集群当前已发布的 Server 数（含本 Server，若它已经发布在本集群）
     * @param alreadyPublished 本 Server 是否已经发布在本集群。已发布过的不再占用新名额，
     *                         否则"重新发布"会在配额用满时被自己挡住
     * @param toolCount        本 Server 当前启用状态的 tool 数
     * @param catalogCount     本 Server 当前 Resource + Prompt 数
     */
    public Map<String, Object> violations(long publishedServers, boolean alreadyPublished,
                                          long toolCount, long catalogCount) {
        Map<String, Object> problems = new LinkedHashMap<>();
        if (maxServers != null && !alreadyPublished && publishedServers >= maxServers) {
            problems.put("quota." + KEY_MAX_SERVERS, describe(KEY_MAX_SERVERS, maxServers, publishedServers));
        }
        if (maxToolsPerServer != null && toolCount > maxToolsPerServer) {
            problems.put("quota." + KEY_MAX_TOOLS, describe(KEY_MAX_TOOLS, maxToolsPerServer, toolCount));
        }
        if (maxCatalogItemsPerServer != null && catalogCount > maxCatalogItemsPerServer) {
            problems.put("quota." + KEY_MAX_CATALOG, describe(KEY_MAX_CATALOG, maxCatalogItemsPerServer, catalogCount));
        }
        return problems;
    }

    public static String labelOf(String key) {
        return LABELS.getOrDefault(key, key);
    }

    private static String describe(String key, long limit, long used) {
        return "配额上限 " + limit + "，当前 " + used + "（" + labelOf(key) + "）";
    }

    private static Integer intOrNull(Map<String, Object> raw, String key) {
        Object value = raw.get(key);
        if (value == null) {
            return null;
        }
        if (value instanceof String text) {
            // 前端数字输入框清空后提交的是空串，按"未设置"处理
            if (text.isBlank()) {
                return null;
            }
            try {
                return bound(Long.parseLong(text.trim()), key);
            } catch (NumberFormatException e) {
                throw invalid(key, value, "必须是整数");
            }
        }
        if (value instanceof Number number) {
            if (number.doubleValue() != Math.floor(number.doubleValue())) {
                throw invalid(key, value, "必须是整数");
            }
            return bound(number.longValue(), key);
        }
        throw invalid(key, value, "必须是整数");
    }

    private static Integer bound(long value, String key) {
        if (value < 0) {
            throw invalid(key, value, "不能为负数（0 表示不允许，留空表示不限）");
        }
        if (value > Integer.MAX_VALUE) {
            throw invalid(key, value, "超出取值范围");
        }
        return (int) value;
    }

    private static PlatformException invalid(String key, Object value, String reason) {
        return PlatformException.validation("发布配额「" + labelOf(key) + "」" + reason,
                Map.of("field", "quota." + key, "value", String.valueOf(value)));
    }
}
