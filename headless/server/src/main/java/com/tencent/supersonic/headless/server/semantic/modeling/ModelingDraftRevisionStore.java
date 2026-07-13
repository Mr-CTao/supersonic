package com.tencent.supersonic.headless.server.semantic.modeling;

import com.tencent.supersonic.common.pojo.User;
import com.tencent.supersonic.headless.server.persistence.dataobject.SemanticModelingDraftDO;
import com.tencent.supersonic.headless.server.persistence.dataobject.SemanticModelingDraftVersionDO;
import com.tencent.supersonic.headless.server.persistence.dataobject.SemanticModelingRevisionAttemptDO;
import com.tencent.supersonic.headless.server.persistence.mapper.SemanticModelingDraftMapper;
import com.tencent.supersonic.headless.server.persistence.mapper.SemanticModelingDraftVersionMapper;
import com.tencent.supersonic.headless.server.persistence.mapper.SemanticModelingRevisionAttemptMapper;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Date;
import java.util.Objects;

/**
 * 阶段 4 AI 草稿修订持久化认领与租约存储服务。
 *
 * <p>
 * 职责说明：调用方先通过 {@link #claim(Long, Integer, String, String, User)} 在短事务中认领租约， 事务提交后再调用外部
 * Provider，最后使用 {@link #completeSuccess(Long, String, String, Long, User)} 或
 * {@link #completeFailure(Long, String, User)} 在另一个短事务中结束尝试。Provider 网络调用不得放入
 * 本服务事务。草稿行锁负责串行化同草稿认领，两个数据库唯一键负责多实例第二道并发保护。
 * </p>
 *
 * <p>
 * 过期同键请求会先持久化为 SYSTEM_FAILED，再返回“必须换新键”的 disposition；这里不能通过抛异常 表达该预期分支，否则事务回滚会恢复 RUNNING
 * 状态并永久污染幂等语义。
 * </p>
 */
@Service
public class ModelingDraftRevisionStore {

    public static final String STATUS_RUNNING = "RUNNING";
    public static final String STATUS_SUCCEEDED = "SUCCEEDED";
    public static final String STATUS_FAILED = "FAILED";
    public static final String STATUS_SYSTEM_FAILED = "SYSTEM_FAILED";

    private static final String ERROR_LEASE_EXPIRED = "REVISION_LEASE_EXPIRED";
    private static final String ERROR_PROVIDER_FAILED = "REVISION_PROVIDER_FAILED";
    private static final String ERROR_BASE_VERSION_CHANGED = "REVISION_BASE_VERSION_CHANGED";
    private static final int MAX_KEY_LENGTH = 128;
    private static final int MAX_FINGERPRINT_LENGTH = 128;
    private static final int MAX_ERROR_CODE_LENGTH = 64;
    private static final int MAX_CHANGE_SUMMARY_LENGTH = 1000;

    private final SemanticModelingDraftMapper draftMapper;
    private final SemanticModelingDraftVersionMapper versionMapper;
    private final SemanticModelingRevisionAttemptMapper attemptMapper;
    private final long leaseMillis;

    /**
     * 创建覆盖两轮 Provider 调用与安全余量的修订存储服务。
     *
     * @param draftMapper 草稿主表 Mapper。
     * @param versionMapper 不可变版本 Mapper。
     * @param attemptMapper 修订尝试 Mapper。
     * @param properties 建模运行参数，提供与 Provider 超时一致的租约时长。
     */
    @Autowired
    public ModelingDraftRevisionStore(SemanticModelingDraftMapper draftMapper,
            SemanticModelingDraftVersionMapper versionMapper,
            SemanticModelingRevisionAttemptMapper attemptMapper,
            SemanticModelingProperties properties) {
        this(draftMapper, versionMapper, attemptMapper, properties.resolveRevisionLeaseMillis());
    }

    /** 测试专用构造函数，允许缩短租约但仍使用相同数据库并发协议。 */
    ModelingDraftRevisionStore(SemanticModelingDraftMapper draftMapper,
            SemanticModelingDraftVersionMapper versionMapper,
            SemanticModelingRevisionAttemptMapper attemptMapper, long leaseMillis) {
        this.draftMapper = draftMapper;
        this.versionMapper = versionMapper;
        this.attemptMapper = attemptMapper;
        this.leaseMillis = leaseMillis;
    }

