package com.tencent.supersonic.headless.server.persistence.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.tencent.supersonic.headless.server.persistence.dataobject.SemanticGapDO;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

/**
 * 语义缺口 Mapper。
 *
 * <p>
 * 职责说明：提供 `s2_semantic_gap` 的基础 CRUD 能力。并发说明：Mapper 不缓存业务状态；同类缺口归并由 Service 基于 normalized question
 * 的分段锁串行化。
 * </p>
 */
@Mapper
public interface SemanticGapMapper extends BaseMapper<SemanticGapDO> {

    /**
     * 在创建 Gap 来源草稿的短事务中锁定缺口行。
     *
     * @param id 缺口 ID。
     * @return 被锁定的缺口，不存在时返回 null。
     */
    @Select("SELECT * FROM s2_semantic_gap WHERE id = #{id} FOR UPDATE")
    SemanticGapDO selectByIdForUpdate(@Param("id") Long id);

    /**
     * 按稳定 ID 顺序批量锁定超时恢复涉及的 Gap。
     *
     * @param ids 候选 Gap ID，调用方保证非空。
     * @return 被锁定的 Gap；返回值仅用于确认 SQL 已完成，状态由后续条件更新处理。
     */
    @Select({"<script>", "SELECT * FROM s2_semantic_gap WHERE id IN",
                    "<foreach collection='ids' item='id' open='(' separator=',' close=')'>",
                    "#{id}", "</foreach>", "ORDER BY id FOR UPDATE", "</script>"})
    List<SemanticGapDO> selectByIdsForUpdate(@Param("ids") List<Long> ids);
}
