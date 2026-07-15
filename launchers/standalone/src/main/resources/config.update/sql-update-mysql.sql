alter table s2_domain add column `entity`varchar(500) DEFAULT NULL COMMENT '主题域实体信息';


--20230808
alter table s2_domain drop column entity;

create table s2_model
(
    id         bigint auto_increment
        primary key,
    name       varchar(100)                null,
    biz_name   varchar(100) null,
    domain_id  bigint                      null,
    viewer     varchar(500) null,
    view_org   varchar(500)  null,
    admin      varchar(500) null,
    admin_org  varchar(500)  null,
    is_open    int                         null,
    created_by varchar(100) null,
    created_at datetime                    null,
    updated_by varchar(100)  null,
    updated_at datetime                    null,
    entity     text         null
) collate = utf8_unicode_ci;

alter table s2_datasource change column domain_id model_id bigint;
alter table s2_dimension change column domain_id model_id bigint;
alter table s2_metric change column domain_id model_id bigint;
alter table s2_datasource_rela change column domain_id model_id bigint;
alter table s2_view_info change column domain_id model_id bigint;
alter table s2_domain_extend change column domain_id model_id bigint;
alter table s2_chat_config change column domain_id model_id bigint;
alter table s2_plugin change column domain model varchar(100);
alter table s2_query_stat_info change column domain_id model_id bigint;

update s2_plugin set config = replace(config, 'domain', 'model');

--20230823
alter table s2_chat_query add column agent_id int after question_id;
alter table s2_chat_query change column query_response query_result mediumtext;

--20230829
alter table s2_database add column admin varchar(500);
alter table s2_database add column viewer varchar(500);
alter table s2_database drop column domain_id;

--20230831
alter table s2_chat add column agent_id int after chat_id;

--20230907
ALTER TABLE s2_model add alias varchar(200) default null after domain_id;

--20230919
alter table s2_metric add tags varchar(500) null;

--20230920
alter table s2_user add is_admin int null;

--20230926
alter table s2_model add drill_down_dimensions varchar(500) null;
alter table s2_metric add relate_dimensions varchar(500) null;


--20231013
alter table s2_dimension add column data_type  varchar(50)  not null DEFAULT 'varchar' comment '维度数据类型 varchar、array';
alter table s2_query_stat_info add column `query_opt_mode` varchar(20) DEFAULT NULL COMMENT '优化模式';
alter table s2_datasource add column depends text COMMENT '上游依赖标识' after datasource_detail;

--20231018
UPDATE `s2_agent` SET `config` = replace (`config`,'DSL','LLM_S2QL') WHERE `config` LIKE '%DSL%';

--20231023
alter table s2_model add column status int null after alias;
alter table s2_model add column description varchar(500) null after status;
alter table s2_datasource add column status int null after database_id;
update s2_model set status = 1;
update s2_datasource set status = 1;
update s2_metric set status = 1;
update s2_dimension set status = 1;

--20231110
UPDATE `s2_agent` SET `config` = replace (`config`,'LLM_S2QL','LLM_S2SQL') WHERE `config` LIKE '%LLM_S2QL%';

--20231113
CREATE TABLE s2_sys_parameter
(
    id  int primary key AUTO_INCREMENT COMMENT '主键id',
    admin varchar(500) COMMENT '系统管理员',
    parameters text null COMMENT '配置项'
);

--20231114
alter table s2_chat_config add column `llm_examples` text COMMENT 'llm examples';

--20231116
alter table s2_datasource add column `filter_sql` varchar(1000) COMMENT 'filter_sql' after depends;

--20231120
alter table s2_dimension add column `is_tag` int(10) DEFAULT NULL;

--20231125
alter table s2_model add column `database_id` INT NOT NULL;
alter table s2_model add column `model_detail` text NOT  NULL;
alter table s2_model add column `depends` varchar(500) DEFAULT NULL;
alter table s2_model add column `filter_sql` varchar(1000) DEFAULT NULL;

CREATE TABLE s2_model_rela
(
    id             BIGINT AUTO_INCREMENT,
    domain_id       BIGINT,
    from_model_id    BIGINT,
    to_model_id      BIGINT,
    join_type       VARCHAR(255),
    join_condition  VARCHAR(255),
    PRIMARY KEY (`id`)
);

alter table s2_view_info change model_id domain_id bigint;
alter table s2_dimension drop column datasource_id;

