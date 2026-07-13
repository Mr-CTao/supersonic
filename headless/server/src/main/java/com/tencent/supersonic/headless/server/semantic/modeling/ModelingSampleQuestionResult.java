package com.tencent.supersonic.headless.server.semantic.modeling;

import lombok.Builder;
import lombok.Data;

import java.util.List;

/**
 * 单条样例问法的验证结果。
 *
 * <p>
 * 职责说明：记录问法是否经隔离草稿 Schema 产生真实 selected parse，以及现有语义翻译器生成的脱敏 S2SQL/SQL 预览和只读判定。阶段 4 只翻译、不执行
 * SQL，也不返回结果行或敏感参数值。本 DTO 只承载 验证快照，不共享执行状态。
 * </p>
 */
@Data
@Builder
public class ModelingSampleQuestionResult {

    /** 样例问法所属草稿模型 key。 */
    private String modelKey;

    /** 管理员可见的样例问法。 */
    private String question;

    /** 是否成功命中预期语义对象。 */
    private Boolean matched;

    /** 实际命中的模型、维度、指标或术语 key。 */
    private List<String> matchedObjectKeys;

    /** 验证模式；阶段 4 使用 DRAFT_SEMANTIC_PIPELINE。 */
    private String validationMode;

    /** 脱敏且限长的 S2SQL 预览。 */
    private String s2sqlPreview;

    /** 脱敏且限长的 SQL 预览。 */
    private String sqlPreview;

    /** SQL AST 是否确认仅包含单条只读查询。 */
    private Boolean readOnly;

    /** 面向管理员的脱敏验证说明。 */
    private String message;
}