    /**
     * 在 Provider 调用前认领草稿修订租约。
     *
     * <p>
     * 调用示例：{@code ClaimResult result = store.claim(12L, 3, key, fingerprint, user)}；仅当
     * {@link ClaimResult#shouldInvokeProvider()} 为 true 时才能发起 Provider 请求。相同键的成功尝试直接
     * 返回可重放结果；RUNNING、FAILED、SYSTEM_FAILED 和已过期尝试均不会再次调用 Provider。
     * </p>
     *
     * @param draftId 草稿 ID。
     * @param baseVersionNo 修订基线版本号。
     * @param idempotencyKey 修订请求幂等键。
     * @param requestFingerprint 规范化请求指纹。
     * @param user 当前操作者。
     * @return 认领 disposition 和对应尝试。
     * @throws ModelingDraftException 参数非法、草稿不存在或数据库唯一约束竞争时抛出。
     */
    @Transactional(rollbackFor = Exception.class)
    public ClaimResult claim(Long draftId, Integer baseVersionNo, String idempotencyKey,
            String requestFingerprint, User user) {
        validateClaim(draftId, baseVersionNo, idempotencyKey, requestFingerprint, user);
        SemanticModelingDraftDO draft = draftMapper.selectByIdForUpdate(draftId);
        if (draft == null) {
            throw new ModelingDraftException(HttpStatus.NOT_FOUND,
                    ModelingDraftConstants.ERROR_NOT_FOUND, "语义建模草稿不存在");
        }

        SemanticModelingRevisionAttemptDO sameKey =
                attemptMapper.selectByDraftIdAndIdempotencyKey(draftId, idempotencyKey);
        if (sameKey != null) {
            return replayOrRejectSameKey(sameKey, baseVersionNo, requestFingerprint, user,
                    databaseNow());
        }

        // restore、手工保存和 AI revision 共享版本表唯一键；必须在 Provider 调用前对称拒绝跨操作复用。
        SemanticModelingDraftVersionDO occupiedVersion =
                versionMapper.selectByDraftIdAndRequestIdempotencyKey(draftId, idempotencyKey);
        if (occupiedVersion != null) {
            return new ClaimResult(ClaimDisposition.IDEMPOTENCY_CONFLICT, null);
        }

        Date now = databaseNow();
        SemanticModelingRevisionAttemptDO active = attemptMapper.selectActiveByDraftId(draftId);
        if (active != null && !isExpired(active, now)) {
            return new ClaimResult(ClaimDisposition.OTHER_ACTIVE_REVISION, active);
        }
        if (active != null && !expire(active, user.getName(), now)) {
            throw conflict("活动修订租约状态已变化，请重新加载草稿");
        }

        if (!ModelingDraftConstants.STATUS_DRAFT.equals(draft.getStatus())
                || !Objects.equals(draft.getCurrentVersionNo(), baseVersionNo)) {
            return new ClaimResult(ClaimDisposition.DRAFT_VERSION_CONFLICT, null);
        }

        SemanticModelingRevisionAttemptDO claimed = new SemanticModelingRevisionAttemptDO();
        claimed.setDraftId(draftId);
        claimed.setBaseVersionNo(baseVersionNo);
        claimed.setIdempotencyKey(idempotencyKey);
        claimed.setRequestFingerprint(requestFingerprint);
        claimed.setStatus(STATUS_RUNNING);
        claimed.setActiveMarker(1);
        claimed.setLeaseStartedAt(now);
        claimed.setLeaseExpiresAt(new Date(now.getTime() + leaseMillis));
        claimed.setCreatedBy(user.getName());
        claimed.setCreatedAt(now);
        claimed.setUpdatedBy(user.getName());
        claimed.setUpdatedAt(now);
        try {
            attemptMapper.insert(claimed);
        } catch (DataIntegrityViolationException exception) {
            throw conflict("草稿已有修订请求被其他实例认领，请重新加载");
        }
        return new ClaimResult(ClaimDisposition.CLAIMED, claimed);
    }

