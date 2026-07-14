package com.tencent.supersonic.headless.server.persistence.dataobject;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.util.Date;

/**
 * AI 语义建模发布步骤记录。
 *
 * <p>
 * 职责说明：映射 {@code s2_semantic_release_step}，分别记录创建、知识刷新和逆序删除步骤的
 * 目标与结果。并发说明：{@code (release_id, step_key)} 唯一键提供跨实例幂等边界；调用方只允许 失败步骤重新认领，已成功步骤必须跳过。
 * </p>
 */
@Data
@TableName("s2_semantic_release_step")
public class SemanticReleaseStepDO {

    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    private Long releaseId;

    /** 发布内稳定步骤键，例如 {@code CREATE_MODEL:order_model}。 */
    private String stepKey;

    private String stepType;

    private String targetType;

    private String targetKey;

    private String targetName;

    private Long targetId;

    private String status;

    private Integer attemptCount;

    /** 脱敏后的失败摘要；成功或重新认领时清空。 */
    private String errorMessage;

    private Date startedAt;

    private Date finishedAt;

    private Date createdAt;

    private Date updatedAt;
}
