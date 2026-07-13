-- 20260710 AI semantic modeling phase 4: persistent revision attempts and cross-instance leases.
-- The table coordinates isolated draft revision only. It stores no Provider prompt or response body,
-- and it never writes approval, publication, rollback or formal semantic asset records.

CREATE TABLE IF NOT EXISTS `s2_semantic_modeling_revision_attempt` (
    `id` bigint(20) NOT NULL AUTO_INCREMENT COMMENT 'AI草稿修订尝试ID',
    `draft_id` bigint NOT NULL COMMENT '所属隔离草稿ID',
    `base_version_no` int NOT NULL COMMENT 'Provider调用绑定的草稿基线版本号',
    `idempotency_key` varchar(128) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL
        COMMENT '草稿内修订幂等键（大小写敏感）',
    `request_fingerprint` varchar(128) NOT NULL COMMENT '规范化修订请求指纹',
    `status` varchar(32) NOT NULL COMMENT 'RUNNING、SUCCEEDED、FAILED或SYSTEM_FAILED',
    `active_marker` int DEFAULT NULL COMMENT 'RUNNING时为1，终态置NULL以释放草稿活动租约',
    `lease_started_at` datetime NOT NULL COMMENT '修订租约开始时间',
    `lease_expires_at` datetime NOT NULL COMMENT '修订租约过期时间',
    `result_version_id` bigint DEFAULT NULL COMMENT 'SUCCEEDED时生成的不可变版本ID',
    `result_version_no` int DEFAULT NULL COMMENT 'SUCCEEDED时生成的草稿版本号',
    `llm_conversation_id` bigint DEFAULT NULL COMMENT 'Provider调用关联的阶段1本地会话ID',
    `error_code` varchar(64) DEFAULT NULL COMMENT '稳定且脱敏的失败码',
    `created_by` varchar(100) NOT NULL COMMENT '认领人',
    `created_at` datetime NOT NULL COMMENT '创建时间',
    `updated_by` varchar(100) NOT NULL COMMENT '最近更新人',
    `updated_at` datetime NOT NULL COMMENT '最近更新时间',
    `finished_at` datetime DEFAULT NULL COMMENT '成功、失败或过期结束时间',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_semantic_revision_attempt_request` (`draft_id`, `idempotency_key`),
    UNIQUE KEY `uk_semantic_revision_attempt_active` (`draft_id`, `active_marker`),
    KEY `idx_semantic_revision_attempt_draft` (`draft_id`, `id`),
    KEY `idx_semantic_revision_attempt_lease` (`status`, `lease_expires_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
  COMMENT='AI语义建模隔离草稿修订尝试与跨实例租约';
