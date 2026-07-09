package com.tencent.supersonic.common.persistence.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.tencent.supersonic.common.persistence.dataobject.LlmModelCapabilityDO;
import org.apache.ibatis.annotations.Mapper;

/**
 * LLM 模型能力 Mapper。
 *
 * <p>
 * 职责说明：提供 `s2_llm_model_capability` 的 MyBatis-Plus 基础 CRUD 能力。并发说明：Mapper 为 MyBatis 代理对象，
 * 不保存业务状态，线程安全由 MyBatis/Spring 管理。
 * </p>
 */
@Mapper
public interface LlmModelCapabilityMapper extends BaseMapper<LlmModelCapabilityDO> {
}
