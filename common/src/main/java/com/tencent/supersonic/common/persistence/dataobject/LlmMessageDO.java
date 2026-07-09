package com.tencent.supersonic.common.persistence.dataobject;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.util.Date;

/**
 * LLM 消息持久化对象。
 *
 * <p>
 * 职责说明：映射 `s2_llm_message`，按顺序保存 system/user/assistant/tool 消息、DeepSeek `reasoning_content` 和预留
 * Tool Calls 原始 JSON。并发说明：同一 conversation 的 `message_order` 由服务层锁串行分配，数据库唯一索引用于阻断异常并发。
 * </p>
 */
@Data
@TableName("s2_llm_message")
public class LlmMessageDO {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long conversationId;

    private String role;

    private String content;

    private String reasoningContent;

    private String contentType;

    private String toolCalls;

    private String toolCallId;

    private Integer tokenCount;

    private Integer messageOrder;

    private Date createdAt;
}
