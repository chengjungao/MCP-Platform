package com.mcpbridge.manager.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.mcpbridge.common.error.ErrorCode;
import com.mcpbridge.common.error.PlatformException;
import com.mcpbridge.common.protocol.McpProtocol;
import com.mcpbridge.common.snapshot.ToolSnapshot;
import com.mcpbridge.common.util.Json;
import com.mcpbridge.manager.domain.McpServer;
import com.mcpbridge.manager.domain.McpTool;
import com.mcpbridge.manager.domain.OverlayStatus;
import com.mcpbridge.manager.web.dto.ServerDtos;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * base ⊕ overlay 生效模型（BR-2）与锚点对账（REG-03）。
 *
 * <p>{@link OverlayService} 刻意不依赖仓储，所以这里是纯单元测试：
 * 覆盖合并是平台最容易出「悄悄改了用户数据」事故的地方，必须逐条钉死。
 */
class OverlayServiceTest {

    private OverlayService service;

    @BeforeEach
    void setUp() {
        service = new OverlayService();
    }

    // ------------------------------------------------------------------ Server 级合并

    @Test
    @DisplayName("Server 覆盖只改写出现过的键，其余回落 base")
    void appliesSparseServerOverlay() {
        McpServer server = serverWithBase();
        server.setOverlay(Json.write(Json.obj().put("title", "订单中心")));

        service.applyServerOverlay(server);

        assertThat(server.getTitle()).isEqualTo("订单中心");
        // 没被覆盖的字段必须原样保留 base 值——这是「稀疏覆盖」的全部意义
        assertThat(server.getName()).isEqualTo("order-service");
        assertThat(server.getDescription()).isEqualTo("订单查询与创建");
        assertThat(server.getPathSegment()).isEqualTo("order");
        assertThat(server.getVersion()).isEqualTo("1.0.0");
        assertThat(server.getListTtlMs()).isEqualTo(McpProtocol.DEFAULT_LIST_TTL_MS);
    }

    @Test
    @DisplayName("覆盖 pathSegment 与 listTtlMs 生效")
    void appliesPathSegmentAndTtlOverlay() {
        McpServer server = serverWithBase();
        ObjectNode overlay = Json.obj();
        overlay.put("pathSegment", "order-v2");
        overlay.put("listTtlMs", 60_000);
        server.setOverlay(Json.write(overlay));

        service.applyServerOverlay(server);

        assertThat(server.getPathSegment()).isEqualTo("order-v2");
        assertThat(server.getListTtlMs()).isEqualTo(60_000);
    }

    @Test
    @DisplayName("baseModel 缺失时用实体列兜底，不抛异常")
    void fallsBackToEntityColumnsWhenBaseModelMissing() {
        McpServer server = new McpServer();
        server.setName("legacy");
        server.setTitle("遗留服务");
        server.setPathSegment("legacy");
        server.setVersion("0.9");

        OverlayService.ServerBase base = service.baseOf(server);

        assertThat(base.name()).isEqualTo("legacy");
        assertThat(base.title()).isEqualTo("遗留服务");
        assertThat(base.baseUrls()).isEmpty();
    }

    @Test
    @DisplayName("writeBaseModel → baseOf 往返一致，含 baseUrls")
    void roundTripsBaseModel() {
        McpServer server = new McpServer();
        OverlayService.ServerBase base = new OverlayService.ServerBase(
                "crm", "客户关系", "描述", "2.1", "crm", List.of("http://a.local", "http://b.local"));

        service.writeBaseModel(server, base);
        OverlayService.ServerBase read = service.baseOf(server);

        assertThat(read).isEqualTo(base);
    }

    @Test
    @DisplayName("覆盖值为空白字符串时回落 base，不把服务名清空")
    void blankOverlayValueFallsBackToBase() {
        McpServer server = serverWithBase();
        server.setOverlay(Json.write(Json.obj().put("title", "   ")));

        service.applyServerOverlay(server);

        assertThat(server.getTitle()).isEqualTo("订单服务");
    }

    // ------------------------------------------------------------------ Tool 级合并

    @Test
    @DisplayName("tool 覆盖：name / description / inputSchema 生效")
    void appliesToolOverlay() {
        McpTool tool = tool("GET /orders/{id}", "getOrdersId", "查询订单", "GET", "/orders/{id}");
        ObjectNode overlay = Json.obj();
        overlay.put("name", "getOrderDetail");
        overlay.put("description", "按订单号查询订单详情");
        ObjectNode schema = Json.obj();
        schema.put("type", "object");
        overlay.set("inputSchema", schema);
        service.writeToolOverlay(tool, overlay);

        assertThat(service.effectiveName(tool)).isEqualTo("getOrderDetail");
        assertThat(service.effectiveDescription(tool)).isEqualTo("按订单号查询订单详情");
        assertThat(service.effectiveInputSchema(tool).get("type").asText()).isEqualTo("object");
        assertThat(tool.getOverlayStatus()).isEqualTo(OverlayStatus.ACTIVE);
    }

