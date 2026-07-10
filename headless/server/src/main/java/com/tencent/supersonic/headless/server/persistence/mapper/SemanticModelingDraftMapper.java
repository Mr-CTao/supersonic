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
 * 职责说明：除 MyBatis-Plus 基础查询外，仅暴露生成认领、幂等读取和乐观锁保存所需的原子 SQL。 所有 SQL 都使用绑定参数。并发说明：条件更新由数据库原子执行，不依赖 JVM
 * 本地锁，适用于多实例部署。
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
}
