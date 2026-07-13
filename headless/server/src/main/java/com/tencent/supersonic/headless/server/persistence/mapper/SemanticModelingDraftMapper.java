package com.tencent.supersonic.headless.server.persistence.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.tencent.supersonic.headless.server.persistence.dataobject.SemanticModelingDraftDO;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.Date;

/**
 * AI 语义建模草稿 Mapper。
 *
 * <p>
 * 职责说明：除 MyBatis-Plus 基础查询外，仅暴露生成认领、幂等读取、乐观锁保存、AI 修订版本写入和提交待审批所需的原子 SQL。 所有 SQL
 * 都使用绑定参数，且不会写入正式语义资产表。并发说明：条件更新由数据库原子执行，不依赖 JVM 本地锁，适用于多实例部署。
 * </p>
 */
@Mapper
public interface SemanticModelingDraftMapper extends BaseMapper<SemanticModelingDraftDO> {

    /**
     * 按创建者和幂等键查询既有草稿。
     *
     * @param createdBy 创建者名称。
     * @param idempotencyKey 幂等键。
     * @return 已存在的草稿，未命中时返回 null。
     */
    @Select("SELECT * FROM s2_semantic_modeling_draft "
            + "WHERE created_by = #{createdBy} AND idempotency_key = #{idempotencyKey}")
    SemanticModelingDraftDO selectByIdempotencyKey(@Param("createdBy") String createdBy,
            @Param("idempotencyKey") String idempotencyKey);

    /**
     * 条件认领待生成草稿。
     *
     * @param id 草稿 ID。
     * @param startedAt 认领时间。
     * @param updatedAt 更新时间。
     * @return 受影响行数；1 表示成功认领，0 表示已被其他 Worker 处理。
     */
    @Update("UPDATE s2_semantic_modeling_draft SET generation_started_at = #{startedAt}, "
            + "updated_at = #{updatedAt} WHERE id = #{id} AND current_attempt_no = #{attemptNo} "
            + "AND status = 'GENERATING' AND generation_started_at IS NULL")
    int claimGeneration(@Param("id") Long id, @Param("attemptNo") Integer attemptNo,
            @Param("startedAt") Date startedAt, @Param("updatedAt") Date updatedAt);

    /**
     * 锁定草稿主记录，供同草稿重新生成的状态机事务使用。
     *
     * @param id 草稿 ID。
     * @return 被锁定的草稿，不存在时返回 null。
     */
    @Select("SELECT * FROM s2_semantic_modeling_draft WHERE id = #{id} FOR UPDATE")
    SemanticModelingDraftDO selectByIdForUpdate(@Param("id") Long id);

    /**
     * 使用乐观锁更新管理员保存的结构化草稿。
     *
     * @param id 草稿 ID。
     * @param expectedLockVersion 客户端读取到的锁版本。
     * @param draftJson 已通过校验的 JSON。
     * @param nextVersionNo 下一版本号。
     * @param updatedBy 更新人。
     * @param updatedAt 更新时间。
     * @return 受影响行数；0 表示版本冲突或记录状态不可保存。
     */
    @Update("UPDATE s2_semantic_modeling_draft SET draft_json = #{draftJson}, "
            + "current_version_no = #{nextVersionNo}, lock_version = lock_version + 1, "
            + "updated_by = #{updatedBy}, updated_at = #{updatedAt} WHERE id = #{id} "
            + "AND lock_version = #{expectedLockVersion} AND status = 'DRAFT'")
    int updateDraftWithVersion(@Param("id") Long id,
            @Param("expectedLockVersion") Integer expectedLockVersion,
            @Param("draftJson") String draftJson, @Param("nextVersionNo") Integer nextVersionNo,
            @Param("updatedBy") String updatedBy, @Param("updatedAt") Date updatedAt);