    /**
     * 在同一事务中保存修订版本并结束活动尝试。
     *
     * <p>
     * 调用示例：{@code store.completeSuccess(attemptId, normalizedJson, summary, conversationId,
     * user)}。事务先锁草稿再锁尝试，锁顺序与认领路径一致，避免不同实例互相等待。若租约已被其他请求 结束或已经过期，本方法不会写入草稿版本。
     * </p>
     *
     * @param attemptId 修订尝试 ID。
     * @param draftJson 已通过结构和字段校验的完整草稿 JSON。
     * @param changeSummary 确定性版本差异摘要。
     * @param llmConversationId Provider 调用关联会话 ID。
     * @param user 当前操作者。
     * @return 完成 disposition、尝试、草稿和版本。
     * @throws ModelingDraftException 尝试不存在、参数非法或数据库写入竞争时抛出。
     */
    @Transactional(rollbackFor = Exception.class)
    public CompletionResult completeSuccess(Long attemptId, String draftJson, String changeSummary,
            Long llmConversationId, User user) {
        validateCompletion(attemptId, user);
        if (StringUtils.isBlank(draftJson)) {
            throw invalidRequest("修订后的草稿 JSON 不能为空");
        }

        SemanticModelingRevisionAttemptDO initial = requireAttempt(attemptId);
        SemanticModelingDraftDO draft = draftMapper.selectByIdForUpdate(initial.getDraftId());
        if (draft == null) {
            throw new ModelingDraftException(HttpStatus.NOT_FOUND,
                    ModelingDraftConstants.ERROR_NOT_FOUND, "语义建模草稿不存在");
        }
        SemanticModelingRevisionAttemptDO attempt = attemptMapper.selectByIdForUpdate(attemptId);
        if (attempt == null) {
            throw new ModelingDraftException(HttpStatus.NOT_FOUND,
                    ModelingDraftConstants.ERROR_NOT_FOUND, "草稿修订尝试不存在");
        }
        if (STATUS_SUCCEEDED.equals(attempt.getStatus())) {
            return replayCompletion(draft, attempt);
        }
        if (!isActiveRunning(attempt)) {
            return new CompletionResult(CompletionDisposition.TERMINAL_REJECTED, attempt, draft,
                    null);
        }

        Date now = databaseNow();
        if (isExpired(attempt, now)) {
            expire(attempt, user.getName(), now);
            return new CompletionResult(CompletionDisposition.LEASE_EXPIRED, attempt, draft, null);
        }
        if (!ModelingDraftConstants.STATUS_DRAFT.equals(draft.getStatus())
                || !Objects.equals(draft.getCurrentVersionNo(), attempt.getBaseVersionNo())) {
            fail(attempt, ERROR_BASE_VERSION_CHANGED, user.getName(), now);
            return new CompletionResult(CompletionDisposition.DRAFT_VERSION_CONFLICT, attempt,
                    draft, null);
        }

        int nextVersionNo = attempt.getBaseVersionNo() + 1;
        int updated =
                draftMapper.updateDraftWithAiVersion(draft.getId(), attempt.getBaseVersionNo(),
                        draftJson, nextVersionNo, llmConversationId, user.getName(), now);
        if (updated != 1) {
            fail(attempt, ERROR_BASE_VERSION_CHANGED, user.getName(), now);
            return new CompletionResult(CompletionDisposition.DRAFT_VERSION_CONFLICT, attempt,
                    draft, null);
        }

        SemanticModelingDraftVersionDO version = new SemanticModelingDraftVersionDO();
        version.setDraftId(draft.getId());
        version.setVersionNo(nextVersionNo);
        version.setDraftJson(draftJson);
        version.setChangeSource(ModelingDraftConstants.VERSION_AI_REVISED);
        version.setChangeSummary(StringUtils.abbreviate(
                StringUtils.defaultIfBlank(changeSummary, "AI 修订草稿"), MAX_CHANGE_SUMMARY_LENGTH));
        version.setLlmConversationId(llmConversationId);
        version.setRequestIdempotencyKey(attempt.getIdempotencyKey());
        version.setRequestFingerprint(attempt.getRequestFingerprint());
        version.setCreatedBy(attempt.getCreatedBy());
        version.setCreatedAt(now);
        versionMapper.insert(version);

        int completed = attemptMapper.markSucceeded(attemptId, version.getId(), nextVersionNo,
                llmConversationId, user.getName(), now);
        if (completed != 1) {
            throw conflict("草稿修订租约已被其他实例结束，版本保存已回滚");
        }
        markSucceededInMemory(attempt, version, llmConversationId, user.getName(), now);
        SemanticModelingDraftDO saved = draftMapper.selectById(draft.getId());
        return new CompletionResult(CompletionDisposition.SUCCEEDED, attempt, saved, version);
    }

