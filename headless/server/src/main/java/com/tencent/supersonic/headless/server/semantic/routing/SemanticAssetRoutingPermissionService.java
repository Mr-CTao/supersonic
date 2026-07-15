package com.tencent.supersonic.headless.server.semantic.routing;

import com.tencent.supersonic.common.pojo.User;
import com.tencent.supersonic.common.pojo.enums.AuthType;
import com.tencent.supersonic.headless.api.pojo.response.DatabaseResp;
import com.tencent.supersonic.headless.api.pojo.response.DomainResp;
import com.tencent.supersonic.headless.api.pojo.response.ModelResp;
import com.tencent.supersonic.headless.server.persistence.dataobject.SemanticAssetRoutingDO;
import com.tencent.supersonic.headless.server.persistence.dataobject.SemanticGapDO;
import com.tencent.supersonic.headless.server.persistence.mapper.SemanticGapMapper;
import com.tencent.supersonic.headless.server.service.DatabaseService;
import com.tencent.supersonic.headless.server.service.DomainService;
import com.tencent.supersonic.headless.server.service.ModelService;
import org.apache.commons.lang3.StringUtils;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 语义资产路由权限服务。
 *
 * <p>
 * 职责：分离分析读取权限、路由详情权限、新建管理权限和目标模型增强权限。候选存在性只在 VIEWER ACL
 * 过滤后处理；无权和不存在统一使用安全消息，避免通过数量或错误差异枚举资产。服务无共享状态。
 * </p>
 */
@Service
public class SemanticAssetRoutingPermissionService {

    private final DatabaseService databaseService;
    private final DomainService domainService;
    private final ModelService modelService;
    private final SemanticGapMapper gapMapper;
    private final SemanticAssetVersionService versionService;

    /**
     * 创建路由权限服务。
     *
     * @param databaseService 数据源 ACL 服务。
     * @param domainService 主题域 ACL 服务。
     * @param modelService 模型 ACL 服务。
     * @param gapMapper 缺口 Mapper。
     * @param versionService 模型及子对象稳定版本快照服务。
     */
    public SemanticAssetRoutingPermissionService(DatabaseService databaseService,
            DomainService domainService, ModelService modelService, SemanticGapMapper gapMapper,
            SemanticAssetVersionService versionService) {
        this.databaseService = databaseService;
        this.domainService = domainService;
        this.modelService = modelService;
        this.gapMapper = gapMapper;
        this.versionService = versionService;
    }

    /**
     * 校验发起分析所需的来源和数据源读取权限。
     *
     * @param request 分析请求。
     * @param user 当前用户。
     * @throws SemanticAssetRoutingException 来源非法、Gap 不一致或数据源不可读时抛出。
     */
    public void requireAnalysisPermission(SemanticAssetRouteAnalyzeReq request, User user) {
        requireAuthenticated(user);
        requireDatabaseReadable(request.getDataSourceId(), user);
        if (SemanticAssetRoutingConstants.SOURCE_SEMANTIC_GAP.equals(request.getSourceType())) {
            if (request.getSourceId() == null) {
                throw invalid("Gap 来源必须提供 sourceId");
            }
            SemanticGapDO gap = gapMapper.selectById(request.getSourceId());
            if (gap == null) {
                throw unavailable();
            }
            if (gap.getDataSourceId() != null
                    && !Objects.equals(gap.getDataSourceId(), request.getDataSourceId())) {
                throw invalid("分析数据源与缺口上下文不一致");
            }
            if (gap.getDomainId() != null && request.getDomainId() != null
                    && !Objects.equals(gap.getDomainId(), request.getDomainId())) {
                throw invalid("分析主题域与缺口上下文不一致");
            }
            if (request.getDomainId() == null) {
                request.setDomainId(gap.getDomainId());
            }
        } else if (!SemanticAssetRoutingConstants.SOURCE_DATA_SOURCE
                .equals(request.getSourceType())) {
            throw invalid("不支持的路由来源类型");
        }
    }

    /**
     * 校验路由详情只能由创建者或超级管理员在仍具备数据源读取权限时访问。
     *
     * @param route 路由记录。
     * @param user 当前用户。
     * @throws SemanticAssetRoutingException 路由不可访问时统一抛出 404。
     */
    public void requireRouteReadable(SemanticAssetRoutingDO route, User user) {
        requireAuthenticated(user);
        if (route == null || (!user.isSuperAdmin()
                && !StringUtils.equalsIgnoreCase(route.getCreatedBy(), user.getName()))) {
            throw unavailable();
        }
        try {
            requireDatabaseReadable(route.getDataSourceId(), user);
        } catch (SemanticAssetRoutingException exception) {
            throw unavailable();
        }
    }

