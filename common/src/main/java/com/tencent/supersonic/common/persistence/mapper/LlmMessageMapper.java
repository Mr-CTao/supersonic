package com.tencent.supersonic.common.persistence.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.tencent.supersonic.common.persistence.dataobject.LlmMessageDO;
import org.apache.ibatis.annotations.Mapper;

/**
 * LLM 消息 Mapper。
 *
 * <p>
 * 职责说明：提供 `s2_llm_message` 的基础 CRUD 能力。并发说明：同会话顺序控制在 Service 层完成，Mapper 自身无共享状态。
 * </p>
 */
@Mapper
public interface LlmMessageMapper extends BaseMapper<LlmMessageDO> {
}
