package com.tencent.supersonic.common.persistence.dataobject;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.util.Date;

/**
 * LLM 模型能力持久化对象。
 *
 * <p>
 * 职责说明：映射 `s2_llm_model_capability`，记录当前 `s2_chat_model` 模型是否支持 JSON mode、Tool Calls、thinking、
 * 对话前缀续写、FIM 和上下文缓存。并发说明：该对象只承载单行数据，不持有共享状态。
 * </p>
 */
@Data
@TableName("s2_llm_model_capability")
public class LlmModelCapabilityDO {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Integer chatModelId;

    private String providerType;

    private String modelName;

    private Integer maxContextTokens;

    private Boolean supportStream;

    private Boolean supportJsonMode;

    private Boolean supportToolCalling;

    private Boolean supportThinking;

    private Boolean supportChatPrefixCompletion;

    private Boolean supportFimCompletion;

    private Boolean supportContextCache;

    private Boolean supportSystemPrompt;

    private Double recommendedTemperature;

    private String usageScene;

    private Boolean enabled;

    private Date createdAt;

    private Date updatedAt;
}
