package com.mcpbridge.manager.service;

import com.mcpbridge.common.error.PlatformException;
import com.mcpbridge.common.snapshot.PromptSnapshot;
import com.mcpbridge.manager.web.dto.ServerDtos;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Resource / Prompt 的配置期校验（SVR-05 / SVR-06）。
 *
 * <p>两条主线：
 * <ul>
 *   <li><b>Resource</b>：URI 必须像 URI（否则会把「订单schema」这样的自然语言存进去），
 *       且静态内容与 tool 映射必须二选一——两者都空等于声明了一个读不出内容的资源，
 *       只会在客户端 {@code resources/read} 时才暴露；</li>
 *   <li><b>Prompt</b>：模板里的占位符必须都声明过。未声明的占位符在运行时被替换成空串，
 *       于是 {@code {{oderId}}} 这样的拼写错误变成线上提示词里一个沉默的空洞。</li>
 * </ul>
 */
class ResourcePromptValidationTest {

    // ---------------------------------------------------------------- Resource

    @Test
    @DisplayName("Resource URI 必须带 scheme——自然语言描述会被拒绝")
    void uriNeedsScheme() {
        assertThat(ResourcePromptService.requireUri("mcp://crm-order/schema"))
                .isEqualTo("mcp://crm-order/schema");
        assertThat(ResourcePromptService.requireUri("https://example.com/schema.json"))
                .isEqualTo("https://example.com/schema.json");

        assertThatThrownBy(() -> ResourcePromptService.requireUri("订单schema"))
                .isInstanceOf(PlatformException.class)
                .hasMessageContaining("scheme");
        assertThatThrownBy(() -> ResourcePromptService.requireUri("  "))
                .isInstanceOf(PlatformException.class);
        assertThatThrownBy(() -> ResourcePromptService.requireUri(null))
                .isInstanceOf(PlatformException.class);
    }

    @Test
    @DisplayName("URI 中的空白被拒绝：空格会让 MCP 客户端无法正确匹配")
    void uriRejectsWhitespace() {
        assertThatThrownBy(() -> ResourcePromptService.requireUri("mcp://crm order/schema"))
                .isInstanceOf(PlatformException.class);
    }

    // ---------------------------------------------------------------- Prompt 名

    @Test
    @DisplayName("Prompt 名允许点与连字符（order.review 这类层次名很常见），但不允许空格")
    void promptNameRules() {
        assertThat(ResourcePromptService.requirePromptName("order.review")).isEqualTo("order.review");
        assertThat(ResourcePromptService.requirePromptName("order-review_2")).isEqualTo("order-review_2");

        assertThatThrownBy(() -> ResourcePromptService.requirePromptName("order review"))
                .isInstanceOf(PlatformException.class)
                .hasMessageContaining("128");
        assertThatThrownBy(() -> ResourcePromptService.requirePromptName(""))
                .isInstanceOf(PlatformException.class);
    }

    // ---------------------------------------------------------------- 参数声明

    @Test
    @DisplayName("参数名不能重复，且必须能写成占位符")
    void argumentNamesAreValidated() {
        assertThat(ResourcePromptService.requireArguments(List.of(
                new ServerDtos.PromptArgumentRequest("orderId", "订单号", true))))
                .containsExactly(new PromptSnapshot.Argument("orderId", "订单号", true));

        assertThatThrownBy(() -> ResourcePromptService.requireArguments(List.of(
                new ServerDtos.PromptArgumentRequest("orderId", null, true),
                new ServerDtos.PromptArgumentRequest("orderId", null, false))))
                .isInstanceOf(PlatformException.class)
                .hasMessageContaining("重复");

        // 含空格的参数名永远写不进 {{...}}，等于声明了一个引用不到的参数
        assertThatThrownBy(() -> ResourcePromptService.requireArguments(List.of(
                new ServerDtos.PromptArgumentRequest("order id", null, true))))
                .isInstanceOf(PlatformException.class);
    }

    @Test
    @DisplayName("无参数声明是合法的：返回空列表而不是 null")
    void emptyArgumentsAreFine() {
        assertThat(ResourcePromptService.requireArguments(null)).isEmpty();
        assertThat(ResourcePromptService.requireArguments(List.of())).isEmpty();
    }

    // ---------------------------------------------------------------- 模板 ↔ 参数一致性

    private static List<PromptSnapshot.Argument> args(String... names) {
        return List.of(names).stream().map(name -> new PromptSnapshot.Argument(name, null, true)).toList();
    }

    @Test
    @DisplayName("正常情形：占位符与参数一一对应，通过")
    void consistentTemplatePasses() {
        ResourcePromptService.requirePlaceholdersDeclared(
                "帮我查订单 {{orderId}} 的物流，用户 {{userId}}", args("orderId", "userId"));
    }

    @Test
    @DisplayName("模板里出现了未声明的占位符：拒绝，并指出是哪一个")
    void undeclaredPlaceholderIsRejected() {
        // 拼写错误 oderId：不拦的话，运行时会被替换成空串，提示词里留下一个空洞
        assertThatThrownBy(() -> ResourcePromptService.requirePlaceholdersDeclared(
                "帮我查订单 {{oderId}} 的物流", args("orderId")))
                .isInstanceOf(PlatformException.class)
                .hasMessageContaining("oderId");
    }

    @Test
    @DisplayName("错误细节同时进 message 和 details：前者给日志，后者给前端逐字段标红")
    void problemDetailsAreStructured() {
        PlatformException failure = (PlatformException) org.assertj.core.api.Assertions
                .catchThrowable(() -> ResourcePromptService.requirePlaceholdersDeclared(
                        "查 {{oderId}}", args("orderId")));

        // message 要能独立说清问题——日志与审计只打 message
        assertThat(failure.getMessage()).contains("oderId");
        // details 要带上出问题的字段名，前端才能定位到具体输入框
        assertThat(failure.details()).containsKey("template");
        assertThat(String.valueOf(failure.details().get("template"))).contains("oderId");
    }

    @Test
    @DisplayName("声明了参数但模板没用：同样拒绝——客户端会让用户填一个对结果毫无影响的值")
    void unusedArgumentIsRejected() {
        assertThatThrownBy(() -> ResourcePromptService.requirePlaceholdersDeclared(
                "帮我查订单 {{orderId}}", args("orderId", "unusedParam")))
                .isInstanceOf(PlatformException.class)
                .hasMessageContaining("unusedParam");
    }

    @Test
    @DisplayName("无参数、无占位符的纯文本模板是合法的（常见于固定话术）")
    void plainTemplateWithoutArgumentsPasses() {
        ResourcePromptService.requirePlaceholdersDeclared("请帮我总结这段需求", List.of());
        ResourcePromptService.requirePlaceholdersDeclared(null, List.of());
    }

    @Test
    @DisplayName("单波浪花括号不算占位符：JSON 片段不该被要求声明成参数")
    void singleBracesDoNotRequireArguments() {
        ResourcePromptService.requirePlaceholdersDeclared("按这个 schema 生成：{\"a\":1}", List.of());
    }
}