    /**
     * 记录 Provider 调用失败并释放活动租约。
     *
     * @param attemptId 修订尝试 ID。
     * @param errorCode 稳定、脱敏的错误码；非法内容会归一化为通用错误码。
     * @param user 当前操作者。
     * @return 结束后的尝试；既有终态会幂等返回。
     */
    @Transactional(rollbackFor = Exception.class)
    public SemanticModelingRevisionAttemptDO completeFailure(Long attemptId, String errorCode,
            User user) {
        validateCompletion(attemptId, user);
        SemanticModelingRevisionAttemptDO initial = requireAttempt(attemptId);
        draftMapper.selectByIdForUpdate(initial.getDraftId());
        SemanticModelingRevisionAttemptDO attempt = attemptMapper.selectByIdForUpdate(attemptId);
        if (attempt == null || !isActiveRunning(attempt)) {
            return attempt;
        }
        Date now = databaseNow();
        if (isExpired(attempt, now)) {
            expire(attempt, user.getName(), now);
        } else {
            fail(attempt, sanitizeErrorCode(errorCode), user.getName(), now);
        }
        return attempt;
    }

    /**
     * 为手工保存、验证或提交审批检查活动 AI 修订。
     *
     * <p>
     * 未过期活动租约会抛出 409；过期租约先持久化为 SYSTEM_FAILED 再允许主流程继续。调用方应把本方法 放在其写事务开始处，并维持“先锁草稿、再锁其他阶段 4 记录”的顺序。
     * </p>
     *
     * @param draftId 草稿 ID。
     * @param user 当前操作者。
     * @throws ModelingDraftException 草稿不存在或存在未过期活动修订时抛出。
     */
    @Transactional(rollbackFor = Exception.class)
    public void assertNoActiveRevision(Long draftId, User user) {
        validateUser(user);
        SemanticModelingDraftDO draft = draftMapper.selectByIdForUpdate(draftId);
        if (draft == null) {
            throw new ModelingDraftException(HttpStatus.NOT_FOUND,
                    ModelingDraftConstants.ERROR_NOT_FOUND, "语义建模草稿不存在");
        }
        SemanticModelingRevisionAttemptDO active = attemptMapper.selectActiveByDraftId(draftId);
        if (active == null) {
            return;
        }
        Date now = databaseNow();
        if (isExpired(active, now)) {
            if (!expire(active, user.getName(), now)) {
                throw conflict("活动修订租约状态已变化，请重新加载草稿");
            }
            return;
        }
        throw new ModelingDraftException(HttpStatus.CONFLICT,
                ModelingDraftConstants.ERROR_REVISION_RUNNING, "AI 正在修订该草稿，请等待完成后再操作");
    }

    /**
     * 查询草稿是否仍有未过期的活动 AI 修订。
     *
     * <p>
     * 本方法只用于报告 DTO 的提交能力快照，不承担写门禁；租约在查询后可能立即变化，因此人工保存、 验证和提交仍必须在各自写事务内调用
     * {@link #assertNoActiveRevision(Long, User)}。为避免只读接口 意外更新审计字段，过期租约由下一次写事务负责回收。
     * </p>
     *
     * @param draftId 草稿 ID。
     * @return true 表示存在未过期 RUNNING 租约。
     */
    public boolean hasActiveRevision(Long draftId) {
        if (draftId == null) {
            return false;
        }
        SemanticModelingRevisionAttemptDO active = attemptMapper.selectActiveByDraftId(draftId);
        return active != null && isActiveRunning(active) && !isExpired(active, databaseNow());
    }

