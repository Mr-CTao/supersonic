package com.tencent.supersonic.headless.server.semantic.modeling.release;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.pagehelper.PageHelper;
import com.github.pagehelper.PageInfo;
import com.tencent.supersonic.common.pojo.User;
import com.tencent.supersonic.headless.server.persistence.dataobject.SemanticModelingDraftDO;
import com.tencent.supersonic.headless.server.persistence.dataobject.SemanticModelingDraftVersionDO;
import com.tencent.supersonic.headless.server.persistence.dataobject.SemanticModelingValidationReportDO;
import com.tencent.supersonic.headless.server.persistence.dataobject.SemanticReleaseDO;
import com.tencent.supersonic.headless.server.persistence.dataobject.SemanticReleaseStepDO;
import com.tencent.supersonic.headless.server.persistence.mapper.SemanticModelingDraftMapper;
import com.tencent.supersonic.headless.server.persistence.mapper.SemanticModelingDraftVersionMapper;
import com.tencent.supersonic.headless.server.persistence.mapper.SemanticModelingValidationReportMapper;
import com.tencent.supersonic.headless.server.persistence.mapper.SemanticReleaseMapper;
import com.tencent.supersonic.headless.server.persistence.mapper.SemanticReleaseStepMapper;
import com.tencent.supersonic.headless.server.semantic.modeling.ModelingDraftConstants;
import com.tencent.supersonic.headless.server.semantic.modeling.ModelingDraftException;
import org.apache.commons.lang3.StringUtils;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 阶段 5 发布状态与步骤持久化仓库。
 *
 * <p>
 * 职责说明：集中处理审批状态机、发布认领、步骤幂等、终态、分页审计与回滚状态。正式语义 资产变更不在事务中执行，避免长事务持锁；每个外部 API 前后分别通过短事务记录步骤。
 * 并发说明：草稿、发布和步骤均使用数据库行锁与唯一键，多实例下同一草稿最多一个发布流程。
 * </p>
 */
@Repository
public class SemanticReleaseStore {

    private static final long STALE_STEP_MILLIS = 5 * 60 * 1000L;
    private static final TypeReference<List<SemanticReleaseResp.ReleasedObject>> RELEASED_OBJECTS =
            new TypeReference<>() {};

    private final SemanticModelingDraftMapper draftMapper;
    private final SemanticModelingDraftVersionMapper versionMapper;
    private final SemanticModelingValidationReportMapper reportMapper;
    private final SemanticReleaseMapper releaseMapper;
    private final SemanticReleaseStepMapper stepMapper;
    private final ObjectMapper objectMapper;

    /**
     * 创建发布持久化仓库。
     *
     * @param draftMapper 草稿 Mapper。
     * @param versionMapper 草稿版本 Mapper。
     * @param reportMapper 验证报告 Mapper。
     * @param releaseMapper 发布主记录 Mapper。
     * @param stepMapper 发布步骤 Mapper。
     * @param objectMapper JSON 序列化器。
     */
    public SemanticReleaseStore(SemanticModelingDraftMapper draftMapper,
            SemanticModelingDraftVersionMapper versionMapper,
            SemanticModelingValidationReportMapper reportMapper,
            SemanticReleaseMapper releaseMapper, SemanticReleaseStepMapper stepMapper,
            ObjectMapper objectMapper) {
        this.draftMapper = draftMapper;
        this.versionMapper = versionMapper;
        this.reportMapper = reportMapper;
        this.releaseMapper = releaseMapper;
        this.stepMapper = stepMapper;
        this.objectMapper = objectMapper;
    }

    /**
     * 审批通过验证报告仍有效的待审批草稿。
     *
     * @param draftId 草稿 ID。
     * @param reason 可选审批备注。
     * @param user 系统管理员。
     * @return 审批后的草稿。
     * @throws ModelingDraftException 草稿状态、版本或验证报告不满足门禁。
     */
    @Transactional
    public SemanticModelingDraftDO approve(Long draftId, String reason, User user) {
        SemanticModelingDraftDO draft = requireDraftForUpdate(draftId);
        requirePendingApproval(draft);
        requireValidSubmission(draft);
        Date now = new Date();
        if (draftMapper.approve(draftId, user.getName(), now, trimToNull(reason)) != 1) {
            throw stateConflict("草稿审批状态已变化，请刷新后重试");
        }
        return draftMapper.selectById(draftId);
    }

