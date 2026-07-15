package com.tencent.supersonic.headless.server.persistence.dataobject;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.util.Date;

/**
 * 语义缺口持久化对象。
 *
 * <p>
 * 职责说明：映射 `s2_semantic_gap` 表，用于保存 Chat BI 问答失败、负反馈和回退信号的聚合结果。安全说明：SQL、S2SQL、
 * 用户问题和反馈在进入本对象前由服务层截断并做基础脱敏，避免管理端列表暴露长文本或敏感号码。并发说明：本对象不持有共享状态； 同类缺口并发归并由语义缺口服务的分段锁保护。
 * </p>
 */
@Data
@TableName("s2_semantic_gap")
public class SemanticGapDO {

    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    private String question;

    private String normalizedQuestion;

    private Integer assistantId;

    private Long userId;

    private Long domainId;

    private Long dataSourceId;

    private String failureType;

    private String failureReason;

    private String matchedModelIds;

    private String matchedMetricIds;

    private String matchedDimensionIds;

    private String generatedSql;

    private String s2sql;

    private String feedback;

    private Integer occurrenceCount;

    private Integer negativeFeedbackCount;

    private Integer priorityScore;

    private String status;

    private Date createdAt;

    private Date lastSeenAt;

    private String createdBy;

    private Date updatedAt;

    private String updatedBy;

    private String ignoreReason;

    private Long sourceQueryId;

    private Long sourceChatId;

    private String recentQuestions;

    private String diagnosticStage;

    private String errorCode;

    private String traceId;

    private Integer errorLine;

    private Integer errorColumn;

    private String errorToken;

    private String suggestion;
}
