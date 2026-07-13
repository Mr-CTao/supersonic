package com.tencent.supersonic.headless.server.semantic.modeling;

import lombok.Builder;
import lombok.Data;

/**
 * 草稿版本计划新增的单个语义对象摘要。
 *
 * <p>
 * 职责说明：供验证报告展示计划模型、维度、指标和术语，不包含正式语义对象 ID，也不执行任何发布写入。对象在报告构建时创建，无共享可变状态。
 * </p>
 */
@Data
@Builder
public class ModelingPlannedObject {

    /** 语义对象类型，例如 MODEL、DIMENSION、METRIC 或 TERM。 */
    private String type;

    /** 草稿内稳定对象 key。 */
    private String key;

    /** 管理员可见名称。 */
    private String name;

    /** 所属草稿模型 key；模型自身或全局术语可为空。 */
    private String modelKey;
}