    /**
     * 拒绝待审批草稿并记录原因。
     *
     * @param draftId 草稿 ID。
     * @param reason 必填拒绝原因。
     * @param user 系统管理员。
     * @return 拒绝后的草稿。
     * @throws ModelingDraftException 原因为空或状态不再待审批。
     */
    @Transactional
    public SemanticModelingDraftDO reject(Long draftId, String reason, User user) {
        if (StringUtils.isBlank(reason)) {
            throw invalid("审批拒绝原因不能为空");
        }
        SemanticModelingDraftDO draft = requireDraftForUpdate(draftId);
        requirePendingApproval(draft);
        Date now = new Date();
        if (draftMapper.reject(draftId, user.getName(), now, reason.trim()) != 1) {
            throw stateConflict("草稿审批状态已变化，请刷新后重试");
        }
        return draftMapper.selectById(draftId);
    }

    /**
     * 创建或恢复同一草稿的唯一发布流程。
     *
     * <p>
     * 已成功或已回滚发布只做幂等读取；失败发布可重新进入 IN_PROGRESS 并由步骤层跳过成功项。
     * </p>
     *
     * @param draftId 草稿 ID。
     * @param idempotencyKey 发布请求幂等键。
     * @param user 发布管理员。
     * @return 发布认领结果。
     * @throws ModelingDraftException 审批、验证或状态门禁不满足。
     */
    @Transactional
    public ReleaseClaim beginRelease(Long draftId, String idempotencyKey, User user) {
        validateIdempotencyKey(idempotencyKey);
        SemanticModelingDraftDO draft = requireDraftForUpdate(draftId);
        requireValidSubmission(draft);
        SemanticReleaseDO existing = selectReleaseByDraftId(draftId);
        if (existing != null) {
            boolean terminal = Set
                    .of(SemanticReleaseConstants.RELEASE_SUCCEEDED,
                            SemanticReleaseConstants.RELEASE_ROLLED_BACK)
                    .contains(existing.getReleaseStatus());
            if (terminal) {
                return new ReleaseClaim(existing, draft, false);
            }
            if (!SemanticReleaseConstants.RELEASABLE_DRAFT_STATUSES.contains(draft.getStatus())) {
                throw stateConflict("只有审批通过或发布失败的草稿可以发布");
            }
            boolean active =
                    SemanticReleaseConstants.RELEASE_IN_PROGRESS.equals(existing.getReleaseStatus())
                            && !isStale(existing.getUpdatedAt());
            if (active) {
                return new ReleaseClaim(existing, draft, false);
            }
            if (!terminal) {
                existing.setReleaseStatus(SemanticReleaseConstants.RELEASE_IN_PROGRESS);
                existing.setErrorMessage(null);
                existing.setUpdatedAt(new Date());
                releaseMapper.updateById(existing);
                ensureDraftReleasing(draft, user);
            }
            return new ReleaseClaim(existing, draft, !terminal);
        }
        if (!SemanticReleaseConstants.RELEASABLE_DRAFT_STATUSES.contains(draft.getStatus())) {
            throw stateConflict("只有审批通过或发布失败的草稿可以发布");
        }

        SemanticModelingDraftVersionDO version = requireSubmittedVersion(draft);
        Date now = new Date();
        SemanticReleaseDO release = new SemanticReleaseDO();
        release.setReleaseNo("AIR-" + UUID.randomUUID().toString().replace("-", ""));
        release.setDraftId(draftId);
        release.setDraftVersionId(version.getId());
        release.setDraftVersionNo(version.getVersionNo());
        release.setValidationReportId(draft.getSubmittedValidationReportId());
        release.setReleaseStatus(SemanticReleaseConstants.RELEASE_IN_PROGRESS);
        release.setReleasedObjects("[]");
        release.setDictReloadStatus(SemanticReleaseConstants.REFRESH_PENDING);
        release.setEmbeddingReloadStatus(SemanticReleaseConstants.REFRESH_PENDING);
        release.setApprovedBy(draft.getApprovedBy());
        release.setReleasedBy(user.getName());
        release.setIdempotencyKey(idempotencyKey);
        release.setCreatedAt(now);
        release.setUpdatedAt(now);
        releaseMapper.insert(release);
        ensureDraftReleasing(draft, user);
        return new ReleaseClaim(release, draft, true);
    }

