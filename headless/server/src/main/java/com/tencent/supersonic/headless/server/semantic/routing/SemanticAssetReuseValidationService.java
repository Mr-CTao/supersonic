package com.tencent.supersonic.headless.server.semantic.routing;

import com.tencent.supersonic.common.pojo.DataEvent;
import com.tencent.supersonic.common.pojo.DataItem;
import com.tencent.supersonic.common.pojo.User;
import com.tencent.supersonic.common.pojo.enums.EventType;
import com.tencent.supersonic.common.pojo.enums.TypeEnums;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 已有语义资产复用校验协调器。
 *
 * <p>职责：在用户确认 {@link SemanticAssetRouteAction#REUSE_EXISTING} 时，重新校验候选的
 * VIEWER 权限和内容版本，并在确认 CAS 成功后复用正式资产已有的 {@link DataEvent} 链路刷新
 * Schema 缓存、词典和 Embedding。该服务不修改正式语义资产，也不执行 SQL；是否发布事件由数据库
 * CAS 的唯一胜者决定，因此不使用进程级锁。</p>
 */
@Service
@Slf4j
public class SemanticAssetReuseValidationService {

    private final SemanticAssetRoutingPermissionService permissionService;
    private final ApplicationEventPublisher eventPublisher;

    /**
     * 创建已有资产复用校验协调器。
     *
     * @param permissionService 候选权限与内容版本复核服务。
     * @param eventPublisher 现有知识刷新事件发布器。
     */
    public SemanticAssetReuseValidationService(
            SemanticAssetRoutingPermissionService permissionService,
            ApplicationEventPublisher eventPublisher) {
        this.permissionService = permissionService;
        this.eventPublisher = eventPublisher;
    }

    /**
     * 在确认 CAS 前校验复用候选。
     *
     * @param candidate 本次分析快照中的候选，必须是 MODEL。
     * @param user 当前确认用户。
     * @throws SemanticAssetRoutingException 候选类型、权限或内容版本不再满足复用条件时抛出。
     */
    public void validateCandidate(SemanticAssetCandidate candidate, User user) {
        if (candidate == null || candidate.getAssetId() == null
                || !TypeEnums.MODEL.name().equalsIgnoreCase(candidate.getAssetType())) {
            throw new SemanticAssetRoutingException(HttpStatus.CONFLICT,
                    "REUSE_TARGET_INVALID", "复用目标已失效，请重新分析");
        }
        permissionService.requireReadableCandidateVersion(candidate, user);
    }

    /**
     * 在确认 CAS 成功后触发现有知识刷新。
     *
     * <p>调用示例：{@code refreshConfirmed(routeId, candidate, user)}。数据库 CAS 胜者和同指纹
     * 幂等重放都可调用本方法，以“至少一次”语义恢复确认落库后发生的事件发布失败。下游执行的是
     * 缓存失效和刷新状态覆盖，重复 UPDATE 事件是安全幂等的；不同指纹或版本冲突不会进入本方法。
     * 发布前仍再次校验权限和版本，避免确认与事件发布之间的目标漂移。</p>
     *
     * @param routeId 路由分析 ID，仅用于脱敏结构化日志。
     * @param candidate 已确认的候选资产。
     * @param user 当前确认用户。
     * @throws SemanticAssetRoutingException 候选权限或内容版本在 CAS 后发生变化时抛出。
     */
    public void refreshConfirmed(Long routeId, SemanticAssetCandidate candidate, User user) {
        validateCandidate(candidate, user);
        DataItem dataItem = DataItem.builder().id(candidate.getAssetId().toString())
                .name(candidate.getName()).bizName(candidate.getBizName())
                .modelId(candidate.getAssetId().toString())
                .domainId(candidate.getDomainId() == null ? null
                        : candidate.getDomainId().toString())
                .type(TypeEnums.MODEL).build();
        eventPublisher.publishEvent(
                new DataEvent(this, List.of(dataItem), EventType.UPDATE, user.getName()));
        log.info("semantic asset reuse validation triggered: routeId={}, action={}, result=QUEUED",
                routeId, SemanticAssetRouteAction.REUSE_EXISTING);
    }
}
