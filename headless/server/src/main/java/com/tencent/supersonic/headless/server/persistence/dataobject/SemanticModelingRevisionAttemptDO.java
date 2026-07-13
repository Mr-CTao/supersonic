package com.tencent.supersonic.headless.server.persistence.dataobject;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.util.Date;

/**
 * AI 语义建模草稿修订尝试持久化对象。
 *
 * <p>
 * 职责说明：映射 {@code s2_semantic_modeling_revision_attempt}，在调用外部 Provider 前持久化修订请求的
 * 幂等键、请求指纹、基线版本和租约，并在终态记录生成的不可变版本。运行态使用 {@code active_marker = 1}，终态必须置空；数据库唯一键保证多实例下同一草稿只有一个活动修订。
 * 本对象不保存修订指令、Provider 原文或未脱敏错误信息。
 * </p>
 */
@Data
@TableName("s2_semantic_modeling_revision_attempt")
public class SemanticModelingRevisionAttemptDO {

    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    private Long draftId;

    /** Provider 调用和最终保存共同绑定的不可变草稿基线版本。 */
    private Integer baseVersionNo;

    /** 草稿内修订幂等键；MySQL 使用二进制排序规则保持跨数据库大小写语义一致。 */
    private String idempotencyKey;

    /** 规范化请求摘要，用于拒绝同一幂等键携带不同修订参数。 */
    private String requestFingerprint;

    /** RUNNING、SUCCEEDED、FAILED 或 SYSTEM_FAILED。 */
    private String status;

    /** RUNNING 时固定为 1，任何终态必须置为 null 以释放草稿级活动租约。 */
    private Integer activeMarker;

    private Date leaseStartedAt;

    private Date leaseExpiresAt;

    /** SUCCEEDED 时绑定的新不可变草稿版本 ID。 */
    private Long resultVersionId;

    /** SUCCEEDED 时绑定的新草稿版本号。 */
    private Integer resultVersionNo;

    /** Provider 调用关联的阶段 1 本地会话 ID。 */
    private Long llmConversationId;

    /** 仅保存稳定、脱敏且长度受控的错误码。 */
    private String errorCode;

    private String createdBy;

    private Date createdAt;

    private String updatedBy;

    private Date updatedAt;

    private Date finishedAt;
}