    /**
     * 认领一个发布或回滚步骤。
     *
     * @param releaseId 发布 ID。
     * @param descriptor 稳定步骤描述。
     * @return execute=false 表示步骤已成功，无需再次调用外部 API。
     * @throws ModelingDraftException 另一实例仍持有未过期步骤租约。
     */
    @Transactional
    public StepClaim claimStep(Long releaseId, StepDescriptor descriptor) {
        requireReleaseForUpdate(releaseId);
        SemanticReleaseStepDO step =
                stepMapper.selectByKeyForUpdate(releaseId, descriptor.stepKey());
        if (step == null) {
            step = new SemanticReleaseStepDO();
            step.setReleaseId(releaseId);
            step.setStepKey(descriptor.stepKey());
            step.setStepType(descriptor.stepType());
            step.setTargetType(descriptor.targetType());
            step.setTargetKey(descriptor.targetKey());
            step.setTargetName(descriptor.targetName());
            step.setStatus(SemanticReleaseConstants.STEP_IN_PROGRESS);
            step.setAttemptCount(1);
            Date now = new Date();
            step.setStartedAt(now);
            step.setCreatedAt(now);
            step.setUpdatedAt(now);
            stepMapper.insert(step);
            return new StepClaim(step, true);
        }
        if (SemanticReleaseConstants.STEP_SUCCEEDED.equals(step.getStatus())) {
            return new StepClaim(step, false);
        }
        if (SemanticReleaseConstants.STEP_IN_PROGRESS.equals(step.getStatus())
                && !isStale(step.getStartedAt())) {
            throw new ModelingDraftException(HttpStatus.CONFLICT,
                    SemanticReleaseConstants.ERROR_STEP_RUNNING, "该发布步骤正在执行，请稍后刷新");
        }
        Date now = new Date();
        step.setStatus(SemanticReleaseConstants.STEP_IN_PROGRESS);
        step.setAttemptCount(Objects.requireNonNullElse(step.getAttemptCount(), 0) + 1);
        step.setErrorMessage(null);
        step.setStartedAt(now);
        step.setFinishedAt(null);
        step.setUpdatedAt(now);
        stepMapper.updateById(step);
        return new StepClaim(step, true);
    }

    /**
     * 把步骤标记为成功并保存正式对象 ID。
     *
     * @param stepId 步骤 ID。
     * @param targetId 正式对象 ID；知识刷新步骤可为空。
     */
    @Transactional
    public void completeStep(Long stepId, Long targetId) {
        SemanticReleaseStepDO step = requireStep(stepId);
        Date now = new Date();
        step.setTargetId(targetId);
        step.setStatus(SemanticReleaseConstants.STEP_SUCCEEDED);
        step.setErrorMessage(null);
        step.setFinishedAt(now);
        step.setUpdatedAt(now);
        stepMapper.updateById(step);
        updateRefreshStatus(step, SemanticReleaseConstants.REFRESH_SUCCEEDED);
    }

    /**
     * 把步骤标记为失败并保存脱敏错误摘要。
     *
     * @param stepId 步骤 ID。
     * @param errorMessage 已脱敏错误摘要。
     */
    @Transactional
    public void failStep(Long stepId, String errorMessage) {
        SemanticReleaseStepDO step = requireStep(stepId);
        Date now = new Date();
        step.setStatus(SemanticReleaseConstants.STEP_FAILED);
        step.setErrorMessage(abbreviate(errorMessage));
        step.setFinishedAt(now);
        step.setUpdatedAt(now);
        stepMapper.updateById(step);
        updateRefreshStatus(step, SemanticReleaseConstants.REFRESH_FAILED);
    }

    /**
     * 根据对象步骤和知识刷新结果完成发布。
     *
     * @param releaseId 发布 ID。
     * @param success 所有对象与两个知识刷新步骤是否成功。
     * @param errorMessage 失败摘要。
     * @param user 发布管理员。
     */
    @Transactional
    public void finishRelease(Long releaseId, boolean success, String errorMessage, User user) {
        SemanticReleaseDO release = requireReleaseForUpdate(releaseId);
        List<SemanticReleaseResp.ReleasedObject> objects = releasedObjects(releaseId);
        release.setReleasedObjects(writeJson(objects));
        release.setReleaseStatus(success ? SemanticReleaseConstants.RELEASE_SUCCEEDED
                : SemanticReleaseConstants.RELEASE_FAILED);
        release.setErrorMessage(success ? null : abbreviate(errorMessage));
        release.setReleasedAt(success ? new Date() : null);
        release.setUpdatedAt(new Date());
        releaseMapper.updateById(release);
        draftMapper
                .finishRelease(release.getDraftId(),
                        success ? ModelingDraftConstants.STATUS_RELEASED
                                : ModelingDraftConstants.STATUS_RELEASE_FAILED,
                        user.getName(), new Date());
    }

