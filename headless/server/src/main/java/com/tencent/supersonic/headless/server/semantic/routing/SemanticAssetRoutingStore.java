package com.tencent.supersonic.headless.server.semantic.routing;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tencent.supersonic.common.pojo.User;
import com.tencent.supersonic.headless.server.persistence.dataobject.SemanticAssetRoutingDO;
import com.tencent.supersonic.headless.server.persistence.mapper.SemanticAssetRoutingMapper;
import org.apache.commons.lang3.StringUtils;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Calendar;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * 语义资产路由短事务存储服务。
 *
 * <p>
 * 职责：集中管理创建幂等、数据库时钟租约、分析完成、失败、重新分析和乐观锁确认。所有外部 Provider 调用必须发生在本服务事务之外；跨实例一致性只依赖数据库唯一键和条件更新，不使用 JVM
 * 锁。
 * </p>
 */
@Service
public class SemanticAssetRoutingStore {

    private final SemanticAssetRoutingMapper mapper;
    private final ObjectMapper objectMapper;

    /**
     * 创建路由存储服务。
     *
     * @param mapper 路由 Mapper。
     * @param objectMapper JSON 映射器。
     */
    public SemanticAssetRoutingStore(SemanticAssetRoutingMapper mapper, ObjectMapper objectMapper) {
        this.mapper = mapper;
        this.objectMapper = objectMapper;
    }

    /**
     * 查找并验证创建幂等重放。
     *
     * @param createdBy 创建者。
     * @param idempotencyKey 幂等键。
     * @param fingerprint 当前请求指纹。
     * @return 已存在且指纹一致的路由。
     * @throws SemanticAssetRoutingException 相同键已用于不同指纹时抛出 409。
     */
    public Optional<SemanticAssetRoutingDO> findIdempotentRoute(String createdBy,
            String idempotencyKey, String fingerprint) {
        SemanticAssetRoutingDO existing = mapper.selectByIdempotencyKey(createdBy, idempotencyKey);
        if (existing == null) {
            return Optional.empty();
        }
        if (!Objects.equals(existing.getRequestFingerprint(), fingerprint)) {
            throw conflict("IDEMPOTENCY_CONFLICT", "Idempotency-Key 已用于不同路由请求");
        }
        return Optional.of(existing);
    }

    /**
     * 使用数据库唯一键原子创建待分析路由。
     *
     * <p>此方法刻意不包裹外层 Spring 事务：PostgreSQL 在唯一键冲突后会把当前事务标记为失败，
     * 若继续在同一事务读取并发胜者会得到事务已终止错误。单条 INSERT 本身具备原子性，冲突后的
     * 查询在独立自动提交事务中执行，既保留幂等语义，也兼容 H2、MySQL 与 PostgreSQL。</p>
     *
     * @param request 已规范化请求。
     * @param idempotencyKey 幂等键。
     * @param fingerprint 请求指纹。
     * @param user 当前用户。
     * @return 新建或并发重放结果。
     */
    public CreateResult createPending(SemanticAssetRouteAnalyzeReq request, String idempotencyKey,
            String fingerprint, User user) {
        Optional<SemanticAssetRoutingDO> replay =
                findIdempotentRoute(user.getName(), idempotencyKey, fingerprint);
        if (replay.isPresent()) {
            return new CreateResult(replay.orElseThrow(), true);
        }
        Date now = databaseTime();
        SemanticAssetRoutingDO route = new SemanticAssetRoutingDO();
        route.setSourceType(request.getSourceType());
        route.setSourceId(request.getSourceId());
        route.setRequestFingerprint(fingerprint);
        route.setIdempotencyKey(idempotencyKey);
        route.setStatus(SemanticAssetRouteStatus.PENDING.name());
        route.setBusinessGoal(request.getBusinessGoal());
        route.setDomainId(request.getDomainId());
        route.setDataSourceId(request.getDataSourceId());
        route.setCatalogName(request.getCatalogName());
        route.setDatabaseName(request.getDatabaseName());
        route.setSelectedTables(writeJson(request.getSelectedTables()));
        route.setChatModelId(request.getChatModelId());
        route.setIncludeSample(Boolean.TRUE.equals(request.getIncludeSampleData()));
        // 即使调用方绕过 Controller/应用服务，持久化边界仍拒绝超大答案快照。
        route.setBusinessAnswers(boundedJson(request.getBusinessAnswers()));
        route.setAnalysisVersion(1);
        route.setLockVersion(0);
        route.setCreatedBy(user.getName());
        route.setCreatedAt(now);
        route.setUpdatedBy(user.getName());
        route.setUpdatedAt(now);
        try {
            mapper.insert(route);
            return new CreateResult(route, false);
        } catch (DuplicateKeyException exception) {
            SemanticAssetRoutingDO concurrent =
                    mapper.selectByIdempotencyKey(user.getName(), idempotencyKey);
            if (concurrent == null
                    || !Objects.equals(fingerprint, concurrent.getRequestFingerprint())) {
                throw conflict("IDEMPOTENCY_CONFLICT", "并发路由请求的幂等指纹不一致");
            }
            return new CreateResult(concurrent, true);
        }
    }

