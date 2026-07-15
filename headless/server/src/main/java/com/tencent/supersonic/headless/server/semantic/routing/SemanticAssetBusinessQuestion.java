package com.tencent.supersonic.headless.server.semantic.routing;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.List;

/**
 * 路由阶段业务问题。
 *
 * <p>
 * 职责：向前端提供稳定 key、控件类型和可选项，避免把底层冲突暴露成超长自由文本表单。对象只承载 安全业务口径，不包含表名、正式资产 ID 或 SQL。
 * </p>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SemanticAssetBusinessQuestion {
    private String key;
    private String question;
    private boolean required;
    private String answerType;
    @Builder.Default
    private List<QuestionOption> options = new ArrayList<>();
    private Integer maxLength;
    private boolean affectsRecommendation;

    /**
     * 业务问题稳定选项。
     *
     * <p>
     * key 用于服务端持久化，label 仅用于展示。
     * </p>
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class QuestionOption {
        private String key;
        private String label;
    }
}
