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
 * 职责说明：映射 {@code s2_semantic_modeling_draft_version}，每次 AI 首次成功或管理员保存时 新增一行 JSON 快照。并发说明：数据库唯一键
 * {@code (draft_id, version_no)} 与主表乐观锁共同 保证版本号不重复；版本行创建后不提供更新或删除入口。
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

    private String createdBy;

    private Date createdAt;
}
