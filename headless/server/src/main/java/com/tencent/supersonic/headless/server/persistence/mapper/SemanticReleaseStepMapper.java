package com.tencent.supersonic.headless.server.persistence.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.tencent.supersonic.headless.server.persistence.dataobject.SemanticReleaseStepDO;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/**
 * AI 语义建模发布步骤 Mapper。
 *
 * <p>
 * 职责说明：提供步骤 CRUD 和发布内唯一步骤的行锁读取。并发说明：步骤认领事务使用 {@link #selectByKeyForUpdate(Long, String)}
 * 串行化失败重试，不依赖 JVM 本地锁。
 * </p>
 */
@Mapper
public interface SemanticReleaseStepMapper extends BaseMapper<SemanticReleaseStepDO> {

    /**
     * 按发布和稳定步骤键锁定步骤。
     *
     * @param releaseId 发布 ID。
     * @param stepKey 稳定步骤键。
     * @return 已有步骤，不存在时返回 null。
     */
    @Select("SELECT * FROM s2_semantic_release_step WHERE release_id = #{releaseId} "
            + "AND step_key = #{stepKey} FOR UPDATE")
    SemanticReleaseStepDO selectByKeyForUpdate(@Param("releaseId") Long releaseId,
            @Param("stepKey") String stepKey);
}
