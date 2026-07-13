package com.tencent.supersonic.headless.server.semantic.modeling;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tencent.supersonic.common.pojo.User;
import com.tencent.supersonic.headless.server.persistence.dataobject.SemanticGapDO;
import com.tencent.supersonic.headless.server.persistence.dataobject.SemanticModelingDraftAttemptDO;
import com.tencent.supersonic.headless.server.persistence.dataobject.SemanticModelingDraftDO;
import com.tencent.supersonic.headless.server.persistence.dataobject.SemanticModelingDraftVersionDO;
import com.tencent.supersonic.headless.server.persistence.mapper.SemanticGapMapper;
import com.tencent.supersonic.headless.server.persistence.mapper.SemanticModelingDraftAttemptMapper;
import com.tencent.supersonic.headless.server.persistence.mapper.SemanticModelingDraftMapper;
import com.tencent.supersonic.headless.server.persistence.mapper.SemanticModelingDraftVersionMapper;
import com.tencent.supersonic.headless.server.semantic.gap.SemanticGapStatus;
import org.apache.commons.lang3.StringUtils;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Date;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * AI 语义建模草稿短事务存储服务。
 *
 * <p>
 * 职责说明：集中管理创建、Gap 行锁、Worker 条件认领、成功/失败落库、版本新增、乐观锁保存和超时 恢复。任何方法都不会调用
 * LLM、正式语义写服务、事件发布或知识库重载。并发说明：共享一致性依赖数据库 行锁、唯一键和条件更新，不依赖单 JVM 锁；每个事务只包含少量本地数据库语句。
 * </p>
 */
@Service
public class ModelingDraftStore {

    private final SemanticModelingDraftMapper draftMapper;
    private final SemanticModelingDraftAttemptMapper attemptMapper;
    private final SemanticModelingDraftVersionMapper versionMapper;
    private final SemanticGapMapper gapMapper;
    private final ObjectMapper objectMapper;
    private final ModelingDraftRevisionStore revisionStore;

    /**
     * 创建存储服务。
     *
     * @param draftMapper 草稿 Mapper。
     * @param attemptMapper 生成尝试 Mapper。
     * @param versionMapper 版本 Mapper。
     * @param gapMapper 缺口 Mapper。
     * @param objectMapper JSON 序列化器。
     * @param revisionStore AI 修订租约存储服务。
     */
    public ModelingDraftStore(SemanticModelingDraftMapper draftMapper,
            SemanticModelingDraftAttemptMapper attemptMapper,
            SemanticModelingDraftVersionMapper versionMapper, SemanticGapMapper gapMapper,
            ObjectMapper objectMapper, ModelingDraftRevisionStore revisionStore) {
        this.draftMapper = draftMapper;
        this.attemptMapper = attemptMapper;
        this.versionMapper = versionMapper;
        this.gapMapper = gapMapper;
        this.objectMapper = objectMapper;
        this.revisionStore = revisionStore;
    }

    /**
     * 在短事务中创建 GENERATING 草稿。
     *
     * <p>
     * Gap 来源会先 {@code SELECT ... FOR UPDATE}，并复用同一缺口已有的活动草稿，从而保证多实例并发 下只有一个活动草稿。LLM
     * 入队必须在本方法返回、事务提交后执行。
     * </p>
     *
     * @param request 已完成服务端元数据预检的请求。
     * @param idempotencyKey 必填幂等键。
     * @param requestFingerprint 规范化创建请求指纹。
     * @param user 创建用户。
     * @return 创建结果，包含是否为既有记录。
     */
    @Transactional(rollbackFor = Exception.class)
    public CreateResult createGenerating(ModelingDraftGenerateReq request, String idempotencyKey,
            String requestFingerprint, User user) {
        SemanticModelingDraftDO replay =
                draftMapper.selectByIdempotencyKey(user.getName(), idempotencyKey);
        if (replay != null) {
            return new CreateResult(replay, true);
        }

        SemanticGapDO lockedGap = null;
        if (ModelingDraftConstants.SOURCE_SEMANTIC_GAP.equals(request.getSourceType())) {
            lockedGap = gapMapper.selectByIdForUpdate(request.getSourceId());
            if (lockedGap == null) {
                throw new ModelingDraftException(HttpStatus.NOT_FOUND,
                        ModelingDraftConstants.ERROR_INVALID_REQUEST, "语义缺口不存在");
            }
            if (SemanticGapStatus.IGNORED.name().equals(lockedGap.getStatus())
                    || SemanticGapStatus.RELEASED.name().equals(lockedGap.getStatus())) {
                throw new ModelingDraftException(HttpStatus.CONFLICT,
                        ModelingDraftConstants.ERROR_CONFLICT, "当前缺口状态不允许发起草稿");
            }
            SemanticModelingDraftDO active =
                    draftMapper.selectOne(new LambdaQueryWrapper<SemanticModelingDraftDO>()
                            .eq(SemanticModelingDraftDO::getSourceType,
                                    ModelingDraftConstants.SOURCE_SEMANTIC_GAP)
                            .eq(SemanticModelingDraftDO::getSourceId, request.getSourceId())
                            .in(SemanticModelingDraftDO::getStatus,
                                    ModelingDraftConstants.STATUS_GENERATING,
                                    ModelingDraftConstants.STATUS_DRAFT,
                                    ModelingDraftConstants.STATUS_PENDING_APPROVAL)
                            .last("LIMIT 1"));
            if (active != null) {
                return new CreateResult(active, true);
            }
        }

        Date now = new Date();
        SemanticModelingDraftDO draft = new SemanticModelingDraftDO();
        draft.setSourceType(request.getSourceType());
        draft.setSourceId(request.getSourceId());
        draft.setTitle(StringUtils.defaultIfBlank(request.getTitle(),
                StringUtils.abbreviate(request.getBusinessGoal(), 128)));
        draft.setBusinessGoal(request.getBusinessGoal());
        draft.setDomainId(request.getDomainId());
        draft.setDataSourceId(request.getDataSourceId());
        draft.setCatalogName(request.getCatalogName());
        draft.setDatabaseName(request.getDatabaseName());
        draft.setSelectedTables(writeJson(request.getSelectedTables()));
        draft.setChatModelId(request.getChatModelId());
        draft.setIncludeSample(Boolean.TRUE.equals(request.getIncludeSampleData()));
        draft.setIdempotencyKey(idempotencyKey);
        draft.setStatus(ModelingDraftConstants.STATUS_GENERATING);
        draft.setCurrentVersionNo(0);
        draft.setCurrentAttemptNo(1);
        draft.setLockVersion(0);
        draft.setCreatedBy(user.getName());
        draft.setCreatedAt(now);
        draft.setUpdatedBy(user.getName());
        draft.setUpdatedAt(now);
        draftMapper.insert(draft);

        // 主表和 attempt 1 必须同事务提交，避免进程退出后留下无法恢复的 GENERATING 主记录。
        insertAttempt(draft.getId(), 1, ModelingDraftConstants.ATTEMPT_TRIGGER_INITIAL,
                request.getChatModelId(), Boolean.TRUE.equals(request.getIncludeSampleData()),
                idempotencyKey, requestFingerprint, user.getName(), now);

        if (lockedGap != null) {
            lockedGap.setStatus(SemanticGapStatus.DRAFTING.name());
            lockedGap.setUpdatedBy(user.getName());
            lockedGap.setUpdatedAt(now);
            gapMapper.updateById(lockedGap);
        }
        return new CreateResult(draft, false);
    }