    /**
     * 判断当前用户是否可以新建语义资产。
     *
     * @param route 路由记录。
     * @param user 当前用户。
     * @return 同时具备数据源管理权和可选主题域编辑权时为 true。
     */
    public boolean canCreateAsset(SemanticAssetRoutingDO route, User user) {
        if (route == null || user == null || StringUtils.isBlank(user.getName())) {
            return false;
        }
        if (user.isSuperAdmin()) {
            return true;
        }
        try {
            DatabaseResp database = requireDatabaseReadable(route.getDataSourceId(), user);
            return hasDatabaseAdmin(database, user)
                    && (route.getDomainId() == null || hasDomainAdmin(route.getDomainId(), user));
        } catch (RuntimeException exception) {
            return false;
        }
    }

    /**
     * 判断当前用户是否可以增强指定候选模型。
     *
     * @param candidate 已持久化候选快照。
     * @param user 当前用户。
     * @return 候选仍位于当前用户 ADMIN 模型集合时为 true。
     */
    public boolean canManageCandidate(SemanticAssetCandidate candidate, User user) {
        if (candidate == null || candidate.getAssetId() == null || user == null) {
            return false;
        }
        if (user.isSuperAdmin()) {
            return true;
        }
        return safe(
                modelService.getModelListWithAuth(user, candidate.getDomainId(), AuthType.ADMIN))
                        .stream().map(ModelResp::getId).anyMatch(candidate.getAssetId()::equals);
    }

    /**
     * 读取并校验目标模型当前版本快照。
     *
     * @param candidate 已确认候选。
     * @return 当前模型、维度和指标内容的稳定版本。
     * @throws SemanticAssetRoutingException 模型不存在、越权或版本漂移时抛出 409/403。
     */
    public Long requireCurrentCandidateVersion(SemanticAssetCandidate candidate, User user) {
        if (!canManageCandidate(candidate, user)) {
            throw new SemanticAssetRoutingException(HttpStatus.FORBIDDEN,
                    "TARGET_MANAGE_PERMISSION_DENIED", "无权管理所选目标资产");
        }
        Long current = versionService.loadVersions(List.of(candidate.getAssetId()))
                .get(candidate.getAssetId());
        if (current == null) {
            throw new SemanticAssetRoutingException(HttpStatus.CONFLICT, "TARGET_ASSET_CHANGED",
                    "目标资产已不存在，请重新分析");
        }
        if (!Objects.equals(current, candidate.getAssetVersion())) {
            throw new SemanticAssetRoutingException(HttpStatus.CONFLICT,
                    "TARGET_ASSET_VERSION_CHANGED", "目标资产版本已变化，请重新分析");
        }
        return current;
    }

    /**
     * 以 VIEWER 权限复核复用候选及版本。
     *
     * @param candidate 已持久化候选。
     * @param user 当前用户。
     * @return 当前模型版本。
     * @throws SemanticAssetRoutingException 候选越权、不存在或版本变化时抛出。
     */
    public Long requireReadableCandidateVersion(SemanticAssetCandidate candidate, User user) {
        if (candidate == null || candidate.getAssetId() == null || user == null) {
            throw unavailable();
        }
        boolean readable = user.isSuperAdmin() || safe(
                modelService.getModelListWithAuth(user, candidate.getDomainId(), AuthType.VIEWER))
                        .stream().map(ModelResp::getId).anyMatch(candidate.getAssetId()::equals);
        if (!readable) {
            throw unavailable();
        }
        Long current = versionService.loadVersions(List.of(candidate.getAssetId()))
                .get(candidate.getAssetId());
        if (current == null || !Objects.equals(current, candidate.getAssetVersion())) {
            throw new SemanticAssetRoutingException(HttpStatus.CONFLICT,
                    "TARGET_ASSET_VERSION_CHANGED", "目标资产版本已变化，请重新分析");
        }
        return current;
    }

