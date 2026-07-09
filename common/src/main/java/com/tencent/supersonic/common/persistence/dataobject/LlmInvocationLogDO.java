package com.tencent.supersonic.common.persistence.dataobject;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.util.Date;

/**
 * LLM 调用日志持久化对象。
 *
 * <p>
 * 职责说明：映射 `s2_llm_invocation_log`，记录每次真实模型调用的模型、耗时、token、状态和脱敏错误摘要。安全说明： 不保存 API
 * Key，不保存完整未脱敏请求体。并发说明：每次调用写入独立日志行，不需要服务层互斥。
 * </p>
 */
@Data
@TableName("s2_llm_invocation_log")
public class LlmInvocationLogDO {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long conversationId;

    private Integer chatModelId;

    private String providerType;

    private String modelName;

    private String requestId;

    private Integer promptTokens;

    private Integer completionTokens;

    private Integer totalTokens;

    private Long latencyMs;

    private String status;

    private String errorCode;

    private String errorMessage;

    private String rawResponseRef;

    private Date createdAt;
}
