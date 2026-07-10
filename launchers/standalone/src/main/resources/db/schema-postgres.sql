CREATE TABLE IF NOT EXISTS s2_agent (
    id SERIAL PRIMARY KEY,
    name varchar(100) DEFAULT NULL,
    description TEXT DEFAULT NULL,
    examples TEXT DEFAULT NULL,
    status smallint DEFAULT NULL,
    model varchar(100) DEFAULT NULL,
    tool_config varchar(6000) DEFAULT NULL,
    llm_config varchar(2000) DEFAULT NULL,
    chat_model_config text DEFAULT NULL,
    visual_config varchar(2000) DEFAULT NULL,
    enable_search smallint DEFAULT 1,
    enable_feedback smallint DEFAULT 1,
    created_by varchar(100) DEFAULT NULL,
    created_at timestamp DEFAULT NULL,
    updated_by varchar(100) DEFAULT NULL,
    updated_at timestamp DEFAULT NULL,
    admin varchar(3000) DEFAULT NULL,
    admin_org varchar(3000) DEFAULT NULL,
    is_open smallint DEFAULT NULL,
    viewer varchar(3000) DEFAULT NULL,
    view_org varchar(3000) DEFAULT NULL
);

CREATE TABLE IF NOT EXISTS s2_auth_groups (
    group_id integer NOT NULL PRIMARY KEY,
    config varchar(2048) DEFAULT NULL
);

CREATE TABLE IF NOT EXISTS s2_available_date_info (
    id SERIAL PRIMARY KEY,
    item_id integer NOT NULL,
    type varchar(255) NOT NULL,
    date_format varchar(64) NOT NULL,
    date_period varchar(64) DEFAULT NULL,
    start_date varchar(64) DEFAULT NULL,
    end_date varchar(64) DEFAULT NULL,
    unavailable_date text,
    created_at timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP,
    created_by varchar(100) NOT NULL,
    updated_at timestamp NULL,
    updated_by varchar(100) NOT NULL,
    status smallint DEFAULT 0,
    UNIQUE(item_id, type)
);

CREATE TABLE IF NOT EXISTS s2_chat (
    chat_id SERIAL PRIMARY KEY,
    agent_id integer DEFAULT NULL,
    chat_name varchar(300) DEFAULT NULL,
    create_time timestamp DEFAULT NULL,
    last_time timestamp DEFAULT NULL,
    creator varchar(30) DEFAULT NULL,
    last_question varchar(200) DEFAULT NULL,
    is_delete smallint DEFAULT 0,
    is_top smallint DEFAULT 0
);

CREATE TABLE IF NOT EXISTS s2_chat_config (
    id SERIAL PRIMARY KEY,
    model_id bigint DEFAULT NULL,
    chat_detail_config text,
    chat_agg_config text,
    recommended_questions text,
    created_at timestamp NOT NULL,
    updated_at timestamp NOT NULL,
    created_by varchar(100) NOT NULL,
    updated_by varchar(100) NOT NULL,
    status smallint NOT NULL,
    llm_examples text
);

CREATE TABLE IF NOT EXISTS s2_chat_memory (
    id SERIAL PRIMARY KEY,
    question varchar(655),
    side_info TEXT,
    query_id bigint,
    agent_id INTEGER,
    db_schema TEXT,
    s2_sql TEXT,
    status varchar(20),
    llm_review varchar(20),
    llm_comment TEXT,
    human_review varchar(20),
    human_comment TEXT,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    created_by varchar(100) DEFAULT NULL,
    updated_by varchar(100) DEFAULT NULL
);

CREATE TABLE IF NOT EXISTS s2_chat_context (
    chat_id bigint NOT NULL PRIMARY KEY,
    modified_at timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP,
    query_user varchar(64) DEFAULT NULL,
    query_text text,
    semantic_parse text,
    ext_data text
);


CREATE TABLE IF NOT EXISTS s2_chat_parse (
    question_id bigint NOT NULL,
    chat_id integer NOT NULL,
    parse_id integer NOT NULL,
    create_time timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP,
    query_text varchar(500) DEFAULT NULL,
    user_name varchar(150) DEFAULT NULL,
    parse_info text NOT NULL,
    is_candidate integer DEFAULT 1,
    CONSTRAINT commonIndex UNIQUE (question_id)
);

