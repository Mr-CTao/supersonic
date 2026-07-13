package com.tencent.supersonic.headless.server.persistence.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.tencent.supersonic.headless.server.persistence.dataobject.SemanticModelingRevisionAttemptDO;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.Date;

/**
 * AI 语义建模草稿修订尝试 Mapper。
 *
 * <p>
 * 职责说明：提供草稿级幂等查询、活动租约查询、行锁读取和终态条件更新。所有 SQL 均使用绑定参数；
 * 并发正确性由草稿主记录行锁、{@code (draft_id, idempotency_key)} 唯一键和 {@code (draft_id, active_marker)}
 * 唯一键共同保证，不依赖 JVM 本地锁。
 * </p>
 */
@Mapper
public interface SemanticModelingRevisionAttemptMapper
        extends BaseMapper<SemanticModelingRevisionAttemptDO> {

    /**
     * 读取数据库事务时间，避免多实例主机时钟偏差造成租约提前过期。
     *
     * @return 当前数据库时间。
     */
    @Select("SELECT CURRENT_TIMESTAMP")
    Date selectCurrentTimestamp();

    /**
     * 查询草稿内指定幂等键的既有修订尝试。
     *
     * @param draftId 草稿 ID。
     * @param idempotencyKey 修订请求幂等键。
     * @return 已存在的尝试；未命中时返回 null。
     */
    @Select("SELECT * FROM s2_semantic_modeling_revision_attempt "
            + "WHERE draft_id = #{draftId} AND idempotency_key = #{idempotencyKey}")
    SemanticModelingRevisionAttemptDO selectByDraftIdAndIdempotencyKey(
            @Param("draftId") Long draftId, @Param("idempotencyKey") String idempotencyKey);

    /**
     * 查询草稿当前活动修订租约。
     *
     * @param draftId 草稿 ID。
     * @return {@code active_marker = 1} 的尝试；不存在时返回 null。
     */
    @Select("SELECT * FROM s2_semantic_modeling_revision_attempt "
            + "WHERE draft_id = #{draftId} AND active_marker = 1")
    SemanticModelingRevisionAttemptDO selectActiveByDraftId(@Param("draftId") Long draftId);

    /**
     * 按主键锁定修订尝试，供完成修订的短事务使用。
     *
     * @param id 修订尝试 ID。
     * @return 被锁定的尝试；不存在时返回 null。
     */
    @Select("SELECT * FROM s2_semantic_modeling_revision_attempt WHERE id = #{id} FOR UPDATE")
    SemanticModelingRevisionAttemptDO selectByIdForUpdate(@Param("id") Long id);

    /**
     * 把过期 RUNNING 租约原子结束为 SYSTEM_FAILED。
     *
     * @param id 修订尝试 ID。
     * @param errorCode 脱敏错误码。
     * @param updatedBy 更新人。
     * @param now 完成时间。
     * @return 受影响行数；1 表示成功释放，0 表示状态已被其他事务改变。
     */
    @Update("UPDATE s2_semantic_modeling_revision_attempt "
            + "SET status = 'SYSTEM_FAILED', active_marker = NULL, error_code = #{errorCode}, "
            + "updated_by = #{updatedBy}, updated_at = #{now}, finished_at = #{now} "
            + "WHERE id = #{id} AND status = 'RUNNING' AND active_marker = 1")
    int expire(@Param("id") Long id, @Param("errorCode") String errorCode,
            @Param("updatedBy") String updatedBy, @Param("now") Date now);

    /**
     * 把 Provider 失败的活动尝试原子结束并释放租约。
     *
     * @param id 修订尝试 ID。
     * @param errorCode 脱敏错误码。
     * @param updatedBy 更新人。
     * @param now 完成时间。
     * @return 受影响行数；1 表示成功，0 表示该尝试已进入终态。
     */
    @Update("UPDATE s2_semantic_modeling_revision_attempt "
            + "SET status = 'FAILED', active_marker = NULL, error_code = #{errorCode}, "
            + "updated_by = #{updatedBy}, updated_at = #{now}, finished_at = #{now} "
            + "WHERE id = #{id} AND status = 'RUNNING' AND active_marker = 1")
    int markFailed(@Param("id") Long id, @Param("errorCode") String errorCode,
            @Param("updatedBy") String updatedBy, @Param("now") Date now);

    /**
     * 绑定新版本并把活动尝试原子结束为 SUCCEEDED。
     *
     * @param id 修订尝试 ID。
     * @param resultVersionId 新不可变版本 ID。
     * @param resultVersionNo 新版本号。
     * @param llmConversationId Provider 调用关联会话 ID。
     * @param updatedBy 更新人。
     * @param now 完成时间。
     * @return 受影响行数；1 表示成功，0 表示租约已被其他事务结束。
     */
    @Update("UPDATE s2_semantic_modeling_revision_attempt "
            + "SET status = 'SUCCEEDED', active_marker = NULL, result_version_id = #{resultVersionId}, "
            + "result_version_no = #{resultVersionNo}, llm_conversation_id = #{llmConversationId}, "
            + "error_code = NULL, updated_by = #{updatedBy}, updated_at = #{now}, "
            + "finished_at = #{now} WHERE id = #{id} AND status = 'RUNNING' "
            + "AND active_marker = 1")
    int markSucceeded(@Param("id") Long id, @Param("resultVersionId") Long resultVersionId,
            @Param("resultVersionNo") Integer resultVersionNo,
            @Param("llmConversationId") Long llmConversationId,
            @Param("updatedBy") String updatedBy, @Param("now") Date now);
}
