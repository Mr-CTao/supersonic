CREATE TABLE IF NOT EXISTS `s2_agent` (
                                          `id` int(11) NOT NULL AUTO_INCREMENT,
    `name` varchar(100) COLLATE utf8mb4_unicode_ci DEFAULT NULL,
    `description` TEXT COLLATE utf8mb4_unicode_ci DEFAULT NULL,
    `examples` TEXT COLLATE utf8mb4_unicode_ci DEFAULT NULL,
    `status` tinyint DEFAULT NULL,
    `model` varchar(100) COLLATE utf8mb4_unicode_ci DEFAULT NULL,
    `tool_config` TEXT COLLATE utf8mb4_unicode_ci DEFAULT NULL,
    `llm_config` TEXT COLLATE utf8mb4_unicode_ci DEFAULT NULL,
    `chat_model_config` text COLLATE utf8mb4_unicode_ci DEFAULT NULL,
    `visual_config` TEXT  COLLATE utf8mb4_unicode_ci DEFAULT NULL,
    `enable_search` tinyint DEFAULT 1,
    `enable_feedback` tinyint DEFAULT 1,
    `created_by` varchar(100) COLLATE utf8mb4_unicode_ci DEFAULT NULL,
    `created_at` datetime DEFAULT NULL,
    `updated_by` varchar(100) COLLATE utf8mb4_unicode_ci DEFAULT NULL,
    `updated_at` datetime DEFAULT NULL,
    `admin` varchar(1000) DEFAULT NULL COMMENT '管理员',
    `admin_org` varchar(1000) DEFAULT NULL COMMENT '管理员组织',
    `is_open` tinyint DEFAULT NULL COMMENT '是否公开',
    `viewer` varchar(1000) DEFAULT NULL COMMENT '可用用户',
    `view_org` varchar(1000) DEFAULT NULL COMMENT '可用组织',
    PRIMARY KEY (`id`)
    ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS `s2_auth_groups` (
                                                `group_id` int(11) NOT NULL,
    `config` varchar(2048) COLLATE utf8mb4_unicode_ci DEFAULT NULL,
    PRIMARY KEY (`group_id`)
    ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;


CREATE TABLE IF NOT EXISTS `s2_available_date_info` (
                                                        `id` int(11) NOT NULL AUTO_INCREMENT,
    `item_id` int(11) NOT NULL,
    `type` varchar(255) COLLATE utf8mb4_unicode_ci NOT NULL,
    `date_format` varchar(64) COLLATE utf8mb4_unicode_ci NOT NULL,
    `date_period` varchar(64) COLLATE utf8mb4_unicode_ci DEFAULT NULL,
    `start_date` varchar(64) COLLATE utf8mb4_unicode_ci DEFAULT NULL,
    `end_date` varchar(64) COLLATE utf8mb4_unicode_ci DEFAULT NULL,
    `unavailable_date` text COLLATE utf8mb4_unicode_ci,
    `created_at` timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    `created_by` varchar(100) COLLATE utf8mb4_unicode_ci NOT NULL,
    `updated_at` timestamp NULL,
    `updated_by` varchar(100) COLLATE utf8mb4_unicode_ci NOT NULL,
    `status` tinyint DEFAULT 0,
    UNIQUE(`item_id`, `type`),
    PRIMARY KEY (`id`)
    ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;


CREATE TABLE IF NOT EXISTS `s2_chat` (
                                         `chat_id` bigint(8) NOT NULL AUTO_INCREMENT,
    `agent_id` int(11) DEFAULT NULL,
    `chat_name` varchar(300) DEFAULT NULL,
    `create_time` datetime DEFAULT NULL,
    `last_time` datetime DEFAULT NULL,
    `creator` varchar(30) DEFAULT NULL,
    `last_question` varchar(200) DEFAULT NULL,
    `is_delete` tinyint DEFAULT '0' COMMENT 'is deleted',
    `is_top` tinyint DEFAULT '0' COMMENT 'is top',
    PRIMARY KEY (`chat_id`)
    ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS `s2_chat_config` (
                                                `id` bigint(20) unsigned NOT NULL AUTO_INCREMENT,
    `model_id` bigint(20) DEFAULT NULL,
    `chat_detail_config` mediumtext COMMENT '明细模式配置信息',
    `chat_agg_config` mediumtext COMMENT '指标模式配置信息',
    `recommended_questions` mediumtext COMMENT '推荐问题配置',
    `created_at` datetime NOT NULL COMMENT '创建时间',
    `updated_at` datetime NOT NULL COMMENT '更新时间',
    `created_by` varchar(100) NOT NULL COMMENT '创建人',
    `updated_by` varchar(100) NOT NULL COMMENT '更新人',
    `status` tinyint NOT NULL COMMENT '主题域扩展信息状态, 0-删除，1-生效',
    `llm_examples` text COMMENT 'llm examples',
    PRIMARY KEY (`id`)
    ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='主题域扩展信息表';

CREATE TABLE IF NOT EXISTS `s2_chat_memory` (
                                                `id` INT NOT NULL AUTO_INCREMENT,
                                                `question` varchar(655)   COMMENT '用户问题' ,
    `side_info` TEXT COMMENT '辅助信息' ,
    `query_id`  BIGINT    COMMENT '问答ID' ,
    `agent_id`  INT    COMMENT '助理ID' ,
    `db_schema`  TEXT    COMMENT 'Schema映射' ,
    `s2_sql` TEXT   COMMENT '大模型解析SQL' ,
    `status` varchar(10)   COMMENT '状态' ,
    `llm_review` varchar(10)    COMMENT '大模型评估结果' ,
    `llm_comment`   TEXT COMMENT '大模型评估意见' ,
    `human_review` varchar(10) COMMENT '管理员评估结果',
    `human_comment` TEXT    COMMENT '管理员评估意见',
    `created_at` TIMESTAMP DEFAULT CURRENT_TIMESTAMP  ,
    `updated_at` TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP ,
    `created_by` varchar(100) DEFAULT NULL   ,
    `updated_by` varchar(100) DEFAULT NULL   ,
    PRIMARY KEY (`id`)
    ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS `s2_chat_context` (
                                                 `chat_id` bigint(20) NOT NULL COMMENT 'context chat id',
    `modified_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT 'row modify time',
    `query_user` varchar(64) DEFAULT NULL COMMENT 'row modify user',
    `query_text` text COMMENT 'query text',
    `semantic_parse` text COMMENT 'parse data',
    `ext_data` text COMMENT 'extend data',
    PRIMARY KEY (`chat_id`)
    ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS `s2_chat_parse` (
                                               `question_id` bigint NOT NULL,
                                               `chat_id` int(11) NOT NULL,
    `parse_id` int(11) NOT NULL,
    `create_time` timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    `query_text` varchar(500) DEFAULT NULL,
    `user_name` varchar(150) DEFAULT NULL,
    `parse_info` mediumtext NOT NULL,
    `is_candidate` int(11) DEFAULT '1' COMMENT '1是candidate,0是selected',
    KEY `commonIndex` (`question_id`)
    ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;


CREATE TABLE IF NOT EXISTS `s2_chat_query`
(
    `question_id`     bigint(20) NOT NULL AUTO_INCREMENT,
    `agent_id`        int(11)             DEFAULT NULL,
    `create_time`     timestamp  NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    `query_text`      mediumtext,
    `user_name`       varchar(150)        DEFAULT NULL,
    `query_state`     int(1)              DEFAULT NULL,
    `chat_id`         bigint(20) NOT NULL,
    `query_result`    mediumtext,
    `score`           int(11)             DEFAULT '0',
    `feedback`        varchar(1024)       DEFAULT '',
    `similar_queries` varchar(1024)       DEFAULT '',
    `parse_time_cost` varchar(1024)       DEFAULT '',
    PRIMARY KEY (`question_id`)
    ) ENGINE = InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS `s2_semantic_gap` (
    `id` bigint(20) NOT NULL AUTO_INCREMENT COMMENT '语义缺口ID',
    `question` text NOT NULL COMMENT '原始问题，已做基础脱敏',
    `normalized_question` varchar(500) NOT NULL COMMENT '归一化问题，用于轻量聚合',
    `assistant_id` int DEFAULT NULL COMMENT '关联助理ID',
    `user_id` bigint DEFAULT NULL COMMENT '提问用户ID',
    `domain_id` bigint DEFAULT NULL COMMENT '可能所属主题域ID',
    `data_source_id` bigint DEFAULT NULL COMMENT '可能关联数据源ID',
    `failure_type` varchar(64) NOT NULL COMMENT '失败类型',
    `failure_reason` varchar(1500) DEFAULT NULL COMMENT '失败原因摘要',
    `matched_model_ids` varchar(1000) DEFAULT NULL COMMENT '命中模型ID列表',
    `matched_metric_ids` varchar(1000) DEFAULT NULL COMMENT '命中指标ID列表',
    `matched_dimension_ids` varchar(1000) DEFAULT NULL COMMENT '命中维度ID列表',
    `generated_sql` mediumtext DEFAULT NULL COMMENT '失败时生成SQL，已脱敏截断',
    `s2sql` mediumtext DEFAULT NULL COMMENT '失败时S2SQL，已脱敏截断',
    `feedback` varchar(1500) DEFAULT NULL COMMENT '用户反馈摘要',
    `occurrence_count` int NOT NULL DEFAULT 1 COMMENT '出现次数',
    `negative_feedback_count` int NOT NULL DEFAULT 0 COMMENT '负反馈次数',
    `priority_score` int NOT NULL DEFAULT 0 COMMENT '优先级分数',
    `status` varchar(64) NOT NULL COMMENT '处理状态',
    `created_at` datetime NOT NULL COMMENT '首次出现时间',
    `last_seen_at` datetime NOT NULL COMMENT '最近出现时间',
    `created_by` varchar(100) DEFAULT NULL COMMENT '创建人或系统标识',
    `updated_at` datetime NOT NULL COMMENT '更新时间',
    `updated_by` varchar(100) DEFAULT NULL COMMENT '更新人',
    `ignore_reason` varchar(1500) DEFAULT NULL COMMENT '忽略原因',
    `source_query_id` bigint DEFAULT NULL COMMENT '最近来源问答ID',
    `source_chat_id` bigint DEFAULT NULL COMMENT '最近来源会话ID',
    `recent_questions` text DEFAULT NULL COMMENT '最近相似问法',
    PRIMARY KEY (`id`),
    KEY `idx_semantic_gap_pool` (`assistant_id`, `domain_id`, `failure_type`, `status`),
    KEY `idx_semantic_gap_priority` (`priority_score`, `last_seen_at`),
    KEY `idx_semantic_gap_normalized` (`normalized_question`(191))
    ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='AI语义建模语义缺口池';


CREATE TABLE IF NOT EXISTS `s2_chat_statistics` (
                                                    `question_id` bigint(20) NOT NULL,
    `chat_id` bigint(20) NOT NULL,
    `user_name` varchar(150) DEFAULT NULL,
    `query_text` varchar(200) DEFAULT NULL,
    `interface_name` varchar(100) DEFAULT NULL,
    `cost` int(6) DEFAULT '0',
    `type` int(11) DEFAULT NULL,
    `create_time` timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    KEY `commonIndex` (`question_id`)
    ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS `s2_chat_model` (
                                               `id` bigint(20) NOT NULL AUTO_INCREMENT,
    `name` varchar(255) NOT NULL COMMENT '名称',
    `description` varchar(500) DEFAULT NULL COMMENT '描述',
    `config` text NOT NULL COMMENT '配置信息',
    `created_at` datetime NOT NULL COMMENT '创建时间',
    `created_by` varchar(100) NOT NULL COMMENT '创建人',
    `updated_at` datetime NOT NULL COMMENT '更新时间',
    `updated_by` varchar(100) NOT NULL COMMENT '更新人',
    `admin` varchar(500) DEFAULT NULL,
    `viewer` varchar(500) DEFAULT NULL,
    `is_open` tinyint DEFAULT NULL COMMENT '是否公开',
    PRIMARY KEY (`id`)
    ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='对话大模型实例表';

CREATE TABLE IF NOT EXISTS `s2_llm_model_capability` (
    `id` bigint(20) NOT NULL AUTO_INCREMENT,
    `chat_model_id` bigint(20) NOT NULL COMMENT '复用 s2_chat_model.id',
    `provider_type` varchar(64) NOT NULL COMMENT '供应商类型',
    `model_name` varchar(255) NOT NULL COMMENT '模型名称',
    `max_context_tokens` int DEFAULT NULL COMMENT '最大上下文 token',
    `support_stream` tinyint DEFAULT 0 COMMENT '是否支持流式输出',
    `support_json_mode` tinyint DEFAULT 0 COMMENT '是否支持 JSON Output',
    `support_tool_calling` tinyint DEFAULT 0 COMMENT '是否支持 Tool Calls',
    `support_thinking` tinyint DEFAULT 0 COMMENT '是否支持思考模式',
    `support_chat_prefix_completion` tinyint DEFAULT 0 COMMENT '是否支持对话前缀续写 Beta',
    `support_fim_completion` tinyint DEFAULT 0 COMMENT '是否支持 FIM /completions Beta',
    `support_context_cache` tinyint DEFAULT 0 COMMENT '是否支持上下文硬盘缓存',
    `support_system_prompt` tinyint DEFAULT 1 COMMENT '是否支持 system prompt',
    `recommended_temperature` double DEFAULT NULL COMMENT '推荐温度',
    `usage_scene` varchar(255) DEFAULT NULL COMMENT '适用场景',
    `enabled` tinyint DEFAULT 1 COMMENT '是否启用',
    `created_at` datetime NOT NULL COMMENT '创建时间',
    `updated_at` datetime NOT NULL COMMENT '更新时间',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_llm_capability_model` (`chat_model_id`, `model_name`)
    ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='LLM 模型能力表';

CREATE TABLE IF NOT EXISTS `s2_llm_conversation` (
    `id` bigint(20) NOT NULL AUTO_INCREMENT,
    `conversation_type` varchar(64) NOT NULL COMMENT '会话类型',
    `chat_model_id` bigint(20) NOT NULL COMMENT '复用 s2_chat_model.id',
    `provider_type` varchar(64) NOT NULL COMMENT '供应商类型',
    `model_name` varchar(255) NOT NULL COMMENT '模型名称',
    `business_id` varchar(128) DEFAULT NULL COMMENT '业务对象 ID',
    `status` varchar(32) NOT NULL COMMENT '会话状态',
    `summary` text DEFAULT NULL COMMENT '上下文摘要预留',
    `created_by` varchar(100) DEFAULT NULL COMMENT '创建人',
    `created_at` datetime NOT NULL COMMENT '创建时间',
    `updated_at` datetime NOT NULL COMMENT '更新时间',
    PRIMARY KEY (`id`),
    KEY `idx_llm_conversation_business` (`conversation_type`, `business_id`)
    ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='LLM 本地会话表';

CREATE TABLE IF NOT EXISTS `s2_llm_message` (
    `id` bigint(20) NOT NULL AUTO_INCREMENT,
    `conversation_id` bigint(20) NOT NULL COMMENT '会话 ID',
    `role` varchar(32) NOT NULL COMMENT 'system/user/assistant/tool',
    `content` mediumtext DEFAULT NULL COMMENT '消息内容',
    `reasoning_content` mediumtext DEFAULT NULL COMMENT 'DeepSeek 思考内容',
    `content_type` varchar(32) DEFAULT NULL COMMENT 'text/json/tool_result',
    `tool_calls` mediumtext DEFAULT NULL COMMENT 'Tool Calls 原始 JSON',
    `tool_call_id` varchar(128) DEFAULT NULL COMMENT 'tool 消息关联 ID',
    `token_count` int DEFAULT NULL COMMENT '估算或实际 token 数',
    `message_order` int NOT NULL COMMENT '会话内顺序',
    `created_at` datetime NOT NULL COMMENT '创建时间',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_llm_message_order` (`conversation_id`, `message_order`),
    KEY `idx_llm_message_conversation` (`conversation_id`)
    ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='LLM 会话消息表';

CREATE TABLE IF NOT EXISTS `s2_llm_invocation_log` (
    `id` bigint(20) NOT NULL AUTO_INCREMENT,
    `conversation_id` bigint(20) NOT NULL COMMENT '会话 ID',
    `chat_model_id` bigint(20) NOT NULL COMMENT '复用 s2_chat_model.id',
    `provider_type` varchar(64) NOT NULL COMMENT '供应商类型',
    `model_name` varchar(255) NOT NULL COMMENT '模型名称',
    `request_id` varchar(128) DEFAULT NULL COMMENT '厂商请求 ID',
    `prompt_tokens` int DEFAULT NULL COMMENT '输入 token',
    `completion_tokens` int DEFAULT NULL COMMENT '输出 token',
    `total_tokens` int DEFAULT NULL COMMENT '总 token',
    `latency_ms` bigint DEFAULT NULL COMMENT '调用耗时',
    `status` varchar(32) NOT NULL COMMENT '调用状态',
    `error_code` varchar(64) DEFAULT NULL COMMENT '统一错误码',
    `error_message` varchar(1000) DEFAULT NULL COMMENT '脱敏错误摘要',
    `raw_response_ref` varchar(1200) DEFAULT NULL COMMENT '脱敏响应摘要',
    `created_at` datetime NOT NULL COMMENT '创建时间',
    PRIMARY KEY (`id`),
    KEY `idx_llm_invocation_conversation` (`conversation_id`)
    ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='LLM 调用日志表';

-- AI semantic modeling phase 3: isolated structured drafts and immutable version snapshots.
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
    `status` varchar(32) NOT NULL COMMENT '草稿状态：GENERATING、DRAFT、GENERATION_FAILED或PENDING_APPROVAL',
    `current_version_no` int NOT NULL DEFAULT 0 COMMENT '当前不可变快照版本号；生成完成前为0',
    `current_attempt_no` int NOT NULL DEFAULT 1 COMMENT '当前生成尝试序号；首次生成固定为1',
    `lock_version` int NOT NULL DEFAULT 0 COMMENT '编辑保存使用的乐观锁版本',
    `generation_started_at` datetime DEFAULT NULL COMMENT 'Worker认领后的生成开始时间；入队前为空',
    `generation_finished_at` datetime DEFAULT NULL COMMENT '生成成功或失败结束时间',
    `draft_json` mediumtext DEFAULT NULL COMMENT '当前通过Schema与业务规则校验的结构化草稿JSON',
    `raw_output` mediumtext DEFAULT NULL COMMENT '首次LLM原始输出，仅后端诊断使用',
    `repaired_output` mediumtext DEFAULT NULL COMMENT '一次修复后的LLM输出，仅后端诊断使用',
    `error_code` varchar(64) DEFAULT NULL COMMENT '脱敏后的统一错误码',
    `error_message` varchar(1500) DEFAULT NULL COMMENT '脱敏后的错误摘要',
    `submitted_validation_report_id` bigint DEFAULT NULL COMMENT '阶段4提交待审批时绑定的验证报告ID',
    `submission_idempotency_key` varchar(128) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin DEFAULT NULL
        COMMENT '提交待审批请求幂等键（大小写敏感）',
    `submitted_by` varchar(100) DEFAULT NULL COMMENT '提交待审批人',
    `submitted_at` datetime DEFAULT NULL COMMENT '提交待审批时间',
    `approved_by` varchar(100) DEFAULT NULL COMMENT '审批通过人',
    `approved_at` datetime DEFAULT NULL COMMENT '审批通过时间',
    `approval_reason` varchar(1000) DEFAULT NULL COMMENT '审批备注或拒绝原因',
    `rejected_by` varchar(100) DEFAULT NULL COMMENT '审批拒绝人',
    `rejected_at` datetime DEFAULT NULL COMMENT '审批拒绝时间',
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

CREATE TABLE IF NOT EXISTS `s2_semantic_modeling_draft_attempt` (
    `id` bigint(20) NOT NULL AUTO_INCREMENT COMMENT '生成尝试ID',
    `draft_id` bigint NOT NULL COMMENT '所属逻辑草稿ID',
    `attempt_no` int NOT NULL COMMENT '草稿内从1递增的尝试序号',
    `trigger_type` varchar(32) NOT NULL COMMENT 'INITIAL或MANUAL_REGENERATION',
    `status` varchar(32) NOT NULL COMMENT 'QUEUED、GENERATING、SUCCEEDED或FAILED',
    `chat_model_id` int NOT NULL COMMENT '本次尝试使用的s2_chat_model.id',
    `include_sample` tinyint(1) NOT NULL DEFAULT 0 COMMENT '本次尝试是否使用服务端脱敏样例',
    `idempotency_key` varchar(128) NOT NULL COMMENT '本次生成操作幂等键',
    `request_fingerprint` varchar(128) DEFAULT NULL COMMENT '用于校验幂等重放请求内容的一致性',
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
    ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='AI语义建模草稿不可变生成尝试历史';

CREATE TABLE IF NOT EXISTS `s2_semantic_modeling_draft_version` (
    `id` bigint(20) NOT NULL AUTO_INCREMENT COMMENT '草稿版本快照ID',
    `draft_id` bigint NOT NULL COMMENT '所属草稿ID',
    `version_no` int NOT NULL COMMENT '草稿内单调递增版本号',
    `draft_json` mediumtext NOT NULL COMMENT '该版本的不可变结构化草稿JSON快照',
    `change_source` varchar(32) NOT NULL COMMENT '变更来源：AI_GENERATED、MANUAL_SAVE或AI_REVISED',
    `change_summary` varchar(1000) DEFAULT NULL COMMENT '版本变更摘要',
    `llm_conversation_id` bigint DEFAULT NULL COMMENT '生成该版本时关联的阶段1本地LLM会话ID',
    `request_idempotency_key` varchar(128) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin DEFAULT NULL
        COMMENT '创建该版本的请求幂等键（大小写敏感）',
    `request_fingerprint` varchar(128) DEFAULT NULL COMMENT '幂等请求规范化参数指纹',
    `result_lock_version` int DEFAULT NULL COMMENT 'RESTORED首次成功响应的草稿锁版本',
    `created_by` varchar(100) NOT NULL COMMENT '版本创建人',
    `created_at` datetime NOT NULL COMMENT '版本创建时间',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_semantic_draft_version` (`draft_id`, `version_no`),
    UNIQUE KEY `uk_semantic_draft_version_request` (`draft_id`, `request_idempotency_key`)
    ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='AI语义建模草稿不可变版本快照表';

-- Phase 4 persists Provider-call ownership before any external AI revision request is sent.
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

-- AI semantic modeling phase 4: isolated validation reports only; never writes formal semantic assets.
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
    ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='AI语义建模隔离草稿验证报告，不写正式语义资产';

-- AI semantic modeling phase 5: auditable publication through existing semantic services.
CREATE TABLE IF NOT EXISTS `s2_semantic_release` (
    `id` bigint(20) NOT NULL AUTO_INCREMENT COMMENT 'AI语义发布ID',
    `release_no` varchar(64) NOT NULL COMMENT '发布版本号',
    `draft_id` bigint NOT NULL COMMENT '关联草稿ID',
    `draft_version_id` bigint NOT NULL COMMENT '发布的不可变草稿版本ID',
    `draft_version_no` int NOT NULL COMMENT '发布的草稿版本号',
    `validation_report_id` bigint NOT NULL COMMENT '发布绑定的通过验证报告ID',
    `release_status` varchar(32) NOT NULL COMMENT 'IN_PROGRESS、SUCCEEDED、FAILED或回滚状态',
    `released_objects` mediumtext NOT NULL COMMENT '已创建对象安全摘要JSON',
    `dict_reload_status` varchar(32) NOT NULL COMMENT 'dict独立刷新状态',
    `embedding_reload_status` varchar(32) NOT NULL COMMENT 'embedding独立刷新状态',
    `approved_by` varchar(100) NOT NULL COMMENT '审批人',
    `released_by` varchar(100) NOT NULL COMMENT '发布人',
    `released_at` datetime DEFAULT NULL COMMENT '完整发布时间',
    `rollback_from_release_id` bigint DEFAULT NULL COMMENT '预留回滚来源发布ID',
    `rollback_reason` varchar(1000) DEFAULT NULL COMMENT '回滚原因',
    `rolled_back_by` varchar(100) DEFAULT NULL COMMENT '回滚人',
    `rolled_back_at` datetime DEFAULT NULL COMMENT '完整回滚时间',
    `error_message` varchar(1000) DEFAULT NULL COMMENT '脱敏失败摘要',
    `idempotency_key` varchar(128) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL
        COMMENT '发布请求幂等键（大小写敏感）',
    `created_at` datetime NOT NULL COMMENT '创建时间',
    `updated_at` datetime NOT NULL COMMENT '更新时间',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_semantic_release_no` (`release_no`),
    UNIQUE KEY `uk_semantic_release_draft` (`draft_id`),
    KEY `idx_semantic_release_status` (`release_status`, `id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='AI语义建模发布与知识刷新审计';

CREATE TABLE IF NOT EXISTS `s2_semantic_release_step` (
    `id` bigint(20) NOT NULL AUTO_INCREMENT COMMENT '发布步骤ID',
    `release_id` bigint NOT NULL COMMENT '发布ID',
    `step_key` varchar(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL COMMENT '发布内稳定步骤键',
    `step_type` varchar(64) NOT NULL COMMENT '创建、刷新或回滚步骤类型',
    `target_type` varchar(32) NOT NULL COMMENT 'MODEL、DIMENSION、METRIC、TERM或KNOWLEDGE',
    `target_key` varchar(255) DEFAULT NULL COMMENT '草稿对象key',
    `target_name` varchar(255) DEFAULT NULL COMMENT '管理员可见名称',
    `target_id` bigint DEFAULT NULL COMMENT '正式对象ID',
    `status` varchar(32) NOT NULL COMMENT 'IN_PROGRESS、SUCCEEDED、FAILED或SKIPPED',
    `attempt_count` int NOT NULL DEFAULT 1 COMMENT '步骤尝试次数',
    `error_message` varchar(1000) DEFAULT NULL COMMENT '脱敏失败摘要',
    `started_at` datetime DEFAULT NULL COMMENT '最近尝试开始时间',
    `finished_at` datetime DEFAULT NULL COMMENT '最近尝试完成时间',
    `created_at` datetime NOT NULL COMMENT '创建时间',
    `updated_at` datetime NOT NULL COMMENT '更新时间',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_semantic_release_step` (`release_id`, `step_key`),
    KEY `idx_semantic_release_step_list` (`release_id`, `id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='AI语义发布逐步骤结果';

CREATE TABLE IF NOT EXISTS `s2_database` (
                                             `id` bigint(20) NOT NULL AUTO_INCREMENT,
    `name` varchar(255) NOT NULL COMMENT '名称',
    `description` varchar(500) DEFAULT NULL COMMENT '描述',
    `version` varchar(64) DEFAULT NULL,
    `type` varchar(20) NOT NULL COMMENT '类型 mysql,clickhouse,tdw',
    `config` text NOT NULL COMMENT '配置信息',
    `created_at` datetime NOT NULL COMMENT '创建时间',
    `created_by` varchar(100) NOT NULL COMMENT '创建人',
    `updated_at` datetime NOT NULL COMMENT '更新时间',
    `updated_by` varchar(100) NOT NULL COMMENT '更新人',
    `admin` varchar(500) DEFAULT NULL,
    `viewer` varchar(500) DEFAULT NULL,
    `is_open` tinyint DEFAULT NULL COMMENT '是否公开',
    PRIMARY KEY (`id`)
    ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='数据库实例表';

CREATE TABLE IF NOT EXISTS `s2_dictionary_conf` (
                                                    `id` bigint(20) unsigned NOT NULL AUTO_INCREMENT,
    `description` varchar(255) ,
    `type` varchar(255)  NOT NULL ,
    `item_id` INT  NOT NULL ,
    `config` mediumtext  ,
    `status` varchar(255) NOT NULL ,
    `created_at` datetime NOT NULL COMMENT '创建时间' ,
    `created_by` varchar(100) NOT NULL ,
    PRIMARY KEY (`id`)
    ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='字典配置信息表';


CREATE TABLE IF NOT EXISTS `s2_dictionary_task` (
                                                    `id` bigint(20) unsigned NOT NULL AUTO_INCREMENT,
    `name` varchar(255) NOT NULL ,
    `description` varchar(255) ,
    `type` varchar(255)  NOT NULL ,
    `item_id` INT  NOT NULL ,
    `config` mediumtext  ,
    `status` varchar(255) NOT NULL ,
    `created_at` datetime DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `created_by` varchar(100) NOT NULL ,
    `elapsed_ms` int(10) DEFAULT NULL ,
    PRIMARY KEY (`id`)
    ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='字典运行任务表';


CREATE TABLE IF NOT EXISTS `s2_dimension` (
                                              `id` bigint(20) NOT NULL AUTO_INCREMENT COMMENT '维度ID',
    `model_id` bigint(20) DEFAULT NULL,
    `name` varchar(255) NOT NULL COMMENT '维度名称',
    `biz_name` varchar(255) NOT NULL COMMENT '字段名称',
    `description` varchar(500) NOT NULL COMMENT '描述',
    `status` tinyint NOT NULL COMMENT '维度状态,0正常,1下架',
    `sensitive_level` int(10) DEFAULT NULL COMMENT '敏感级别',
    `type` varchar(50) NOT NULL COMMENT '维度类型 categorical,time',
    `type_params` text COMMENT '类型参数',
    `data_type` varchar(50)  DEFAULT null comment '维度数据类型 varchar、array',
    `expr` text NOT NULL COMMENT '表达式',
    `created_at` datetime NOT NULL COMMENT '创建时间',
    `created_by` varchar(100) NOT NULL COMMENT '创建人',
    `updated_at` datetime NOT NULL COMMENT '更新时间',
    `updated_by` varchar(100) NOT NULL COMMENT '更新人',
    `semantic_type` varchar(20) NOT NULL COMMENT '语义类型DATE, ID, CATEGORY',
    `alias` varchar(500) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci DEFAULT NULL,
    `default_values` varchar(500) DEFAULT NULL,
    `dim_value_maps` varchar(5000) DEFAULT NULL,
    `is_tag` tinyint DEFAULT NULL,
    `ext` varchar(1000) DEFAULT NULL,
    PRIMARY KEY (`id`)
    ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='维度表';

CREATE TABLE IF NOT EXISTS `s2_domain` (
                                           `id` bigint(20) NOT NULL AUTO_INCREMENT COMMENT '自增ID',
    `name` varchar(255) DEFAULT NULL COMMENT '主题域名称',
    `biz_name` varchar(255) DEFAULT NULL COMMENT '内部名称',
    `parent_id` bigint(20) DEFAULT '0' COMMENT '父主题域ID',
    `status` tinyint NOT NULL COMMENT '主题域状态',
    `created_at` datetime DEFAULT NULL COMMENT '创建时间',
    `created_by` varchar(100) DEFAULT NULL COMMENT '创建人',
    `updated_at` datetime DEFAULT NULL COMMENT '更新时间',
    `updated_by` varchar(100) DEFAULT NULL COMMENT '更新人',
    `admin` varchar(3000) DEFAULT NULL COMMENT '主题域管理员',
    `admin_org` varchar(3000) DEFAULT NULL COMMENT '主题域管理员组织',
    `is_open` tinyint DEFAULT NULL COMMENT '主题域是否公开',
    `viewer` varchar(3000) DEFAULT NULL COMMENT '主题域可用用户',
    `view_org` varchar(3000) DEFAULT NULL COMMENT '主题域可用组织',
    `entity` varchar(500) DEFAULT NULL COMMENT '主题域实体信息',
    PRIMARY KEY (`id`)
    ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='主题域基础信息表';


CREATE TABLE IF NOT EXISTS `s2_metric`
(
    `id`                bigint(20)   NOT NULL AUTO_INCREMENT,
    `model_id`          bigint(20)   DEFAULT NULL,
    `name`              varchar(255) NOT NULL COMMENT '指标名称',
    `biz_name`          varchar(255) NOT NULL COMMENT '字段名称',
    `description`       varchar(500) DEFAULT NULL COMMENT '描述',
    `status`            tinyint      NOT NULL COMMENT '指标状态',
    `sensitive_level`   tinyint      NOT NULL COMMENT '敏感级别',
    `type`              varchar(50)  NOT NULL COMMENT '指标类型',
    `type_params`       text         NOT NULL COMMENT '类型参数',
    `created_at`        datetime     NOT NULL COMMENT '创建时间',
    `created_by`        varchar(100) NOT NULL COMMENT '创建人',
    `updated_at`        datetime     NOT NULL COMMENT '更新时间',
    `updated_by`        varchar(100) NOT NULL COMMENT '更新人',
    `data_format_type`  varchar(50)  DEFAULT NULL COMMENT '数值类型',
    `data_format`       varchar(500) DEFAULT NULL COMMENT '数值类型参数',
    `alias`             varchar(500) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci DEFAULT NULL,
    `classifications`   varchar(500) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci DEFAULT NULL,
    `relate_dimensions` varchar(500) DEFAULT NULL COMMENT '指标相关维度',
    `ext`               text DEFAULT NULL,
    `define_type` varchar(50)  DEFAULT NULL, -- MEASURE, FIELD, METRIC
    `is_publish` tinyint DEFAULT NULL COMMENT '是否发布',
    PRIMARY KEY (`id`)
    ) ENGINE = InnoDB
    DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT ='指标表';


CREATE TABLE IF NOT EXISTS `s2_model` (
                                          `id` bigint(20) NOT NULL AUTO_INCREMENT,
    `name` varchar(100) COLLATE utf8mb4_unicode_ci DEFAULT NULL,
    `biz_name` varchar(100) COLLATE utf8mb4_unicode_ci DEFAULT NULL,
    `domain_id` bigint(20) DEFAULT NULL,
    `alias` varchar(200) COLLATE utf8mb4_unicode_ci DEFAULT NULL,
    `status` tinyint DEFAULT NULL,
    `description` varchar(500) DEFAULT NULL,
    `viewer` varchar(500) COLLATE utf8mb4_unicode_ci DEFAULT NULL,
    `view_org` varchar(500) COLLATE utf8mb4_unicode_ci DEFAULT NULL,
    `admin` varchar(500) COLLATE utf8mb4_unicode_ci DEFAULT NULL,
    `admin_org` varchar(500) COLLATE utf8mb4_unicode_ci DEFAULT NULL,
    `is_open` tinyint DEFAULT NULL,
    `created_by` varchar(100) COLLATE utf8mb4_unicode_ci DEFAULT NULL,
    `created_at` datetime DEFAULT NULL,
    `updated_by` varchar(100) COLLATE utf8mb4_unicode_ci DEFAULT NULL,
    `updated_at` datetime DEFAULT NULL,
    `entity` text COLLATE utf8mb4_unicode_ci,
    `drill_down_dimensions` TEXT DEFAULT NULL,
    `database_id` INT NOT  NULL ,
    `model_detail` text NOT  NULL ,
    `source_type` varchar(128) DEFAULT NULL ,
    `depends` varchar(500) DEFAULT NULL ,
    `filter_sql` varchar(1000) DEFAULT NULL ,
    `tag_object_id` int(11) DEFAULT '0',
    `ext` varchar(1000) DEFAULT NULL,
    PRIMARY KEY (`id`)
    ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS `s2_plugin` (
                                           `id` bigint(20) NOT NULL AUTO_INCREMENT,
    `type` varchar(50) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT 'DASHBOARD,WIDGET,URL',
    `data_set` varchar(100) COLLATE utf8mb4_unicode_ci DEFAULT NULL,
    `pattern` varchar(500) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci DEFAULT NULL,
    `parse_mode` varchar(100) COLLATE utf8mb4_unicode_ci DEFAULT NULL,
    `parse_mode_config` text COLLATE utf8mb4_unicode_ci,
    `name` varchar(100) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci DEFAULT NULL,
    `created_at` datetime DEFAULT NULL,
    `created_by` varchar(100) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci DEFAULT NULL,
    `updated_at` datetime DEFAULT NULL,
    `updated_by` varchar(100) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci DEFAULT NULL,
    `config` text CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci,
    `comment` text COLLATE utf8mb4_unicode_ci,
    PRIMARY KEY (`id`)
    ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS `s2_query_stat_info` (
                                                    `id` bigint(20) unsigned NOT NULL AUTO_INCREMENT,
    `trace_id` varchar(200) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '查询标识',
    `model_id` bigint(20) DEFAULT NULL,
    `data_set_id` bigint(20) DEFAULT NULL,
    `query_user` varchar(200) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '执行sql的用户',
    `created_at` datetime DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `query_type` varchar(200) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '查询对应的场景',
    `query_type_back` int(10) DEFAULT '0' COMMENT '查询类型, 0-正常查询, 1-预刷类型',
    `query_sql_cmd` mediumtext COLLATE utf8mb4_unicode_ci COMMENT '对应查询的struct',
    `sql_cmd_md5` varchar(200) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT 'sql md5值',
    `query_struct_cmd` mediumtext COLLATE utf8mb4_unicode_ci COMMENT '对应查询的struct',
    `struct_cmd_md5` varchar(200) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT 'sql md5值',
    `query_sql` mediumtext COLLATE utf8mb4_unicode_ci COMMENT '对应查询的sql',
    `sql_md5` varchar(200) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT 'sql md5值',
    `query_engine` varchar(20) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '查询引擎',
    `elapsed_ms` bigint(10) DEFAULT NULL COMMENT '查询耗时',
    `query_state` varchar(20) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '查询最终状态',
    `native_query` int(10) DEFAULT NULL COMMENT '1-明细查询,0-聚合查询',
    `start_date` varchar(50) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT 'sql开始日期',
    `end_date` varchar(50) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT 'sql结束日期',
    `dimensions` mediumtext COLLATE utf8mb4_unicode_ci COMMENT 'sql 涉及的维度',
    `metrics` mediumtext COLLATE utf8mb4_unicode_ci COMMENT 'sql 涉及的指标',
    `select_cols` mediumtext COLLATE utf8mb4_unicode_ci COMMENT 'sql select部分涉及的标签',
    `agg_cols` mediumtext COLLATE utf8mb4_unicode_ci COMMENT 'sql agg部分涉及的标签',
    `filter_cols` mediumtext COLLATE utf8mb4_unicode_ci COMMENT 'sql where部分涉及的标签',
    `group_by_cols` mediumtext COLLATE utf8mb4_unicode_ci COMMENT 'sql grouy by部分涉及的标签',
    `order_by_cols` mediumtext COLLATE utf8mb4_unicode_ci COMMENT 'sql order by部分涉及的标签',
    `use_result_cache` tinyint(1) DEFAULT '-1' COMMENT '是否命中sql缓存',
    `use_sql_cache` tinyint(1) DEFAULT '-1' COMMENT '是否命中sql缓存',
    `sql_cache_key` mediumtext COLLATE utf8mb4_unicode_ci COMMENT '缓存的key',
    `result_cache_key` mediumtext COLLATE utf8mb4_unicode_ci COMMENT '缓存的key',
    `query_opt_mode` varchar(20) null comment '优化模式',
    PRIMARY KEY (`id`),
    KEY `domain_index` (`model_id`)
    ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='查询统计信息表';

CREATE TABLE IF NOT EXISTS `s2_canvas`
(
    `id`         bigint(20)   NOT NULL AUTO_INCREMENT,
    `domain_id`  bigint(20)   DEFAULT NULL,
    `type`       varchar(20)  DEFAULT NULL COMMENT 'datasource、dimension、metric',
    `config`     text COMMENT 'config detail',
    `created_at` datetime     DEFAULT NULL,
    `created_by` varchar(100) DEFAULT NULL,
    `updated_at` datetime     DEFAULT NULL,
    `updated_by` varchar(100) NOT NULL,
    PRIMARY KEY (`id`)
    ) ENGINE = InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS s2_user
(
    id       int(11) NOT NULL AUTO_INCREMENT,
    name     varchar(100) not null,
    display_name varchar(100) null,
    password varchar(256) null,
    salt varchar(256) DEFAULT NULL COMMENT 'md5密码盐',
    email varchar(100) null,
    is_admin tinyint null,
    last_login datetime DEFAULT NULL,
    UNIQUE (`name`),
    PRIMARY KEY (`id`)
    ) ENGINE = InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS s2_system_config
(
    id  int primary key AUTO_INCREMENT COMMENT '主键id',
    admin varchar(500) COMMENT '系统管理员',
    parameters text null COMMENT '配置项'
    )ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS s2_model_rela
(
    id             bigint primary key AUTO_INCREMENT,
    domain_id       bigint,
    from_model_id    bigint,
    to_model_id      bigint,
    join_type       VARCHAR(255),
    join_condition  text
    )ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS `s2_collect` (
                                            `id` bigint NOT NULL primary key AUTO_INCREMENT,
                                            `type` varchar(20) NOT NULL,
    `username` varchar(20) NOT NULL,
    `collect_id` bigint NOT NULL,
    `create_time` datetime,
    `update_time` datetime
    )ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS `s2_metric_query_default_config` (
                                                                `id` bigint NOT NULL PRIMARY KEY AUTO_INCREMENT,
                                                                `metric_id` bigint,
                                                                `user_name` varchar(255) NOT NULL,
    `default_config` varchar(1000) NOT NULL,
    `created_at` datetime null,
    `updated_at` datetime null,
    `created_by` varchar(100) null,
    `updated_by` varchar(100) null
    )ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS `s2_app`
(
    id          bigint PRIMARY KEY AUTO_INCREMENT,
    name        VARCHAR(255),
    description VARCHAR(255),
    status      INT,
    config      TEXT,
    end_date    datetime,
    qps         INT,
    app_secret  VARCHAR(255),
    owner       VARCHAR(255),
    `created_at`     datetime null,
    `updated_at`     datetime null,
    `created_by`     varchar(255) null,
    `updated_by`     varchar(255) null
    )ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS s2_data_set
(
    id          BIGINT AUTO_INCREMENT PRIMARY KEY,
    domain_id   BIGINT,
    `name`      VARCHAR(255),
    biz_name    VARCHAR(255),
    `description` VARCHAR(255),
    `status`      INT,
    alias       VARCHAR(255),
    data_set_detail text,
    created_at  datetime,
    created_by  VARCHAR(255),
    updated_at  datetime,
    updated_by  VARCHAR(255),
    query_config VARCHAR(3000),
    `admin` varchar(3000) DEFAULT NULL,
    `admin_org` varchar(3000) DEFAULT NULL
    )ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS s2_tag(
                                     `id` INT NOT NULL  AUTO_INCREMENT,
                                     `item_id` INT  NOT NULL ,
                                     `type` varchar(255)  NOT NULL ,
    `created_at` datetime NOT NULL ,
    `created_by` varchar(100) NOT NULL ,
    `updated_at` datetime DEFAULT NULL ,
    `updated_by` varchar(100) DEFAULT NULL ,
    `ext` text DEFAULT NULL  ,
    PRIMARY KEY (`id`)
    )ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS `s2_tag_object`
(
    `id`                bigint(20)   NOT NULL AUTO_INCREMENT,
    `domain_id`         bigint(20)   DEFAULT NULL,
    `name`              varchar(255) NOT NULL COMMENT '名称',
    `biz_name`          varchar(255) NOT NULL COMMENT '英文名称',
    `description`       varchar(500) DEFAULT NULL COMMENT '描述',
    `status`            tinyint NOT NULL DEFAULT '1' COMMENT '状态',
    `sensitive_level`   tinyint NOT NULL DEFAULT '0' COMMENT '敏感级别',
    `created_at`        datetime     NOT NULL COMMENT '创建时间',
    `created_by`        varchar(100) NOT NULL COMMENT '创建人',
    `updated_at`        datetime      NULL COMMENT '更新时间',
    `updated_by`        varchar(100)  NULL COMMENT '更新人',
    `ext`               text DEFAULT NULL,
    PRIMARY KEY (`id`)
    ) ENGINE = InnoDB
    DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT ='标签对象表';

CREATE TABLE IF NOT EXISTS `s2_query_rule` (
                                               `id` bigint(20)   NOT NULL AUTO_INCREMENT,
    `data_set_id` bigint(20) ,
    `priority` int(10) NOT NULL DEFAULT '1' ,
    `rule_type` varchar(255)  NOT NULL ,
    `name` varchar(255)  NOT NULL ,
    `biz_name` varchar(255)  NOT NULL ,
    `description` varchar(500) DEFAULT NULL ,
    `rule` text DEFAULT NULL  ,
    `action` text DEFAULT NULL  ,
    `status` INT  NOT NULL DEFAULT '1' ,
    `created_at` datetime NOT NULL ,
    `created_by` varchar(100) NOT NULL ,
    `updated_at` datetime DEFAULT NULL ,
    `updated_by` varchar(100) DEFAULT NULL ,
    `ext` text DEFAULT NULL  ,
    PRIMARY KEY (`id`)
    ) ENGINE = InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT ='查询规则表';

CREATE TABLE IF NOT EXISTS `s2_term` (
                                         `id` bigint(20) NOT NULL  AUTO_INCREMENT,
    `domain_id` bigint(20),
    `name` varchar(255)  NOT NULL ,
    `description` varchar(500) DEFAULT NULL ,
    `alias` varchar(1000)  NOT NULL ,
    `related_metrics` varchar(1000)  DEFAULT NULL ,
    `related_dimensions` varchar(1000)  DEFAULT NULL,
    `created_at` datetime NOT NULL ,
    `created_by` varchar(100) NOT NULL ,
    `updated_at` datetime DEFAULT NULL ,
    `updated_by` varchar(100) DEFAULT NULL ,
    PRIMARY KEY (`id`)
    ) ENGINE = InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT ='术语表';

CREATE TABLE IF NOT EXISTS `s2_user_token` (
                                               `id` bigint NOT NULL AUTO_INCREMENT,
                                               `name` VARCHAR(255) NOT NULL,
    `user_name` VARCHAR(255)  NOT NULL,
    `expire_time` BIGINT(20) NOT NULL,
    `token` text NOT NULL,
    `salt` VARCHAR(255)  default NULL,
    `create_time` DATETIME NOT NULL,
    `create_by` VARCHAR(255) NOT NULL,
    `update_time` DATETIME default NULL,
    `update_by` VARCHAR(255) NOT NULL,
    `expire_date_time` DATETIME NOT NULL,
    unique key name_username (`name`, `user_name`),
    PRIMARY KEY (`id`)
    ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci  comment='用户令牌信息表';