    /** 处理相同幂等键的安全重放或拒绝分支。 */
    private ClaimResult replayOrRejectSameKey(SemanticModelingRevisionAttemptDO attempt,
            Integer baseVersionNo, String requestFingerprint, User user, Date now) {
        if (!Objects.equals(baseVersionNo, attempt.getBaseVersionNo())
                || !Objects.equals(requestFingerprint, attempt.getRequestFingerprint())) {
            return new ClaimResult(ClaimDisposition.IDEMPOTENCY_CONFLICT, attempt);
        }
        if (STATUS_SUCCEEDED.equals(attempt.getStatus())) {
            return new ClaimResult(ClaimDisposition.REPLAY_SUCCEEDED, attempt);
        }
        if (STATUS_RUNNING.equals(attempt.getStatus()) && isExpired(attempt, now)) {
            if (!expire(attempt, user.getName(), now)) {
                throw conflict("修订租约状态已变化，请重新加载草稿");
            }
            return new ClaimResult(ClaimDisposition.SAME_KEY_EXPIRED, attempt);
        }
        if (STATUS_RUNNING.equals(attempt.getStatus())) {
            return new ClaimResult(ClaimDisposition.SAME_KEY_RUNNING, attempt);
        }
        return new ClaimResult(ClaimDisposition.SAME_KEY_TERMINAL, attempt);
    }

    /** 把活动尝试安全过期，并同步内存对象供调用方返回。 */
    private boolean expire(SemanticModelingRevisionAttemptDO attempt, String updatedBy, Date now) {
        int updated = attemptMapper.expire(attempt.getId(), ERROR_LEASE_EXPIRED, updatedBy, now);
        if (updated == 1) {
            attempt.setStatus(STATUS_SYSTEM_FAILED);
            attempt.setActiveMarker(null);
            attempt.setErrorCode(ERROR_LEASE_EXPIRED);
            attempt.setUpdatedBy(updatedBy);
            attempt.setUpdatedAt(now);
            attempt.setFinishedAt(now);
            return true;
        }
        return false;
    }

    /** 把 Provider 或版本冲突失败安全结束，并同步内存对象。 */
    private void fail(SemanticModelingRevisionAttemptDO attempt, String errorCode, String updatedBy,
            Date now) {
        int updated = attemptMapper.markFailed(attempt.getId(), errorCode, updatedBy, now);
        if (updated != 1) {
            throw conflict("草稿修订租约已被其他实例结束");
        }
        attempt.setStatus(STATUS_FAILED);
        attempt.setActiveMarker(null);
        attempt.setErrorCode(errorCode);
        attempt.setUpdatedBy(updatedBy);
        attempt.setUpdatedAt(now);
        attempt.setFinishedAt(now);
    }

    /** 同步成功终态到内存对象，避免事务内再次查询大字段。 */
    private void markSucceededInMemory(SemanticModelingRevisionAttemptDO attempt,
            SemanticModelingDraftVersionDO version, Long llmConversationId, String updatedBy,
            Date now) {
        attempt.setStatus(STATUS_SUCCEEDED);
        attempt.setActiveMarker(null);
        attempt.setResultVersionId(version.getId());
        attempt.setResultVersionNo(version.getVersionNo());
        attempt.setLlmConversationId(llmConversationId);
        attempt.setErrorCode(null);
        attempt.setUpdatedBy(updatedBy);
        attempt.setUpdatedAt(now);
        attempt.setFinishedAt(now);
    }

    /** 构造已成功 attempt 的幂等完成结果。 */
    private CompletionResult replayCompletion(SemanticModelingDraftDO draft,
            SemanticModelingRevisionAttemptDO attempt) {
        SemanticModelingDraftVersionDO version = attempt.getResultVersionId() == null ? null
                : versionMapper.selectById(attempt.getResultVersionId());
        if (version == null) {
            return new CompletionResult(CompletionDisposition.TERMINAL_REJECTED, attempt, draft,
                    null);
        }
        return new CompletionResult(CompletionDisposition.REPLAY_SUCCEEDED, attempt, draft,
                version);
    }

    /** 查询修订尝试，不存在时返回统一 404。 */
    private SemanticModelingRevisionAttemptDO requireAttempt(Long attemptId) {
        SemanticModelingRevisionAttemptDO attempt = attemptMapper.selectById(attemptId);
        if (attempt == null) {
            throw new ModelingDraftException(HttpStatus.NOT_FOUND,
                    ModelingDraftConstants.ERROR_NOT_FOUND, "草稿修订尝试不存在");
        }
        return attempt;
    }

    /** 判断尝试是否仍持有活动租约。 */
    private boolean isActiveRunning(SemanticModelingRevisionAttemptDO attempt) {
        return STATUS_RUNNING.equals(attempt.getStatus())
                && Objects.equals(attempt.getActiveMarker(), 1);
    }