CREATE TABLE IF NOT EXISTS s2_chat_query (
    question_id SERIAL PRIMARY KEY,
    agent_id integer DEFAULT NULL,
    create_time timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP,
    query_text text,
    user_name varchar(150) DEFAULT NULL,
    query_state smallint DEFAULT NULL,
    chat_id bigint NOT NULL,
    query_result text,
    score integer DEFAULT 0,
    feedback varchar(1024) DEFAULT '',
    similar_queries varchar(1024) DEFAULT '',
    parse_time_cost varchar(1024) DEFAULT ''
);

CREATE TABLE IF NOT EXISTS s2_semantic_gap (
    id SERIAL PRIMARY KEY,
    question text NOT NULL,
    normalized_question varchar(500) NOT NULL,
    assistant_id integer DEFAULT NULL,
    user_id bigint DEFAULT NULL,
    domain_id bigint DEFAULT NULL,
    data_source_id bigint DEFAULT NULL,
    failure_type varchar(64) NOT NULL,
    failure_reason varchar(1500) DEFAULT NULL,
    matched_model_ids varchar(1000) DEFAULT NULL,
    matched_metric_ids varchar(1000) DEFAULT NULL,
    matched_dimension_ids varchar(1000) DEFAULT NULL,
    generated_sql text DEFAULT NULL,
    s2sql text DEFAULT NULL,
    feedback varchar(1500) DEFAULT NULL,
    occurrence_count integer NOT NULL DEFAULT 1,
    negative_feedback_count integer NOT NULL DEFAULT 0,
    priority_score integer NOT NULL DEFAULT 0,
    status varchar(64) NOT NULL,
    created_at timestamp NOT NULL,
    last_seen_at timestamp NOT NULL,
    created_by varchar(100) DEFAULT NULL,
    updated_at timestamp NOT NULL,
    updated_by varchar(100) DEFAULT NULL,
    ignore_reason varchar(1500) DEFAULT NULL,
    source_query_id bigint DEFAULT NULL,
    source_chat_id bigint DEFAULT NULL,
    recent_questions text DEFAULT NULL
);

CREATE INDEX IF NOT EXISTS idx_semantic_gap_pool
    ON s2_semantic_gap (assistant_id, domain_id, failure_type, status);
CREATE INDEX IF NOT EXISTS idx_semantic_gap_priority
    ON s2_semantic_gap (priority_score, last_seen_at);
CREATE INDEX IF NOT EXISTS idx_semantic_gap_normalized
    ON s2_semantic_gap (normalized_question);