    /**
     * 查询幂等键对应草稿，用于唯一键并发冲突后的新事务重放。
     *
     * @param createdBy 创建者。
     * @param idempotencyKey 幂等键。
     * @return 既有草稿或 null。
     */
    public SemanticModelingDraftDO findByIdempotencyKey(String createdBy, String idempotencyKey) {
        return draftMapper.selectByIdempotencyKey(createdBy, idempotencyKey);
    }

    /**
     * 查询人工重新生成幂等键对应的 attempt。
     *
     * @param createdBy 操作者。
     * @param idempotencyKey 幂等键。
     * @return 既有 attempt 或 null。
     */
    public SemanticModelingDraftAttemptDO findAttemptByIdempotencyKey(String createdBy,
            String idempotencyKey) {
        return attemptMapper.selectByIdempotencyKey(createdBy, idempotencyKey);
    }

    /**
     * 在短事务中把失败草稿切换为下一次人工生成。
     *
     * <p>
     * Gap 来源严格按“先锁 Gap、再锁草稿”的全局顺序，避免与新建草稿形成死锁。主表、attempt 和 Gap 状态同事务提交；调用方必须在事务返回后才入队。
     * </p>
     *
     * @param draftSnapshot 已通过 ACL 检查的草稿快照，仅用于确定固定锁顺序。
     * @param expectedLockVersion 客户端锁版本。
     * @param chatModelId 本次模型 ID。
     * @param includeSample 是否使用脱敏样例。
     * @param idempotencyKey 幂等键。
     * @param requestFingerprint 规范化请求指纹。
     * @param maxManualRegenerations 最大人工重试次数。
     * @param user 操作者。
     * @return 新 attempt 或幂等重放结果。
     */
    @Transactional(rollbackFor = Exception.class)
    public RegenerationResult regenerate(SemanticModelingDraftDO draftSnapshot,
            Integer expectedLockVersion, Integer chatModelId, boolean includeSample,
            String idempotencyKey, String requestFingerprint, int maxManualRegenerations,
            User user) {
        SemanticModelingDraftAttemptDO replay =
                attemptMapper.selectByIdempotencyKey(user.getName(), idempotencyKey);
        if (replay != null) {
            validateRegenerationReplay(draftSnapshot.getId(), requestFingerprint, replay);
            return new RegenerationResult(draftMapper.selectById(draftSnapshot.getId()), replay,
                    true);
        }

        SemanticGapDO lockedGap = null;
        if (ModelingDraftConstants.SOURCE_SEMANTIC_GAP.equals(draftSnapshot.getSourceType())
                && draftSnapshot.getSourceId() != null) {
            lockedGap = gapMapper.selectByIdForUpdate(draftSnapshot.getSourceId());
            if (lockedGap == null) {
                throw new ModelingDraftException(HttpStatus.NOT_FOUND,
                        ModelingDraftConstants.ERROR_INVALID_REQUEST, "语义缺口不存在");
            }
            if (SemanticGapStatus.IGNORED.name().equals(lockedGap.getStatus())
                    || SemanticGapStatus.RELEASED.name().equals(lockedGap.getStatus())) {
                throw new ModelingDraftException(HttpStatus.CONFLICT,
                        ModelingDraftConstants.ERROR_REGENERATION_NOT_ALLOWED, "当前缺口状态不允许重新生成草稿");
            }
        }

        SemanticModelingDraftDO current = draftMapper.selectByIdForUpdate(draftSnapshot.getId());
        if (current == null) {
            throw new ModelingDraftException(HttpStatus.NOT_FOUND,
                    ModelingDraftConstants.ERROR_NOT_FOUND, "语义建模草稿不存在");
        }
        // 同幂等键的并发请求可能在事务外首次查询时尚未看到对方未提交记录；获得行锁后必须复查。
        replay = attemptMapper.selectByIdempotencyKey(user.getName(), idempotencyKey);
        if (replay != null) {
            validateRegenerationReplay(current.getId(), requestFingerprint, replay);
            return new RegenerationResult(current, replay, true);
        }
        if (!ModelingDraftConstants.STATUS_GENERATION_FAILED.equals(current.getStatus())
                || current.getCurrentVersionNo() == null || current.getCurrentVersionNo() != 0) {
            throw new ModelingDraftException(HttpStatus.CONFLICT,
                    ModelingDraftConstants.ERROR_REGENERATION_NOT_ALLOWED, "只有尚未产生版本的生成失败草稿可以重新生成");
        }
        if (!Objects.equals(current.getLockVersion(), expectedLockVersion)) {
            throw new ModelingDraftException(HttpStatus.CONFLICT,
                    ModelingDraftConstants.ERROR_CONFLICT, "草稿已被其他操作更新，请重新加载后重试");
        }
        int currentAttemptNo = Objects.requireNonNullElse(current.getCurrentAttemptNo(), 1);
        int manualCount = Math.max(0, currentAttemptNo - 1);
        if (manualCount >= maxManualRegenerations) {
            throw new ModelingDraftException(HttpStatus.CONFLICT,
                    ModelingDraftConstants.ERROR_REGENERATION_LIMIT, "该草稿已达到人工重新生成次数上限");
        }

        if (lockedGap != null) {
            SemanticModelingDraftDO otherActive =
                    draftMapper.selectOne(new LambdaQueryWrapper<SemanticModelingDraftDO>()
                            .eq(SemanticModelingDraftDO::getSourceType,
                                    ModelingDraftConstants.SOURCE_SEMANTIC_GAP)
                            .eq(SemanticModelingDraftDO::getSourceId, current.getSourceId())
                            .ne(SemanticModelingDraftDO::getId, current.getId())
                            .in(SemanticModelingDraftDO::getStatus,
                                    ModelingDraftConstants.STATUS_GENERATING,
                                    ModelingDraftConstants.STATUS_DRAFT,
                                    ModelingDraftConstants.STATUS_PENDING_APPROVAL)
                            .last("LIMIT 1"));
            if (otherActive != null) {
                throw new ModelingDraftException(HttpStatus.CONFLICT,
                        ModelingDraftConstants.ERROR_ACTIVE_DRAFT_CONFLICT,
                        "该语义缺口已有其他活动草稿，请先处理后再重试");
            }
        }

        int nextAttemptNo = currentAttemptNo + 1;
        Date now = new Date();
        insertAttempt(current.getId(), nextAttemptNo,
                ModelingDraftConstants.ATTEMPT_TRIGGER_MANUAL_REGENERATION, chatModelId,
                includeSample, idempotencyKey, requestFingerprint, user.getName(), now);
        int updated = draftMapper.update(null, new LambdaUpdateWrapper<SemanticModelingDraftDO>()
                .eq(SemanticModelingDraftDO::getId, current.getId())
                .eq(SemanticModelingDraftDO::getStatus,
                        ModelingDraftConstants.STATUS_GENERATION_FAILED)
                .eq(SemanticModelingDraftDO::getLockVersion, expectedLockVersion)
                .eq(SemanticModelingDraftDO::getCurrentAttemptNo, currentAttemptNo)
                .set(SemanticModelingDraftDO::getStatus, ModelingDraftConstants.STATUS_GENERATING)
                .set(SemanticModelingDraftDO::getChatModelId, chatModelId)
                .set(SemanticModelingDraftDO::getIncludeSample, includeSample)
                .set(SemanticModelingDraftDO::getCurrentAttemptNo, nextAttemptNo)
                .setSql("lock_version = lock_version + 1")
                .set(SemanticModelingDraftDO::getLlmConversationId, null)
                .set(SemanticModelingDraftDO::getGenerationStartedAt, null)
                .set(SemanticModelingDraftDO::getGenerationFinishedAt, null)
                .set(SemanticModelingDraftDO::getRawOutput, null)
                .set(SemanticModelingDraftDO::getRepairedOutput, null)
                .set(SemanticModelingDraftDO::getErrorCode, null)
                .set(SemanticModelingDraftDO::getErrorMessage, null)
                .set(SemanticModelingDraftDO::getUpdatedBy, user.getName())
                .set(SemanticModelingDraftDO::getUpdatedAt, now));
        if (updated != 1) {
            throw new ModelingDraftException(HttpStatus.CONFLICT,
                    ModelingDraftConstants.ERROR_CONFLICT, "草稿已被其他操作更新，请重新加载后重试");
        }
        if (lockedGap != null) {
            lockedGap.setStatus(SemanticGapStatus.DRAFTING.name());
            lockedGap.setUpdatedBy(user.getName());
            lockedGap.setUpdatedAt(now);
            gapMapper.updateById(lockedGap);
        }
        return new RegenerationResult(draftMapper.selectById(current.getId()),
                attemptMapper.selectOne(new LambdaQueryWrapper<SemanticModelingDraftAttemptDO>()
                        .eq(SemanticModelingDraftAttemptDO::getDraftId, current.getId())
                        .eq(SemanticModelingDraftAttemptDO::getAttemptNo, nextAttemptNo)),
                false);
    }