    /** 判断租约是否已到期；空到期时间按系统失败处理，避免永久占锁。 */
    private boolean isExpired(SemanticModelingRevisionAttemptDO attempt, Date now) {
        return attempt.getLeaseExpiresAt() == null || !attempt.getLeaseExpiresAt().after(now);
    }

    /** 使用数据库统一时钟判断租约，避免不同应用实例的系统时间偏差破坏互斥语义。 */
    private Date databaseNow() {
        Date now = attemptMapper.selectCurrentTimestamp();
        if (now == null) {
            throw new ModelingDraftException(HttpStatus.INTERNAL_SERVER_ERROR,
                    ModelingDraftConstants.ERROR_INTERNAL, "无法读取数据库租约时间");
        }
        return now;
    }

    /** 校验认领参数，避免超长键落库后产生跨库差异。 */
    private void validateClaim(Long draftId, Integer baseVersionNo, String idempotencyKey,
            String requestFingerprint, User user) {
        validateUser(user);
        if (draftId == null || baseVersionNo == null || baseVersionNo < 1) {
            throw invalidRequest("草稿 ID 和基线版本必须有效");
        }
        if (StringUtils.isBlank(idempotencyKey) || idempotencyKey.length() > MAX_KEY_LENGTH
                || !idempotencyKey.matches("[A-Za-z0-9._:-]+")) {
            throw invalidRequest("Idempotency-Key 必填且格式不合法");
        }
        if (StringUtils.isBlank(requestFingerprint)
                || requestFingerprint.length() > MAX_FINGERPRINT_LENGTH
                || !requestFingerprint.matches("[A-Za-z0-9._:-]+")) {
            throw invalidRequest("修订请求指纹不能为空且格式必须安全");
        }
    }

    /** 校验完成请求公共参数。 */
    private void validateCompletion(Long attemptId, User user) {
        validateUser(user);
        if (attemptId == null || attemptId <= 0) {
            throw invalidRequest("修订尝试 ID 必须有效");
        }
    }

    /** 校验内部调用用户，防止空审计人写入非空列。 */
    private void validateUser(User user) {
        if (user == null || StringUtils.isBlank(user.getName())) {
            throw invalidRequest("当前用户不能为空");
        }
    }

    /** 只接受稳定错误码，任何疑似消息内容都替换为通用码。 */
    private String sanitizeErrorCode(String errorCode) {
        if (StringUtils.isBlank(errorCode) || errorCode.length() > MAX_ERROR_CODE_LENGTH
                || !errorCode.matches("[A-Z0-9_:-]+")) {
            return ERROR_PROVIDER_FAILED;
        }
        return errorCode;
    }

    /** 构造请求参数错误。 */
    private ModelingDraftException invalidRequest(String message) {
        return new ModelingDraftException(HttpStatus.BAD_REQUEST,
                ModelingDraftConstants.ERROR_INVALID_REQUEST, message);
    }

    /** 构造修订租约冲突。 */
    private ModelingDraftException conflict(String message) {
        return new ModelingDraftException(HttpStatus.CONFLICT,
                ModelingDraftConstants.ERROR_CONFLICT, message);
    }

    /** Provider 调用前认领结果。 */
    public record ClaimResult(ClaimDisposition disposition,
            SemanticModelingRevisionAttemptDO attempt) {

        /** 只有新认领成功才能调用 Provider。 */
        public boolean shouldInvokeProvider() {
            return disposition == ClaimDisposition.CLAIMED;
        }
    }

    /** Provider 调用前认领状态。 */
    public enum ClaimDisposition {
        CLAIMED,
        REPLAY_SUCCEEDED,
        SAME_KEY_RUNNING,
        SAME_KEY_TERMINAL,
        SAME_KEY_EXPIRED,
        IDEMPOTENCY_CONFLICT,
        OTHER_ACTIVE_REVISION,
        DRAFT_VERSION_CONFLICT
    }

    /** Provider 调用后的持久化完成结果。 */
    public record CompletionResult(CompletionDisposition disposition,
            SemanticModelingRevisionAttemptDO attempt, SemanticModelingDraftDO draft,
            SemanticModelingDraftVersionDO version) {}

    /** Provider 调用后的持久化状态。 */
    public enum CompletionDisposition {
        SUCCEEDED, REPLAY_SUCCEEDED, LEASE_EXPIRED, DRAFT_VERSION_CONFLICT, TERMINAL_REJECTED
    }
}
