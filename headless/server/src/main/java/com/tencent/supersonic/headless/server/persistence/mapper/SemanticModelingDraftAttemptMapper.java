package com.tencent.supersonic.headless.server.persistence.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.tencent.supersonic.headless.server.persistence.dataobject.SemanticModelingDraftAttemptDO;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.Date;

/**
 * AI 语义建模草稿生成尝试 Mapper。
 *
 * <p>
 * 职责说明：提供 attempt 基础查询、幂等读取和数据库原子认领。SQL 只使用绑定参数；大文本诊断字段 由业务层按需写入，不参与列表查询。并发说明：认领条件同时校验状态，确保多实例下单个
 * attempt 只有一个 Worker 获得执行权。
 * </p>
 */
@Mapper
public interface SemanticModelingDraftAttemptMapper
        extends BaseMapper<SemanticModelingDraftAttemptDO> {

    /**
     * 按操作者和幂等键查询既有尝试。
     *
     * @param createdBy 操作者。
     * @param idempotencyKey 幂等键。
     * @return 已存在的尝试，未命中返回 null。
     */
    @Select("SELECT * FROM s2_semantic_modeling_draft_attempt "
            + "WHERE created_by = #{createdBy} AND idempotency_key = #{idempotencyKey}")
    SemanticModelingDraftAttemptDO selectByIdempotencyKey(@Param("createdBy") String createdBy,
            @Param("idempotencyKey") String idempotencyKey);

    /**
     * 原子认领排队中的生成尝试。
     *
     * @param draftId 草稿 ID。
     * @param attemptNo 尝试序号。
     * @param startedAt 开始时间。
     * @param updatedAt 更新时间。
     * @param updatedBy 操作者。
     * @return 1 表示成功认领，0 表示已被其他 Worker 处理。
     */
    @Update("UPDATE s2_semantic_modeling_draft_attempt "
            + "SET status = 'GENERATING', started_at = #{startedAt}, updated_at = #{updatedAt}, "
            + "updated_by = #{updatedBy} WHERE draft_id = #{draftId} "
            + "AND attempt_no = #{attemptNo} AND status = 'QUEUED'")
    int claimAttempt(@Param("draftId") Long draftId, @Param("attemptNo") Integer attemptNo,
            @Param("startedAt") Date startedAt, @Param("updatedAt") Date updatedAt,
            @Param("updatedBy") String updatedBy);
}