    /** 校验人工重试的幂等重放必须属于同一草稿且参数完全一致。 */
    public void validateRegenerationReplay(Long draftId, String requestFingerprint,
            SemanticModelingDraftAttemptDO replay) {
        if (!Objects.equals(replay.getDraftId(), draftId)
                || !Objects.equals(replay.getRequestFingerprint(), requestFingerprint)
                || !ModelingDraftConstants.ATTEMPT_TRIGGER_MANUAL_REGENERATION
                        .equals(replay.getTriggerType())) {
            throw new ModelingDraftException(HttpStatus.CONFLICT,
                    ModelingDraftConstants.ERROR_IDEMPOTENCY_CONFLICT,
                    "Idempotency-Key 已用于其他重新生成请求");
        }
    }

    /**
     * 原子认领生成任务。
     *
     * @param draftId 草稿 ID。
     * @return true 表示当前 Worker 获得执行权。
     */
    @Transactional(rollbackFor = Exception.class)
    public boolean claimGeneration(Long draftId, Integer attemptNo, String operator) {
        Date now = new Date();
        if (draftMapper.claimGeneration(draftId, attemptNo, now, now) != 1) {
            return false;
        }
        if (attemptMapper.claimAttempt(draftId, attemptNo, now, now, operator) != 1) {
            // 主表已认领但 attempt 未认领代表状态数据不一致，抛出异常让事务整体回滚。
            throw new IllegalStateException("Draft attempt claim state mismatch");
        }
        return true;
    }

