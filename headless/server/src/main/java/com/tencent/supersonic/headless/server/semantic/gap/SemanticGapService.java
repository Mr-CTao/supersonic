package com.tencent.supersonic.headless.server.semantic.gap;

import com.github.pagehelper.PageInfo;
import com.tencent.supersonic.headless.server.persistence.dataobject.SemanticGapDO;

/**
 * 语义缺口服务接口。
 *
 * <p>
 * 职责说明：提供缺口事件采集、列表查询、详情、忽略、重新打开和阶段 2 草稿占位能力。并发说明：同类缺口归并必须由实现类保证 occurrence_count、last_seen_at 和
 * priority_score 的一致性。
 * </p>
 */
public interface SemanticGapService {

    /**
     * 采集一次语义缺口信号并按轻量规则聚合。
     *
     * @param eventReq 问答失败、执行异常、用户负反馈或回退事件。
     * @return 创建或更新后的语义缺口。
     * @throws IllegalArgumentException 当问题文本为空且无法从 queryId 反查时抛出。
     *
     * @example semanticGapService.capture(SemanticGapEventReq.builder().question("库存占用").build())
     */
    SemanticGapDO capture(SemanticGapEventReq eventReq);

    /**
     * 异步提交一次语义缺口采集任务。
     *
     * @param eventReq 问答失败、执行异常、用户负反馈或回退事件；调用后由后台任务消费。
     *
     *        <p>
     *        设计取舍：该方法用于 Chat BI 主链路，提交失败或任务执行失败只记录日志，不反向影响问答/反馈接口。 同步
     *        {@link #capture(SemanticGapEventReq)} 保留给异步任务内部和单元测试直接断言。
     *        </p>
     */
    void captureAsync(SemanticGapEventReq eventReq);

    /**
     * 分页查询语义缺口池。
     *
     * @param queryReq 筛选和分页条件。
     * @return 分页缺口列表。
     */
    PageInfo<SemanticGapDO> query(SemanticGapQueryReq queryReq);

    /**
     * 查询缺口详情。
     *
     * @param id 缺口 ID。
     * @return 缺口详情；不存在时返回 null。
     */
    SemanticGapDO get(Long id);

    /**
     * 忽略缺口。
     *
     * @param id 缺口 ID。
     * @param req 忽略原因。
     * @param operator 操作人。
     * @return 更新后的缺口。
     */
    SemanticGapDO ignore(Long id, SemanticGapActionReq req, String operator);

    /**
     * 重新打开已忽略缺口。
     *
     * @param id 缺口 ID。
     * @param operator 操作人。
     * @return 更新后的缺口。
     */
    SemanticGapDO reopen(Long id, String operator);

    /**
     * 在关联 AI 语义资产完整发布后把缺口标记为已发布。
     *
     * <p>
     * 该操作由阶段 5 发布编排调用，重复调用保持 RELEASED，不向客户端开放任意状态写入。
     * </p>
     *
     * @param id 缺口 ID。
     * @param operator 发布管理员。
     */
    void markReleased(Long id, String operator);

    /**
     * 在关联 AI 新增对象完整回滚后将缺口重新放回治理队列。
     *
     * @param id 缺口 ID。
     * @param operator 回滚管理员。
     */
    void markReopenedAfterRollback(Long id, String operator);

    /**
     * 阶段 2 草稿占位入口。
     *
     * @param id 缺口 ID。
     * @return 未启用提示，不调用任何 LLM。
     */
    SemanticGapDraftResp createDraftPlaceholder(Long id);
}