    /**
     * 把成功发布认领为回滚中并保存原因。
     *
     * @param releaseId 发布 ID。
     * @param reason 回滚原因。
     * @param user 回滚管理员。
     * @return 回滚认领结果。
     */
    @Transactional
    public RollbackClaim beginRollback(Long releaseId, String reason, User user) {
        if (StringUtils.isBlank(reason)) {
            throw invalid("回滚原因不能为空");
        }
        SemanticReleaseDO release = requireReleaseForUpdate(releaseId);
        if (SemanticReleaseConstants.RELEASE_ROLLED_BACK.equals(release.getReleaseStatus())) {
            return new RollbackClaim(release, false);
        }
        if (SemanticReleaseConstants.RELEASE_ROLLBACK_IN_PROGRESS.equals(release.getReleaseStatus())
                && !isStale(release.getUpdatedAt())) {
            return new RollbackClaim(release, false);
        }
        if (!Set.of(SemanticReleaseConstants.RELEASE_SUCCEEDED,
                SemanticReleaseConstants.RELEASE_ROLLBACK_FAILED,
                SemanticReleaseConstants.RELEASE_ROLLBACK_IN_PROGRESS)
                .contains(release.getReleaseStatus())) {
            throw stateConflict("只有成功发布的 AI 新增对象可以回滚");
        }
        release.setReleaseStatus(SemanticReleaseConstants.RELEASE_ROLLBACK_IN_PROGRESS);
        // 第一版在原发布记录内执行补偿，因此把自身 ID 作为回滚来源，明确审计回滚点。
        release.setRollbackFromReleaseId(release.getId());
        release.setRollbackReason(reason.trim());
        release.setRolledBackBy(user.getName());
        release.setRolledBackAt(null);
        release.setDictReloadStatus(SemanticReleaseConstants.REFRESH_PENDING);
        release.setEmbeddingReloadStatus(SemanticReleaseConstants.REFRESH_PENDING);
        release.setErrorMessage(null);
        release.setUpdatedAt(new Date());
        releaseMapper.updateById(release);
        return new RollbackClaim(release, true);
    }

    /**
     * 完成回滚状态；仅全部对象删除和两个知识刷新步骤成功时标记 ROLLED_BACK。
     *
     * @param releaseId 发布 ID。
     * @param success 回滚是否完整成功。
     * @param errorMessage 失败摘要。
     * @param user 回滚管理员。
     */
    @Transactional
    public void finishRollback(Long releaseId, boolean success, String errorMessage, User user) {
        SemanticReleaseDO release = requireReleaseForUpdate(releaseId);
        release.setReleaseStatus(success ? SemanticReleaseConstants.RELEASE_ROLLED_BACK
                : SemanticReleaseConstants.RELEASE_ROLLBACK_FAILED);
        release.setRolledBackAt(success ? new Date() : null);
        release.setErrorMessage(success ? null : abbreviate(errorMessage));
        release.setUpdatedAt(new Date());
        releaseMapper.updateById(release);
        if (success) {
            draftMapper.markRolledBack(release.getDraftId(), user.getName(), new Date());
        }
    }

    /**
     * 分页查询待审批和已做审批决定的草稿。
     *
     * @param request 状态、关键词和分页条件。
     * @return 审批摘要分页。
     */
    public PageInfo<SemanticApprovalResp> queryApprovals(SemanticReleaseQueryReq request) {
        LambdaQueryWrapper<SemanticModelingDraftDO> wrapper =
                new LambdaQueryWrapper<SemanticModelingDraftDO>()
                        .orderByDesc(SemanticModelingDraftDO::getSubmittedAt);
        if (StringUtils.isNotBlank(request.getStatus())) {
            wrapper.eq(SemanticModelingDraftDO::getStatus, normalizeStatus(request.getStatus()));
        } else {
            wrapper.in(SemanticModelingDraftDO::getStatus,
                    ModelingDraftConstants.STATUS_PENDING_APPROVAL,
                    ModelingDraftConstants.STATUS_APPROVED, ModelingDraftConstants.STATUS_REJECTED,
                    ModelingDraftConstants.STATUS_RELEASE_FAILED);
        }
        if (StringUtils.isNotBlank(request.getKeyword())) {
            String keyword = StringUtils.abbreviate(request.getKeyword().trim(), 128);
            wrapper.and(nested -> nested.like(SemanticModelingDraftDO::getTitle, keyword).or()
                    .like(SemanticModelingDraftDO::getBusinessGoal, keyword));
        }
        PageInfo<SemanticModelingDraftDO> page =
                PageHelper.startPage(request.getPage(), request.getPageSize())
                        .doSelectPageInfo(() -> draftMapper.selectList(wrapper));
        Map<Long, SemanticModelingValidationReportDO> reports = reportsById(
                page.getList().stream().map(SemanticModelingDraftDO::getSubmittedValidationReportId)
                        .filter(Objects::nonNull).collect(Collectors.toSet()));
        return mapPage(page, page.getList().stream().map(draft -> approvalResponse(draft,
                reports.get(draft.getSubmittedValidationReportId()))).toList());
    }

