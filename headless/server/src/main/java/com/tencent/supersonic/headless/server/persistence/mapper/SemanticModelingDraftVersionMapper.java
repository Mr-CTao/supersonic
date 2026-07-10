package com.tencent.supersonic.headless.server.persistence.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.tencent.supersonic.headless.server.persistence.dataobject.SemanticModelingDraftVersionDO;
import org.apache.ibatis.annotations.Mapper;

/**
 * AI 语义建模草稿版本 Mapper。
 *
 * <p>
 * 职责说明：只为版本快照提供新增和只读查询能力。并发说明：版本唯一性由数据库唯一约束保证， Service 不对版本记录执行覆盖更新。
 * </p>
 */
@Mapper
public interface SemanticModelingDraftVersionMapper
        extends BaseMapper<SemanticModelingDraftVersionDO> {
}