    @Test
    @DisplayName("覆盖 inputSchema 不是对象时忽略，回落 base schema")
    void ignoresNonObjectSchemaOverride() {
        McpTool tool = tool("GET /orders", "listOrders", null, "GET", "/orders");
        tool.setBaseInputSchema("{\"type\":\"object\",\"properties\":{\"status\":{\"type\":\"string\"}}}");
        service.writeToolOverlay(tool, Json.obj().put("inputSchema", "not-an-object"));

        JsonNode effective = service.effectiveInputSchema(tool);

        assertThat(effective.get("properties").has("status")).isTrue();
    }

    @Test
    @DisplayName("base 无 schema 时给出合法的空 object schema，而不是 null")
    void yieldsEmptyObjectSchemaWhenNothingConfigured() {
        McpTool tool = tool("GET /ping", "ping", null, "GET", "/ping");

        JsonNode schema = service.effectiveInputSchema(tool);

        assertThat(schema.get("$schema").asText()).isEqualTo(McpProtocol.JSON_SCHEMA_DIALECT);
        assertThat(schema.get("type").asText()).isEqualTo("object");
        assertThat(schema.get("properties").isEmpty()).isTrue();
    }

    @Test
    @DisplayName("description 为空时回落 summary")
    void descriptionFallsBackToSummary() {
        McpTool tool = tool("GET /orders", "listOrders", "列出订单", "GET", "/orders");
        tool.setBaseDescription(null);

        assertThat(service.effectiveDescription(tool)).isEqualTo("列出订单");
    }

    @Test
    @DisplayName("写空覆盖会清空 overlay 并把状态归位 NONE")
    void emptyOverlayClearsAndResetsStatus() {
        McpTool tool = tool("GET /orders", "listOrders", null, "GET", "/orders");
        service.writeToolOverlay(tool, Json.obj().put("name", "x"));
        assertThat(tool.getOverlayStatus()).isEqualTo(OverlayStatus.ACTIVE);

        service.writeToolOverlay(tool, Json.obj());

        assertThat(tool.getOverlay()).isNull();
        assertThat(tool.getOverlayStatus()).isEqualTo(OverlayStatus.NONE);
    }

    @Test
    @DisplayName("覆盖后的 tool 名仍必须满足 MCP 命名约束（BR-1）")
    void rejectsIllegalToolName() {
        assertThatThrownBy(() -> service.requireValidToolName("查询 订单"))
                .isInstanceOf(PlatformException.class)
                .satisfies(e -> {
                    PlatformException ex = (PlatformException) e;
                    assertThat(ex.errorCode()).isEqualTo(ErrorCode.VALIDATION_FAILED);
                    assertThat(ex.details()).containsEntry("field", "name");
                });
    }

    @Test
    @DisplayName("parameterIn 从 jsonb 文本解析为有序映射")
    void parsesParameterIn() {
        McpTool tool = tool("POST /orders", "createOrder", null, "POST", "/orders");
        tool.setParameterIn("{\"orderId\":\"path\",\"status\":\"query\",\"body\":\"body\"}");

        assertThat(service.parameterInOf(tool))
                .containsEntry("orderId", "path")
                .containsEntry("status", "query")
                .containsEntry("body", "body");
    }

    @Test
    @DisplayName("非流式 tool 的快照不带 streamFormat，避免下游误判")
    void snapshotDropsStreamFormatWhenNotStreaming() {
        McpTool tool = tool("GET /orders", "listOrders", null, "GET", "/orders");
        tool.setStreamFormat("SSE");
        tool.setStreaming(false);

        ToolSnapshot snapshot = service.toSnapshot(tool);

        assertThat(snapshot.streaming()).isFalse();
        assertThat(snapshot.streamFormat()).isNull();
    }

    @Test
    @DisplayName("覆盖 streaming=true 时快照带上 streamFormat")
    void snapshotKeepsStreamFormatWhenStreamingOverridden() {
        McpTool tool = tool("GET /events", "listEvents", null, "GET", "/events");
        tool.setStreaming(false);
        ObjectNode overlay = Json.obj();
        overlay.put("streaming", true);
        overlay.put("streamFormat", "NDJSON");
        service.writeToolOverlay(tool, overlay);

        ToolSnapshot snapshot = service.toSnapshot(tool);

        assertThat(snapshot.streaming()).isTrue();
        assertThat(snapshot.streamFormat()).isEqualTo("NDJSON");
    }

    // ------------------------------------------------------------------ 锚点对账