    /**
     * 分页查询发布审计主记录。
     *
     * @param request 状态、关键词和分页条件。
     * @return 不含步骤明细的发布分页。
     */
    public PageInfo<SemanticReleaseResp> queryReleases(SemanticReleaseQueryReq request) {
        LambdaQueryWrapper<SemanticReleaseDO> wrapper =
                new LambdaQueryWrapper<SemanticReleaseDO>().orderByDesc(SemanticReleaseDO::getId);
        if (StringUtils.isNotBlank(request.getStatus())) {
            wrapper.eq(SemanticReleaseDO::getReleaseStatus, normalizeStatus(request.getStatus()));
        }
        if (StringUtils.isNotBlank(request.getKeyword())) {
            String keyword = StringUtils.abbreviate(request.getKeyword().trim(), 128);
            wrapper.like(SemanticReleaseDO::getReleaseNo, keyword);
        }
        PageInfo<SemanticReleaseDO> page =
                PageHelper.startPage(request.getPage(), request.getPageSize())
                        .doSelectPageInfo(() -> releaseMapper.selectList(wrapper));
        Map<Long, SemanticModelingDraftDO> drafts = draftsById(page.getList().stream()
                .map(SemanticReleaseDO::getDraftId).collect(Collectors.toSet()));
        return mapPage(page, page.getList().stream()
                .map(release -> releaseResponse(release, drafts.get(release.getDraftId()), false))
                .toList());
    }

    /**
     * 查询发布详情及全部步骤。
     *
     * @param releaseId 发布 ID。
     * @return 发布详情。
     * @throws ModelingDraftException 发布不存在。
     */
    public SemanticReleaseResp getRelease(Long releaseId) {
        SemanticReleaseDO release = releaseMapper.selectById(releaseId);
        if (release == null) {
            throw notFound();
        }
        return releaseResponse(release, draftMapper.selectById(release.getDraftId()), true);
    }

    /** 查询发布的创建成功对象步骤，供回滚按类型逆序执行。 */
    public List<SemanticReleaseStepDO> successfulObjectSteps(Long releaseId) {
        return stepMapper.selectList(new LambdaQueryWrapper<SemanticReleaseStepDO>()
                .eq(SemanticReleaseStepDO::getReleaseId, releaseId)
                .eq(SemanticReleaseStepDO::getStatus, SemanticReleaseConstants.STEP_SUCCEEDED)
                .in(SemanticReleaseStepDO::getStepType, SemanticReleaseConstants.STEP_CREATE_MODEL,
                        SemanticReleaseConstants.STEP_CREATE_DIMENSION,
                        SemanticReleaseConstants.STEP_CREATE_METRIC,
                        SemanticReleaseConstants.STEP_CREATE_TERM));
    }

    /** 查询已成功的对象回滚步骤数量，供知识刷新重试确认对象补偿已完整。 */
    public int successfulRollbackStepCount(Long releaseId) {
        return stepMapper.selectList(new LambdaQueryWrapper<SemanticReleaseStepDO>()
                .eq(SemanticReleaseStepDO::getReleaseId, releaseId)
                .eq(SemanticReleaseStepDO::getStatus, SemanticReleaseConstants.STEP_SUCCEEDED)
                .in(SemanticReleaseStepDO::getStepType, SemanticReleaseConstants.STEP_ROLLBACK_TERM,
                        SemanticReleaseConstants.STEP_ROLLBACK_METRIC,
                        SemanticReleaseConstants.STEP_ROLLBACK_DIMENSION,
                        SemanticReleaseConstants.STEP_ROLLBACK_MODEL))
                .size();
    }

    /** 查询发布主记录；服务层在权限校验后用于知识刷新重试。 */
    public SemanticReleaseDO requireRelease(Long releaseId) {
        SemanticReleaseDO release = releaseMapper.selectById(releaseId);
        if (release == null) {
            throw notFound();
        }
        return release;
    }

    /**
     * 查询发布绑定草稿，供刷新重试和回滚关联缺口使用。
     *
     * @param draftId 草稿 ID。
     * @return 草稿主记录。
     * @throws ModelingDraftException 草稿不存在。
     */
    public SemanticModelingDraftDO requireDraft(Long draftId) {
        SemanticModelingDraftDO draft = draftMapper.selectById(draftId);
        if (draft == null) {
            throw new ModelingDraftException(HttpStatus.NOT_FOUND,
                    ModelingDraftConstants.ERROR_NOT_FOUND, "语义建模草稿不存在");
        }
        return draft;
    }

