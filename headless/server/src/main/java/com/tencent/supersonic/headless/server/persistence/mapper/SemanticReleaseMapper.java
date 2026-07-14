package com.tencent.supersonic.headless.server.persistence.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.tencent.supersonic.headless.server.persistence.dataobject.SemanticReleaseDO;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/**
 * AI 语义建模发布主记录 Mapper。
 *
 * <p>
 * 职责说明：提供基础 CRUD 和发布行锁读取；所有 SQL 使用 MyBatis 参数绑定。并发说明：发布、 重试和回滚认领必须使用
 * {@link #selectByIdForUpdate(Long)}，避免多实例同时推进同一发布。
 * </p>
 */
@Mapper
public interface SemanticReleaseMapper extends BaseMapper<SemanticReleaseDO> {

    /**
     * 锁定发布主记录。
     *
     * @param id 发布 ID。
     * @return 被锁定的发布记录，不存在时返回 null。
     */
    @Select("SELECT * FROM s2_semantic_release WHERE id = #{id} FOR UPDATE")
    SemanticReleaseDO selectByIdForUpdate(@Param("id") Long id);
}