-- 20231211
CREATE TABLE `s2_collect`
(
    `id`          bigint      NOT NULL primary key AUTO_INCREMENT,
    `type`        varchar(20) NOT NULL,
    `username`    varchar(20) NOT NULL,
    `collect_id`  bigint      NOT NULL,
    `create_time` datetime,
    `update_time` datetime
)ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

alter table s2_metric add column `ext` text DEFAULT NULL;

CREATE TABLE `s2_metric_query_default_config`
(
    `id`             bigint  NOT NULL PRIMARY KEY AUTO_INCREMENT,
    `metric_id`      bigint,
    `user_name`      varchar(255)  NOT NULL,
    `default_config` varchar(1000) NOT NULL,
    `created_at`     datetime null,
    `updated_at`     datetime null,
    `created_by`     varchar(100) null,
    `updated_by`     varchar(100) null
)ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

--20231214
alter table s2_chat_query add column `similar_queries` varchar(1024) DEFAULT '';
alter table s2_model add column `source_type` varchar(128) DEFAULT NULL;


CREATE TABLE `s2_app`
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


--20240115
alter table s2_metric add column `define_type` varchar(50)  DEFAULT NULL; -- MEASURE, FIELD, METRIC
update s2_metric set define_type = 'MEASURE';

--20240129
CREATE TABLE s2_view(
    id          BIGINT AUTO_INCREMENT PRIMARY KEY,
    domain_id   BIGINT,
    `name`      VARCHAR(255),
    biz_name    VARCHAR(255),
    `description` VARCHAR(255),
    `status`      INT,
    alias       VARCHAR(255),
    view_detail text,
    created_at  datetime,
    created_by  VARCHAR(255),
    updated_at  datetime,
    updated_by  VARCHAR(255),
    query_config VARCHAR(3000),
    `admin` varchar(3000) DEFAULT NULL,
    `admin_org` varchar(3000) DEFAULT NULL
)ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

alter table s2_plugin change column model `view` varchar(100);
alter table s2_view_info rename to s2_canvas;

alter table s2_query_stat_info add column `view_id` bigint(20) DEFAULT NULL after `model_id`;

--20240301
CREATE TABLE IF NOT EXISTS `s2_dictionary_conf` (
   `id` INT NOT NULL AUTO_INCREMENT,
   `description` varchar(255) ,
   `type` varchar(255)  NOT NULL ,
   `item_id` INT  NOT NULL ,
   `config` text  ,
   `status` varchar(255) NOT NULL ,
   `created_at` TIMESTAMP DEFAULT CURRENT_TIMESTAMP  ,
   `created_by` varchar(100) NOT NULL ,
   PRIMARY KEY (`id`)
);
COMMENT ON TABLE s2_dictionary_conf IS 'dictionary conf information table';

CREATE TABLE IF NOT EXISTS `s2_dictionary_task` (
   `id` INT NOT NULL AUTO_INCREMENT,
   `name` varchar(255) NOT NULL ,
   `description` varchar(255) ,
   `type` varchar(255)  NOT NULL ,
   `item_id` INT  NOT NULL ,
   `config` text  ,
   `status` varchar(255) NOT NULL ,
   `created_at` TIMESTAMP DEFAULT CURRENT_TIMESTAMP  ,
   `created_by` varchar(100) NOT NULL ,
   `elapsed_ms` bigINT DEFAULT NULL ,
   PRIMARY KEY (`id`)
);
COMMENT ON TABLE s2_dictionary_task IS 'dictionary task information table';


--20240229
alter table s2_view rename to s2_data_set;
alter table s2_query_stat_info change view_id data_set_id bigint;
alter table s2_plugin change `view` data_set varchar(200);
alter table s2_data_set change view_detail data_set_detail text;

--20240311
alter table s2_data_set add column query_type varchar(100) DEFAULT NULL;

