-- 20260710 AI semantic modeling phase 4: multi-turn revision persistence and validation gate.
-- This migration only extends isolated drafts and creates validation reports. It never writes
-- formal models, dimensions, metrics, terms, approval records, published assets or rollback data.

ALTER TABLE `s2_semantic_modeling_draft`
    ADD COLUMN `submitted_validation_report_id` bigint DEFAULT NULL
        COMMENT '阶段4提交待审批时绑定的验证报告ID' AFTER `error_message`,
    ADD COLUMN `submission_idempotency_key` varchar(128)
        CHARACTER SET utf8mb4 COLLATE utf8mb4_bin DEFAULT NULL
        COMMENT '提交待审批请求幂等键（大小写敏感）' AFTER `submitted_validation_report_id`,
    ADD COLUMN `submitted_by` varchar(100) DEFAULT NULL
        COMMENT '提交待审批人' AFTER `submission_idempotency_key`,
    ADD COLUMN `submitted_at` datetime DEFAULT NULL
        COMMENT '提交待审批时间' AFTER `submitted_by`;

ALTER TABLE `s2_semantic_modeling_draft_version`
    ADD COLUMN `request_idempotency_key` varchar(128)
        CHARACTER SET utf8mb4 COLLATE utf8mb4_bin DEFAULT NULL
        COMMENT '创建该版本的请求幂等键（大小写敏感）' AFTER `llm_conversation_id`,
    ADD COLUMN `request_fingerprint` varchar(128) DEFAULT NULL
        COMMENT '幂等请求规范化参数指纹' AFTER `request_idempotency_key`,
    ADD UNIQUE KEY `uk_semantic_draft_version_request`
        (`draft_id`, `request_idempotency_key`);

CREATE TABLE IF NOT EXISTS `s2_semantic_validation_report` (
    `id` bigint(20) NOT NULL AUTO_INCREMENT COMMENT '草稿验证报告ID',
    `draft_id` bigint NOT NULL COMMENT '所属隔离草稿ID',
    `draft_version_id` bigint NOT NULL COMMENT '被验证的不可变草稿版本ID',
    `draft_version_no` int NOT NULL COMMENT '被验证的草稿版本号',
    `status` varchar(32) NOT NULL COMMENT 'RUNNING、PASSED、WARNING、FAILED或SYSTEM_FAILED',
    `validation_options` mediumtext DEFAULT NULL COMMENT '规范化验证选项JSON',
    `required_check_results` mediumtext DEFAULT NULL COMMENT '阶段4固定十项必需检查JSON',
    `planned_objects` mediumtext DEFAULT NULL COMMENT '计划语义对象摘要JSON，不代表已发布',
    `field_existence_result` mediumtext DEFAULT NULL COMMENT '字段存在性检查JSON',
    `conflict_result` mediumtext DEFAULT NULL COMMENT '名称和术语冲突检查JSON',
    `sensitive_field_result` mediumtext DEFAULT NULL COMMENT '敏感字段检查JSON',
    `sample_question_results` mediumtext DEFAULT NULL COMMENT '样例问法验证结果JSON',
    `sql_safety_result` mediumtext DEFAULT NULL COMMENT 'SQL只读安全检查JSON',
    `performance_risk_result` mediumtext DEFAULT NULL COMMENT '性能风险检查JSON',
    `uncertainty_result` mediumtext DEFAULT NULL COMMENT '不确定项检查JSON',
    `blocking_items` mediumtext DEFAULT NULL COMMENT '阻塞提交待审批的问题JSON',
    `warning_items` mediumtext DEFAULT NULL COMMENT '非阻塞警告JSON',
    `blocking_count` int NOT NULL DEFAULT 0 COMMENT '阻塞项数量',
    `warning_count` int NOT NULL DEFAULT 0 COMMENT '警告项数量',
    `active_marker` int DEFAULT NULL COMMENT 'RUNNING时为1，终态置NULL以释放单草稿运行锁',
    `system_error_code` varchar(64) DEFAULT NULL COMMENT '脱敏系统错误码',
    `created_by` varchar(100) NOT NULL COMMENT '触发验证人',
    `created_at` datetime NOT NULL COMMENT '验证报告创建时间',
    `finished_at` datetime DEFAULT NULL COMMENT '验证完成或失败时间',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_semantic_validation_active` (`draft_id`, `active_marker`),
    KEY `idx_semantic_validation_draft` (`draft_id`, `id`),
    KEY `idx_semantic_validation_version_status` (`draft_version_id`, `status`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
  COMMENT='AI语义建模隔离草稿验证报告，不写正式语义资产';
