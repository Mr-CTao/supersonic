-- 20260718 Forecast MVP 元数据库正向迁移（H2）。
CREATE TABLE IF NOT EXISTS s2_forecast_profile (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    name VARCHAR(255) NOT NULL,
    source_database_id BIGINT NOT NULL,
    decision_database_id BIGINT NOT NULL,
    time_zone VARCHAR(64) NOT NULL,
    sync_cron VARCHAR(64) NOT NULL,
    forecast_cron VARCHAR(64) NOT NULL,
    reconcile_cron VARCHAR(64) NOT NULL,
    history_days INT NOT NULL,
    enabled BOOLEAN NOT NULL DEFAULT TRUE,
    deleted BOOLEAN NOT NULL DEFAULT FALSE,
    lock_version INT NOT NULL DEFAULT 0,
    last_sync_at TIMESTAMP DEFAULT NULL,
    last_forecast_at TIMESTAMP DEFAULT NULL,
    created_by VARCHAR(100) NOT NULL,
    created_at TIMESTAMP NOT NULL,
    updated_by VARCHAR(100) NOT NULL,
    updated_at TIMESTAMP NOT NULL
);
CREATE INDEX IF NOT EXISTS idx_forecast_profile_source ON s2_forecast_profile(source_database_id, deleted);

CREATE TABLE IF NOT EXISTS s2_forecast_stream (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    profile_id BIGINT NOT NULL,
    name VARCHAR(255) NOT NULL,
    enabled BOOLEAN NOT NULL DEFAULT TRUE,
    deleted BOOLEAN NOT NULL DEFAULT FALSE,
    active_mapping_id BIGINT DEFAULT NULL,
    active_mapping_version INT DEFAULT NULL,
    lock_version INT NOT NULL DEFAULT 0,
    last_sync_at TIMESTAMP DEFAULT NULL,
    created_by VARCHAR(100) NOT NULL,
    created_at TIMESTAMP NOT NULL,
    updated_by VARCHAR(100) NOT NULL,
    updated_at TIMESTAMP NOT NULL
);
CREATE INDEX IF NOT EXISTS idx_forecast_stream_profile ON s2_forecast_stream(profile_id, deleted, id);

CREATE TABLE IF NOT EXISTS s2_forecast_mapping (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    stream_id BIGINT NOT NULL,
    mapping_version INT NOT NULL,
    status VARCHAR(32) NOT NULL,
    config_json CLOB NOT NULL,
    config_checksum VARCHAR(64) NOT NULL,
    valid BOOLEAN NOT NULL DEFAULT FALSE,
    validation_summary CLOB DEFAULT NULL,
    created_by VARCHAR(100) NOT NULL,
    created_at TIMESTAMP NOT NULL,
    published_at TIMESTAMP DEFAULT NULL
);
CREATE UNIQUE INDEX IF NOT EXISTS uk_forecast_mapping_version ON s2_forecast_mapping(stream_id, mapping_version);