    /** 确保当前草稿进入发布中状态。 */
    private void ensureDraftReleasing(SemanticModelingDraftDO draft, User user) {
        if (draftMapper.markReleasing(draft.getId(), user.getName(), new Date()) != 1) {
            throw stateConflict("草稿发布状态已变化，请刷新后重试");
        }
    }

    /** 读取并锁定草稿。 */
    private SemanticModelingDraftDO requireDraftForUpdate(Long draftId) {
        SemanticModelingDraftDO draft = draftMapper.selectByIdForUpdate(draftId);
        if (draft == null) {
            throw new ModelingDraftException(HttpStatus.NOT_FOUND,
                    ModelingDraftConstants.ERROR_NOT_FOUND, "语义建模草稿不存在");
        }
        return draft;
    }

    /** 校验草稿仍处于待审批。 */
    private void requirePendingApproval(SemanticModelingDraftDO draft) {
        if (!ModelingDraftConstants.STATUS_PENDING_APPROVAL.equals(draft.getStatus())) {
            throw stateConflict("草稿已不处于待审批状态");
        }
    }

    /** 重新验证提交绑定的报告仍属于当前不可变版本且结果通过。 */
    private SemanticModelingValidationReportDO requireValidSubmission(
            SemanticModelingDraftDO draft) {
        if (draft.getSubmittedValidationReportId() == null) {
            throw stateConflict("草稿缺少提交审批时绑定的验证报告");
        }
        SemanticModelingValidationReportDO report =
                reportMapper.selectById(draft.getSubmittedValidationReportId());
        if (report == null || !Objects.equals(report.getDraftId(), draft.getId())
                || !Objects.equals(report.getDraftVersionNo(), draft.getCurrentVersionNo())
                || !ModelingDraftConstants.VALIDATION_PASSED.equals(report.getStatus())
                || Objects.requireNonNullElse(report.getBlockingCount(), 0) != 0) {
            throw stateConflict("验证报告已失效或未通过，不能审批发布");
        }
        return report;
    }

    /** 查询提交审批绑定的不可变版本。 */
    private SemanticModelingDraftVersionDO requireSubmittedVersion(SemanticModelingDraftDO draft) {
        SemanticModelingDraftVersionDO version =
                versionMapper.selectOne(new LambdaQueryWrapper<SemanticModelingDraftVersionDO>()
                        .eq(SemanticModelingDraftVersionDO::getDraftId, draft.getId())
                        .eq(SemanticModelingDraftVersionDO::getVersionNo,
                                draft.getCurrentVersionNo()));
        if (version == null) {
            throw stateConflict("草稿不可变版本不存在，不能发布");
        }
        return version;
    }

    /** 按草稿查询唯一发布记录。 */
    private SemanticReleaseDO selectReleaseByDraftId(Long draftId) {
        return releaseMapper.selectOne(new LambdaQueryWrapper<SemanticReleaseDO>()
                .eq(SemanticReleaseDO::getDraftId, draftId));
    }

    /** 锁定发布主记录。 */
    private SemanticReleaseDO requireReleaseForUpdate(Long releaseId) {
        SemanticReleaseDO release = releaseMapper.selectByIdForUpdate(releaseId);
        if (release == null) {
            throw notFound();
        }
        return release;
    }

    /** 读取步骤并确保存在。 */
    private SemanticReleaseStepDO requireStep(Long stepId) {
        SemanticReleaseStepDO step = stepMapper.selectById(stepId);
        if (step == null) {
            throw new ModelingDraftException(HttpStatus.NOT_FOUND,
                    SemanticReleaseConstants.ERROR_NOT_FOUND, "发布步骤不存在");
        }
        return step;
    }

    /** 判断未完成步骤是否超过保守恢复窗口。 */
    private boolean isStale(Date startedAt) {
        return startedAt == null
                || startedAt.getTime() + STALE_STEP_MILLIS < System.currentTimeMillis();
    }

    /** 把知识刷新步骤结果同步到发布主记录的独立状态字段。 */
    private void updateRefreshStatus(SemanticReleaseStepDO step, String status) {
        if (!SemanticReleaseConstants.TYPE_KNOWLEDGE.equals(step.getTargetType())) {
            return;
        }
        SemanticReleaseDO release = releaseMapper.selectByIdForUpdate(step.getReleaseId());
        if (release == null) {
            throw notFound();
        }
        if (SemanticReleaseConstants.STEP_RELOAD_DICT.equals(step.getStepType())) {
            release.setDictReloadStatus(status);
        } else if (SemanticReleaseConstants.STEP_RELOAD_EMBEDDING.equals(step.getStepType())) {
            release.setEmbeddingReloadStatus(status);
        }
        release.setUpdatedAt(new Date());
        releaseMapper.updateById(release);
    }

