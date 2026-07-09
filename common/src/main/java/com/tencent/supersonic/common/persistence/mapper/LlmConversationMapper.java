package com.tencent.supersonic.common.persistence.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.tencent.supersonic.common.persistence.dataobject.LlmConversationDO;
import org.apache.ibatis.annotations.Mapper;

/**
 * LLM 会话 Mapper。
 *
 * <p>
 * 职责说明：提供 `s2_llm_conversation` 的基础 CRUD 能力。并发说明：Mapper 代理不保存业务状态，线程安全由 MyBatis/Spring 管理。
 * </p>
 */
@Mapper
public interface LlmConversationMapper extends BaseMapper<LlmConversationDO> {
}