    /**
     * 批量复核响应中的候选仍可见且版本未漂移。
     *
     * <p>候选按主题域批量获取 VIEWER ACL，再一次性读取模型版本，避免详情轮询在候选循环中产生
     * N+1 查询。任一候选被撤权或删除时统一返回不可用，不通过候选数量或名称泄露旧快照。</p>
     *
     * @param candidates 持久化候选快照。
     * @param user 当前用户。
     * @throws SemanticAssetRoutingException 候选 ACL、存在性或版本发生变化时抛出。
     */
    public void requireCandidateSnapshotReadable(List<SemanticAssetCandidate> candidates,
            User user) {
        if (candidates == null || candidates.isEmpty()) {
            return;
        }
        requireAuthenticated(user);
        Set<Long> candidateIds = candidates.stream().map(SemanticAssetCandidate::getAssetId)
                .filter(Objects::nonNull).collect(Collectors.toSet());
        if (candidateIds.size() != candidates.size()) {
            throw unavailable();
        }
        if (!user.isSuperAdmin()) {
            Map<Long, List<SemanticAssetCandidate>> byDomain = candidates.stream()
                    .collect(Collectors.groupingBy(SemanticAssetCandidate::getDomainId));
            Set<Long> readableIds = byDomain.entrySet().stream()
                    .flatMap(entry -> safe(modelService.getModelListWithAuth(user, entry.getKey(),
                            AuthType.VIEWER)).stream())
                    .map(ModelResp::getId).collect(Collectors.toSet());
            if (!readableIds.containsAll(candidateIds)) {
                throw unavailable();
            }
        }
        Map<Long, Long> currentVersions = versionService.loadVersions(candidateIds);
        boolean changed = candidates.stream().anyMatch(candidate -> {
            Long currentVersion = currentVersions.get(candidate.getAssetId());
            return currentVersion == null
                    || !Objects.equals(currentVersion, candidate.getAssetVersion());
        });
        if (changed) {
            throw new SemanticAssetRoutingException(HttpStatus.CONFLICT,
                    "CANDIDATE_SNAPSHOT_CHANGED", "候选资产已变化，请重新分析");
        }
    }

    /** 校验登录身份。 */
    private void requireAuthenticated(User user) {
        if (user == null || StringUtils.isBlank(user.getName())) {
            throw unavailable();
        }
    }

    /** 校验数据源基础读取 ACL。 */
    private DatabaseResp requireDatabaseReadable(Long databaseId, User user) {
        try {
            DatabaseResp database = databaseService.getDatabase(databaseId, user);
            if (database == null) {
                throw new IllegalArgumentException("database unavailable");
            }
            return database;
        } catch (RuntimeException exception) {
            throw new SemanticAssetRoutingException(HttpStatus.FORBIDDEN, "ROUTE_ACCESS_DENIED",
                    "无权访问路由分析范围");
        }
    }

    /** 判断数据源创建者、管理员或现有 edit 标记。 */
    private boolean hasDatabaseAdmin(DatabaseResp database, User user) {
        return database.isHasEditPermission()
                || StringUtils.equalsIgnoreCase(database.getCreatedBy(), user.getName())
                || safe(database.getAdmins()).stream().filter(StringUtils::isNotBlank)
                        .anyMatch(name -> StringUtils.equalsIgnoreCase(name, user.getName()));
    }

    /** 判断用户是否具备整个主题域编辑权限。 */
    private boolean hasDomainAdmin(Long domainId, User user) {
        return safe(domainService.getDomainListWithAdminAuth(user)).stream()
                .filter(Objects::nonNull)
                .anyMatch(domain -> Objects.equals(domainId, domain.getId())
                        && domain.isHasEditPermission());
    }

    /** 创建不泄露对象存在性的统一异常。 */
    private SemanticAssetRoutingException unavailable() {
        return new SemanticAssetRoutingException(HttpStatus.NOT_FOUND,
                "ROUTE_NOT_FOUND_OR_INACCESSIBLE", "路由分析不存在或不可访问");
    }

    /** 创建安全的输入异常。 */
    private SemanticAssetRoutingException invalid(String message) {
        return new SemanticAssetRoutingException(HttpStatus.UNPROCESSABLE_ENTITY,
                "INVALID_ROUTE_REQUEST", message);
    }

    /** 把可空列表转换为空列表。 */
    private <T> List<T> safe(List<T> values) {
        return values == null ? List.of() : values;
    }
}