    @Test
    @DisplayName("文档升级后锚点消失的覆盖进入挂起区，绝不静默丢弃（REG-03）")
    void suspendsOverlayWhoseAnchorDisappeared() {
        McpTool alive = tool("GET /orders", "listOrders", null, "GET", "/orders");
        service.writeToolOverlay(alive, Json.obj().put("name", "listOrdersV2"));
        McpTool gone = tool("DELETE /orders/{id}", "deleteOrdersId", null, "DELETE", "/orders/{id}");
        service.writeToolOverlay(gone, Json.obj().put("name", "cancelOrder"));

        OverlayService.ReconcileResult result =
                service.reconcileAnchors(List.of(alive, gone), Set.of("GET /orders"));

        assertThat(result.suspended()).containsExactly("DELETE /orders/{id}");
        assertThat(result.restored()).isEmpty();
        assertThat(gone.getOverlayStatus()).isEqualTo(OverlayStatus.SUSPENDED);
        assertThat(alive.getOverlayStatus()).isEqualTo(OverlayStatus.ACTIVE);
        // 挂起只是「不生效」，覆盖内容必须原样保留，等接口回来时能自动恢复
        assertThat(gone.getOverlay()).contains("cancelOrder");
    }

    @Test
    @DisplayName("锚点重新出现时从挂起区恢复")
    void restoresOverlayWhoseAnchorReappeared() {
        McpTool tool = tool("GET /orders", "listOrders", null, "GET", "/orders");
        service.writeToolOverlay(tool, Json.obj().put("name", "listOrdersV2"));
        tool.setOverlayStatus(OverlayStatus.SUSPENDED);

        OverlayService.ReconcileResult result =
                service.reconcileAnchors(List.of(tool), Set.of("GET /orders"));

        assertThat(result.restored()).containsExactly("GET /orders");
        assertThat(tool.getOverlayStatus()).isEqualTo(OverlayStatus.ACTIVE);
    }

    @Test
    @DisplayName("没有覆盖的 tool 状态一律归位 NONE，不参与对账")
    void resetsStatusForToolsWithoutOverlay() {
        McpTool tool = tool("GET /orders", "listOrders", null, "GET", "/orders");
        tool.setOverlayStatus(OverlayStatus.SUSPENDED);

        OverlayService.ReconcileResult result = service.reconcileAnchors(List.of(tool), Set.of());

        assertThat(result.suspended()).isEmpty();
        assertThat(result.restored()).isEmpty();
        assertThat(tool.getOverlayStatus()).isEqualTo(OverlayStatus.NONE);
    }

    // ------------------------------------------------------------------ 差异视图

    @Test
    @DisplayName("差异视图标记被覆盖的字段并列出挂起项")
    void diffMarksOverriddenFieldsAndSuspendedAnchors() {
        McpServer server = serverWithBase();
        server.setOverlay(Json.write(Json.obj().put("title", "订单中心")));
        service.applyServerOverlay(server);

        McpTool overridden = tool("GET /orders", "listOrders", "列出订单", "GET", "/orders");
        service.writeToolOverlay(overridden, Json.obj().put("name", "searchOrders"));
        McpTool suspended = tool("DELETE /orders/{id}", "deleteOrdersId", null, "DELETE", "/orders/{id}");
        service.writeToolOverlay(suspended, Json.obj().put("description", "取消订单"));
        suspended.setOverlayStatus(OverlayStatus.SUSPENDED);
        McpTool untouched = tool("POST /orders", "createOrder", null, "POST", "/orders");

        ServerDtos.DiffView diff = service.diff(server, List.of(overridden, suspended, untouched));

        assertThat(diff.serverId()).isEqualTo(7L);
        assertThat(field(diff.serverFields(), "title").overridden()).isTrue();
        assertThat(field(diff.serverFields(), "title").effectiveValue()).isEqualTo("订单中心");
        assertThat(field(diff.serverFields(), "name").overridden()).isFalse();
        // 没有覆盖的 tool 不进差异列表，否则 UI 会被几百条「无变化」淹没
        assertThat(diff.tools()).hasSize(2);
        assertThat(diff.tools().get(0).effectiveName()).isEqualTo("searchOrders");
        assertThat(diff.suspendedOverlays()).containsExactly("DELETE /orders/{id}");
    }

    // ------------------------------------------------------------------ 夹具

    private McpServer serverWithBase() {
        McpServer server = new McpServer();
        server.setId(7L);
        service.writeBaseModel(server, new OverlayService.ServerBase(
                "order-service", "订单服务", "订单查询与创建", "1.0.0", "order",
                List.of("http://order.local")));
        // base 写入后实体列还是空的，先按 base 落一次生效值
        service.applyServerOverlay(server);
        return server;
    }

    private static McpTool tool(String anchor, String baseName, String summary, String method, String path) {
        McpTool tool = new McpTool();
        tool.setAnchor(anchor);
        tool.setBaseName(baseName);
        tool.setBaseSummary(summary);
        tool.setMethod(method);
        tool.setPath(path);
        tool.setEnabled(true);
        return tool;
    }

    private static ServerDtos.FieldChange field(List<ServerDtos.FieldChange> changes, String name) {
        return changes.stream().filter(c -> name.equals(c.field())).findFirst().orElseThrow();
    }
}