--20240319
CREATE TABLE IF NOT EXISTS `s2_tag_object`
(
    `id`                bigint(20)   NOT NULL AUTO_INCREMENT,
    `domain_id`         bigint(20)   DEFAULT NULL,
    `name`              varchar(255) NOT NULL COMMENT '名称',
    `biz_name`          varchar(255) NOT NULL COMMENT '英文名称',
    `description`       varchar(500) DEFAULT NULL COMMENT '描述',
    `status`            int(10) NOT NULL DEFAULT '1' COMMENT '状态',
    `sensitive_level`   int(10) NOT NULL DEFAULT '0' COMMENT '敏感级别',
    `created_at`        datetime     NOT NULL COMMENT '创建时间',
    `created_by`        varchar(100) NOT NULL COMMENT '创建人',
    `updated_at`        datetime      NULL COMMENT '更新时间',
    `updated_by`        varchar(100)  NULL COMMENT '更新人',
    `ext`               text DEFAULT NULL,
    PRIMARY KEY (`id`)
) ENGINE = InnoDB
DEFAULT CHARSET = utf8 COMMENT ='标签表对象';

alter table s2_model add column `tag_object_id` bigint(20) DEFAULT NULL after domain_id;

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

--20240321
CREATE TABLE IF NOT EXISTS `s2_query_rule` (
    `id` INT NOT NULL  AUTO_INCREMENT,
    `data_set_id` INT ,
    `priority` INT  NOT NULL DEFAULT '1' ,
    `rule_type` varchar(255)  NOT NULL ,
    `name` varchar(255)  NOT NULL ,
    `biz_name` varchar(255)  NOT NULL ,
    `description` varchar(500) DEFAULT NULL ,
    `rule` LONGVARCHAR DEFAULT NULL  ,
    `action` LONGVARCHAR DEFAULT NULL  ,
    `status` INT  NOT NULL DEFAULT '1' ,
    `created_at` TIMESTAMP NOT NULL ,
    `created_by` varchar(100) NOT NULL ,
    `updated_at` TIMESTAMP DEFAULT NULL ,
    `updated_by` varchar(100) DEFAULT NULL ,
    `ext` LONGVARCHAR DEFAULT NULL  ,
    PRIMARY KEY (`id`)
    );
COMMENT ON TABLE s2_query_rule IS 'tag query rule table';

--20240325
alter table s2_metric  change tags classifications varchar(500) null;
alter table s2_metric  add column `is_publish` int(10) DEFAULT NULL COMMENT '是否发布';
update s2_metric set is_publish = 1;

--20240402
alter table s2_dimension add column `ext` varchar(1000) DEFAULT NULL;

--20240510
CREATE TABLE IF NOT EXISTS `s2_term` (
    `id` bigint(20) NOT NULL  AUTO_INCREMENT,
    `domain_id` bigint(20),
    `name` varchar(255)  NOT NULL ,
    `description` varchar(500) DEFAULT NULL ,
    `alias` varchar(1000)  NOT NULL ,
    `created_at` datetime NOT NULL ,
    `created_by` varchar(100) NOT NULL ,
    `updated_at` datetime DEFAULT NULL ,
    `updated_by` varchar(100) DEFAULT NULL ,
    PRIMARY KEY (`id`)
);

--20240520
alter table s2_agent add column `llm_config` varchar(2000) COLLATE utf8_unicode_ci DEFAULT NULL COMMENT '大模型配置';
alter table s2_agent add column `multi_turn_config` varchar(2000) COLLATE utf8_unicode_ci DEFAULT NULL;

alter table s2_model add column `ext` varchar(1000) DEFAULT NULL;

--20240601
alter table s2_sys_parameter rename to s2_system_config;

--20240603
alter table s2_chat_query add column `parse_time_cost` varchar(1024);

--20240609
alter table s2_user add column `salt` varchar(256) DEFAULT NULL COMMENT 'md5密码盐';

--20240621
alter table s2_agent add column `visual_config` varchar(2000)  COLLATE utf8_unicode_ci DEFAULT NULL COMMENT '可视化配置';

alter table s2_term add column `related_metrics` varchar(1000)  DEFAULT NULL  COMMENT '术语关联的指标';
alter table s2_term add column `related_dimensions` varchar(1000)  DEFAULT NULL  COMMENT '术语关联的维度';

--20240627
CREATE TABLE IF NOT EXISTS `s2_chat_memory` (
    `id` INT NOT NULL AUTO_INCREMENT,
    `question` varchar(655),
    `agent_id`  INT    ,
    `db_schema`  TEXT    ,
    `s2_sql` TEXT   ,
    `status` char(10)   ,
    `llm_review` char(10)   ,
    `llm_comment`   TEXT,
    `human_review` char(10) ,
    `human_comment` TEXT    ,
    `created_at` TIMESTAMP DEFAULT CURRENT_TIMESTAMP  ,
    `updated_at` TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP ,
    `created_by` varchar(100) NOT NULL   ,
    `updated_by` varchar(100) NOT NULL   ,
    PRIMARY KEY (`id`)
    ) ENGINE=InnoDB DEFAULT CHARSET=utf8;

