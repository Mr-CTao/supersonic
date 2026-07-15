package com.tencent.supersonic.headless.api.pojo.response;

import lombok.Data;

/**
 * 语义模型最小健康摘要。
 *
 * <p>
 * 职责：向管理端聚合编译、Schema 缓存、词典和 Embedding 的最近状态，并保留最近一次 结构化问答失败的错误码与 traceId。该对象不包含 SQL、凭证或原始异常信息。
 */
@Data
public class ModelHealthResp {
    private Long modelId;
    private SemanticValidationStatus compileStatus = SemanticValidationStatus.SKIPPED;
    private Long lastValidatedAt;
    private String contentDigest;
    private RefreshStatus schemaCacheStatus = RefreshStatus.UNKNOWN;
    private RefreshStatus dictionaryStatus = RefreshStatus.UNKNOWN;
    private RefreshStatus embeddingStatus = RefreshStatus.UNKNOWN;
    private String lastErrorCode;
    private String lastTraceId;
    private String message;
}