    /**
     * 原子认领分析租约。
     *
     * @param route 已认领的路由快照；锁版本用于防止迟到失败覆盖新租约。
     * @param userName 操作者。
     * @return 认领后的最新记录；失败时为空。
     */
    public Optional<SemanticAssetRoutingDO> claimAnalysis(Long id, String userName) {
        Date now = databaseTime();
        Date leaseExpiresAt = addSeconds(now, SemanticAssetRoutingConstants.ANALYSIS_LEASE_SECONDS);
        if (mapper.claimAnalysis(id, now, leaseExpiresAt, userName) != 1) {
            return Optional.empty();
        }
        return Optional.ofNullable(mapper.selectById(id));
    }

    /**
     * 完成已认领分析。
     *
     * @param route 认领后快照。
     * @param candidates 候选快照。
     * @param coverage 规则证据。
     * @param advice 可选 LLM 建议。
     * @param decision 最终策略裁决。
     * @param llmConversationId 实际调用 LLM 时创建的独立会话 ID。
     * @param userName 操作者。
     * @return 完成后的记录。
     */
    @Transactional(rollbackFor = Exception.class)
    public SemanticAssetRoutingDO completeAnalysis(SemanticAssetRoutingDO route,
            List<SemanticAssetCandidate> candidates, SemanticAssetCoverageResult coverage,
            Optional<SemanticAssetRoutingAdvice> advice, SemanticAssetRoutingDecision decision,
            Long llmConversationId, String userName) {
        Date now = databaseTime();
        Date expiresAt = addHours(now, SemanticAssetRoutingConstants.ROUTE_EXPIRATION_HOURS);
        SemanticAssetCandidate selected = safe(candidates).stream().filter(
                item -> Objects.equals(item.getCandidateHandle(), decision.getCandidateHandle()))
                .findFirst().orElse(null);
        int updated = mapper.completeAnalysis(route.getId(), route.getLockVersion(),
                boundedJson(candidates), boundedJson(coverage),
                advice.map(this::boundedJson).orElse(null), decision.getAction().name(),
                selected == null ? null : selected.getAssetType(),
                selected == null ? null : selected.getAssetId(),
                selected == null ? null : selected.getAssetVersion(),
                boundedJson(decision.getCoveredCapabilities()),
                boundedJson(decision.getMissingCapabilities()),
                boundedJson(decision.getResultOperations()),
                boundedJson(decision.getBusinessQuestions()), decision.getDecisionSource().name(),
                llmConversationId, now, expiresAt, userName);
        if (updated != 1) {
            throw conflict("ANALYSIS_LEASE_CONFLICT", "路由分析租约或版本已变化，请查询最新状态");
        }
        return mapper.selectById(route.getId());
    }

    /**
     * 把分析安全结束为失败态。
     *
     * @param route 认领时的路由快照。
     * @param errorCode 脱敏错误码。
     * @param message 安全错误消息。
     * @param userName 操作者。
     */
    public void failAnalysis(SemanticAssetRoutingDO route, String errorCode, String message,
            String userName) {
        failAnalysis(route, errorCode, message, null, userName);
    }

    /**
     * 把分析安全结束为失败态，并保留已创建的 LLM 会话审计引用。
     *
     * @param route 认领时的路由快照。
     * @param errorCode 脱敏错误码。
     * @param message 安全错误消息。
     * @param llmConversationId 已创建的会话 ID；创建前失败时为空。
     * @param userName 操作者。
     */
    public void failAnalysis(SemanticAssetRoutingDO route, String errorCode, String message,
            Long llmConversationId, String userName) {
        mapper.failAnalysis(route.getId(), route.getLockVersion(),
                StringUtils.abbreviate(errorCode, 64), StringUtils.abbreviate(message, 1000),
                databaseTime(), llmConversationId, userName);
    }

    /**
     * 使用乐观锁持久化最终确认。
     *
     * @return 确认后的记录。
     */
    @Transactional(rollbackFor = Exception.class)
    public ConfirmationResult confirm(SemanticAssetRoutingDO route,
            SemanticAssetRouteConfirmReq request, SemanticAssetCandidate candidate,
            String idempotencyKey, String fingerprint, User user) {
        int updated = mapper.confirm(route.getId(), request.getAnalysisVersion(),
                route.getLockVersion(), request.getAction().name(),
                candidate == null ? null : candidate.getAssetType(),
                candidate == null ? null : candidate.getAssetId(),
                candidate == null ? null : candidate.getAssetVersion(),
                boundedJson(request.getBusinessAnswers()), request.getOverrideReason(),
                idempotencyKey, fingerprint, user.getName(), databaseTime());
        if (updated != 1) {
            SemanticAssetRoutingDO concurrent = mapper.selectById(route.getId());
            if (concurrent != null
                    && Objects.equals(idempotencyKey,
                            concurrent.getConfirmationIdempotencyKey())) {
                if (!Objects.equals(fingerprint,
                        concurrent.getConfirmationRequestFingerprint())) {
                    throw conflict("IDEMPOTENCY_CONFLICT",
                            "Idempotency-Key 已用于不同路由确认请求");
                }
                // 两个实例并发确认同一请求时，乐观锁败者重放数据库中的同一确认结果。
                return new ConfirmationResult(concurrent, true);
            }
            throw conflict("ROUTE_CONFIRMATION_CONFLICT", "路由版本、状态或确认结果已变化");
        }
        return new ConfirmationResult(mapper.selectById(route.getId()), false);
    }

