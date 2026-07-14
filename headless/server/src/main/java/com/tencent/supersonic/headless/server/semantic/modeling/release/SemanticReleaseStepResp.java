package com.tencent.supersonic.headless.server.semantic.modeling.release;

import lombok.Builder;
import lombok.Data;

import java.util.Date;

/**
 * 发布或回滚单步骤管理端响应。
 *
 * <p>
 * 职责说明：展示步骤类型、目标、正式对象 ID、当前结果和重试次数。错误消息已由服务层脱敏， 不包含堆栈与凭证。
 * </p>
 */
@Data
@Builder
public class SemanticReleaseStepResp {
    private Long id;
    private String stepKey;
    private String stepType;
    private String targetType;
    private String targetKey;
    private String targetName;
    private Long targetId;
    private String status;
    private Integer attemptCount;
    private String errorMessage;
    private Date startedAt;
    private Date finishedAt;
}
