-- 20260714 AI semantic modeling phase 5: approval, release steps, knowledge refresh and rollback.
ALTER TABLE `s2_semantic_modeling_draft`
    ADD COLUMN `approved_by` varchar(100) DEFAULT NULL COMMENT '审批通过人' AFTER `submitted_at`,
    ADD COLUMN `approved_at` datetime DEFAULT NULL COMMENT '审批通过时间' AFTER `approved_by`,
    ADD COLUMN `approval_reason` varchar(1000) DEFAULT NULL COMMENT '审批备注或拒绝原因' AFTER `approved_at`,
    ADD COLUMN `rejected_by` varchar(100) DEFAULT NULL COMMENT '审批拒绝人' AFTER `approval_reason`,
    ADD COLUMN `rejected_at` datetime DEFAULT NULL COMMENT '审批拒绝时间' AFTER `rejected_by`;

CREATE TABLE IF NOT EXISTS `s2_semantic_release` (
    `id` bigint(20) NOT NULL AUTO_INCREMENT,
    `release_no` varchar(64) NOT NULL,
    `draft_id` bigint NOT NULL,
    `draft_version_id` bigint NOT NULL,
    `draft_version_no` int NOT NULL,
    `validation_report_id` bigint NOT NULL,
    `release_status` varchar(32) NOT NULL,
    `released_objects` mediumtext NOT NULL,
    `dict_reload_status` varchar(32) NOT NULL,
    `embedding_reload_status` varchar(32) NOT NULL,
    `approved_by` varchar(100) NOT NULL,
    `released_by` varchar(100) NOT NULL,
    `released_at` datetime DEFAULT NULL,
    `rollback_from_release_id` bigint DEFAULT NULL,
    `rollback_reason` varchar(1000) DEFAULT NULL,
    `rolled_back_by` varchar(100) DEFAULT NULL,
    `rolled_back_at` datetime DEFAULT NULL,
    `error_message` varchar(1000) DEFAULT NULL,
    `idempotency_key` varchar(128) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL,
    `created_at` datetime NOT NULL,
    `updated_at` datetime NOT NULL,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_semantic_release_no` (`release_no`),
    UNIQUE KEY `uk_semantic_release_draft` (`draft_id`),
    KEY `idx_semantic_release_status` (`release_status`, `id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='AI语义建模发布与知识刷新审计';

CREATE TABLE IF NOT EXISTS `s2_semantic_release_step` (
    `id` bigint(20) NOT NULL AUTO_INCREMENT,
    `release_id` bigint NOT NULL,
    `step_key` varchar(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL,
    `step_type` varchar(64) NOT NULL,
    `target_type` varchar(32) NOT NULL,
    `target_key` varchar(255) DEFAULT NULL,
    `target_name` varchar(255) DEFAULT NULL,
    `target_id` bigint DEFAULT NULL,
    `status` varchar(32) NOT NULL,
    `attempt_count` int NOT NULL DEFAULT 1,
    `error_message` varchar(1000) DEFAULT NULL,
    `started_at` datetime DEFAULT NULL,
    `finished_at` datetime DEFAULT NULL,
    `created_at` datetime NOT NULL,
    `updated_at` datetime NOT NULL,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_semantic_release_step` (`release_id`, `step_key`),
    KEY `idx_semantic_release_step_list` (`release_id`, `id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='AI语义发布逐步骤结果';
