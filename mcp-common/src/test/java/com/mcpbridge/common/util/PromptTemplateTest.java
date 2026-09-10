package com.mcpbridge.common.util;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Prompt 模板占位符规则（SVR-06）——Manager 与 Executor 的<b>共用契约</b>。
 *
 * <p>这个类存在的意义就是让两侧没有第二份实现。控制面用它做保存期校验
 * （模板里的占位符必须都声明过），数据面用它做运行时渲染。
 * 一旦两边规则不一致，就会出现「控制面放行的模板，运行时按另一套规则渲染」。
 */
class PromptTemplateTest {

    @Test
    @DisplayName("提取占位符：按首次出现顺序去重，允许名字周围有空白")
    void extractsPlaceholdersInOrder() {
        String template = "订单 {{orderId}} 的用户 {{ userId }} 与 {{orderId}} 重复出现";

        assertThat(PromptTemplate.placeholders(template))
                .containsExactly("orderId", "userId");
    }

    @Test
    @DisplayName("名字里允许下划线、点、连字符——这些不能算边界情况")
    void allowsDotsUnderscoresAndDashes() {
        assertThat(PromptTemplate.placeholders("{{order_id}} {{order.id}} {{order-id}}"))
                .containsExactly("order_id", "order.id", "order-id");
    }

    @Test
    @DisplayName("渲染：已提供的参数替换成值，未提供的替换成空串而不是原样保留")
    void rendersUnknownPlaceholderAsEmpty() {
        // 原样保留会让 {{orderId}} 出现在最终提示词里，模型会把它当成真的标识符去猜
        String rendered = PromptTemplate.render("查 {{orderId}} 的物流",
                Map.of("orderId", "A-1"));

        assertThat(rendered).isEqualTo("查 A-1 的物流");
        assertThat(PromptTemplate.render("查 {{orderId}} 的物流", Map.of())).isEqualTo("查  的物流");
    }

    @Test
    @DisplayName("渲染：参数值里的 $ 与 \\ 不会被当成正则替换转义")
    void renderQuotesReplacementLiterally() {
        // Matcher.appendReplacement 会把 $1 / \ 当特殊序列，不 quote 会抛异常或插错内容
        String rendered = PromptTemplate.render("金额 {{amount}}", Map.of("amount", "$100\\元"));

        assertThat(rendered).isEqualTo("金额 $100\\元");
    }

    @Test
    @DisplayName("空模板与 null 一律渲染成空串，不抛异常")
    void blankTemplateIsEmpty() {
        assertThat(PromptTemplate.render(null, Map.of("a", "1"))).isEmpty();
        assertThat(PromptTemplate.render("   ", Map.of("a", "1"))).isEmpty();
        assertThat(PromptTemplate.placeholders(null)).isEmpty();
    }

    @Test
    @DisplayName("单花括号不是占位符——避免把 JSON 片段误判成参数")
    void singleBracesAreNotPlaceholders() {
        assertThat(PromptTemplate.placeholders("{\"a\":1} {notAPlaceholder}")).isEmpty();
    }

    @Test
    @DisplayName("声明的参数名必须能写成占位符，否则等于声明了一个引用不到的参数")
    void declaredNamesMustBePlaceholderCompatible() {
        // 这条不是洁癖：含空格的名字永远写不进 {{...}}，保存时会被 Manager 拒绝
        List<String> legal = List.of("orderId", "order_id", "order.id", "order-id");
        for (String name : legal) {
            assertThat(PromptTemplate.placeholders("{{" + name + "}}"))
                    .as("参数名 %s 应能写成占位符", name)
                    .containsExactly(name);
        }
        assertThat(PromptTemplate.placeholders("{{order id}}")).isEmpty();
    }
}