    /**
     * 保存 Gateway 会话 ID，便于详情审计但不暴露消息正文。
     *
     * @param draftId 草稿 ID。
     * @param conversationId 会话 ID。
     */
    @Transactional(rollbackFor = Exception.class)
    public void updateConversationId(Long draftId, Integer attemptNo, Long conversationId) {
        int updated = draftMapper.update(null, new LambdaUpdateWrapper<SemanticModelingDraftDO>()
                .eq(SemanticModelingDraftDO::getId, draftId)
                .eq(SemanticModelingDraftDO::getCurrentAttemptNo, attemptNo)
                .eq(SemanticModelingDraftDO::getStatus, ModelingDraftConstants.STATUS_GENERATING)
                .set(SemanticModelingDraftDO::getLlmConversationId, conversationId)
                .set(SemanticModelingDraftDO::getUpdatedAt, new Date()));
        if (updated == 1) {
            int attemptUpdated = attemptMapper.update(null,
                    new LambdaUpdateWrapper<SemanticModelingDraftAttemptDO>()
                            .eq(SemanticModelingDraftAttemptDO::getDraftId, draftId)
                            .eq(SemanticModelingDraftAttemptDO::getAttemptNo, attemptNo)
                            .eq(SemanticModelingDraftAttemptDO::getStatus,
                                    ModelingDraftConstants.ATTEMPT_STATUS_GENERATING)
                            .set(SemanticModelingDraftAttemptDO::getLlmConversationId,
                                    conversationId)
                            .set(SemanticModelingDraftAttemptDO::getUpdatedAt, new Date()));
            if (attemptUpdated != 1) {
                throw new IllegalStateException("Draft attempt conversation state mismatch");
            }
        }
    }

    /**
     * 保存单次 Provider 请求 ID，便于在不暴露模型正文的前提下关联调用日志。
     *
     * @param draftId 草稿 ID。
     * @param attemptNo 尝试序号。
     * @param stage generate 或 repair。
     * @param requestId Provider 请求 ID，可为空。
     */
    public void updateProviderRequestId(Long draftId, Integer attemptNo, String stage,
            String requestId) {
        if (StringUtils.isBlank(requestId)) {
            return;
        }
        LambdaUpdateWrapper<SemanticModelingDraftAttemptDO> update =
                new LambdaUpdateWrapper<SemanticModelingDraftAttemptDO>()
                        .eq(SemanticModelingDraftAttemptDO::getDraftId, draftId)
                        .eq(SemanticModelingDraftAttemptDO::getAttemptNo, attemptNo)
                        .eq(SemanticModelingDraftAttemptDO::getStatus,
                                ModelingDraftConstants.ATTEMPT_STATUS_GENERATING)
                        .set(SemanticModelingDraftAttemptDO::getUpdatedAt, new Date());
        if ("repair".equals(stage)) {
            update.set(SemanticModelingDraftAttemptDO::getRepairRequestId, requestId);
        } else {
            update.set(SemanticModelingDraftAttemptDO::getGenerateRequestId, requestId);
        }
        attemptMapper.update(null, update);
    }

