package com.tencent.supersonic.headless.server.semantic.modeling;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 语义建模草稿版本之间的一条结构化差异。
 *
 * <p>
 * 职责说明：向管理端提供稳定 JSONPath、变更类型和经过长度限制的前后值。对象仅在单次比较响应中创建，不作为共享状态使用； 值字段可能包含管理员有权查看的草稿内容，但不会包含无限长度的原始
 * JSON。
 * </p>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ModelingDraftDiffItem {

    /** 变更所在的稳定 JSONPath。 */
    private String path;

    /** 变更类型，仅使用 ADDED、REMOVED 或 CHANGED。 */
    private String changeType;

    /** 变更前的截断值；新增项为空。 */
    private String beforeValue;

    /** 变更后的截断值；删除项为空。 */
    private String afterValue;
}
