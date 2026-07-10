-- 20260710 AI semantic modeling phase 3: generation-attempt history and retry support.
-- This migration is isolated from formal semantic assets and intentionally adds no foreign keys.

ALTER TABLE `s2_semantic_modeling_draft`
    ADD COLUMN `current_attempt_no` int NOT NULL DEFAULT 1
    COMMENT '当前生成尝试序号；首次生成固定为1' AFTER `current_version_no`;

CREATE TABLE IF NOT EXISTS `s2_semantic_modeling_draft_attempt` (
    `id` bigint(20) NOT NULL AUTO_INCREMENT COMMENT '生成尝试ID',
    `draft_id` bigint NOT NULL COMMENT '所属逻辑草稿ID',
    `attempt_no` int NOT NULL COMMENT '草稿内从1递增的尝试序号',
    `trigger_type` varchar(32) NOT NULL COMMENT 'INITIAL或MANUAL_REGENERATION',
    `status` varchar(32) NOT NULL COMMENT 'QUEUED、GENERATING、SUCCEEDED或FAILED',
    `chat_model_id` int NOT NULL COMMENT '本次尝试使用的s2_chat_model.id',
    `include_sample` tinyint(1) NOT NULL DEFAULT 0 COMMENT '本次尝试是否使用服务端脱敏样例',
    `idempotency_key` varchar(128) NOT NULL COMMENT '本次生成操作幂等键',
    `request_fingerprint` varchar(128) DEFAULT NULL COMMENT '幂等请求内容指纹',
    `llm_conversation_id` bigint DEFAULT NULL COMMENT '本次尝试的阶段1会话ID',
    `generate_request_id` varchar(128) DEFAULT NULL COMMENT '首次生成Provider请求ID',
    `repair_request_id` varchar(128) DEFAULT NULL COMMENT '一次修复Provider请求ID',
    `raw_output` mediumtext DEFAULT NULL COMMENT '首次LLM输出，仅后端诊断使用',
    `repaired_output` mediumtext DEFAULT NULL COMMENT '修复后输出，仅后端诊断使用',
    `failure_stage` varchar(64) DEFAULT NULL COMMENT '脱敏后的失败阶段',
    `validation_issues` mediumtext DEFAULT NULL COMMENT '脱敏后的结构校验问题JSON',
    `error_code` varchar(64) DEFAULT NULL COMMENT '统一错误码',
    `error_message` varchar(1500) DEFAULT NULL COMMENT '脱敏错误摘要',
    `started_at` datetime DEFAULT NULL COMMENT 'Worker认领时间',
    `finished_at` datetime DEFAULT NULL COMMENT '尝试完成时间',
    `created_by` varchar(100) NOT NULL COMMENT '创建人',
    `created_at` datetime NOT NULL COMMENT '创建时间',
    `updated_by` varchar(100) NOT NULL COMMENT '最近更新人',
    `updated_at` datetime NOT NULL COMMENT '最近更新时间',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_semantic_draft_attempt_no` (`draft_id`, `attempt_no`),
    UNIQUE KEY `uk_semantic_draft_attempt_idempotency` (`created_by`, `idempotency_key`),
    KEY `idx_semantic_draft_attempt_list` (`draft_id`, `attempt_no`),
    KEY `idx_semantic_draft_attempt_recovery` (`status`, `started_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
  COMMENT='AI语义建模草稿不可变生成尝试历史';

INSERT IGNORE INTO `s2_semantic_modeling_draft_attempt` (
    `draft_id`, `attempt_no`, `trigger_type`, `status`, `chat_model_id`, `include_sample`,
    `idempotency_key`, `llm_conversation_id`, `raw_output`, `repaired_output`, `error_code`,
    `error_message`, `started_at`, `finished_at`, `created_by`, `created_at`, `updated_by`, `updated_at`
)
SELECT `id`,
       1,
       'INITIAL',
       CASE
           WHEN `status` = 'DRAFT' THEN 'SUCCEEDED'
           WHEN `status` = 'GENERATION_FAILED' THEN 'FAILED'
           WHEN `status` = 'GENERATING' AND `generation_started_at` IS NULL THEN 'QUEUED'
           ELSE 'GENERATING'
       END,
       `chat_model_id`,
       `include_sample`,
       `idempotency_key`,
       `llm_conversation_id`,
       `raw_output`,
       `repaired_output`,
       `error_code`,
       `error_message`,
       `generation_started_at`,
       `generation_finished_at`,
       `created_by`,
       `created_at`,
       `updated_by`,
       `updated_at`
FROM `s2_semantic_modeling_draft`;
