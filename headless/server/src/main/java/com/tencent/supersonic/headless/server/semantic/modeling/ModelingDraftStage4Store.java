package com.tencent.supersonic.headless.server.semantic.modeling;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tencent.supersonic.common.pojo.User;
import com.tencent.supersonic.headless.server.persistence.dataobject.SemanticModelingDraftDO;
import com.tencent.supersonic.headless.server.persistence.dataobject.SemanticModelingDraftVersionDO;
import com.tencent.supersonic.headless.server.persistence.dataobject.SemanticModelingValidationReportDO;
import com.tencent.supersonic.headless.server.persistence.mapper.SemanticModelingDraftMapper;
import com.tencent.supersonic.headless.server.persistence.mapper.SemanticModelingDraftVersionMapper;
import com.tencent.supersonic.headless.server.persistence.mapper.SemanticModelingValidationReportMapper;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Date;
import java.util.List;
import java.util.Objects;

/**
 * 阶段 4 草稿版本、验证报告和提交门禁的短事务存储服务。
 *
 * <p>
 * 职责说明：使用数据库行锁、修订租约门禁和唯一约束保证同一草稿只运行一个验证，以及提交审批时报告 仍是当前版本的最新可提交报告。AI 修订版本保存由
 * {@link ModelingDraftRevisionStore} 单一路径负责； LLM 网络调用和验证计算均不得放入本服务事务。服务不持有 JVM 共享可变状态。
 * </p>
 */
@Service
public class ModelingDraftStage4Store {

    private static final TypeReference<List<ModelingValidationCheckResult>> CHECK_RESULTS_TYPE =
            new TypeReference<>() {};

    private final SemanticModelingDraftMapper draftMapper;
    private final SemanticModelingValidationReportMapper reportMapper;
    private final SemanticModelingDraftVersionMapper versionMapper;
    private final ModelingDraftRevisionStore revisionStore;
    private final SemanticModelingProperties properties;
    private final ObjectMapper objectMapper;

    /**
     * 创建阶段 4 短事务存储服务。
     *
     * @param draftMapper 草稿 Mapper。
     * @param reportMapper 验证报告 Mapper。
     * @param versionMapper 不可变草稿版本 Mapper。
     * @param revisionStore AI 修订租约存储服务。
     * @param properties 阶段 4 验证租约配置。
     * @param objectMapper 只用于反序列化受控必需检查 JSON。
     */
    public ModelingDraftStage4Store(SemanticModelingDraftMapper draftMapper,
            SemanticModelingValidationReportMapper reportMapper,
            SemanticModelingDraftVersionMapper versionMapper,
            ModelingDraftRevisionStore revisionStore, SemanticModelingProperties properties,
            ObjectMapper objectMapper) {
        this.draftMapper = draftMapper;
        this.reportMapper = reportMapper;
        this.versionMapper = versionMapper;
        this.revisionStore = revisionStore;
        this.properties = properties;
        this.objectMapper = objectMapper;
    }

