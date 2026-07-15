package com.tencent.supersonic.headless.server.persistence.dataobject;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.util.Date;

/**
 * 语义资产路由分析持久化快照。
 *
 * <p>职责：映射 {@code s2_semantic_asset_route}，保存来源、请求指纹、候选事实、规则/LLM 证据、推荐、
 * 确认、租约、乐观锁和消费审计。JSON 字段只保存有限结构化摘要，不保存样例行、Prompt 或 SQL 条件值。
 * 对象本身不承担并发控制，跨实例一致性由唯一键和 Mapper 条件更新保证。</p>
 */
@Data
@TableName("s2_semantic_asset_route")
public class SemanticAssetRoutingDO {
    @TableId(value = "id", type = IdType.AUTO)
    private Long id;
    private String sourceType;
    private Long sourceId;
    private String requestFingerprint;
    private String idempotencyKey;
    private String status;
    private String businessGoal;
    private Long domainId;
    private Long dataSourceId;
    private String catalogName;
    private String databaseName;
    private String selectedTables;
    private Integer chatModelId;
    private Boolean includeSample;
    private String candidateSnapshot;
    private String ruleEvidence;
    private String llmAdvice;
    private String recommendedAction;
    private String recommendedCandidateType;
    private Long recommendedCandidateId;
    private Long recommendedCandidateVersion;
    private String coveredCapabilities;
    private String missingCapabilities;
    private String resultOperations;
    private String businessQuestions;
    private String decisionSource;
    private String confirmedAction;
    private String confirmedCandidateType;
    private Long confirmedCandidateId;
    private Long confirmedCandidateVersion;
    private String businessAnswers;
    private String overrideReason;
    private String confirmedBy;
    private Date confirmedAt;
    private Long llmConversationId;
    private String failureCode;
    private String failureMessage;
    private Date analysisStartedAt;
    private Date analysisCompletedAt;
    private Date leaseExpiresAt;
    private Date expiresAt;
    private Integer analysisVersion;
    private Integer lockVersion;
    private String confirmationIdempotencyKey;
    private String confirmationRequestFingerprint;
    private Long consumedByDraftId;
    private Date consumedAt;
    private String createdBy;
    private Date createdAt;
    private String updatedBy;
    private Date updatedAt;
}