    /**
     * 保存澄清答案并切换到下一分析版本。
     *
     * @return 待重新分析的最新记录。
     */
    @Transactional(rollbackFor = Exception.class)
    public SemanticAssetRoutingDO prepareReanalysis(SemanticAssetRoutingDO route,
            SemanticAssetRouteConfirmReq request, String idempotencyKey, String fingerprint,
            String reanalysisFingerprint, User user) {
        int updated = mapper.prepareReanalysis(route.getId(), request.getAnalysisVersion(),
                route.getLockVersion(), boundedJson(request.getBusinessAnswers()), idempotencyKey,
                fingerprint, reanalysisFingerprint, user.getName(), databaseTime());
        if (updated != 1) {
            throw conflict("ROUTE_CONFIRMATION_CONFLICT", "路由版本已变化，无法使用旧问题答案");
        }
        return mapper.selectById(route.getId());
    }

    /**
     * 查询路由，并在必要时使用数据库时间推进过期状态。
     *
     * @param id 路由 ID。
     * @return 最新记录或 null。
     */
    public SemanticAssetRoutingDO findById(Long id) {
        SemanticAssetRoutingDO route = mapper.selectById(id);
        if (route != null && SemanticAssetRouteStatus.SUCCEEDED.name().equals(route.getStatus())
                && route.getConfirmedAction() == null && route.getExpiresAt() != null) {
            Date now = databaseTime();
            // 轮询成功路由是高频路径；只在真正过期时才执行条件更新和二次读取。
            if (!route.getExpiresAt().after(now) && mapper.expire(id, now) == 1) {
                route = mapper.selectById(id);
            }
        }
        return route;
    }

    /**
     * 返回与租约、过期判断和消费条件一致的数据库时间。
     *
     * @return 数据库当前时间。
     * @throws SemanticAssetRoutingException 数据库无法返回时间时 fail-closed。
     */
    public Date currentDatabaseTime() {
        return databaseTime();
    }

    /** 获取数据库时间；失败时必须 fail-closed，不能混用 JVM 时钟。 */
    private Date databaseTime() {
        Date now = mapper.selectDatabaseTime();
        if (now == null) {
            throw new SemanticAssetRoutingException(HttpStatus.SERVICE_UNAVAILABLE,
                    "DATABASE_TIME_UNAVAILABLE", "无法获取路由数据库时间，请稍后重试");
        }
        return now;
    }

    /** 序列化并限制 JSON 大小。 */
    private String boundedJson(Object value) {
        String json = writeJson(value);
        if (json.length() > SemanticAssetRoutingConstants.MAX_JSON_CHARACTERS) {
            throw new SemanticAssetRoutingException(HttpStatus.UNPROCESSABLE_ENTITY,
                    "ROUTING_CONTEXT_TOO_LARGE", "路由分析上下文过大，请缩小选表或业务范围");
        }
        return json;
    }

    /** 序列化受控结构，不回显原始异常消息。 */
    private String writeJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value == null ? Map.of() : value);
        } catch (JsonProcessingException exception) {
            throw new SemanticAssetRoutingException(HttpStatus.INTERNAL_SERVER_ERROR,
                    "ROUTING_SERIALIZATION_FAILED", "无法保存路由分析快照");
        }
    }

    /** 基于数据库时间增加秒数。 */
    private Date addSeconds(Date base, int seconds) {
        return new Date(base.getTime() + seconds * 1000L);
    }

    /** 基于数据库时间增加小时数。 */
    private Date addHours(Date base, int hours) {
        Calendar calendar = Calendar.getInstance();
        calendar.setTime(base);
        calendar.add(Calendar.HOUR_OF_DAY, hours);
        return calendar.getTime();
    }

    /** 把可空列表转换为空列表。 */
    private <T> List<T> safe(List<T> values) {
        return values == null ? List.of() : values;
    }

    /** 创建稳定 409 冲突异常。 */
    private SemanticAssetRoutingException conflict(String code, String message) {
        return new SemanticAssetRoutingException(HttpStatus.CONFLICT, code, message);
    }

    /**
     * 创建待分析路由结果。
     *
     * @param route 路由记录。
     * @param replay 是否幂等重放。
     */
    public record CreateResult(SemanticAssetRoutingDO route, boolean replay) {}

    /** 确认写入或并发幂等重放结果。 */
    public record ConfirmationResult(SemanticAssetRoutingDO route, boolean replay) {}
}