    /**
     * 在同一短事务中完成主表更新并插入版本 1。
     *
     * @param draftId 草稿 ID。
     * @param draftJson 已校验并规范化的结构化草稿。
     * @param rawOutput 首次模型原文，仅后端诊断保存。
     * @param repairedOutput 修复模型原文，可为空。
     * @param conversationId Gateway 会话 ID。
     * @param user 操作者。
     * @return true 表示成功从 GENERATING 转为 DRAFT。
     */
    @Transactional(rollbackFor = Exception.class)
    public boolean completeGeneration(Long draftId, Integer attemptNo, String draftJson,
            String rawOutput, String repairedOutput, Long conversationId, String generateRequestId,
            String repairRequestId, User user) {
        // 与创建、重试和超时恢复统一使用“Gap → draft”锁顺序，避免多事务交叉等待。
        lockGapForDraft(draftId);
        Date now = new Date();
        int updated = draftMapper.update(null, new LambdaUpdateWrapper<SemanticModelingDraftDO>()
                .eq(SemanticModelingDraftDO::getId, draftId)
                .eq(SemanticModelingDraftDO::getCurrentAttemptNo, attemptNo)
                .eq(SemanticModelingDraftDO::getStatus, ModelingDraftConstants.STATUS_GENERATING)
                .set(SemanticModelingDraftDO::getStatus, ModelingDraftConstants.STATUS_DRAFT)
                .set(SemanticModelingDraftDO::getDraftJson, draftJson)
                .set(SemanticModelingDraftDO::getRawOutput, rawOutput)
                .set(SemanticModelingDraftDO::getRepairedOutput, repairedOutput)
                .set(SemanticModelingDraftDO::getLlmConversationId, conversationId)
                .set(SemanticModelingDraftDO::getCurrentVersionNo, 1)
                .set(SemanticModelingDraftDO::getErrorCode, null)
                .set(SemanticModelingDraftDO::getErrorMessage, null)
                .set(SemanticModelingDraftDO::getGenerationFinishedAt, now)
                .set(SemanticModelingDraftDO::getUpdatedBy, user.getName())
                .set(SemanticModelingDraftDO::getUpdatedAt, now));
        if (updated != 1) {
            return false;
        }

        int attemptUpdated = attemptMapper.update(null,
                new LambdaUpdateWrapper<SemanticModelingDraftAttemptDO>()
                        .eq(SemanticModelingDraftAttemptDO::getDraftId, draftId)
                        .eq(SemanticModelingDraftAttemptDO::getAttemptNo, attemptNo)
                        .eq(SemanticModelingDraftAttemptDO::getStatus,
                                ModelingDraftConstants.ATTEMPT_STATUS_GENERATING)
                        .set(SemanticModelingDraftAttemptDO::getStatus,
                                ModelingDraftConstants.ATTEMPT_STATUS_SUCCEEDED)
                        .set(SemanticModelingDraftAttemptDO::getLlmConversationId, conversationId)
                        .set(SemanticModelingDraftAttemptDO::getGenerateRequestId,
                                generateRequestId)
                        .set(SemanticModelingDraftAttemptDO::getRepairRequestId, repairRequestId)
                        .set(SemanticModelingDraftAttemptDO::getRawOutput, rawOutput)
                        .set(SemanticModelingDraftAttemptDO::getRepairedOutput, repairedOutput)
                        .set(SemanticModelingDraftAttemptDO::getFailureStage, null)
                        .set(SemanticModelingDraftAttemptDO::getValidationIssues, null)
                        .set(SemanticModelingDraftAttemptDO::getErrorCode, null)
                        .set(SemanticModelingDraftAttemptDO::getErrorMessage, null)
                        .set(SemanticModelingDraftAttemptDO::getFinishedAt, now)
                        .set(SemanticModelingDraftAttemptDO::getUpdatedBy, user.getName())
                        .set(SemanticModelingDraftAttemptDO::getUpdatedAt, now));
        if (attemptUpdated != 1) {
            throw new IllegalStateException("Draft attempt completion state mismatch");
        }

        SemanticModelingDraftVersionDO version = new SemanticModelingDraftVersionDO();
        version.setDraftId(draftId);
        version.setVersionNo(1);
        version.setDraftJson(draftJson);
        version.setChangeSource(ModelingDraftConstants.VERSION_AI_GENERATED);
        version.setChangeSummary(StringUtils.isBlank(repairedOutput) ? "AI 首次生成" : "AI 修复后生成");
        version.setLlmConversationId(conversationId);
        version.setCreatedBy(user.getName());
        version.setCreatedAt(now);
        versionMapper.insert(version);
        moveGapStatus(draftId, SemanticGapStatus.WAITING_CONFIRMATION, user.getName(), now);
        return true;
    }

