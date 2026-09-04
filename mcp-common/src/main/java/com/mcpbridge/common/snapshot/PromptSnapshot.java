package com.mcpbridge.common.snapshot;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;

/**
 * MCP Prompt 定义（SVR-06，P1）：手动配置的模板文本，可引用 tool。
 *
 * @param name      prompt 名
 * @param title     展示名
 * @param description 描述
 * @param template  模板文本，占位符形如 {@code {{argName}}}
 * @param arguments 参数声明
 * @param ttlMs     list 响应缓存提示
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record PromptSnapshot(
        String name,
        String title,
        String description,
        String template,
        List<Argument> arguments,
        Integer ttlMs) {

    /**
     * @param name        参数名
     * @param description 参数说明
     * @param required    是否必填
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Argument(String name, String description, boolean required) {
    }
}
