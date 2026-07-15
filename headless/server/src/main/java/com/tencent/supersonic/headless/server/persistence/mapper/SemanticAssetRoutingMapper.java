package com.tencent.supersonic.headless.server.persistence.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.tencent.supersonic.headless.server.persistence.dataobject.SemanticAssetRoutingDO;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.Date;

/**
 * 语义资产路由 Mapper。
 *
 * <p>
 * 职责：提供幂等读取、数据库时间、租约认领、成功/失败完成、重新分析和乐观锁确认的原子 SQL。 所有外部值均使用 MyBatis 绑定参数，Mapper 不缓存业务状态，适用于多实例并发。
 * </p>
 */
@Mapper
public interface SemanticAssetRoutingMapper extends BaseMapper<SemanticAssetRoutingDO> {

    /**
     * 按创建者和幂等键读取路由。
     *
     * @param createdBy 创建者。
     * @param idempotencyKey 幂等键。
     * @return 已有路由或 null。
     */
    @Select("SELECT * FROM s2_semantic_asset_route WHERE created_by = #{createdBy} "
            + "AND idempotency_key = #{idempotencyKey}")
    SemanticAssetRoutingDO selectByIdempotencyKey(@Param("createdBy") String createdBy,
            @Param("idempotencyKey") String idempotencyKey);

    /**
     * 获取数据库当前时间，作为租约与审计唯一时钟。
     *
     * @return 数据库时间。
     */
    @Select("SELECT CURRENT_TIMESTAMP")
    Date selectDatabaseTime();

    /**
     * 原子认领待分析、失败重试或租约过期的路由。
     *
     * @param id 路由 ID。
     * @param now 数据库时间。
     * @param leaseExpiresAt 租约截止时间。
     * @param updatedBy 操作者。
     * @return 1 表示认领成功。
     */
    @Update("UPDATE s2_semantic_asset_route SET status = 'ANALYZING', "
            + "analysis_started_at = #{now}, lease_expires_at = #{leaseExpiresAt}, "
            + "failure_code = NULL, failure_message = NULL, lock_version = lock_version + 1, "
            + "updated_by = #{updatedBy}, updated_at = #{now} WHERE id = #{id} AND "
            + "(status = 'PENDING' OR status = 'FAILED' OR "
            + "(status = 'ANALYZING' AND (lease_expires_at IS NULL "
            + "OR lease_expires_at < #{now})))")
    int claimAnalysis(@Param("id") Long id, @Param("now") Date now,
            @Param("leaseExpiresAt") Date leaseExpiresAt, @Param("updatedBy") String updatedBy);

    /**
     * 使用认领后的锁版本完成分析。
     *
     * @return 1 表示完成成功，0 表示租约或版本已变化。
     */
    @Update("UPDATE s2_semantic_asset_route SET status = 'SUCCEEDED', "
            + "candidate_snapshot = #{candidateSnapshot}, rule_evidence = #{ruleEvidence}, "
            + "llm_advice = #{llmAdvice}, recommended_action = #{recommendedAction}, "
            + "recommended_candidate_type = #{candidateType}, recommended_candidate_id = #{candidateId}, "
            + "recommended_candidate_version = #{candidateVersion}, covered_capabilities = #{covered}, "
            + "missing_capabilities = #{missing}, result_operations = #{operations}, "
            + "business_questions = #{questions}, decision_source = #{decisionSource}, "
            + "llm_conversation_id = #{llmConversationId}, "
            + "analysis_completed_at = #{now}, lease_expires_at = NULL, expires_at = #{expiresAt}, "
            + "lock_version = lock_version + 1, updated_by = #{updatedBy}, updated_at = #{now} "
            + "WHERE id = #{id} AND status = 'ANALYZING' AND lock_version = #{lockVersion}")
    int completeAnalysis(@Param("id") Long id, @Param("lockVersion") Integer lockVersion,
            @Param("candidateSnapshot") String candidateSnapshot,
            @Param("ruleEvidence") String ruleEvidence, @Param("llmAdvice") String llmAdvice,
            @Param("recommendedAction") String recommendedAction,
            @Param("candidateType") String candidateType, @Param("candidateId") Long candidateId,
            @Param("candidateVersion") Long candidateVersion, @Param("covered") String covered,
            @Param("missing") String missing, @Param("operations") String operations,
            @Param("questions") String questions, @Param("decisionSource") String decisionSource,
            @Param("llmConversationId") Long llmConversationId, @Param("now") Date now,
            @Param("expiresAt") Date expiresAt, @Param("updatedBy") String updatedBy);

    /**
     * 把当前租约安全结束为失败态。
     *
     * @return 受影响行数。
     */
    @Update("UPDATE s2_semantic_asset_route SET status = 'FAILED', failure_code = #{failureCode}, "
            + "failure_message = #{failureMessage}, analysis_completed_at = #{now}, "
            + "llm_conversation_id = COALESCE(#{llmConversationId}, llm_conversation_id), "
            + "lease_expires_at = NULL, lock_version = lock_version + 1, updated_by = #{updatedBy}, "
            + "updated_at = #{now} WHERE id = #{id} AND status = 'ANALYZING' "
            + "AND lock_version = #{lockVersion}")
    int failAnalysis(@Param("id") Long id, @Param("lockVersion") Integer lockVersion,
            @Param("failureCode") String failureCode,
            @Param("failureMessage") String failureMessage, @Param("now") Date now,
            @Param("llmConversationId") Long llmConversationId,
            @Param("updatedBy") String updatedBy);