    /**
     * 将尚在 GENERATING 的草稿原子转为失败。
     *
     * @param draftId 草稿 ID。
     * @param errorCode 稳定错误码。
     * @param errorMessage 脱敏友好消息。
     * @param rawOutput 首次模型原文，仅后端保存。
     * @param repairedOutput 修复模型原文，仅后端保存。
     * @param operator 操作者名称。
     * @return 是否实际更新。
     */
    @Transactional(rollbackFor = Exception.class)
    public boolean failGeneration(Long draftId, Integer attemptNo, String errorCode,
            String errorMessage, String rawOutput, String repairedOutput, String failureStage,
            List<ModelingValidationIssue> validationIssues, String generateRequestId,
            String repairRequestId, String operator) {
        // 失败回写同样可能与管理员重试并发，必须先锁 Gap 再更新草稿主行。
        lockGapForDraft(draftId);
        Date now = new Date();
        int updated = draftMapper.update(null, new LambdaUpdateWrapper<SemanticModelingDraftDO>()
                .eq(SemanticModelingDraftDO::getId, draftId)
                .eq(SemanticModelingDraftDO::getCurrentAttemptNo, attemptNo)
                .eq(SemanticModelingDraftDO::getStatus, ModelingDraftConstants.STATUS_GENERATING)
                .set(SemanticModelingDraftDO::getStatus,
                        ModelingDraftConstants.STATUS_GENERATION_FAILED)
                .set(SemanticModelingDraftDO::getRawOutput, rawOutput)
                .set(SemanticModelingDraftDO::getRepairedOutput, repairedOutput)
                .set(SemanticModelingDraftDO::getErrorCode, errorCode)
                .set(SemanticModelingDraftDO::getErrorMessage,
                        StringUtils.abbreviate(errorMessage, 1000))
                .set(SemanticModelingDraftDO::getGenerationFinishedAt, now)
                .set(SemanticModelingDraftDO::getUpdatedBy, operator)
                .set(SemanticModelingDraftDO::getUpdatedAt, now));
        if (updated == 1) {
            int attemptUpdated = attemptMapper.update(null,
                    new LambdaUpdateWrapper<SemanticModelingDraftAttemptDO>()
                            .eq(SemanticModelingDraftAttemptDO::getDraftId, draftId)
                            .eq(SemanticModelingDraftAttemptDO::getAttemptNo, attemptNo)
                            .in(SemanticModelingDraftAttemptDO::getStatus,
                                    ModelingDraftConstants.ATTEMPT_STATUS_QUEUED,
                                    ModelingDraftConstants.ATTEMPT_STATUS_GENERATING)
                            .set(SemanticModelingDraftAttemptDO::getStatus,
                                    ModelingDraftConstants.ATTEMPT_STATUS_FAILED)
                            .set(SemanticModelingDraftAttemptDO::getGenerateRequestId,
                                    generateRequestId)
                            .set(SemanticModelingDraftAttemptDO::getRepairRequestId,
                                    repairRequestId)
                            .set(SemanticModelingDraftAttemptDO::getRawOutput, rawOutput)
                            .set(SemanticModelingDraftAttemptDO::getRepairedOutput, repairedOutput)
                            .set(SemanticModelingDraftAttemptDO::getFailureStage, failureStage)
                            .set(SemanticModelingDraftAttemptDO::getValidationIssues,
                                    writeJson(limitIssues(validationIssues)))
                            .set(SemanticModelingDraftAttemptDO::getErrorCode, errorCode)
                            .set(SemanticModelingDraftAttemptDO::getErrorMessage,
                                    StringUtils.abbreviate(errorMessage, 1000))
                            .set(SemanticModelingDraftAttemptDO::getFinishedAt, now)
                            .set(SemanticModelingDraftAttemptDO::getUpdatedBy, operator)
                            .set(SemanticModelingDraftAttemptDO::getUpdatedAt, now));
            if (attemptUpdated != 1) {
                throw new IllegalStateException("Draft attempt failure state mismatch");
            }
            moveGapStatus(draftId, SemanticGapStatus.PENDING_ANALYSIS, operator, now);
        }
        return updated == 1;
    }

    /**
     * 使用乐观锁保存人工修改并新增不可变版本。
     *
     * @param draft 草稿当前记录。
     * @param expectedLockVersion 客户端锁版本。
     * @param draftJson 已校验 JSON。
     * @param changeSummary 变更摘要。
     * @param user 操作者。
     * @return 保存后的最新主记录。
     * @throws ModelingDraftException 旧锁版本或状态不可保存时抛出 409。
     */
    @Transactional(rollbackFor = Exception.class)
    public SemanticModelingDraftDO saveVersion(SemanticModelingDraftDO draft,
            Integer expectedLockVersion, String draftJson, String changeSummary, User user) {
        // 人工保存和 AI 修订都写草稿主记录；先锁 draft 再读 attempt，避免跨实例产生并行版本分叉。
        revisionStore.assertNoActiveRevision(draft.getId(), user);
        int nextVersionNo = draft.getCurrentVersionNo() + 1;
        Date now = new Date();
        int updated = draftMapper.updateDraftWithVersion(draft.getId(), expectedLockVersion,
                draftJson, nextVersionNo, user.getName(), now);
        if (updated != 1) {
            throw new ModelingDraftException(HttpStatus.CONFLICT,
                    ModelingDraftConstants.ERROR_CONFLICT, "草稿已被其他操作更新，请重新加载后再保存");
        }

        SemanticModelingDraftVersionDO version = new SemanticModelingDraftVersionDO();
        version.setDraftId(draft.getId());
        version.setVersionNo(nextVersionNo);
        version.setDraftJson(draftJson);
        version.setChangeSource(ModelingDraftConstants.VERSION_MANUAL_SAVE);
        version.setChangeSummary(StringUtils.defaultIfBlank(changeSummary, "管理员保存草稿"));
        version.setLlmConversationId(draft.getLlmConversationId());
        version.setCreatedBy(user.getName());
        version.setCreatedAt(now);
        versionMapper.insert(version);
        return draftMapper.selectById(draft.getId());
    }