CREATE TABLE IF NOT EXISTS s2_forecast_job (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    parent_job_id BIGINT DEFAULT NULL,
    profile_id BIGINT NOT NULL,
    stream_id BIGINT DEFAULT NULL,
    mapping_id BIGINT DEFAULT NULL,
    job_type VARCHAR(32) NOT NULL,
    status VARCHAR(32) NOT NULL,
    active_concurrency_key VARCHAR(160) DEFAULT NULL,
    idempotency_key VARCHAR(128) NOT NULL,
    request_fingerprint VARCHAR(64) NOT NULL,
    parameters_json CLOB NOT NULL,
    progress_percent INT NOT NULL DEFAULT 0,
    rows_read BIGINT NOT NULL DEFAULT 0,
    rows_written BIGINT NOT NULL DEFAULT 0,
    rows_aggregated BIGINT NOT NULL DEFAULT 0,
    checkpoint_updated_at TIMESTAMP DEFAULT NULL,
    checkpoint_record_id VARCHAR(512) DEFAULT NULL,
    retry_count INT NOT NULL DEFAULT 0,
    max_retries INT NOT NULL DEFAULT 3,
    worker_id VARCHAR(128) DEFAULT NULL,
    lease_expires_at TIMESTAMP DEFAULT NULL,
    heartbeat_at TIMESTAMP DEFAULT NULL,
    error_code VARCHAR(64) DEFAULT NULL,
    error_message VARCHAR(1000) DEFAULT NULL,
    lock_version INT NOT NULL DEFAULT 0,
    created_by VARCHAR(100) NOT NULL,
    created_at TIMESTAMP NOT NULL,
    started_at TIMESTAMP DEFAULT NULL,
    finished_at TIMESTAMP DEFAULT NULL,
    updated_at TIMESTAMP NOT NULL
);
CREATE UNIQUE INDEX IF NOT EXISTS uk_forecast_job_idempotency ON s2_forecast_job(created_by, idempotency_key);
CREATE UNIQUE INDEX IF NOT EXISTS uk_forecast_job_active_concurrency ON s2_forecast_job(active_concurrency_key);
CREATE INDEX IF NOT EXISTS idx_forecast_job_queue ON s2_forecast_job(status, created_at, id);
CREATE INDEX IF NOT EXISTS idx_forecast_job_profile ON s2_forecast_job(profile_id, created_at, id);
CREATE INDEX IF NOT EXISTS idx_forecast_job_stream_activation ON s2_forecast_job(stream_id, job_type, id);

CREATE TABLE IF NOT EXISTS s2_forecast_watermark (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    stream_id BIGINT NOT NULL,
    mapping_id BIGINT NOT NULL,
    watermark_updated_at TIMESTAMP DEFAULT NULL,
    watermark_record_id VARCHAR(512) DEFAULT NULL,
    checkpoint_updated_at TIMESTAMP DEFAULT NULL,
    checkpoint_record_id VARCHAR(512) DEFAULT NULL,
    last_batch_id VARCHAR(64) DEFAULT NULL,
    last_success_at TIMESTAMP DEFAULT NULL,
    lock_version INT NOT NULL DEFAULT 0,
    created_at TIMESTAMP NOT NULL,
    updated_at TIMESTAMP NOT NULL
);
CREATE UNIQUE INDEX IF NOT EXISTS uk_forecast_watermark ON s2_forecast_watermark(stream_id, mapping_id);

CREATE TABLE IF NOT EXISTS s2_forecast_worker_node (
    worker_id VARCHAR(128) PRIMARY KEY,
    worker_version VARCHAR(64) NOT NULL,
    active_jobs INT NOT NULL DEFAULT 0,
    started_at TIMESTAMP NOT NULL,
    heartbeat_at TIMESTAMP NOT NULL
);
CREATE INDEX IF NOT EXISTS idx_forecast_worker_heartbeat ON s2_forecast_worker_node(heartbeat_at);

CREATE TABLE IF NOT EXISTS s2_forecast_resource_lease (
    lease_key VARCHAR(160) PRIMARY KEY,
    owner_job_id BIGINT DEFAULT NULL,
    worker_id VARCHAR(128) DEFAULT NULL,
    lease_expires_at TIMESTAMP NOT NULL,
    lock_version INT NOT NULL DEFAULT 0,
    updated_at TIMESTAMP NOT NULL
);

-- 版本标记必须最后写入；若前序 DDL 失败，应用启动门禁仍会拒绝进入就绪态。
CREATE TABLE IF NOT EXISTS s2_forecast_schema_version (
    component VARCHAR(64) PRIMARY KEY,
    version INT NOT NULL,
    installed_at TIMESTAMP NOT NULL
);
INSERT INTO s2_forecast_schema_version (component, version, installed_at)
SELECT 'forecast_meta', 2, CURRENT_TIMESTAMP
WHERE NOT EXISTS (
    SELECT 1 FROM s2_forecast_schema_version WHERE component = 'forecast_meta'
);