CREATE TABLE IF NOT EXISTS s2_chat_statistics (
    question_id bigint NOT NULL,
    chat_id bigint NOT NULL,
    user_name varchar(150) DEFAULT NULL,
    query_text varchar(200) DEFAULT NULL,
    interface_name varchar(100) DEFAULT NULL,
    cost integer DEFAULT 0,
    type integer DEFAULT NULL,
    create_time timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE IF NOT EXISTS s2_chat_model (
    id SERIAL PRIMARY KEY,
    name varchar(255) NOT NULL,
    description varchar(500) DEFAULT NULL,
    config text NOT NULL,
    created_at timestamp NOT NULL,
    created_by varchar(100) NOT NULL,
    updated_at timestamp NOT NULL,
    updated_by varchar(100) NOT NULL,
    admin varchar(500) DEFAULT NULL,
    viewer varchar(500) DEFAULT NULL,
    is_open smallint DEFAULT NULL
);

CREATE TABLE IF NOT EXISTS s2_llm_model_capability (
    id SERIAL PRIMARY KEY,
    chat_model_id integer NOT NULL,
    provider_type varchar(64) NOT NULL,
    model_name varchar(255) NOT NULL,
    max_context_tokens integer DEFAULT NULL,
    support_stream boolean DEFAULT false,
    support_json_mode boolean DEFAULT false,
    support_tool_calling boolean DEFAULT false,
    support_thinking boolean DEFAULT false,
    support_chat_prefix_completion boolean DEFAULT false,
    support_fim_completion boolean DEFAULT false,
    support_context_cache boolean DEFAULT false,
    support_system_prompt boolean DEFAULT true,
    recommended_temperature double precision DEFAULT NULL,
    usage_scene varchar(255) DEFAULT NULL,
    enabled boolean DEFAULT true,
    created_at timestamp NOT NULL,
    updated_at timestamp NOT NULL,
    UNIQUE (chat_model_id, model_name)
);

CREATE TABLE IF NOT EXISTS s2_llm_conversation (
    id SERIAL PRIMARY KEY,
    conversation_type varchar(64) NOT NULL,
    chat_model_id integer NOT NULL,
    provider_type varchar(64) NOT NULL,
    model_name varchar(255) NOT NULL,
    business_id varchar(128) DEFAULT NULL,
    status varchar(32) NOT NULL,
    summary text DEFAULT NULL,
    created_by varchar(100) DEFAULT NULL,
    created_at timestamp NOT NULL,
    updated_at timestamp NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_llm_conversation_business
    ON s2_llm_conversation (conversation_type, business_id);

CREATE TABLE IF NOT EXISTS s2_llm_message (
    id SERIAL PRIMARY KEY,
    conversation_id bigint NOT NULL,
    role varchar(32) NOT NULL,
    content text DEFAULT NULL,
    reasoning_content text DEFAULT NULL,
    content_type varchar(32) DEFAULT NULL,
    tool_calls text DEFAULT NULL,
    tool_call_id varchar(128) DEFAULT NULL,
    token_count integer DEFAULT NULL,
    message_order integer NOT NULL,
    created_at timestamp NOT NULL,
    UNIQUE (conversation_id, message_order)
);

CREATE INDEX IF NOT EXISTS idx_llm_message_conversation
    ON s2_llm_message (conversation_id);

CREATE TABLE IF NOT EXISTS s2_llm_invocation_log (
    id SERIAL PRIMARY KEY,
    conversation_id bigint NOT NULL,
    chat_model_id integer NOT NULL,
    provider_type varchar(64) NOT NULL,
    model_name varchar(255) NOT NULL,
    request_id varchar(128) DEFAULT NULL,
    prompt_tokens integer DEFAULT NULL,
    completion_tokens integer DEFAULT NULL,
    total_tokens integer DEFAULT NULL,
    latency_ms bigint DEFAULT NULL,
    status varchar(32) NOT NULL,
    error_code varchar(64) DEFAULT NULL,
    error_message varchar(1000) DEFAULT NULL,
    raw_response_ref varchar(1200) DEFAULT NULL,
    created_at timestamp NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_llm_invocation_conversation
    ON s2_llm_invocation_log (conversation_id);

-- AI semantic modeling phase 3: isolated structured drafts and immutable version snapshots.
CREATE TABLE IF NOT EXISTS s2_semantic_modeling_draft (
    id BIGSERIAL PRIMARY KEY,
    source_type varchar(32) NOT NULL,
    source_id bigint DEFAULT NULL,
    title varchar(255) DEFAULT NULL,
    business_goal text NOT NULL,
    domain_id bigint DEFAULT NULL,
    data_source_id bigint NOT NULL,
    catalog_name varchar(255) DEFAULT NULL,
    database_name varchar(255) DEFAULT NULL,
    selected_tables text NOT NULL,
    chat_model_id integer NOT NULL,
    llm_conversation_id bigint DEFAULT NULL,
    include_sample boolean NOT NULL DEFAULT false,
    idempotency_key varchar(128) NOT NULL,
    status varchar(32) NOT NULL,
    current_version_no integer NOT NULL DEFAULT 0,
    current_attempt_no integer NOT NULL DEFAULT 1,
    lock_version integer NOT NULL DEFAULT 0,
    generation_started_at timestamp DEFAULT NULL,
    generation_finished_at timestamp DEFAULT NULL,
    draft_json text DEFAULT NULL,
    raw_output text DEFAULT NULL,
    repaired_output text DEFAULT NULL,
    error_code varchar(64) DEFAULT NULL,
    error_message varchar(1500) DEFAULT NULL,
    created_by varchar(100) NOT NULL,
    created_at timestamp NOT NULL,
    updated_by varchar(100) NOT NULL,
    updated_at timestamp NOT NULL
);

COMMENT ON TABLE s2_semantic_modeling_draft IS
    'AI semantic modeling structured draft; statuses are GENERATING, DRAFT and GENERATION_FAILED';
CREATE UNIQUE INDEX IF NOT EXISTS uk_semantic_draft_idempotency
    ON s2_semantic_modeling_draft (created_by, idempotency_key);
CREATE INDEX IF NOT EXISTS idx_semantic_draft_source
    ON s2_semantic_modeling_draft (source_type, source_id, status);
CREATE INDEX IF NOT EXISTS idx_semantic_draft_list
    ON s2_semantic_modeling_draft (data_source_id, id DESC);
CREATE INDEX IF NOT EXISTS idx_semantic_draft_generation
    ON s2_semantic_modeling_draft (status, generation_started_at);

-- Each row is an immutable generation attempt. The draft main row mirrors only the current
-- attempt so list/detail reads stay fast while earlier failures remain available for diagnostics.
CREATE TABLE IF NOT EXISTS s2_semantic_modeling_draft_attempt (
    id BIGSERIAL PRIMARY KEY,
    draft_id bigint NOT NULL,
    attempt_no integer NOT NULL,
    trigger_type varchar(32) NOT NULL,
    status varchar(32) NOT NULL,
    chat_model_id integer NOT NULL,
    include_sample boolean NOT NULL DEFAULT false,
    idempotency_key varchar(128) NOT NULL,
    request_fingerprint varchar(128) DEFAULT NULL,
    llm_conversation_id bigint DEFAULT NULL,
    generate_request_id varchar(128) DEFAULT NULL,
    repair_request_id varchar(128) DEFAULT NULL,
    raw_output text DEFAULT NULL,
    repaired_output text DEFAULT NULL,
    failure_stage varchar(64) DEFAULT NULL,
    validation_issues text DEFAULT NULL,
    error_code varchar(64) DEFAULT NULL,
    error_message varchar(1500) DEFAULT NULL,
    started_at timestamp DEFAULT NULL,
    finished_at timestamp DEFAULT NULL,
    created_by varchar(100) NOT NULL,
    created_at timestamp NOT NULL,
    updated_by varchar(100) NOT NULL,
    updated_at timestamp NOT NULL
);

COMMENT ON TABLE s2_semantic_modeling_draft_attempt IS
    'Immutable generation-attempt history for AI semantic modeling drafts';
CREATE UNIQUE INDEX IF NOT EXISTS uk_semantic_draft_attempt_no
    ON s2_semantic_modeling_draft_attempt (draft_id, attempt_no);
CREATE UNIQUE INDEX IF NOT EXISTS uk_semantic_draft_attempt_idempotency
    ON s2_semantic_modeling_draft_attempt (created_by, idempotency_key);
CREATE INDEX IF NOT EXISTS idx_semantic_draft_attempt_list
    ON s2_semantic_modeling_draft_attempt (draft_id, attempt_no DESC);
CREATE INDEX IF NOT EXISTS idx_semantic_draft_attempt_recovery
    ON s2_semantic_modeling_draft_attempt (status, started_at);

CREATE TABLE IF NOT EXISTS s2_semantic_modeling_draft_version (
    id BIGSERIAL PRIMARY KEY,
    draft_id bigint NOT NULL,
    version_no integer NOT NULL,
    draft_json text NOT NULL,
    change_source varchar(32) NOT NULL,
    change_summary varchar(1000) DEFAULT NULL,
    llm_conversation_id bigint DEFAULT NULL,
    created_by varchar(100) NOT NULL,
    created_at timestamp NOT NULL
);

COMMENT ON TABLE s2_semantic_modeling_draft_version IS
    'Immutable AI semantic modeling draft snapshots; change source is AI_GENERATED or MANUAL_SAVE';
CREATE UNIQUE INDEX IF NOT EXISTS uk_semantic_draft_version
    ON s2_semantic_modeling_draft_version (draft_id, version_no);

CREATE TABLE IF NOT EXISTS s2_database (
    id SERIAL PRIMARY KEY,
    name varchar(255) NOT NULL,
    description varchar(500) DEFAULT NULL,
    version varchar(64) DEFAULT NULL,
    type varchar(20) NOT NULL,
    config text NOT NULL,
    created_at timestamp NOT NULL,
    created_by varchar(100) NOT NULL,
    updated_at timestamp NOT NULL,
    updated_by varchar(100) NOT NULL,
    admin varchar(500) DEFAULT NULL,
    viewer varchar(500) DEFAULT NULL,
    is_open smallint DEFAULT NULL
);


CREATE TABLE IF NOT EXISTS s2_dictionary_conf (
    id SERIAL PRIMARY KEY,
    description varchar(255),
    type varchar(255) NOT NULL,
    item_id INTEGER NOT NULL,
    config text,
    status varchar(255) NOT NULL,
    created_at timestamp NOT NULL,
    created_by varchar(100) NOT NULL
);

CREATE TABLE IF NOT EXISTS s2_dictionary_task (
    id SERIAL PRIMARY KEY,
    name varchar(255) NOT NULL,
    description varchar(255),
    type varchar(255) NOT NULL,
    item_id INTEGER NOT NULL,
    config text,
    status varchar(255) NOT NULL,
    created_at timestamp DEFAULT CURRENT_TIMESTAMP,
    created_by varchar(100) NOT NULL,
    elapsed_ms integer DEFAULT NULL
);

CREATE TABLE IF NOT EXISTS s2_dimension (
    id SERIAL PRIMARY KEY,
    model_id bigint DEFAULT NULL,
    name varchar(255) NOT NULL,
    biz_name varchar(255) NOT NULL,
    description varchar(500) NOT NULL,
    status smallint NOT NULL,
    sensitive_level integer DEFAULT NULL,
    type varchar(50) NOT NULL,
    type_params text,
    data_type varchar(50) DEFAULT NULL,
    expr text NOT NULL,
    created_at timestamp NOT NULL,
    created_by varchar(100) NOT NULL,
    updated_at timestamp NOT NULL,
    updated_by varchar(100) NOT NULL,
    semantic_type varchar(20) NOT NULL,
    alias varchar(500) DEFAULT NULL,
    default_values varchar(500) DEFAULT NULL,
    dim_value_maps varchar(5000) DEFAULT NULL,
    is_tag smallint DEFAULT NULL,
    ext varchar(1000) DEFAULT NULL
);

CREATE TABLE IF NOT EXISTS s2_domain (
    id SERIAL PRIMARY KEY,
    name varchar(255) DEFAULT NULL,
    biz_name varchar(255) DEFAULT NULL,
    parent_id bigint DEFAULT 0,
    status smallint NOT NULL,
    created_at timestamp DEFAULT NULL,
    created_by varchar(100) DEFAULT NULL,
    updated_at timestamp DEFAULT NULL,
    updated_by varchar(100) DEFAULT NULL,
    admin varchar(3000) DEFAULT NULL,
    admin_org varchar(3000) DEFAULT NULL,
    is_open smallint DEFAULT NULL,
    viewer varchar(3000) DEFAULT NULL,
    view_org varchar(3000) DEFAULT NULL,
    entity varchar(500) DEFAULT NULL
);

CREATE TABLE IF NOT EXISTS s2_metric (
    id SERIAL PRIMARY KEY,
    model_id bigint DEFAULT NULL,
    name varchar(255) NOT NULL,
    biz_name varchar(255) NOT NULL,
    description varchar(500) DEFAULT NULL,
    status smallint NOT NULL,
    sensitive_level smallint NOT NULL,
    type varchar(50) NOT NULL,
    type_params text NOT NULL,
    created_at timestamp NOT NULL,
    created_by varchar(100) NOT NULL,
    updated_at timestamp NOT NULL,
    updated_by varchar(100) NOT NULL,
    data_format_type varchar(50) DEFAULT NULL,
    data_format varchar(500) DEFAULT NULL,
    alias varchar(500) DEFAULT NULL,
    classifications varchar(500) DEFAULT NULL,
    relate_dimensions varchar(500) DEFAULT NULL,
    ext text DEFAULT NULL,
    define_type varchar(50) DEFAULT NULL,
    is_publish smallint DEFAULT NULL
);

CREATE TABLE IF NOT EXISTS s2_model (
    id SERIAL PRIMARY KEY,
    name varchar(100) DEFAULT NULL,
    biz_name varchar(100) DEFAULT NULL,
    domain_id bigint DEFAULT NULL,
    alias varchar(200) DEFAULT NULL,
    status smallint DEFAULT NULL,
    description varchar(500) DEFAULT NULL,
    viewer varchar(500) DEFAULT NULL,
    view_org varchar(500) DEFAULT NULL,
    admin varchar(500) DEFAULT NULL,
    admin_org varchar(500) DEFAULT NULL,
    is_open smallint DEFAULT NULL,
    created_by varchar(100) DEFAULT NULL,
    created_at timestamp DEFAULT NULL,
    updated_by varchar(100) DEFAULT NULL,
    updated_at timestamp DEFAULT NULL,
    entity text,
    drill_down_dimensions TEXT DEFAULT NULL,
    database_id INTEGER NOT NULL,
    model_detail text NOT NULL,
    source_type varchar(128) DEFAULT NULL,
    depends varchar(500) DEFAULT NULL,
    filter_sql varchar(1000) DEFAULT NULL,
    tag_object_id integer DEFAULT 0,
    ext varchar(1000) DEFAULT NULL
);

CREATE TABLE IF NOT EXISTS s2_data_set (
    id SERIAL PRIMARY KEY,
    domain_id bigint,
    name varchar(255),
    biz_name varchar(255),
    description varchar(255),
    status integer,
    alias varchar(255),
    data_set_detail text,
    created_at timestamp,
    created_by varchar(255),
    updated_at timestamp,
    updated_by varchar(255),
    query_config varchar(3000),
    admin varchar(3000) DEFAULT NULL,
    admin_org varchar(3000) DEFAULT NULL
);

CREATE TABLE IF NOT EXISTS s2_tag (
    id SERIAL PRIMARY KEY,
    item_id INTEGER NOT NULL,
    type varchar(255) NOT NULL,
    created_at timestamp NOT NULL,
    created_by varchar(100) NOT NULL,
    updated_at timestamp DEFAULT NULL,
    updated_by varchar(100) DEFAULT NULL,
    ext text DEFAULT NULL
);

CREATE TABLE IF NOT EXISTS s2_tag_object (
    id SERIAL PRIMARY KEY,
    domain_id bigint DEFAULT NULL,
    name varchar(255) NOT NULL,
    biz_name varchar(255) NOT NULL,
    description varchar(500) DEFAULT NULL,
    status smallint NOT NULL DEFAULT 1,
    sensitive_level smallint NOT NULL DEFAULT 0,
    created_at timestamp NOT NULL,
    created_by varchar(100) NOT NULL,
    updated_at timestamp NULL,
    updated_by varchar(100) NULL,
    ext text DEFAULT NULL
);

CREATE TABLE IF NOT EXISTS s2_query_rule (
    id SERIAL PRIMARY KEY,
    data_set_id bigint,
    priority integer NOT NULL DEFAULT 1,
    rule_type varchar(255) NOT NULL,
    name varchar(255) NOT NULL,
    biz_name varchar(255) NOT NULL,
    description varchar(500) DEFAULT NULL,
    rule text DEFAULT NULL,
    action text DEFAULT NULL,
    status INTEGER NOT NULL DEFAULT 1,
    created_at timestamp NOT NULL,
    created_by varchar(100) NOT NULL,
    updated_at timestamp DEFAULT NULL,
    updated_by varchar(100) DEFAULT NULL,
    ext text DEFAULT NULL
);

CREATE TABLE IF NOT EXISTS s2_term (
    id SERIAL PRIMARY KEY,
    domain_id bigint,
    name varchar(255) NOT NULL,
    description varchar(500) DEFAULT NULL,
    alias varchar(1000) NOT NULL,
    related_metrics varchar(1000) DEFAULT NULL,
    related_dimensions varchar(1000) DEFAULT NULL,
    created_at timestamp NOT NULL,
    created_by varchar(100) NOT NULL,
    updated_at timestamp DEFAULT NULL,
    updated_by varchar(100) DEFAULT NULL
);

CREATE TABLE IF NOT EXISTS s2_user_token (
    id SERIAL PRIMARY KEY,
    name VARCHAR(255) NOT NULL,
    user_name VARCHAR(255) NOT NULL,
    expire_time bigint NOT NULL,
    token text NOT NULL,
    salt VARCHAR(255) default NULL,
    create_time TIMESTAMP NOT NULL,
    create_by VARCHAR(255) NOT NULL,
    update_time TIMESTAMP default NULL,
    update_by VARCHAR(255) NOT NULL,
    expire_date_time TIMESTAMP NOT NULL,
    UNIQUE (name, user_name)
);

CREATE TABLE IF NOT EXISTS s2_app (
    id SERIAL PRIMARY KEY,
    name VARCHAR(255),
    description VARCHAR(255),
    status INTEGER,
    config TEXT,
    end_date timestamp,
    qps INTEGER,
    app_secret VARCHAR(255),
    owner VARCHAR(255),
    created_at timestamp NULL,
    updated_at timestamp NULL,
    created_by varchar(255) NULL,
    updated_by varchar(255) NULL
);

CREATE TABLE IF NOT EXISTS s2_plugin (
    id SERIAL PRIMARY KEY,
    type varchar(50) DEFAULT NULL,
    data_set varchar(100) DEFAULT NULL,
    pattern varchar(500) DEFAULT NULL,
    parse_mode varchar(100) DEFAULT NULL,
    parse_mode_config text,
    name varchar(100) DEFAULT NULL,
    created_at timestamp DEFAULT NULL,
    created_by varchar(100) DEFAULT NULL,
    updated_at timestamp DEFAULT NULL,
    updated_by varchar(100) DEFAULT NULL,
    config text,
    comment text
);

CREATE TABLE IF NOT EXISTS s2_query_stat_info (
    id SERIAL PRIMARY KEY,
    trace_id varchar(200) DEFAULT NULL,
    model_id bigint DEFAULT NULL,
    data_set_id bigint DEFAULT NULL,
    query_user varchar(200) DEFAULT NULL,
    created_at timestamp DEFAULT CURRENT_TIMESTAMP,
    query_type varchar(200) DEFAULT NULL,
    query_type_back integer DEFAULT 0,
    query_sql_cmd text,
    sql_cmd_md5 varchar(200) DEFAULT NULL,
    query_struct_cmd text,
    struct_cmd_md5 varchar(200) DEFAULT NULL,
    query_sql text,
    sql_md5 varchar(200) DEFAULT NULL,
    query_engine varchar(20) DEFAULT NULL,
    elapsed_ms bigint DEFAULT NULL,
    query_state varchar(20) DEFAULT NULL,
    native_query boolean DEFAULT false,
    start_date varchar(50) DEFAULT NULL,
    end_date varchar(50) DEFAULT NULL,
    dimensions text,
    metrics text,
    select_cols text,
    agg_cols text,
    filter_cols text,
    group_by_cols text,
    order_by_cols text,
    use_result_cache boolean DEFAULT false,
    use_sql_cache boolean DEFAULT false,
    sql_cache_key text,
    result_cache_key text,
    query_opt_mode varchar(20) DEFAULT NULL
);

CREATE TABLE IF NOT EXISTS s2_canvas (
    id SERIAL PRIMARY KEY,
    domain_id bigint DEFAULT NULL,
    type varchar(20) DEFAULT NULL,
    config text,
    created_at timestamp DEFAULT NULL,
    created_by varchar(100) DEFAULT NULL,
    updated_at timestamp DEFAULT NULL,
    updated_by varchar(100) NOT NULL
);

CREATE TABLE IF NOT EXISTS s2_system_config (
    id SERIAL PRIMARY KEY,
    admin varchar(500),
    parameters text
);

CREATE TABLE IF NOT EXISTS s2_model_rela (
    id SERIAL PRIMARY KEY,
    domain_id bigint,
    from_model_id bigint,
    to_model_id bigint,
    join_type VARCHAR(255),
    join_condition text
);

CREATE TABLE IF NOT EXISTS s2_collect (
    id SERIAL PRIMARY KEY,
    type varchar(20) NOT NULL,
    username varchar(20) NOT NULL,
    collect_id bigint NOT NULL,
    create_time timestamp,
    update_time timestamp
);

CREATE TABLE IF NOT EXISTS s2_metric_query_default_config (
    id SERIAL PRIMARY KEY,
    metric_id bigint,
    user_name varchar(255) NOT NULL,
    default_config varchar(1000) NOT NULL,
    created_at timestamp NULL,
    updated_at timestamp NULL,
    created_by varchar(100) NULL,
    updated_by varchar(100) NULL
);

CREATE TABLE IF NOT EXISTS s2_user (
    id SERIAL PRIMARY KEY,
    name varchar(100) NOT NULL,
    display_name varchar(100) NULL,
    password varchar(256) NULL,
    salt varchar(256) DEFAULT NULL,
    email varchar(100) NULL,
    is_admin smallint NULL,
    last_login timestamp NULL,
    UNIQUE(name)
);