    /**
     * 在单个短事务中把历史快照追加为当前草稿的新版本。
     *
     * <p>
     * 锁顺序固定为 draft 行、revision attempt、validation report；先持有主行锁可串行化保存、恢复和提交，
     * 数据库唯一幂等键负责多实例重试。历史快照只读，正式语义对象完全不参与本事务。
     * </p>
     *
     * @param draftId 草稿 ID。
     * @param targetVersionNo 需要复制的历史版本号。
     * @param expectedCurrentVersionNo 客户端确认的当前版本号。
     * @param expectedLockVersion 客户端确认的乐观锁版本。
     * @param idempotencyKey 请求幂等键。
     * @param fingerprint 绑定目标和基线的请求指纹。
     * @param user 当前管理员。
     * @return 新版本及重放标志。
     */
    @Transactional(rollbackFor = Exception.class)
    public RestoreResult restoreVersion(Long draftId, Integer targetVersionNo,
            Integer expectedCurrentVersionNo, Integer expectedLockVersion, String idempotencyKey,
            String fingerprint, User user) {
        SemanticModelingDraftDO locked = draftMapper.selectByIdForUpdate(draftId);
        if (locked == null) {
            throw new ModelingDraftException(HttpStatus.NOT_FOUND,
                    ModelingDraftConstants.ERROR_NOT_FOUND, "语义建模草稿不存在");
        }
        // draft 行锁已经串行化同草稿写操作；历史成功结果必须先于后续活动租约重放，且不产生任何新写入。
        RestoreResult replay = replayRestore(draftId, idempotencyKey, fingerprint);
        if (replay != null) {
            return replay;
        }
        // 非重放路径与验证、提交保持相同锁序：先 draft，再 revision attempt，最后读取 validation report。
        revisionStore.assertNoActiveRevision(draftId, user);
        if (!ModelingDraftConstants.STATUS_DRAFT.equals(locked.getStatus())
                || !Objects.equals(locked.getCurrentVersionNo(), expectedCurrentVersionNo)
                || !Objects.equals(locked.getLockVersion(), expectedLockVersion)) {
            throw new ModelingDraftException(HttpStatus.CONFLICT,
                    ModelingDraftConstants.ERROR_CONFLICT, "草稿版本已变化，请刷新后重试");
        }
        requireNoActiveValidation(draftId, "验证运行期间不能恢复草稿版本");
        SemanticModelingDraftVersionDO target =
                versionMapper.selectOne(new LambdaQueryWrapper<SemanticModelingDraftVersionDO>()
                        .eq(SemanticModelingDraftVersionDO::getDraftId, draftId)
                        .eq(SemanticModelingDraftVersionDO::getVersionNo, targetVersionNo));
        if (target == null) {
            throw new ModelingDraftException(HttpStatus.NOT_FOUND,
                    ModelingDraftConstants.ERROR_NOT_FOUND, "目标草稿版本不存在");
        }
        int nextVersionNo = locked.getCurrentVersionNo() + 1;
        Date now = requireDatabaseTime();
        int updated = draftMapper.updateDraftForRestore(draftId, expectedCurrentVersionNo,
                expectedLockVersion, target.getDraftJson(), nextVersionNo, user.getName(), now);
        if (updated != 1) {
            throw new ModelingDraftException(HttpStatus.CONFLICT,
                    ModelingDraftConstants.ERROR_CONFLICT, "草稿版本已变化，请刷新后重试");
        }
        SemanticModelingDraftVersionDO restored = new SemanticModelingDraftVersionDO();
        restored.setDraftId(draftId);
        restored.setVersionNo(nextVersionNo);
        restored.setDraftJson(target.getDraftJson());
        restored.setChangeSource(ModelingDraftConstants.VERSION_RESTORED);
        restored.setChangeSummary("从历史草稿版本恢复为新版本");
        restored.setLlmConversationId(locked.getLlmConversationId());
        restored.setRequestIdempotencyKey(idempotencyKey);
        restored.setRequestFingerprint(fingerprint);
        restored.setResultLockVersion(expectedLockVersion + 1);
        restored.setCreatedBy(user.getName());
        restored.setCreatedAt(now);
        versionMapper.insert(restored);
        return new RestoreResult(restored, expectedLockVersion + 1, false);
    }