    /**
     * 使用客户端确认的当前版本和锁版本推进追加式恢复。
     *
     * @param id 草稿 ID。
     * @param expectedCurrentVersionNo 客户端确认的当前版本号。
     * @param expectedLockVersion 客户端确认的锁版本。
     * @param draftJson 历史快照原文。
     * @param nextVersionNo 连续的新版本号。
     * @param updatedBy 操作者。
     * @param updatedAt 更新时间。
     * @return 1 表示推进成功，0 表示状态或基线已变化。
     */
    @Update("UPDATE s2_semantic_modeling_draft SET draft_json = #{draftJson}, "
            + "current_version_no = #{nextVersionNo}, lock_version = lock_version + 1, "
            + "updated_by = #{updatedBy}, updated_at = #{updatedAt} WHERE id = #{id} "
            + "AND current_version_no = #{expectedCurrentVersionNo} "
            + "AND lock_version = #{expectedLockVersion} AND status = 'DRAFT'")
    int updateDraftForRestore(@Param("id") Long id,
            @Param("expectedCurrentVersionNo") Integer expectedCurrentVersionNo,
            @Param("expectedLockVersion") Integer expectedLockVersion,
            @Param("draftJson") String draftJson, @Param("nextVersionNo") Integer nextVersionNo,
            @Param("updatedBy") String updatedBy, @Param("updatedAt") Date updatedAt);

    /**
     * 基于指定当前版本原子写入 AI 修订后的完整草稿。
     *
     * <p>
     * 该方法只更新隔离草稿主记录；调用方必须在同一事务中插入不可变版本快照。使用当前版本号而非 JVM 锁，确保跨实例并发修订只有一个请求成功。
     * </p>
     *
     * @param id 草稿 ID。
     * @param expectedVersionNo AI 修订所基于的当前版本号。
     * @param draftJson 已通过结构与字段校验的完整草稿 JSON。
     * @param nextVersionNo 新版本号。
     * @param llmConversationId 生成本次修订的 Gateway 会话 ID。
     * @param updatedBy 修订人。
     * @param updatedAt 修订时间。
     * @return 受影响行数；0 表示版本或状态已变化。
     */
    @Update("UPDATE s2_semantic_modeling_draft SET draft_json = #{draftJson}, "
            + "current_version_no = #{nextVersionNo}, lock_version = lock_version + 1, "
            + "llm_conversation_id = #{llmConversationId}, updated_by = #{updatedBy}, "
            + "updated_at = #{updatedAt} WHERE id = #{id} AND status = 'DRAFT' "
            + "AND current_version_no = #{expectedVersionNo}")
    int updateDraftWithAiVersion(@Param("id") Long id,
            @Param("expectedVersionNo") Integer expectedVersionNo,
            @Param("draftJson") String draftJson, @Param("nextVersionNo") Integer nextVersionNo,
            @Param("llmConversationId") Long llmConversationId,
            @Param("updatedBy") String updatedBy, @Param("updatedAt") Date updatedAt);

    /**
     * 在最新版本验证通过后原子提交待审批。
     *
     * <p>
     * 本方法只记录阶段 5 的待审批交接状态，不执行审批、正式语义资产写入、发布或回滚。调用方必须先确认报告属于同一草稿和指定版本。
     * </p>
     *
     * @param id 草稿 ID。
     * @param expectedVersionNo 已通过验证且准备提交的版本号。
     * @param validationReportId 绑定的通过验证报告 ID。
     * @param idempotencyKey 提交请求幂等键。
     * @param submittedBy 提交人。
     * @param submittedAt 提交时间。
     * @return 受影响行数；0 表示草稿版本或状态已变化。
     */
    @Update("UPDATE s2_semantic_modeling_draft SET status = 'PENDING_APPROVAL', "
            + "submitted_validation_report_id = #{validationReportId}, "
            + "submission_idempotency_key = #{idempotencyKey}, submitted_by = #{submittedBy}, "
            + "submitted_at = #{submittedAt}, lock_version = lock_version + 1, "
            + "updated_by = #{submittedBy}, updated_at = #{submittedAt} WHERE id = #{id} "
            + "AND status = 'DRAFT' AND current_version_no = #{expectedVersionNo}")
    int submitForApproval(@Param("id") Long id,
            @Param("expectedVersionNo") Integer expectedVersionNo,
            @Param("validationReportId") Long validationReportId,
            @Param("idempotencyKey") String idempotencyKey,
            @Param("submittedBy") String submittedBy, @Param("submittedAt") Date submittedAt);
}