    /** 从创建成功步骤生成回滚白名单和安全展示对象摘要。 */
    private List<SemanticReleaseResp.ReleasedObject> releasedObjects(Long releaseId) {
        return successfulObjectSteps(releaseId).stream()
                .map(step -> SemanticReleaseResp.ReleasedObject.builder().type(step.getTargetType())
                        .key(step.getTargetKey()).name(step.getTargetName())
                        .targetId(step.getTargetId()).build())
                .toList();
    }

    /** 批量读取验证报告，避免待审批列表 N+1。 */
    private Map<Long, SemanticModelingValidationReportDO> reportsById(Set<Long> ids) {
        if (ids.isEmpty()) {
            return Map.of();
        }
        return reportMapper.selectByIds(ids).stream().collect(
                Collectors.toMap(SemanticModelingValidationReportDO::getId, Function.identity()));
    }

    /** 批量读取草稿摘要，避免发布列表 N+1。 */
    private Map<Long, SemanticModelingDraftDO> draftsById(Set<Long> ids) {
        if (ids.isEmpty()) {
            return Map.of();
        }
        return draftMapper.selectByIds(ids).stream()
                .collect(Collectors.toMap(SemanticModelingDraftDO::getId, Function.identity()));
    }

    /** 组装待审批响应并安全计算计划对象数量。 */
    private SemanticApprovalResp approvalResponse(SemanticModelingDraftDO draft,
            SemanticModelingValidationReportDO report) {
        return SemanticApprovalResp.builder().draftId(draft.getId()).title(draft.getTitle())
                .businessGoal(draft.getBusinessGoal())
                .sourceGapId(
                        ModelingDraftConstants.SOURCE_SEMANTIC_GAP.equals(draft.getSourceType())
                                ? draft.getSourceId()
                                : null)
                .domainId(draft.getDomainId()).dataSourceId(draft.getDataSourceId())
                .status(draft.getStatus()).draftVersionNo(draft.getCurrentVersionNo())
                .validationReportId(draft.getSubmittedValidationReportId())
                .validationStatus(report == null ? null : report.getStatus())
                .plannedObjectCount(plannedObjectCount(report)).submittedBy(draft.getSubmittedBy())
                .submittedAt(draft.getSubmittedAt()).approvedBy(draft.getApprovedBy())
                .approvedAt(draft.getApprovedAt()).approvalReason(draft.getApprovalReason())
                .build();
    }

    /** 仅解析计划对象数组长度，解析失败按 0 展示且不泄露原文。 */
    private int plannedObjectCount(SemanticModelingValidationReportDO report) {
        if (report == null || StringUtils.isBlank(report.getPlannedObjects())) {
            return 0;
        }
        try {
            return objectMapper.readTree(report.getPlannedObjects()).size();
        } catch (Exception ignored) {
            return 0;
        }
    }

    /** 组装发布响应；详情按需查询步骤，列表不产生 N+1。 */
    private SemanticReleaseResp releaseResponse(SemanticReleaseDO release,
            SemanticModelingDraftDO draft, boolean includeSteps) {
        return SemanticReleaseResp.builder().id(release.getId()).releaseNo(release.getReleaseNo())
                .draftId(release.getDraftId()).draftVersionNo(release.getDraftVersionNo())
                .validationReportId(release.getValidationReportId())
                .draftTitle(draft == null ? null : draft.getTitle())
                .sourceGapId(draft != null
                        && ModelingDraftConstants.SOURCE_SEMANTIC_GAP.equals(draft.getSourceType())
                                ? draft.getSourceId()
                                : null)
                .releaseStatus(release.getReleaseStatus())
                .dictReloadStatus(release.getDictReloadStatus())
                .embeddingReloadStatus(release.getEmbeddingReloadStatus())
                .approvedBy(release.getApprovedBy()).releasedBy(release.getReleasedBy())
                .releasedAt(release.getReleasedAt()).errorMessage(release.getErrorMessage())
                .rollbackReason(release.getRollbackReason()).rolledBackBy(release.getRolledBackBy())
                .rolledBackAt(release.getRolledBackAt())
                .releasedObjects(readReleasedObjects(release.getReleasedObjects()))
                .steps(includeSteps ? stepResponses(release.getId()) : List.of()).build();
    }

    /** 查询并按创建顺序返回步骤。 */
    private List<SemanticReleaseStepResp> stepResponses(Long releaseId) {
        return stepMapper
                .selectList(new LambdaQueryWrapper<SemanticReleaseStepDO>()
                        .eq(SemanticReleaseStepDO::getReleaseId, releaseId)
                        .orderByAsc(SemanticReleaseStepDO::getId))
                .stream().map(this::stepResponse).toList();
    }

