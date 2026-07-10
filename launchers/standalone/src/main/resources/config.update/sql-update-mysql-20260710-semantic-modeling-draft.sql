-- 20260710 AI semantic modeling phase 3: structured draft and immutable versions.
-- This migration only creates isolated draft tables. It never writes formal semantic assets
-- and intentionally declares no foreign keys so rollback remains independent of phase 1/2 data.

CREATE TABLE IF NOT EXISTS `s2_semantic_modeling_draft` (
    `id` bigint(20) NOT NULL AUTO_INCREMENT COMMENT 'AI语义建模草稿ID',
    `source_type` varchar(32) NOT NULL COMMENT '来源类型：SEMANTIC_GAP或DATA_SOURCE',
    `source_id` bigint DEFAULT NULL COMMENT '来源对象ID；缺口来源时为语义缺口ID',
    `title` varchar(255) DEFAULT NULL COMMENT '可选草稿标题',
    `business_goal` text NOT NULL COMMENT '管理员确认的业务建模目标',
    `domain_id` bigint DEFAULT NULL COMMENT '可选目标主题域ID',
    `data_source_id` bigint NOT NULL COMMENT '已通过权限校验的数据源ID',
    `catalog_name` varchar(255) DEFAULT NULL COMMENT '选表所属catalog',
    `database_name` varchar(255) DEFAULT NULL COMMENT '选表所属database或schema',
    `selected_tables` text NOT NULL COMMENT '服务端确认后的选表JSON数组',
    `chat_model_id` int NOT NULL COMMENT '用于生成草稿的s2_chat_model.id',
    `llm_conversation_id` bigint DEFAULT NULL COMMENT '阶段1本地LLM会话ID',
    `include_sample` tinyint(1) NOT NULL DEFAULT 0 COMMENT '是否使用服务端脱敏样例数据',
    `idempotency_key` varchar(128) NOT NULL COMMENT '创建请求幂等键',
    `status` varchar(32) NOT NULL COMMENT '生成状态：GENERATING、DRAFT或GENERATION_FAILED',
    `current_version_no` int NOT NULL DEFAULT 0 COMMENT '当前不可变快照版本号；生成完成前为0',
    `lock_version` int NOT NULL DEFAULT 0 COMMENT '编辑保存使用的乐观锁版本',
    `generation_started_at` datetime DEFAULT NULL COMMENT 'Worker认领后的生成开始时间；入队前为空',
    `generation_finished_at` datetime DEFAULT NULL COMMENT '生成成功或失败结束时间',
    `draft_json` mediumtext DEFAULT NULL COMMENT '当前通过Schema与业务规则校验的结构化草稿JSON',
    `raw_output` mediumtext DEFAULT NULL COMMENT '首次LLM原始输出，仅后端诊断使用',
    `repaired_output` mediumtext DEFAULT NULL COMMENT '一次修复后的LLM输出，仅后端诊断使用',
    `error_code` varchar(64) DEFAULT NULL COMMENT '脱敏后的统一错误码',
    `error_message` varchar(1500) DEFAULT NULL COMMENT '脱敏后的错误摘要',
    `created_by` varchar(100) NOT NULL COMMENT '创建人',
    `created_at` datetime NOT NULL COMMENT '创建时间',
    `updated_by` varchar(100) NOT NULL COMMENT '最近更新人',
    `updated_at` datetime NOT NULL COMMENT '最近更新时间',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_semantic_draft_idempotency` (`created_by`, `idempotency_key`),
    KEY `idx_semantic_draft_source` (`source_type`, `source_id`, `status`),
    KEY `idx_semantic_draft_list` (`data_source_id`, `id`),
    KEY `idx_semantic_draft_generation` (`status`, `generation_started_at`)
    ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='AI语义建模结构化草稿主表';

CREATE TABLE IF NOT EXISTS `s2_semantic_modeling_draft_version` (
    `id` bigint(20) NOT NULL AUTO_INCREMENT COMMENT '草稿版本快照ID',
    `draft_id` bigint NOT NULL COMMENT '所属草稿ID',
    `version_no` int NOT NULL COMMENT '草稿内单调递增版本号',
    `draft_json` mediumtext NOT NULL COMMENT '该版本的不可变结构化草稿JSON快照',
    `change_source` varchar(32) NOT NULL COMMENT '变更来源：AI_GENERATED或MANUAL_SAVE',
    `change_summary` varchar(1000) DEFAULT NULL COMMENT '版本变更摘要',
    `llm_conversation_id` bigint DEFAULT NULL COMMENT '生成该版本时关联的阶段1本地LLM会话ID',
    `created_by` varchar(100) NOT NULL COMMENT '版本创建人',
    `created_at` datetime NOT NULL COMMENT '版本创建时间',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_semantic_draft_version` (`draft_id`, `version_no`)
    ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='AI语义建模草稿不可变版本快照表';
