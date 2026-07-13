package com.tencent.supersonic.headless.server.semantic.modeling;

import lombok.Builder;
import lombok.Data;

/**
 * 单条语义建模验证发现。
 *
 * <p>
 * 职责说明：以稳定分类、错误码、严重级别和 JSON 路径描述一个阻塞项或警告项。消息必须脱敏，不得包含样例原值、完整 SQL 参数或内部异常栈。 本 DTO
 * 在单次响应中创建，不持有共享可变状态。
 * </p>
 */
@Data
@Builder
public class ModelingValidationFinding {

    /** 对象类型；用于不回显原始名称时仍向前端解释问题归属。 */
    private String objectType;

    /** 草稿本地对象 key，不包含正式资产 ID 或敏感值。 */
    private String objectKey;

    /** 检查类别，例如 FIELD_EXISTENCE、SENSITIVE_FIELD 或 SQL_SAFETY。 */
    private String category;

    /** 供前端稳定映射的机器错误码。 */
    private String code;

    /** 严重级别，例如 BLOCKING 或 WARNING。 */
    private String severity;

    /** 对应草稿对象的 JSONPath；无法定位时可为空。 */
    private String path;

    /** 面向管理员的脱敏说明。 */
    private String message;

    /** 关联模型 key；报告级问题可为空。 */
    private String modelKey;
}
