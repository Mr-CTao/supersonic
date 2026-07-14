package com.tencent.supersonic.headless.server.persistence.dataobject;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.util.Date;

/**
 * AI 语义建模发布主记录。
 *
 * <p>
 * 职责说明：映射 {@code s2_semantic_release}，保存一次草稿发布的治理状态、知识刷新结果和
 * 回滚审计摘要。正式模型、维度、指标和术语仍由既有语义管理服务写入，本对象只保存编排证据。 并发说明：同一草稿由数据库唯一键限制为一条发布记录，状态推进在事务内配合草稿行锁完成。
 * </p>
 */
@Data
@TableName("s2_semantic_release")
public class SemanticReleaseDO {

    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    private String releaseNo;

    private Long draftId;

    private Long draftVersionId;

    private Integer draftVersionNo;

    private Long validationReportId;

    private String releaseStatus;

    /** 已发布对象的安全 JSON 摘要；只包含类型、草稿 key、名称和正式对象 ID。 */
    private String releasedObjects;

    private String dictReloadStatus;

    private String embeddingReloadStatus;

    private String approvedBy;

    private String releasedBy;

    private Date releasedAt;

    private Long rollbackFromReleaseId;

    private String rollbackReason;

    private String rolledBackBy;

    private Date rolledBackAt;

    /** 面向管理员的脱敏错误摘要，不保存堆栈、SQL 或凭证。 */
    private String errorMessage;

    private String idempotencyKey;

    private Date createdAt;

    private Date updatedAt;
}
