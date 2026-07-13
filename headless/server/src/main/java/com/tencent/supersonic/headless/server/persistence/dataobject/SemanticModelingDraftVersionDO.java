package com.tencent.supersonic.headless.server.persistence.dataobject;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.util.Date;

/**
 * AI 语义建模草稿不可变版本快照。
 *
 * <p>
 * 职责说明：映射 {@code s2_semantic_modeling_draft_version}，每次 AI 首次成功、管理员保存或阶段 4 AI 修订时新增一行 JSON
 * 快照。修订请求的幂等键和指纹随快照保存，用于识别安全重放与参数冲突；RESTORED 版本额外保存首次成功响应的锁版本， 避免后续重放混入当前草稿状态。并发说明：数据库唯一键
 * {@code (draft_id, version_no)} 与主表乐观锁共同保证版本号不重复； 版本行创建后不提供更新或删除入口。
 * </p>
 */
@Data
@TableName("s2_semantic_modeling_draft_version")
public class SemanticModelingDraftVersionDO {

    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    private Long draftId;

    private Integer versionNo;

    private String draftJson;

    private String changeSource;

    private String changeSummary;

    private Long llmConversationId;

    /** 创建该版本的请求幂等键；历史阶段 3 版本允许为空。 */
    private String requestIdempotencyKey;

    /** 幂等请求规范化参数的摘要，用于拒绝相同键携带不同修订指令。 */
    private String requestFingerprint;

    /** RESTORED 操作首次成功后的草稿锁版本；其他版本及迁移前历史记录允许为空。 */
    private Integer resultLockVersion;

    private String createdBy;

    private Date createdAt;
}
