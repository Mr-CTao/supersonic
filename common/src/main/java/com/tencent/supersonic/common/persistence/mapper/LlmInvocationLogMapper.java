package com.tencent.supersonic.common.persistence.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.tencent.supersonic.common.llm.LlmInvocationLogQueryReq;
import com.tencent.supersonic.common.persistence.dataobject.LlmInvocationLogDO;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

/**
 * LLM 调用日志 Mapper。
 *
 * <p>
 * 职责说明：提供 `s2_llm_invocation_log` 的基础 CRUD，以及在数据库侧完成会话所有者权限过滤的日志查询。
 * 并发说明：每次调用独立插入日志行，查询只使用绑定参数且 Mapper 不保存业务状态。
 * </p>
 */
@Mapper
public interface LlmInvocationLogMapper extends BaseMapper<LlmInvocationLogDO> {

    /**
     * 查询当前用户可见的调用日志。
     *
     * <p>
     * 普通用户通过相关 {@code EXISTS} 在数据库内校验会话创建者，避免先加载该用户全部会话 ID，
     * 从而消除大集合内存占用和 PostgreSQL bind 参数上限风险。超级管理员传入 null 所有者，动态 SQL
     * 会完全移除权限子句。所有筛选和数量限制均使用 MyBatis 绑定参数，不拼接客户端输入。
     * </p>
     *
     * @param request 日志筛选条件，可为空。
     * @param createdBy 普通用户名称；超级管理员传 null。
     * @param limit 最大返回数量，由服务端限制为 1 至 200。
     * @return 按创建时间和 ID 倒序排列的可见日志。
     */
    @Select({"<script>", "SELECT log.* FROM s2_llm_invocation_log log WHERE 1 = 1",
            "<if test='createdBy != null and createdBy != &quot;&quot;'>",
            "AND EXISTS (SELECT 1 FROM s2_llm_conversation conversation",
            "WHERE conversation.id = log.conversation_id",
            "AND conversation.created_by = #{createdBy})", "</if>",
            "<if test='request != null and request.providerType != null and request.providerType != &quot;&quot;'>",
            "AND log.provider_type = #{request.providerType}", "</if>",
            "<if test='request != null and request.modelName != null and request.modelName != &quot;&quot;'>",
            "AND log.model_name = #{request.modelName}", "</if>",
            "<if test='request != null and request.status != null and request.status != &quot;&quot;'>",
            "AND log.status = #{request.status}", "</if>",
            "<if test='request != null and request.errorCode != null and request.errorCode != &quot;&quot;'>",
            "AND log.error_code = #{request.errorCode}", "</if>",
            "<if test='request != null and request.conversationId != null'>",
            "AND log.conversation_id = #{request.conversationId}", "</if>",
            "<if test='request != null and request.startTime != null and request.startTime != &quot;&quot;'>",
            "AND log.created_at &gt;= #{request.startTime}", "</if>",
            "<if test='request != null and request.endTime != null and request.endTime != &quot;&quot;'>",
            "AND log.created_at &lt;= #{request.endTime}", "</if>",
            "ORDER BY log.created_at DESC, log.id DESC", "LIMIT #{limit}", "</script>"})
    List<LlmInvocationLogDO> selectAccessibleLogs(
            @Param("request") LlmInvocationLogQueryReq request,
            @Param("createdBy") String createdBy, @Param("limit") int limit);
}
