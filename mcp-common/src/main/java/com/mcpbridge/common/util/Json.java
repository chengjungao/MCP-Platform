package com.mcpbridge.common.util;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.mcpbridge.common.error.ErrorCode;
import com.mcpbridge.common.error.PlatformException;

import java.util.Map;

/**
 * 共享 Jackson 门面。控制面与数据面对 JSON 的处理口径必须一致
 * （时间格式 ISO-8601、忽略未知字段、null 不输出），否则快照在两侧解析会出现语义漂移。
 */
public final class Json {

    public static final ObjectMapper MAPPER = new ObjectMapper()
            .registerModule(new JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
            .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .setSerializationInclusion(JsonInclude.Include.NON_NULL);

    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {
    };

    private Json() {
    }

    public static String write(Object value) {
        try {
            return MAPPER.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new PlatformException(ErrorCode.INTERNAL_ERROR, "JSON 序列化失败: " + e.getOriginalMessage(), e);
        }
    }

    public static byte[] writeBytes(Object value) {
        try {
            return MAPPER.writeValueAsBytes(value);
        } catch (JsonProcessingException e) {
            throw new PlatformException(ErrorCode.INTERNAL_ERROR, "JSON 序列化失败: " + e.getOriginalMessage(), e);
        }
    }

    public static JsonNode tree(String text) {
        try {
            return MAPPER.readTree(text);
        } catch (JsonProcessingException e) {
            throw new PlatformException(ErrorCode.VALIDATION_FAILED,
                    "非法 JSON: " + e.getOriginalMessage(), e, Map.of("line", e.getLocation().getLineNr()));
        }
    }

    public static JsonNode tree(byte[] bytes) {
        try {
            return MAPPER.readTree(bytes);
        } catch (Exception e) {
            throw new PlatformException(ErrorCode.VALIDATION_FAILED, "非法 JSON 字节流", e);
        }
    }

    public static <T> T read(String text, Class<T> type) {
        try {
            return MAPPER.readValue(text, type);
        } catch (JsonProcessingException e) {
            throw new PlatformException(ErrorCode.VALIDATION_FAILED,
                    "JSON 反序列化为 " + type.getSimpleName() + " 失败: " + e.getOriginalMessage(), e);
        }
    }

    public static <T> T read(String text, TypeReference<T> type) {
        try {
            return MAPPER.readValue(text, type);
        } catch (JsonProcessingException e) {
            throw new PlatformException(ErrorCode.VALIDATION_FAILED, "JSON 反序列化失败: " + e.getOriginalMessage(), e);
        }
    }

    /** 把任意 POJO/Map/JsonNode 转成目标类型（用于快照与实体之间的模型转换）。 */
    public static <T> T convert(Object from, Class<T> type) {
        return MAPPER.convertValue(from, type);
    }

    public static Map<String, Object> toMap(JsonNode node) {
        if (node == null || node.isNull()) {
            return Map.of();
        }
        return MAPPER.convertValue(node, MAP_TYPE);
    }

    public static ObjectNode obj() {
        return MAPPER.createObjectNode();
    }

    public static ArrayNode arr() {
        return MAPPER.createArrayNode();
    }
}
