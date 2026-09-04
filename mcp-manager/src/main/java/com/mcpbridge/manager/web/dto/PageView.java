package com.mcpbridge.manager.web.dto;

import org.springframework.data.domain.Page;

import java.util.List;
import java.util.function.Function;

/**
 * 分页视图。屏蔽 Spring Data 的 {@code Page} 序列化细节（它带大量内部字段）。
 */
public record PageView<T>(List<T> items, long total, int page, int size, int totalPages) {

    public static <E, T> PageView<T> of(Page<E> page, Function<E, T> mapper) {
        return new PageView<>(page.getContent().stream().map(mapper).toList(),
                page.getTotalElements(), page.getNumber(), page.getSize(), page.getTotalPages());
    }

    public static <T> PageView<T> of(List<T> items) {
        return new PageView<>(items, items.size(), 0, Math.max(items.size(), 1), 1);
    }
}