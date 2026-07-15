-- 20260714 AI semantic asset routing before draft generation.
-- No foreign keys point to formal semantic metadata: policy and permission checks remain in the
-- service layer, while this table provides durable idempotency, leases and audit snapshots.
CREATE TABLE IF NOT EXISTS `s2_semantic_asset_route` (
    `id` bigint(20) NOT NULL AUTO_INCREMENT COMMENT '语义资产路由分析ID',
    `source_type` varchar(32) NOT NULL COMMENT '来源类型，例如SEMANTIC_GAP或DATA_SOURCE',
    `source_id` bigint DEFAULT NULL COMMENT '来源对象ID',
    `request_fingerprint` varchar(128) NOT NULL COMMENT '影响路由结果的规范化请求指纹',
    `idempotency_key` varchar(128) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL
        COMMENT '创建分析请求幂等键（大小写敏感）',
    `status` varchar(32) NOT NULL COMMENT 'PENDING、ANALYZING、SUCCEEDED、FAILED或EXPIRED',
    `business_goal` text NOT NULL COMMENT '脱敏后的业务目标',
    `domain_id` bigint DEFAULT NULL COMMENT '主题域快照ID',
    `data_source_id` bigint NOT NULL COMMENT '已通过权限校验的数据源快照ID',
    `catalog_name` varchar(255) DEFAULT NULL COMMENT '分析时catalog快照',
    `database_name` varchar(255) DEFAULT NULL COMMENT '分析时database或schema快照',
    `selected_tables` mediumtext NOT NULL COMMENT '分析时选表快照JSON',
    `chat_model_id` int NOT NULL COMMENT '路由语义建议使用的LLM配置ID',
    `include_sample` tinyint(1) NOT NULL DEFAULT 0 COMMENT '是否允许使用服务端脱敏样例',
    `candidate_snapshot` mediumtext DEFAULT NULL COMMENT 'ACL过滤后的候选快照JSON',
    `rule_evidence` mediumtext DEFAULT NULL COMMENT '确定性召回和覆盖证据JSON',
    `llm_advice` mediumtext DEFAULT NULL COMMENT '通过Schema校验的受限LLM建议JSON',
    `recommended_action` varchar(32) DEFAULT NULL COMMENT '服务端策略推荐动作',
    `recommended_candidate_type` varchar(32) DEFAULT NULL COMMENT '推荐候选资产类型',
    `recommended_candidate_id` bigint DEFAULT NULL COMMENT '推荐候选资产ID',
    `recommended_candidate_version` bigint DEFAULT NULL COMMENT '推荐时目标资产版本快照',
    `covered_capabilities` mediumtext DEFAULT NULL COMMENT '已覆盖能力JSON',
    `missing_capabilities` mediumtext DEFAULT NULL COMMENT '缺失能力JSON',
    `result_operations` mediumtext DEFAULT NULL COMMENT '排序、Top N和分页等查询层能力JSON',
    `business_questions` mediumtext DEFAULT NULL COMMENT '待管理员确认的结构化业务问题JSON',
    `decision_source` varchar(32) DEFAULT NULL COMMENT 'RULE_ONLY或RULE_AND_LLM',
    `confirmed_action` varchar(32) DEFAULT NULL COMMENT '管理员最终确认动作',
    `confirmed_candidate_type` varchar(32) DEFAULT NULL COMMENT '最终确认候选资产类型',
    `confirmed_candidate_id` bigint DEFAULT NULL COMMENT '最终确认候选资产ID',
    `confirmed_candidate_version` bigint DEFAULT NULL COMMENT '确认时目标资产版本快照',
    `business_answers` mediumtext DEFAULT NULL COMMENT '结构化业务答案JSON',
    `override_reason` varchar(1000) DEFAULT NULL COMMENT '覆盖系统推荐时的必填原因',
    `confirmed_by` varchar(100) DEFAULT NULL COMMENT '确认管理员',
    `confirmed_at` datetime DEFAULT NULL COMMENT '确认时间',
    `llm_conversation_id` bigint DEFAULT NULL COMMENT '受限路由Advisor关联的本地LLM会话ID',
    `failure_code` varchar(64) DEFAULT NULL COMMENT '稳定且脱敏的失败码',
    `failure_message` varchar(1500) DEFAULT NULL COMMENT '脱敏失败摘要',
    `analysis_started_at` datetime DEFAULT NULL COMMENT '分析租约开始时间',
    `analysis_completed_at` datetime DEFAULT NULL COMMENT '分析完成时间',
    `lease_expires_at` datetime DEFAULT NULL COMMENT '活动分析租约过期时间',
    `expires_at` datetime DEFAULT NULL COMMENT '已完成路由快照过期时间',
    `analysis_version` int NOT NULL DEFAULT 1 COMMENT '澄清重分析版本',
    `confirmation_idempotency_key` varchar(128) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin DEFAULT NULL COMMENT '确认请求幂等键',
    `confirmation_request_fingerprint` varchar(128) DEFAULT NULL COMMENT '确认请求指纹',
    `consumed_by_draft_id` bigint DEFAULT NULL COMMENT '成功消费该确认快照的唯一草稿ID',
    `consumed_at` datetime DEFAULT NULL COMMENT '路由被草稿消费的时间',
    `lock_version` int NOT NULL DEFAULT 0 COMMENT '确认和消费使用的乐观锁版本',
    `created_by` varchar(100) NOT NULL COMMENT '分析创建人',
    `created_at` datetime NOT NULL COMMENT '创建时间',
    `updated_by` varchar(100) NOT NULL COMMENT '最近更新人',
    `updated_at` datetime NOT NULL COMMENT '最近更新时间',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_semantic_asset_route_idempotency` (`created_by`, `idempotency_key`),
    KEY `idx_semantic_asset_route_source` (`source_type`, `source_id`, `id`),
    KEY `idx_semantic_asset_route_lease` (`status`, `lease_expires_at`),
    KEY `idx_semantic_asset_route_confirmed_by` (`confirmed_by`, `confirmed_at`),
    KEY `idx_semantic_asset_route_decision` (`confirmed_action`, `confirmed_at`),
    UNIQUE KEY `uk_semantic_asset_route_consumed_draft` (`consumed_by_draft_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
  COMMENT='语义资产复用、增强、新建或待确认的可审计路由快照';

-- Dynamic DDL keeps fresh installs, upgrades and retry-after-partial-failure convergent.
SET @asset_route_ddl = IF(
    EXISTS(SELECT 1 FROM information_schema.columns WHERE table_schema = DATABASE()
        AND table_name = 's2_semantic_modeling_draft' AND column_name = 'route_analysis_id'),
    'DO 0',
    'ALTER TABLE `s2_semantic_modeling_draft` ADD COLUMN `route_analysis_id` bigint DEFAULT NULL COMMENT ''已确认语义资产路由分析ID'' AFTER `error_message`'
);
PREPARE asset_route_stmt FROM @asset_route_ddl;
EXECUTE asset_route_stmt;
DEALLOCATE PREPARE asset_route_stmt;
SET @asset_route_ddl = IF(
    EXISTS(SELECT 1 FROM information_schema.columns WHERE table_schema = DATABASE()
        AND table_name = 's2_semantic_modeling_draft' AND column_name = 'route_action'),
    'DO 0',
    'ALTER TABLE `s2_semantic_modeling_draft` ADD COLUMN `route_action` varchar(32) DEFAULT NULL COMMENT ''确认路由动作'' AFTER `route_analysis_id`'
);
PREPARE asset_route_stmt FROM @asset_route_ddl;
EXECUTE asset_route_stmt;
DEALLOCATE PREPARE asset_route_stmt;
SET @asset_route_ddl = IF(
    EXISTS(SELECT 1 FROM information_schema.columns WHERE table_schema = DATABASE()
        AND table_name = 's2_semantic_modeling_draft' AND column_name = 'route_target_asset_type'),
    'DO 0',
    'ALTER TABLE `s2_semantic_modeling_draft` ADD COLUMN `route_target_asset_type` varchar(32) DEFAULT NULL COMMENT ''确认目标资产类型'' AFTER `route_action`'
);
PREPARE asset_route_stmt FROM @asset_route_ddl;
EXECUTE asset_route_stmt;
DEALLOCATE PREPARE asset_route_stmt;
SET @asset_route_ddl = IF(
    EXISTS(SELECT 1 FROM information_schema.columns WHERE table_schema = DATABASE()
        AND table_name = 's2_semantic_modeling_draft' AND column_name = 'route_target_asset_id'),
    'DO 0',
    'ALTER TABLE `s2_semantic_modeling_draft` ADD COLUMN `route_target_asset_id` bigint DEFAULT NULL COMMENT ''确认目标资产ID'' AFTER `route_target_asset_type`'
);
PREPARE asset_route_stmt FROM @asset_route_ddl;
EXECUTE asset_route_stmt;
DEALLOCATE PREPARE asset_route_stmt;
SET @asset_route_ddl = IF(
    EXISTS(SELECT 1 FROM information_schema.columns WHERE table_schema = DATABASE()
        AND table_name = 's2_semantic_modeling_draft' AND column_name = 'route_target_asset_version'),
    'DO 0',
    'ALTER TABLE `s2_semantic_modeling_draft` ADD COLUMN `route_target_asset_version` bigint DEFAULT NULL COMMENT ''确认目标资产版本快照'' AFTER `route_target_asset_id`'
);
PREPARE asset_route_stmt FROM @asset_route_ddl;
EXECUTE asset_route_stmt;
DEALLOCATE PREPARE asset_route_stmt;
SET @asset_route_ddl = IF(
    EXISTS(SELECT 1 FROM information_schema.statistics WHERE table_schema = DATABASE()
        AND table_name = 's2_semantic_modeling_draft' AND index_name = 'uk_semantic_draft_route_analysis'),
    'DO 0',
    'CREATE UNIQUE INDEX `uk_semantic_draft_route_analysis` ON `s2_semantic_modeling_draft` (`route_analysis_id`)'
);
PREPARE asset_route_stmt FROM @asset_route_ddl;
EXECUTE asset_route_stmt;
DEALLOCATE PREPARE asset_route_stmt;
SET @asset_route_ddl = NULL;
