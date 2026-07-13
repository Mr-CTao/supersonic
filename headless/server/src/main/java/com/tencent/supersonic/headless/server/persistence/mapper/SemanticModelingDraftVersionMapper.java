package com.tencent.supersonic.headless.server.persistence.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.tencent.supersonic.headless.server.persistence.dataobject.SemanticModelingDraftVersionDO;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/**
 * AI 语义建模草稿版本 Mapper。
 *
 * <p>
 * 职责说明：只为版本快照提供新增、只读查询和阶段 4 AI 修订幂等重放查询能力。并发说明：版本唯一性由数据库唯一约束保证，Service 不对版本记录执行覆盖更新。
 * </p>
 */
@Mapper
public interface SemanticModelingDraftVersionMapper
        extends BaseMapper<SemanticModelingDraftVersionDO> {

    /**
     * 按草稿和修订请求幂等键查询既有不可变版本。
     *
     * @param draftId 草稿 ID。
     * @param requestIdempotencyKey AI 修订请求幂等键。
     * @return 已创建的版本；未命中时返回 null。
     */
    @Select("SELECT * FROM s2_semantic_modeling_draft_version "
            + "WHERE draft_id = #{draftId} AND request_idempotency_key = #{requestIdempotencyKey}")
    SemanticModelingDraftVersionDO selectByDraftIdAndRequestIdempotencyKey(
            @Param("draftId") Long draftId,
            @Param("requestIdempotencyKey") String requestIdempotencyKey);
}