    /**
     * 在短事务中认领一次同步验证任务。
     *
     * <p>
     * 事务先锁定草稿主记录，再检查活动报告并插入 {@code active_marker=1}；唯一约束是多实例并发的第二道 防线。超过十分钟仍为 RUNNING 的报告会安全标记为
     * SYSTEM_FAILED，避免进程崩溃永久阻塞草稿。
     * </p>
     *
     * @param draftId 草稿 ID。
     * @param version 需要验证的不可变版本。
     * @param optionsJson 规范化验证选项。
     * @param user 操作者。
     * @return 新建的 RUNNING 报告。
     */
    @Transactional(rollbackFor = Exception.class)
    public SemanticModelingValidationReportDO startValidation(Long draftId,
            SemanticModelingDraftVersionDO version, String optionsJson, User user) {
        SemanticModelingDraftDO locked = draftMapper.selectByIdForUpdate(draftId);
        if (locked == null) {
            throw new ModelingDraftException(HttpStatus.NOT_FOUND,
                    ModelingDraftConstants.ERROR_NOT_FOUND, "语义建模草稿不存在");
        }
        // 先持有草稿行锁，再检查修订 attempt，保持所有阶段 4 写路径的全局锁顺序。
        revisionStore.assertNoActiveRevision(draftId, user);
        if (!ModelingDraftConstants.STATUS_DRAFT.equals(locked.getStatus())
                || !Objects.equals(locked.getCurrentVersionNo(), version.getVersionNo())) {
            throw new ModelingDraftException(HttpStatus.CONFLICT,
                    ModelingDraftConstants.ERROR_CONFLICT, "只能验证当前 DRAFT 版本");
        }

        SemanticModelingValidationReportDO active = selectActiveReport(draftId);
        Date now = requireDatabaseTime();
        requireNoActiveValidation(draftId, active, now, "该草稿已有验证任务正在运行");

        SemanticModelingValidationReportDO report = new SemanticModelingValidationReportDO();
        report.setDraftId(draftId);
        report.setDraftVersionId(version.getId());
        report.setDraftVersionNo(version.getVersionNo());
        report.setStatus(ModelingDraftConstants.VALIDATION_RUNNING);
        report.setValidationOptions(optionsJson);
        report.setBlockingCount(0);
        report.setWarningCount(0);
        report.setActiveMarker(1);
        report.setCreatedBy(user.getName());
        report.setCreatedAt(now);
        try {
            reportMapper.insert(report);
        } catch (DataIntegrityViolationException exception) {
            throw new ModelingDraftException(HttpStatus.CONFLICT,
                    ModelingDraftConstants.ERROR_VALIDATION_RUNNING, "该草稿已有验证任务正在运行");
        }
        return report;
    }

    /**
     * 原子完成验证报告并释放活动标记。
     *
     * @param completed 包含最终状态和各类已序列化检查结果的报告对象。
     * @return 完成后的报告记录。
     * @throws ModelingDraftException 报告已被恢复任务或其他线程结束时抛出 409。
     */
    @Transactional(rollbackFor = Exception.class)
    public SemanticModelingValidationReportDO completeValidation(
            SemanticModelingValidationReportDO completed) {
        int updated = reportMapper.update(null,
                new LambdaUpdateWrapper<SemanticModelingValidationReportDO>()
                        .eq(SemanticModelingValidationReportDO::getId, completed.getId())
                        .eq(SemanticModelingValidationReportDO::getStatus,
                                ModelingDraftConstants.VALIDATION_RUNNING)
                        .eq(SemanticModelingValidationReportDO::getActiveMarker, 1)
                        .set(SemanticModelingValidationReportDO::getStatus, completed.getStatus())
                        // 必需检查快照必须与最终状态原子落库，submit 才能重新执行 fail-closed 门禁。
                        .set(SemanticModelingValidationReportDO::getRequiredCheckResults,
                                completed.getRequiredCheckResults())
                        .set(SemanticModelingValidationReportDO::getPlannedObjects,
                                completed.getPlannedObjects())
                        .set(SemanticModelingValidationReportDO::getFieldExistenceResult,
                                completed.getFieldExistenceResult())
                        .set(SemanticModelingValidationReportDO::getConflictResult,
                                completed.getConflictResult())
                        .set(SemanticModelingValidationReportDO::getSensitiveFieldResult,
                                completed.getSensitiveFieldResult())
                        .set(SemanticModelingValidationReportDO::getSampleQuestionResults,
                                completed.getSampleQuestionResults())
                        .set(SemanticModelingValidationReportDO::getSqlSafetyResult,
                                completed.getSqlSafetyResult())
                        .set(SemanticModelingValidationReportDO::getPerformanceRiskResult,
                                completed.getPerformanceRiskResult())
                        .set(SemanticModelingValidationReportDO::getUncertaintyResult,
                                completed.getUncertaintyResult())
                        .set(SemanticModelingValidationReportDO::getBlockingItems,
                                completed.getBlockingItems())
                        .set(SemanticModelingValidationReportDO::getWarningItems,
                                completed.getWarningItems())
                        .set(SemanticModelingValidationReportDO::getBlockingCount,
                                completed.getBlockingCount())
                        .set(SemanticModelingValidationReportDO::getWarningCount,
                                completed.getWarningCount())
                        .set(SemanticModelingValidationReportDO::getSystemErrorCode,
                                completed.getSystemErrorCode())
                        .set(SemanticModelingValidationReportDO::getActiveMarker, null)
                        .set(SemanticModelingValidationReportDO::getFinishedAt,
                                completed.getFinishedAt()));
        if (updated != 1) {
            throw new ModelingDraftException(HttpStatus.CONFLICT,
                    ModelingDraftConstants.ERROR_CONFLICT, "验证报告状态已变化，请重新加载");
        }
        return reportMapper.selectById(completed.getId());
    }

