package com.tencent.supersonic.headless.server.semantic.modeling.release;

import lombok.Builder;
import lombok.Data;

import java.util.ArrayList;
import java.util.Date;
import java.util.List;

/**
 * 阶段 5 发布审计响应。
 *
 * <p>
 * 职责说明：返回发布主状态、独立知识刷新状态、对象摘要和可选步骤详情。所有错误信息均为 管理员可见的脱敏摘要，不暴露内部异常堆栈。
 * </p>
 */
@Data
@Builder
public class SemanticReleaseResp {
    private Long id;
    private String releaseNo;
    private Long draftId;
    private Integer draftVersionNo;
    private Long validationReportId;
    private String draftTitle;
    private Long sourceGapId;
    private String releaseStatus;
    private String dictReloadStatus;
    private String embeddingReloadStatus;
    private String approvedBy;
    private String releasedBy;
    private Date releasedAt;
    private String errorMessage;
    private String rollbackReason;
    private String rolledBackBy;
    private Date rolledBackAt;
    @Builder.Default
    private List<ReleasedObject> releasedObjects = new ArrayList<>();
    @Builder.Default
    private List<SemanticReleaseStepResp> steps = new ArrayList<>();

    /**
     * 已发布 AI 新增对象摘要。
     *
     * <p>
     * 仅保存回滚白名单所需的服务端对象 ID 与草稿 key，不接受客户端写入。
     * </p>
     */
    @Data
    @Builder
    public static class ReleasedObject {
        private String type;
        private String key;
        private String name;
        private Long targetId;
    }
}
