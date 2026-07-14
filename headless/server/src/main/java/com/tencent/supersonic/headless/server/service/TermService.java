package com.tencent.supersonic.headless.server.service;

import com.tencent.supersonic.common.pojo.User;
import com.tencent.supersonic.headless.api.pojo.request.MetaBatchReq;
import com.tencent.supersonic.headless.api.pojo.request.TermReq;
import com.tencent.supersonic.headless.api.pojo.response.TermResp;

import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 正式语义术语管理服务。
 *
 * <p>
 * 职责说明：通过现有业务校验和持久化链路管理域级术语与维度/指标关联。阶段 5 仅复用本接口， 不直接写术语元数据表。实现由 Spring 单例托管，不在接口层保存共享状态。
 * </p>
 */
public interface TermService {

    /**
     * 通过现有术语管理能力创建或更新术语，并返回持久化后的正式对象。
     *
     * <p>
     * 原有控制器可以忽略返回值；阶段 5 发布编排使用正式 ID 记录发布步骤和限定回滚范围。
     * </p>
     *
     * @param termSetReq 术语请求。
     * @param user 当前操作者。
     * @return 持久化后的术语响应，包含正式术语 ID。
     */
    TermResp saveOrUpdate(TermReq termSetReq, User user);

    /**
     * 删除一个正式术语。
     *
     * @param id 术语 ID。
     */
    void delete(Long id);

    /**
     * 批量删除正式术语。
     *
     * @param metaBatchReq 只包含待删除术语 ID 的批量请求。
     * @throws RuntimeException ID 集合为空或持久化删除失败。
     */
    void deleteBatch(MetaBatchReq metaBatchReq);

    /**
     * 查询主题域内术语。
     *
     * @param domainId 主题域 ID。
     * @param queryKey 可选名称、描述或别名关键词。
     * @return 匹配的正式术语列表。
     */
    List<TermResp> getTerms(Long domainId, String queryKey);

    /**
     * 批量查询多个主题域的术语并按主题域分组。
     *
     * @param domainIds 主题域 ID 集合。
     * @return 主题域 ID 到术语列表的映射；输入为空时返回空映射。
     */
    Map<Long, List<TermResp>> getTermSets(Set<Long> domainIds);
}