    /**
     * 在数据库行锁内执行提交审批门禁和状态迁移。
     *
     * @param draftId 草稿 ID。
     * @param versionNo 客户端确认的当前版本。
     * @param reportId 客户端确认的验证报告。
     * @param idempotencyKey 提交幂等键。
     * @param user 操作者。
     * @return 提交后的草稿和是否幂等重放。
     */
    @Transactional(rollbackFor = Exception.class)
    public SubmissionResult submit(Long draftId, Integer versionNo, Long reportId,
            String idempotencyKey, User user) {
        SemanticModelingDraftDO locked = draftMapper.selectByIdForUpdate(draftId);
        if (locked == null) {
            throw new ModelingDraftException(HttpStatus.NOT_FOUND,
                    ModelingDraftConstants.ERROR_NOT_FOUND, "语义建模草稿不存在");
        }
        // 修订完成前不得把同一基线提交审批；重复锁定当前草稿行会复用本事务已有行锁。
        revisionStore.assertNoActiveRevision(draftId, user);
        if (ModelingDraftConstants.STATUS_PENDING_APPROVAL.equals(locked.getStatus())) {
            boolean replay = Objects.equals(idempotencyKey, locked.getSubmissionIdempotencyKey())
                    && Objects.equals(versionNo, locked.getCurrentVersionNo())
                    && Objects.equals(reportId, locked.getSubmittedValidationReportId());
            if (replay) {
                return new SubmissionResult(locked, true);
            }
            throw submissionBlocked("草稿已经提交审批");
        }
        if (!ModelingDraftConstants.STATUS_DRAFT.equals(locked.getStatus())
                || !Objects.equals(versionNo, locked.getCurrentVersionNo())) {
            throw submissionBlocked("草稿版本已变化，请重新验证后提交");
        }

        SemanticModelingValidationReportDO report = reportMapper.selectById(reportId);
        if (!isSubmittableReport(report, locked)) {
            throw submissionBlocked("验证报告未通过或不属于当前草稿版本");
        }
        SemanticModelingValidationReportDO latest = findLatestReport(draftId, versionNo);
        if (latest == null || !Objects.equals(latest.getId(), reportId)) {
            throw submissionBlocked("只能使用当前版本最新的验证报告提交审批");
        }

        Date now = new Date();
        int updated = draftMapper.submitForApproval(draftId, versionNo, reportId, idempotencyKey,
                user.getName(), now);
        if (updated != 1) {
            throw submissionBlocked("草稿状态已变化，请重新加载");
        }
        return new SubmissionResult(draftMapper.selectById(draftId), false);
    }

    /** 查询当前活动验证报告。 */
    private SemanticModelingValidationReportDO selectActiveReport(Long draftId) {
        return reportMapper.selectOne(new LambdaQueryWrapper<SemanticModelingValidationReportDO>()
                .eq(SemanticModelingValidationReportDO::getDraftId, draftId)
                .eq(SemanticModelingValidationReportDO::getActiveMarker, 1));
    }

    /**
     * 查询指定草稿版本最新的验证报告，包括仍在运行的报告。
     *
     * <p>
     * 提交审批必须以最新一次验证尝试为准；若更新报告仍为 {@code RUNNING}，旧的通过报告也不能继续 跨过门禁。查询只读取单行且由调用方结合草稿行锁保证提交时的一致性。
     * </p>
     *
     * @param draftId 草稿 ID。
     * @param versionNo 草稿版本号。
     * @return 最新报告；不存在时返回 null。
     */
    public SemanticModelingValidationReportDO findLatestReport(Long draftId, Integer versionNo) {
        return reportMapper.selectOne(new LambdaQueryWrapper<SemanticModelingValidationReportDO>()
                .eq(SemanticModelingValidationReportDO::getDraftId, draftId)
                .eq(SemanticModelingValidationReportDO::getDraftVersionNo, versionNo)
                .orderByDesc(SemanticModelingValidationReportDO::getId).last("LIMIT 1"));
    }

