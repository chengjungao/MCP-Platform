package com.mcpbridge.common.util;

import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Prompt 模板的占位符规则（SVR-06）。
 *
 * <p>占位符写作 {@code {{argName}}}，其中 argName 允许字母数字、下划线、点、连字符。
 *
 * <p><b>这个类是 Manager 与 Executor 的共用契约</b>，放这里而不是各自一份：
 * <ul>
 *   <li>Manager 在<b>配置期</b>用它校验「模板里的占位符都声明过」（拼错 {@code {{oderId}}}
 *       会在保存时被拦下，而不是等到运行时悄悄变成一个空洞）；</li>
 *   <li>Executor 在<b>运行时</b>用它渲染，未提供的占位符替换成空串。</li>
 * </ul>
 * 两边若各写一份正则，就会出现「控制面认为合法、数据面按另一套规则渲染」的错位。
 */
public final class PromptTemplate {

    /** 占位符：{@code {{ name }}}，允许 name 周围有空白。 */
    public static final Pattern PLACEHOLDER = Pattern.compile("\\{\\{\\s*([A-Za-z0-9_.\\-]+)\\s*}}");

    private PromptTemplate() {
    }

    /**
     * 提取模板中出现的占位符，按首次出现顺序去重。
     *
     * <p>用于配置期校验。返回有序集合是为了错误信息里能按用户书写的顺序列出问题。
     */
    public static Set<String> placeholders(String template) {
        Set<String> found = new LinkedHashSet<>();
        if (template == null || template.isBlank()) {
            return found;
        }
        Matcher matcher = PLACEHOLDER.matcher(template);
        while (matcher.find()) {
            found.add(matcher.group(1));
        }
        return found;
    }

    /**
     * 渲染模板：提供的参数替换为值，<b>未提供的替换成空串</b>而不是原样保留。
     *
     * <p>原样保留会让 {@code {{orderId}}} 出现在最终提示词里，模型会把它当成真的标识符去猜——
     * 一个明确的空缺远好过一个像模像样的假值。这也是配置期要拦住未声明占位符的原因：
     * 运行时的「替换成空串」是兜底，不该是常态。
     */
    public static String render(String template, Map<String, String> arguments) {
        if (template == null || template.isBlank()) {
            return "";
        }
        Map<String, String> args = arguments == null ? Map.of() : arguments;
        Matcher matcher = PLACEHOLDER.matcher(template);
        StringBuilder rendered = new StringBuilder();
        while (matcher.find()) {
            matcher.appendReplacement(rendered,
                    Matcher.quoteReplacement(args.getOrDefault(matcher.group(1), "")));
        }
        matcher.appendTail(rendered);
        return rendered.toString();
    }
}