    /**
     * 使用分析版本和乐观锁持久化最终确认。
     *
     * @return 1 表示确认成功。
     */
    @Update("UPDATE s2_semantic_asset_route SET confirmed_action = #{action}, "
            + "confirmed_candidate_type = #{candidateType}, confirmed_candidate_id = #{candidateId}, "
            + "confirmed_candidate_version = #{candidateVersion}, business_answers = #{answers}, "
            + "override_reason = #{overrideReason}, confirmed_by = #{confirmedBy}, confirmed_at = #{now}, "
            + "confirmation_idempotency_key = #{idempotencyKey}, "
            + "confirmation_request_fingerprint = #{fingerprint}, lock_version = lock_version + 1, "
            + "updated_by = #{confirmedBy}, updated_at = #{now} WHERE id = #{id} AND status = 'SUCCEEDED' "
            + "AND analysis_version = #{analysisVersion} AND lock_version = #{lockVersion} "
            + "AND confirmed_action IS NULL AND (expires_at IS NULL OR expires_at > #{now})")
    int confirm(@Param("id") Long id, @Param("analysisVersion") Integer analysisVersion,
            @Param("lockVersion") Integer lockVersion, @Param("action") String action,
            @Param("candidateType") String candidateType, @Param("candidateId") Long candidateId,
            @Param("candidateVersion") Long candidateVersion, @Param("answers") String answers,
            @Param("overrideReason") String overrideReason,
            @Param("idempotencyKey") String idempotencyKey,
            @Param("fingerprint") String fingerprint, @Param("confirmedBy") String confirmedBy,
            @Param("now") Date now);

    /**
     * 原子消费已确认的新建路由；一个路由只能绑定一个草稿。
     *
     * @return 受影响行数，0 表示路由已变化、过期或已消费。
     */
    @Update("UPDATE s2_semantic_asset_route SET consumed_by_draft_id = #{draftId}, "
            + "consumed_at = CURRENT_TIMESTAMP, updated_by = #{updatedBy}, "
            + "updated_at = CURRENT_TIMESTAMP WHERE id = #{id} AND status = 'SUCCEEDED' "
            + "AND confirmed_action = 'CREATE_NEW' AND confirmed_candidate_id IS NULL "
            + "AND consumed_by_draft_id IS NULL "
            + "AND (expires_at IS NULL OR expires_at > CURRENT_TIMESTAMP)")
    int consumeCreateRoute(@Param("id") Long id, @Param("draftId") Long draftId,
            @Param("updatedBy") String updatedBy);

    /**
     * 原子消费已确认的增强路由，并再次比较目标资产类型、ID 和版本快照。
     *
     * @return 受影响行数，0 表示路由或目标快照已变化、过期或已消费。
     */
    @Update("UPDATE s2_semantic_asset_route SET consumed_by_draft_id = #{draftId}, "
            + "consumed_at = CURRENT_TIMESTAMP, updated_by = #{updatedBy}, "
            + "updated_at = CURRENT_TIMESTAMP WHERE id = #{id} AND status = 'SUCCEEDED' "
            + "AND confirmed_action = 'EXTEND_EXISTING' "
            + "AND confirmed_candidate_type = #{targetType} "
            + "AND confirmed_candidate_id = #{targetId} "
            + "AND confirmed_candidate_version = #{targetVersion} "
            + "AND consumed_by_draft_id IS NULL "
            + "AND (expires_at IS NULL OR expires_at > CURRENT_TIMESTAMP)")
    int consumeExtendRoute(@Param("id") Long id, @Param("draftId") Long draftId,
            @Param("targetType") String targetType, @Param("targetId") Long targetId,
            @Param("targetVersion") Long targetVersion, @Param("updatedBy") String updatedBy);

    /**
     * 保存澄清答案并开启新的分析版本。
     *
     * @return 1 表示重新分析已准备。
     */
    @Update("UPDATE s2_semantic_asset_route SET status = 'PENDING', business_answers = #{answers}, "
            + "business_questions = NULL, recommended_action = NULL, decision_source = NULL, "
            + "llm_advice = NULL, llm_conversation_id = NULL, "
            + "confirmation_idempotency_key = #{idempotencyKey}, "
            + "confirmation_request_fingerprint = #{fingerprint}, "
            + "request_fingerprint = #{reanalysisFingerprint}, "
            + "analysis_version = analysis_version + 1, "
            + "lock_version = lock_version + 1, updated_by = #{updatedBy}, updated_at = #{now} "
            + "WHERE id = #{id} AND status = 'SUCCEEDED' AND analysis_version = #{analysisVersion} "
            + "AND lock_version = #{lockVersion} AND confirmed_action IS NULL "
            + "AND (expires_at IS NULL OR expires_at > #{now})")
    int prepareReanalysis(@Param("id") Long id, @Param("analysisVersion") Integer analysisVersion,
            @Param("lockVersion") Integer lockVersion, @Param("answers") String answers,
            @Param("idempotencyKey") String idempotencyKey,
            @Param("fingerprint") String fingerprint,
            @Param("reanalysisFingerprint") String reanalysisFingerprint,
            @Param("updatedBy") String updatedBy,
            @Param("now") Date now);

    /**
     * 把未确认且已超过有效期的成功分析标记为过期。
     *
     * @return 受影响行数。
     */
    @Update("UPDATE s2_semantic_asset_route SET status = 'EXPIRED', lock_version = lock_version + 1, "
            + "updated_at = #{now} WHERE id = #{id} AND status = 'SUCCEEDED' "
            + "AND confirmed_action IS NULL AND expires_at < #{now}")
    int expire(@Param("id") Long id, @Param("now") Date now);
}