    /**
     * 查询路径在短事务内懒恢复过期 RUNNING 报告。
     *
     * <p>
     * 先锁 draft 再读取活动报告；条件更新同时校验 RUNNING 和 active_marker=1，因而与正常完成并发时只有一方获胜。
     * </p>
     *
     * @param draftId 草稿 ID。
     * @return 是否由本事务把过期报告推进到 SYSTEM_FAILED。
     */
    @Transactional(rollbackFor = Exception.class)
    public boolean recoverStaleValidation(Long draftId) {
        if (draftMapper.selectByIdForUpdate(draftId) == null) {
            return false;
        }
        SemanticModelingValidationReportDO active = selectActiveReport(draftId);
        if (active == null) {
            return false;
        }
        Date now = requireDatabaseTime();
        return recoverStaleReport(active, now);
    }

    /**
     * 读取并校验历史 restore 的确定性结果；安全重放不得受后续草稿或租约状态影响。
     */
    private RestoreResult replayRestore(Long draftId, String idempotencyKey, String fingerprint) {
        SemanticModelingDraftVersionDO replay =
                versionMapper.selectByDraftIdAndRequestIdempotencyKey(draftId, idempotencyKey);
        if (replay == null) {
            return null;
        }
        if (!Objects.equals(fingerprint, replay.getRequestFingerprint())
                || !ModelingDraftConstants.VERSION_RESTORED.equals(replay.getChangeSource())) {
            throw new ModelingDraftException(HttpStatus.CONFLICT,
                    ModelingDraftConstants.ERROR_IDEMPOTENCY_CONFLICT, "幂等键已被其他草稿操作使用");
        }
        if (replay.getResultLockVersion() == null) {
            // 迁移前异常历史行无法重建首次响应锁版本；拒绝混用当前状态比猜测更安全。
            throw new RestoreResultIncompleteException();
        }
        return new RestoreResult(replay, replay.getResultLockVersion(), true);
    }

    /** 活动报告存在时使用数据库时钟恢复过期租约；新鲜报告继续阻止 restore。 */
    private void requireNoActiveValidation(Long draftId, String conflictMessage) {
        SemanticModelingValidationReportDO active = selectActiveReport(draftId);
        if (active != null) {
            requireNoActiveValidation(draftId, active, requireDatabaseTime(), conflictMessage);
        }
    }

    /**
     * 使用调用方已读取的同一数据库时间判断并结束租约，避免 startValidation 产生两套时间语义。
     */
    private void requireNoActiveValidation(Long draftId, SemanticModelingValidationReportDO active,
            Date now, String conflictMessage) {
        if (active == null) {
            return;
        }
        if (!isStale(active, now)) {
            throw validationRunning(conflictMessage);
        }
        if (recoverStaleReport(active, now)) {
            return;
        }
        // 条件更新为零说明正常完成可能先获胜；仅在重新读取仍有活动报告时继续阻塞。
        if (selectActiveReport(draftId) != null) {
            throw validationRunning(conflictMessage);
        }
    }

    /** 只在明确超过租约时执行带 RUNNING/active_marker 条件的原子恢复。 */
    private boolean recoverStaleReport(SemanticModelingValidationReportDO active, Date now) {
        return isStale(active, now) && expireStaleReport(active.getId(), now) == 1;
    }

    /** 判断运行报告是否超过恢复阈值。 */
    private boolean isStale(SemanticModelingValidationReportDO report, Date now) {
        return report.getCreatedAt() == null || now.getTime()
                - report.getCreatedAt().getTime() > properties.resolveValidationLeaseMillis();
    }

    /** 数据库时间不可用时拒绝执行租约判断，避免 JVM 偏移误结束正常验证。 */
    private Date requireDatabaseTime() {
        try {
            Date now = reportMapper.selectCurrentTimestamp();
            if (now == null) {
                throw new ValidationLeaseClockUnavailableException(null);
            }
            return now;
        } catch (ValidationLeaseClockUnavailableException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            // 包装为专用系统异常，Controller 兜底只返回稳定 INTERNAL_ERROR，不泄漏数据库细节。
            throw new ValidationLeaseClockUnavailableException(exception);
        }
    }

