package com.tencent.supersonic.common.persistence.dataobject;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.util.Date;

/**
 * LLM 会话持久化对象。
 *
 * <p>
 * 职责说明：映射 `s2_llm_conversation`，保存业务会话、选用模型、业务对象和摘要，为 DeepSeek 无状态多轮调用提供本地上下文。
 * 并发说明：对象本身无共享状态；同会话消息顺序由服务层 conversationId 锁和数据库唯一约束共同保护。
 * </p>
 */
@Data
@TableName("s2_llm_conversation")
public class LlmConversationDO {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String conversationType;

    private Integer chatModelId;

    private String providerType;

    private String modelName;

    private String businessId;

    private String status;

    private String summary;

    private String createdBy;

    private Date createdAt;

    private Date updatedAt;
}
