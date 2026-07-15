package com.tencent.supersonic.headless.server.semantic.routing;

import lombok.Getter;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

/**
 * 语义资产路由安全异常。
 *
 * <p>
 * 职责：携带稳定错误码和安全用户消息，不暴露 SQL、Prompt、Provider 原文或权限对象。异常实例只在 单次请求内使用，不作为共享状态。
 * </p>
 */
@Getter
public class SemanticAssetRoutingException extends ResponseStatusException {

    private final String errorCode;

    /**
     * 创建路由异常。
     *
     * @param status HTTP 状态。
     * @param errorCode 稳定错误码。
     * @param message 可安全展示的中文消息。
     */
    public SemanticAssetRoutingException(HttpStatus status, String errorCode, String message) {
        super(status, message);
        this.errorCode = errorCode;
    }
}