    /** 把崩溃遗留的 RUNNING 报告安全结束并释放唯一活动标记。 */
    private int expireStaleReport(Long reportId, Date now) {
        return reportMapper.update(null,
                new LambdaUpdateWrapper<SemanticModelingValidationReportDO>()
                        .eq(SemanticModelingValidationReportDO::getId, reportId)
                        .eq(SemanticModelingValidationReportDO::getStatus,
                                ModelingDraftConstants.VALIDATION_RUNNING)
                        .eq(SemanticModelingValidationReportDO::getActiveMarker, 1)
                        .set(SemanticModelingValidationReportDO::getStatus,
                                ModelingDraftConstants.VALIDATION_SYSTEM_FAILED)
                        .set(SemanticModelingValidationReportDO::getSystemErrorCode,
                                ModelingDraftConstants.ERROR_VALIDATION_STALE_RECOVERED)
                        .set(SemanticModelingValidationReportDO::getBlockingCount, 1)
                        .set(SemanticModelingValidationReportDO::getWarningCount, 0)
                        .set(SemanticModelingValidationReportDO::getActiveMarker, null)
                        .set(SemanticModelingValidationReportDO::getFinishedAt, now));
    }

    /** 判断报告能否跨过提交审批门禁。 */
    private boolean isSubmittableReport(SemanticModelingValidationReportDO report,
            SemanticModelingDraftDO draft) {
        return report != null && Objects.equals(report.getDraftId(), draft.getId())
                && Objects.equals(report.getDraftVersionNo(), draft.getCurrentVersionNo())
                && report.getFinishedAt() != null
                && Objects.requireNonNullElse(report.getBlockingCount(), 0) == 0
                && ModelingValidationGate.isCompleteForSubmission(readRequiredChecks(report))
                && (ModelingDraftConstants.VALIDATION_PASSED.equals(report.getStatus())
                        || ModelingDraftConstants.VALIDATION_WARNING.equals(report.getStatus()));
    }

    /** 历史空值、损坏 JSON 或未知状态安全退化为空集合，由门禁拒绝提交。 */
    private List<ModelingValidationCheckResult> readRequiredChecks(
            SemanticModelingValidationReportDO report) {
        if (report == null
                || org.apache.commons.lang3.StringUtils.isBlank(report.getRequiredCheckResults())) {
            return List.of();
        }
        try {
            return objectMapper.readValue(report.getRequiredCheckResults(), CHECK_RESULTS_TYPE);
        } catch (JsonProcessingException exception) {
            return List.of();
        }
    }

    /** 构造提交门禁异常。 */
    private ModelingDraftException submissionBlocked(String message) {
        return new ModelingDraftException(HttpStatus.CONFLICT,
                ModelingDraftConstants.ERROR_SUBMISSION_CONFLICT, message);
    }

    /** 构造稳定的活动验证业务冲突。 */
    private ModelingDraftException validationRunning(String message) {
        return new ModelingDraftException(HttpStatus.CONFLICT,
                ModelingDraftConstants.ERROR_VALIDATION_RUNNING, message);
    }

    /** 数据库统一时钟不可用时由全局异常处理器转换为脱敏 INTERNAL_ERROR。 */
    private static final class ValidationLeaseClockUnavailableException extends RuntimeException {

        private ValidationLeaseClockUnavailableException(Throwable cause) {
            super("Validation lease database clock is unavailable", cause);
        }
    }

    /** 迁移前 restore 结果缺少确定性锁版本时拒绝猜测当前状态。 */
    private static final class RestoreResultIncompleteException extends RuntimeException {

        private RestoreResultIncompleteException() {
            super("Stored restore result is incomplete");
        }
    }

    /**
     * 提交状态迁移结果。
     *
     * @param draft 提交后的草稿。
     * @param replay 是否为同幂等键重放。
     */
    public record SubmissionResult(SemanticModelingDraftDO draft, boolean replay) {}

    /**
     * 追加式恢复事务结果。
     *
     * @param version 新建或幂等重放的恢复版本。
     * @param lockVersion 响应可见的草稿锁版本。
     * @param idempotentReplay 是否未新增版本而重放已有结果。
     */
    public record RestoreResult(SemanticModelingDraftVersionDO version, Integer lockVersion,
            boolean idempotentReplay) {}
}