    /**
     * 批量把超时 GENERATING 草稿恢复为失败状态。
     *
     * @param cutoff 超时截止时间。
     * @return 被恢复的草稿数量。
     */
    @Transactional(rollbackFor = Exception.class)
    public int failStaleGenerations(Date cutoff) {
        List<SemanticModelingDraftDO> stale =
                draftMapper.selectList(new LambdaQueryWrapper<SemanticModelingDraftDO>()
                        .eq(SemanticModelingDraftDO::getStatus,
                                ModelingDraftConstants.STATUS_GENERATING)
                        .and(wrapper -> wrapper
                                .lt(SemanticModelingDraftDO::getGenerationStartedAt, cutoff)
                                .or(nested -> nested
                                        .isNull(SemanticModelingDraftDO::getGenerationStartedAt)
                                        .lt(SemanticModelingDraftDO::getCreatedAt, cutoff))));
        if (stale.isEmpty()) {
            return 0;
        }
        List<Long> gapIds = stale.stream()
                .filter(item -> ModelingDraftConstants.SOURCE_SEMANTIC_GAP
                        .equals(item.getSourceType()))
                .map(SemanticModelingDraftDO::getSourceId).filter(Objects::nonNull).distinct()
                .sorted().toList();
        if (!gapIds.isEmpty()) {
            // 所有涉及 Gap 的状态机事务统一先锁 Gap，再触碰草稿主行，避免和人工重试死锁。
            gapMapper.selectByIdsForUpdate(gapIds);
        }
        Date now = new Date();
        int updated = 0;
        Set<Long> updatedDraftIds = new java.util.LinkedHashSet<>();
        for (SemanticModelingDraftDO draft : stale) {
            int attemptNo = Objects.requireNonNullElse(draft.getCurrentAttemptNo(), 1);
            int changed = draftMapper.update(null,
                    new LambdaUpdateWrapper<SemanticModelingDraftDO>()
                            .eq(SemanticModelingDraftDO::getId, draft.getId())
                            .eq(SemanticModelingDraftDO::getCurrentAttemptNo, attemptNo)
                            .eq(SemanticModelingDraftDO::getStatus,
                                    ModelingDraftConstants.STATUS_GENERATING)
                            // 把超时条件放回原子 UPDATE，避免 SELECT 后刚被 Worker 认领的任务被误杀。
                            .and(wrapper -> wrapper
                                    .lt(SemanticModelingDraftDO::getGenerationStartedAt, cutoff)
                                    .or(nested -> nested
                                            .isNull(SemanticModelingDraftDO::getGenerationStartedAt)
                                            .lt(SemanticModelingDraftDO::getCreatedAt, cutoff)))
                            .set(SemanticModelingDraftDO::getStatus,
                                    ModelingDraftConstants.STATUS_GENERATION_FAILED)
                            .set(SemanticModelingDraftDO::getErrorCode,
                                    ModelingDraftConstants.ERROR_GENERATION_TIMEOUT)
                            .set(SemanticModelingDraftDO::getErrorMessage, "草稿生成超时，可从失败草稿重新生成")
                            .set(SemanticModelingDraftDO::getGenerationFinishedAt, now)
                            .set(SemanticModelingDraftDO::getUpdatedAt, now));
            if (changed != 1) {
                continue;
            }
            int attemptUpdated = attemptMapper.update(null,
                    new LambdaUpdateWrapper<SemanticModelingDraftAttemptDO>()
                            .eq(SemanticModelingDraftAttemptDO::getDraftId, draft.getId())
                            .eq(SemanticModelingDraftAttemptDO::getAttemptNo, attemptNo)
                            .in(SemanticModelingDraftAttemptDO::getStatus,
                                    ModelingDraftConstants.ATTEMPT_STATUS_QUEUED,
                                    ModelingDraftConstants.ATTEMPT_STATUS_GENERATING)
                            .set(SemanticModelingDraftAttemptDO::getStatus,
                                    ModelingDraftConstants.ATTEMPT_STATUS_FAILED)
                            .set(SemanticModelingDraftAttemptDO::getFailureStage,
                                    ModelingDraftConstants.FAILURE_STAGE_TIMEOUT)
                            .set(SemanticModelingDraftAttemptDO::getErrorCode,
                                    ModelingDraftConstants.ERROR_GENERATION_TIMEOUT)
                            .set(SemanticModelingDraftAttemptDO::getErrorMessage,
                                    "草稿生成超时，可从失败草稿重新生成")
                            .set(SemanticModelingDraftAttemptDO::getFinishedAt, now)
                            .set(SemanticModelingDraftAttemptDO::getUpdatedAt, now));
            if (attemptUpdated != 1) {
                throw new IllegalStateException("Draft stale attempt state mismatch");
            }
            updated++;
            updatedDraftIds.add(draft.getId());
        }
        if (updatedDraftIds.isEmpty()) {
            return 0;
        }

        List<Long> updatedGapIds = stale.stream()
                .filter(item -> updatedDraftIds.contains(item.getId()))
                .filter(item -> ModelingDraftConstants.SOURCE_SEMANTIC_GAP
                        .equals(item.getSourceType()))
                .map(SemanticModelingDraftDO::getSourceId).filter(Objects::nonNull).distinct()
                .toList();
        if (!updatedGapIds.isEmpty()) {
            // 批量复核候选 Gap 是否已有刚认领、刚完成或新创建的活动草稿；存在活动草稿时
            // 保留 DRAFTING/WAITING_CONFIRMATION，避免恢复线程回写旧状态。
            Set<Long> activeGapIds = draftMapper
                    .selectList(new LambdaQueryWrapper<SemanticModelingDraftDO>()
                            .eq(SemanticModelingDraftDO::getSourceType,
                                    ModelingDraftConstants.SOURCE_SEMANTIC_GAP)
                            .in(SemanticModelingDraftDO::getSourceId, updatedGapIds)
                            .in(SemanticModelingDraftDO::getStatus,
                                    ModelingDraftConstants.STATUS_GENERATING,
                                    ModelingDraftConstants.STATUS_DRAFT,
                                    ModelingDraftConstants.STATUS_PENDING_APPROVAL))
                    .stream().map(SemanticModelingDraftDO::getSourceId).filter(Objects::nonNull)
                    .collect(java.util.stream.Collectors.toSet());
            List<Long> recoverableGapIds =
                    updatedGapIds.stream().filter(id -> !activeGapIds.contains(id)).toList();
            if (!recoverableGapIds.isEmpty()) {
                gapMapper.update(null,
                        new LambdaUpdateWrapper<SemanticGapDO>()
                                .in(SemanticGapDO::getId, recoverableGapIds)
                                .eq(SemanticGapDO::getStatus, SemanticGapStatus.DRAFTING.name())
                                .set(SemanticGapDO::getStatus,
                                        SemanticGapStatus.PENDING_ANALYSIS.name())
                                .set(SemanticGapDO::getUpdatedAt, now));
            }
        }
        return updated;
    }

