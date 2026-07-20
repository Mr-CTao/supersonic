package com.tencent.supersonic.forecast.server.util;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tencent.supersonic.common.pojo.exception.InvalidArgumentException;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * Forecast 配置序列化与幂等指纹工具。
 *
 * <p>
 * 使用应用统一 ObjectMapper，敏感凭据从不进入待序列化对象。MessageDigest 为每次调用创建， 避免共享可变实例的线程安全问题。
 * </p>
 */
@Component
public class ForecastJson {

    private final ObjectMapper objectMapper;

    /**
     * 创建 JSON 工具。
     *
     * @param objectMapper 应用 ObjectMapper。
     */
    public ForecastJson(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    /**
     * 序列化受控对象。
     *
     * @param value 对象。
     * @return JSON。
     * @throws InvalidArgumentException 序列化失败。
     */
    public String write(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new InvalidArgumentException("Forecast 配置无法序列化");
        }
    }

    /**
     * 反序列化受控对象。
     *
     * @param json JSON。
     * @param type 目标类型。
     * @param <T> 类型参数。
     * @return 目标对象。
     * @throws InvalidArgumentException JSON 已损坏或结构不兼容。
     */
    public <T> T read(String json, Class<T> type) {
        try {
            return objectMapper.readValue(json, type);
        } catch (JsonProcessingException exception) {
            throw new InvalidArgumentException("Forecast 配置 JSON 无效");
        }
    }

    /**
     * 计算用于版本与幂等校验的 SHA-256。
     *
     * @param value 原文。
     * @return 小写十六进制摘要。
     */
    public String sha256(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("JVM 缺少 SHA-256", exception);
        }
    }
}
