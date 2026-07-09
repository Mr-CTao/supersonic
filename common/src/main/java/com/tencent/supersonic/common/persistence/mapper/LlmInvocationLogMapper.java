package com.tencent.supersonic.common.persistence.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.tencent.supersonic.common.persistence.dataobject.LlmInvocationLogDO;
import org.apache.ibatis.annotations.Mapper;

/**
 * LLM 调用日志 Mapper。
 *
 * <p>
 * 职责说明：提供 `s2_llm_invocation_log` 的基础 CRUD 能力。并发说明：每次调用独立插入日志行，Mapper 不保存业务状态。
 * </p>
 */
@Mapper
public interface LlmInvocationLogMapper extends BaseMapper<LlmInvocationLogDO> {
}