    /** 按草稿来源更新 Gap 状态，调用方已处于短事务。 */
    private void moveGapStatus(Long draftId, SemanticGapStatus target, String operator, Date now) {
        SemanticModelingDraftDO draft = draftMapper.selectById(draftId);
        if (draft == null
                || !ModelingDraftConstants.SOURCE_SEMANTIC_GAP.equals(draft.getSourceType())
                || draft.getSourceId() == null) {
            return;
        }
        gapMapper.update(null, new LambdaUpdateWrapper<SemanticGapDO>()
                .eq(SemanticGapDO::getId, draft.getSourceId())
                // 成功/失败只能结束本草稿建立的 DRAFTING 状态，不能覆盖管理员并发动作。
                .eq(SemanticGapDO::getStatus, SemanticGapStatus.DRAFTING.name())
                .set(SemanticGapDO::getStatus, target.name())
                .set(SemanticGapDO::getUpdatedBy, operator).set(SemanticGapDO::getUpdatedAt, now));
    }

    /** 按草稿来源锁定 Gap 行；数据源来源无需额外数据库锁。 */
    private void lockGapForDraft(Long draftId) {
        SemanticModelingDraftDO draft = draftMapper.selectById(draftId);
        if (draft != null
                && ModelingDraftConstants.SOURCE_SEMANTIC_GAP.equals(draft.getSourceType())
                && draft.getSourceId() != null) {
            gapMapper.selectByIdForUpdate(draft.getSourceId());
        }
    }

    /** 插入一条 QUEUED attempt；调用方负责与主表变更放在同一事务。 */
    private void insertAttempt(Long draftId, int attemptNo, String triggerType, Integer chatModelId,
            boolean includeSample, String idempotencyKey, String requestFingerprint,
            String operator, Date now) {
        SemanticModelingDraftAttemptDO attempt = new SemanticModelingDraftAttemptDO();
        attempt.setDraftId(draftId);
        attempt.setAttemptNo(attemptNo);
        attempt.setTriggerType(triggerType);
        attempt.setStatus(ModelingDraftConstants.ATTEMPT_STATUS_QUEUED);
        attempt.setChatModelId(chatModelId);
        attempt.setIncludeSample(includeSample);
        attempt.setIdempotencyKey(idempotencyKey);
        attempt.setRequestFingerprint(requestFingerprint);
        attempt.setCreatedBy(operator);
        attempt.setCreatedAt(now);
        attempt.setUpdatedBy(operator);
        attempt.setUpdatedAt(now);
        attemptMapper.insert(attempt);
    }

    /** 限制持久化的安全校验问题数量，防止异常 Provider 造成诊断字段无界增长。 */
    private List<ModelingValidationIssue> limitIssues(List<ModelingValidationIssue> issues) {
        if (issues == null || issues.isEmpty()) {
            return List.of();
        }
        return List.copyOf(issues.subList(0,
                Math.min(ModelingDraftConstants.MAX_VALIDATION_ISSUES, issues.size())));
    }

    /** 序列化服务端规范化选表。 */
    private String writeJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new ModelingDraftException(HttpStatus.BAD_REQUEST,
                    ModelingDraftConstants.ERROR_INVALID_REQUEST, "选表参数无法序列化");
        }
    }

    /** 创建事务结果。 */
    public record CreateResult(SemanticModelingDraftDO draft, boolean replay) {}

    /** 人工重新生成事务结果。 */
    public record RegenerationResult(SemanticModelingDraftDO draft,
            SemanticModelingDraftAttemptDO attempt, boolean replay) {}
}