    /** 转换单个步骤响应。 */
    private SemanticReleaseStepResp stepResponse(SemanticReleaseStepDO step) {
        return SemanticReleaseStepResp.builder().id(step.getId()).stepKey(step.getStepKey())
                .stepType(step.getStepType()).targetType(step.getTargetType())
                .targetKey(step.getTargetKey()).targetName(step.getTargetName())
                .targetId(step.getTargetId()).status(step.getStatus())
                .attemptCount(step.getAttemptCount()).errorMessage(step.getErrorMessage())
                .startedAt(step.getStartedAt()).finishedAt(step.getFinishedAt()).build();
    }

    /** 安全解析已发布对象摘要。 */
    private List<SemanticReleaseResp.ReleasedObject> readReleasedObjects(String json) {
        if (StringUtils.isBlank(json)) {
            return List.of();
        }
        try {
            return objectMapper.readValue(json, RELEASED_OBJECTS);
        } catch (Exception ignored) {
            return List.of();
        }
    }

    /** 序列化服务端生成的对象摘要。 */
    private String writeJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception exception) {
            throw new ModelingDraftException(HttpStatus.INTERNAL_SERVER_ERROR,
                    SemanticReleaseConstants.ERROR_INTERNAL, "发布审计结果序列化失败");
        }
    }

    /** 校验发布请求幂等键。 */
    private void validateIdempotencyKey(String key) {
        if (StringUtils.isBlank(key)
                || key.length() > SemanticReleaseConstants.IDEMPOTENCY_KEY_MAX_LENGTH) {
            throw invalid("Idempotency-Key 不能为空且不能超过 128 个字符");
        }
    }

    /** 规范状态筛选。 */
    private String normalizeStatus(String value) {
        return value.trim().toUpperCase(Locale.ROOT);
    }

    /** 截断管理员可见错误，防止异常正文无界写入。 */
    private String abbreviate(String value) {
        return StringUtils.abbreviate(StringUtils.defaultIfBlank(value, "操作失败"),
                SemanticReleaseConstants.ERROR_MESSAGE_MAX_LENGTH);
    }

    /** 空白审批备注归一为 null。 */
    private String trimToNull(String value) {
        return StringUtils.trimToNull(value);
    }

    /** 构造输入错误。 */
    private ModelingDraftException invalid(String message) {
        return new ModelingDraftException(HttpStatus.BAD_REQUEST,
                ModelingDraftConstants.ERROR_INVALID_REQUEST, message);
    }

    /** 构造状态冲突。 */
    private ModelingDraftException stateConflict(String message) {
        return new ModelingDraftException(HttpStatus.CONFLICT,
                SemanticReleaseConstants.ERROR_INVALID_STATE, message);
    }

    /** 构造发布不存在错误。 */
    private ModelingDraftException notFound() {
        return new ModelingDraftException(HttpStatus.NOT_FOUND,
                SemanticReleaseConstants.ERROR_NOT_FOUND, "发布记录不存在");
    }

    /** 复制分页元信息并替换响应列表。 */
    private <S, T> PageInfo<T> mapPage(PageInfo<S> source, List<T> list) {
        PageInfo<T> target = new PageInfo<>();
        target.setPageNum(source.getPageNum());
        target.setPageSize(source.getPageSize());
        target.setSize(source.getSize());
        target.setStartRow(source.getStartRow());
        target.setEndRow(source.getEndRow());
        target.setTotal(source.getTotal());
        target.setPages(source.getPages());
        target.setPrePage(source.getPrePage());
        target.setNextPage(source.getNextPage());
        target.setIsFirstPage(source.isIsFirstPage());
        target.setIsLastPage(source.isIsLastPage());
        target.setHasPreviousPage(source.isHasPreviousPage());
        target.setHasNextPage(source.isHasNextPage());
        target.setNavigatePages(source.getNavigatePages());
        target.setNavigatepageNums(source.getNavigatepageNums());
        target.setNavigateFirstPage(source.getNavigateFirstPage());
        target.setNavigateLastPage(source.getNavigateLastPage());
        target.setList(list);
        return target;
    }

    /** 发布认领结果。 */
    public record ReleaseClaim(SemanticReleaseDO release, SemanticModelingDraftDO draft,
            boolean execute) {}

    /** 回滚认领结果。 */
    public record RollbackClaim(SemanticReleaseDO release, boolean execute) {}

    /** 步骤认领结果。 */
    public record StepClaim(SemanticReleaseStepDO step, boolean execute) {}

    /** 发布步骤稳定描述。 */
    public record StepDescriptor(String stepKey, String stepType, String targetType,
            String targetKey, String targetName) {}
}
