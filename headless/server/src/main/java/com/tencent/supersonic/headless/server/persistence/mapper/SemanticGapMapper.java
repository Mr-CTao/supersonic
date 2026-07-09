package com.tencent.supersonic.headless.server.persistence.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.tencent.supersonic.headless.server.persistence.dataobject.SemanticGapDO;
import org.apache.ibatis.annotations.Mapper;

/**
 * 语义缺口 Mapper。
 *
 * <p>职责说明：提供 `s2_semantic_gap` 的基础 CRUD 能力。并发说明：Mapper 不缓存业务状态；同类缺口归并由 Service
 * 基于 normalized question 的分段锁串行化。</p>
 */
@Mapper
public interface SemanticGapMapper extends BaseMapper<SemanticGapDO> {
}