--20240705
alter table s2_agent add column `prompt_config` text COLLATE utf8_unicode_ci DEFAULT NULL COMMENT '提示词配置';

--20240707
alter table s2_agent add `model_config` text null;

--20240710
alter table s2_agent add `enable_memory_review` tinyint DEFAULT 0;

--20240718
alter table s2_chat_memory add `side_info` TEXT DEFAULT NULL COMMENT '辅助信息';

--20240730
alter table s2_chat_parse modify column `chat_id` int(11);

--20240806
UPDATE `s2_dimension` SET `type` = 'identify' WHERE `type` in ('primary','foreign');
alter table singer drop column imp_date;

--20240913
ALTER TABLE s2_model MODIFY COLUMN drill_down_dimensions TEXT DEFAULT NULL;

--20241009
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
   PRIMARY KEY (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8 COMMENT='对话大模型实例表';
ALTER TABLE s2_agent CHANGE COLUMN config tool_config text;
ALTER TABLE s2_agent CHANGE COLUMN model_config chat_model_config text;
--20241011
ALTER TABLE s2_agent DROP COLUMN  `prompt_config`;
ALTER TABLE s2_agent DROP COLUMN  `multi_turn_config`;
ALTER TABLE s2_agent DROP COLUMN  `enable_memory_review`;

--20241012
alter table s2_agent add column `enable_feedback` tinyint DEFAULT 1;

--20241116
alter table s2_agent add column `admin` varchar(1000) COLLATE utf8_unicode_ci DEFAULT NULL;
alter table s2_agent add column `viewer` varchar(1000) COLLATE utf8_unicode_ci DEFAULT NULL;

--20241201
ALTER TABLE s2_query_stat_info RENAME COLUMN `user` TO `query_user`;
ALTER TABLE s2_chat_context RENAME COLUMN `user` TO `query_user`;

--20241226
ALTER TABLE s2_chat_memory add column `query_id` BIGINT DEFAULT NULL;
ALTER TABLE s2_query_stat_info RENAME COLUMN `sql` TO `query_sql`;

--20250224
ALTER TABLE s2_agent add column `admin_org` varchar(3000) DEFAULT NULL COMMENT '管理员组织';
ALTER TABLE s2_agent add column `view_org` varchar(3000) DEFAULT NULL COMMENT '可用组织';
ALTER TABLE s2_agent add column `is_open` tinyint DEFAULT NULL COMMENT '是否公开';

--20250309
ALTER TABLE s2_model_rela alter column join_condition type text;

--20250310
ALTER TABLE s2_chat_model add column is_open tinyint DEFAULT NULL COMMENT '是否公开';
ALTER TABLE s2_database add column is_open tinyint DEFAULT NULL COMMENT '是否公开';

--20250321
ALTER TABLE s2_user add column last_login datetime DEFAULT NULL;

--20260708 LLM Conversation Gateway phase 1
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

--20260709 AI semantic modeling phase 2: semantic gap pool
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

-- 20260710 AI semantic modeling phase 3: structured draft and immutable versions
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

-- CREATE TABLE IF NOT EXISTS does not merge phase-4 columns into an existing phase-3 table.
-- Guard every ALTER through information_schema so fresh installs and partial retries converge.
SET @stage4_ddl = IF(
    EXISTS(SELECT 1 FROM information_schema.columns
           WHERE table_schema = DATABASE()
             AND table_name = 's2_semantic_modeling_draft'
             AND column_name = 'submitted_validation_report_id'),
    'DO 0',
    'ALTER TABLE `s2_semantic_modeling_draft` ADD COLUMN `submitted_validation_report_id` bigint DEFAULT NULL COMMENT ''阶段4提交待审批时绑定的验证报告ID'' AFTER `error_message`'
);
PREPARE stage4_stmt FROM @stage4_ddl;
EXECUTE stage4_stmt;
DEALLOCATE PREPARE stage4_stmt;

SET @stage4_ddl = IF(
    EXISTS(SELECT 1 FROM information_schema.columns
           WHERE table_schema = DATABASE()
             AND table_name = 's2_semantic_modeling_draft'
             AND column_name = 'submission_idempotency_key'),
    'DO 0',
    'ALTER TABLE `s2_semantic_modeling_draft` ADD COLUMN `submission_idempotency_key` varchar(128) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin DEFAULT NULL COMMENT ''提交待审批请求幂等键（大小写敏感）'' AFTER `submitted_validation_report_id`'
);
PREPARE stage4_stmt FROM @stage4_ddl;
EXECUTE stage4_stmt;
DEALLOCATE PREPARE stage4_stmt;

SET @stage4_ddl = IF(
    EXISTS(SELECT 1 FROM information_schema.columns
           WHERE table_schema = DATABASE()
             AND table_name = 's2_semantic_modeling_draft'
             AND column_name = 'submitted_by'),
    'DO 0',
    'ALTER TABLE `s2_semantic_modeling_draft` ADD COLUMN `submitted_by` varchar(100) DEFAULT NULL COMMENT ''提交待审批人'' AFTER `submission_idempotency_key`'
);
PREPARE stage4_stmt FROM @stage4_ddl;
EXECUTE stage4_stmt;
DEALLOCATE PREPARE stage4_stmt;

SET @stage4_ddl = IF(
    EXISTS(SELECT 1 FROM information_schema.columns
           WHERE table_schema = DATABASE()
             AND table_name = 's2_semantic_modeling_draft'
             AND column_name = 'submitted_at'),
    'DO 0',
    'ALTER TABLE `s2_semantic_modeling_draft` ADD COLUMN `submitted_at` datetime DEFAULT NULL COMMENT ''提交待审批时间'' AFTER `submitted_by`'
);
PREPARE stage4_stmt FROM @stage4_ddl;
EXECUTE stage4_stmt;
DEALLOCATE PREPARE stage4_stmt;

-- Re-applying this section also repairs a pre-fix case-insensitive phase-4 column safely.
ALTER TABLE `s2_semantic_modeling_draft`
    MODIFY COLUMN `submission_idempotency_key` varchar(128)
        CHARACTER SET utf8mb4 COLLATE utf8mb4_bin DEFAULT NULL
        COMMENT '提交待审批请求幂等键（大小写敏感）';

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

-- Add version-level idempotency metadata to an existing phase-3 table before creating its index.
SET @stage4_ddl = IF(
    EXISTS(SELECT 1 FROM information_schema.columns
           WHERE table_schema = DATABASE()
             AND table_name = 's2_semantic_modeling_draft_version'
             AND column_name = 'request_idempotency_key'),
    'DO 0',
    'ALTER TABLE `s2_semantic_modeling_draft_version` ADD COLUMN `request_idempotency_key` varchar(128) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin DEFAULT NULL COMMENT ''创建该版本的请求幂等键（大小写敏感）'' AFTER `llm_conversation_id`'
);
PREPARE stage4_stmt FROM @stage4_ddl;
EXECUTE stage4_stmt;
DEALLOCATE PREPARE stage4_stmt;

SET @stage4_ddl = IF(
    EXISTS(SELECT 1 FROM information_schema.columns
           WHERE table_schema = DATABASE()
             AND table_name = 's2_semantic_modeling_draft_version'
             AND column_name = 'request_fingerprint'),
    'DO 0',
    'ALTER TABLE `s2_semantic_modeling_draft_version` ADD COLUMN `request_fingerprint` varchar(128) DEFAULT NULL COMMENT ''幂等请求规范化参数指纹'' AFTER `request_idempotency_key`'
);
PREPARE stage4_stmt FROM @stage4_ddl;
EXECUTE stage4_stmt;
DEALLOCATE PREPARE stage4_stmt;

SET @stage4_ddl = IF(
    EXISTS(SELECT 1 FROM information_schema.columns
           WHERE table_schema = DATABASE()
             AND table_name = 's2_semantic_modeling_draft_version'
             AND column_name = 'result_lock_version'),
    'DO 0',
    'ALTER TABLE `s2_semantic_modeling_draft_version` ADD COLUMN `result_lock_version` int DEFAULT NULL COMMENT ''RESTORED首次成功响应的草稿锁版本'' AFTER `request_fingerprint`'
);
PREPARE stage4_stmt FROM @stage4_ddl;
EXECUTE stage4_stmt;
DEALLOCATE PREPARE stage4_stmt;

ALTER TABLE `s2_semantic_modeling_draft_version`
    MODIFY COLUMN `request_idempotency_key` varchar(128)
        CHARACTER SET utf8mb4 COLLATE utf8mb4_bin DEFAULT NULL
        COMMENT '创建该版本的请求幂等键（大小写敏感）';

SET @stage4_ddl = IF(
    EXISTS(SELECT 1 FROM information_schema.statistics
           WHERE table_schema = DATABASE()
             AND table_name = 's2_semantic_modeling_draft_version'
             AND index_name = 'uk_semantic_draft_version_request'),
    'DO 0',
    'CREATE UNIQUE INDEX `uk_semantic_draft_version_request` ON `s2_semantic_modeling_draft_version` (`draft_id`, `request_idempotency_key`)'
);
PREPARE stage4_stmt FROM @stage4_ddl;
EXECUTE stage4_stmt;
DEALLOCATE PREPARE stage4_stmt;
SET @stage4_ddl = NULL;

-- Persist Provider-call ownership so aggregate upgrades receive the same cross-instance revision lease.
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

-- 20260710 AI semantic modeling phase 4: isolated validation reports; never writes formal semantic assets.
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

-- 20260714 phase 5 approval and auditable release orchestration.
SET @stage5_ddl = IF(
    EXISTS(SELECT 1 FROM information_schema.columns WHERE table_schema = DATABASE()
        AND table_name = 's2_semantic_modeling_draft' AND column_name = 'approved_by'),
    'DO 0',
    'ALTER TABLE `s2_semantic_modeling_draft` ADD COLUMN `approved_by` varchar(100) DEFAULT NULL AFTER `submitted_at`'
);
PREPARE stage5_stmt FROM @stage5_ddl;
EXECUTE stage5_stmt;
DEALLOCATE PREPARE stage5_stmt;
SET @stage5_ddl = IF(
    EXISTS(SELECT 1 FROM information_schema.columns WHERE table_schema = DATABASE()
        AND table_name = 's2_semantic_modeling_draft' AND column_name = 'approved_at'),
    'DO 0',
    'ALTER TABLE `s2_semantic_modeling_draft` ADD COLUMN `approved_at` datetime DEFAULT NULL AFTER `approved_by`'
);
PREPARE stage5_stmt FROM @stage5_ddl;
EXECUTE stage5_stmt;
DEALLOCATE PREPARE stage5_stmt;
SET @stage5_ddl = IF(
    EXISTS(SELECT 1 FROM information_schema.columns WHERE table_schema = DATABASE()
        AND table_name = 's2_semantic_modeling_draft' AND column_name = 'approval_reason'),
    'DO 0',
    'ALTER TABLE `s2_semantic_modeling_draft` ADD COLUMN `approval_reason` varchar(1000) DEFAULT NULL AFTER `approved_at`'
);
PREPARE stage5_stmt FROM @stage5_ddl;
EXECUTE stage5_stmt;
DEALLOCATE PREPARE stage5_stmt;
SET @stage5_ddl = IF(
    EXISTS(SELECT 1 FROM information_schema.columns WHERE table_schema = DATABASE()
        AND table_name = 's2_semantic_modeling_draft' AND column_name = 'rejected_by'),
    'DO 0',
    'ALTER TABLE `s2_semantic_modeling_draft` ADD COLUMN `rejected_by` varchar(100) DEFAULT NULL AFTER `approval_reason`'
);
PREPARE stage5_stmt FROM @stage5_ddl;
EXECUTE stage5_stmt;
DEALLOCATE PREPARE stage5_stmt;
SET @stage5_ddl = IF(
    EXISTS(SELECT 1 FROM information_schema.columns WHERE table_schema = DATABASE()
        AND table_name = 's2_semantic_modeling_draft' AND column_name = 'rejected_at'),
    'DO 0',
    'ALTER TABLE `s2_semantic_modeling_draft` ADD COLUMN `rejected_at` datetime DEFAULT NULL AFTER `rejected_by`'
);
PREPARE stage5_stmt FROM @stage5_ddl;
EXECUTE stage5_stmt;
DEALLOCATE PREPARE stage5_stmt;
SET @stage5_ddl = NULL;

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
