-- 20260718 Forecast MVP 元数据库正向迁移（PostgreSQL）。
CREATE TABLE IF NOT EXISTS s2_forecast_profile (
    id BIGSERIAL PRIMARY KEY, name VARCHAR(255) NOT NULL, source_database_id BIGINT NOT NULL,
    decision_database_id BIGINT NOT NULL, time_zone VARCHAR(64) NOT NULL,
    sync_cron VARCHAR(64) NOT NULL, forecast_cron VARCHAR(64) NOT NULL,
    reconcile_cron VARCHAR(64) NOT NULL, history_days INTEGER NOT NULL,
    enabled BOOLEAN NOT NULL DEFAULT TRUE, deleted BOOLEAN NOT NULL DEFAULT FALSE,
    lock_version INTEGER NOT NULL DEFAULT 0, last_sync_at TIMESTAMP NULL,
    last_forecast_at TIMESTAMP NULL, created_by VARCHAR(100) NOT NULL,
    created_at TIMESTAMP NOT NULL, updated_by VARCHAR(100) NOT NULL, updated_at TIMESTAMP NOT NULL
);
CREATE INDEX IF NOT EXISTS idx_forecast_profile_source ON s2_forecast_profile(source_database_id, deleted);

CREATE TABLE IF NOT EXISTS s2_forecast_stream (
    id BIGSERIAL PRIMARY KEY, profile_id BIGINT NOT NULL, name VARCHAR(255) NOT NULL,
    enabled BOOLEAN NOT NULL DEFAULT TRUE, deleted BOOLEAN NOT NULL DEFAULT FALSE,
    active_mapping_id BIGINT NULL, active_mapping_version INTEGER NULL,
    lock_version INTEGER NOT NULL DEFAULT 0, last_sync_at TIMESTAMP NULL,
    created_by VARCHAR(100) NOT NULL, created_at TIMESTAMP NOT NULL,
    updated_by VARCHAR(100) NOT NULL, updated_at TIMESTAMP NOT NULL
);
CREATE INDEX IF NOT EXISTS idx_forecast_stream_profile ON s2_forecast_stream(profile_id, deleted, id);

CREATE TABLE IF NOT EXISTS s2_forecast_mapping (
    id BIGSERIAL PRIMARY KEY, stream_id BIGINT NOT NULL, mapping_version INTEGER NOT NULL,
    status VARCHAR(32) NOT NULL, config_json TEXT NOT NULL, config_checksum VARCHAR(64) NOT NULL,
    valid BOOLEAN NOT NULL DEFAULT FALSE, validation_summary TEXT NULL,
    created_by VARCHAR(100) NOT NULL, created_at TIMESTAMP NOT NULL, published_at TIMESTAMP NULL,
    UNIQUE(stream_id, mapping_version)
);

CREATE TABLE IF NOT EXISTS s2_forecast_job (
    id BIGSERIAL PRIMARY KEY, parent_job_id BIGINT NULL, profile_id BIGINT NOT NULL,
    stream_id BIGINT NULL, mapping_id BIGINT NULL, job_type VARCHAR(32) NOT NULL,
    status VARCHAR(32) NOT NULL, active_concurrency_key VARCHAR(160) NULL,
    idempotency_key VARCHAR(128) NOT NULL,
    request_fingerprint VARCHAR(64) NOT NULL, parameters_json TEXT NOT NULL,
    progress_percent INTEGER NOT NULL DEFAULT 0, rows_read BIGINT NOT NULL DEFAULT 0,
    rows_written BIGINT NOT NULL DEFAULT 0, rows_aggregated BIGINT NOT NULL DEFAULT 0,
    checkpoint_updated_at TIMESTAMP NULL, checkpoint_record_id VARCHAR(512) NULL,
    retry_count INTEGER NOT NULL DEFAULT 0, max_retries INTEGER NOT NULL DEFAULT 3,
    worker_id VARCHAR(128) NULL, lease_expires_at TIMESTAMP NULL, heartbeat_at TIMESTAMP NULL,
    error_code VARCHAR(64) NULL, error_message VARCHAR(1000) NULL,
    lock_version INTEGER NOT NULL DEFAULT 0, created_by VARCHAR(100) NOT NULL,
    created_at TIMESTAMP NOT NULL, started_at TIMESTAMP NULL, finished_at TIMESTAMP NULL,
    updated_at TIMESTAMP NOT NULL, UNIQUE(created_by, idempotency_key)
);
CREATE INDEX IF NOT EXISTS idx_forecast_job_queue ON s2_forecast_job(status, created_at, id);
CREATE INDEX IF NOT EXISTS idx_forecast_job_profile ON s2_forecast_job(profile_id, created_at, id);
CREATE UNIQUE INDEX IF NOT EXISTS uk_forecast_job_active_concurrency
    ON s2_forecast_job(active_concurrency_key);
CREATE INDEX IF NOT EXISTS idx_forecast_job_stream_activation
    ON s2_forecast_job(stream_id, job_type, id);

CREATE TABLE IF NOT EXISTS s2_forecast_watermark (
    id BIGSERIAL PRIMARY KEY, stream_id BIGINT NOT NULL, mapping_id BIGINT NOT NULL,
    watermark_updated_at TIMESTAMP NULL, watermark_record_id VARCHAR(512) NULL,
    checkpoint_updated_at TIMESTAMP NULL, checkpoint_record_id VARCHAR(512) NULL,
    last_batch_id VARCHAR(64) NULL, last_success_at TIMESTAMP NULL,
    lock_version INTEGER NOT NULL DEFAULT 0, created_at TIMESTAMP NOT NULL,
    updated_at TIMESTAMP NOT NULL, UNIQUE(stream_id, mapping_id)
);

CREATE TABLE IF NOT EXISTS s2_forecast_worker_node (
    worker_id VARCHAR(128) PRIMARY KEY, worker_version VARCHAR(64) NOT NULL,
    active_jobs INTEGER NOT NULL DEFAULT 0, started_at TIMESTAMP NOT NULL,
    heartbeat_at TIMESTAMP NOT NULL
);
CREATE INDEX IF NOT EXISTS idx_forecast_worker_heartbeat ON s2_forecast_worker_node(heartbeat_at);

CREATE TABLE IF NOT EXISTS s2_forecast_resource_lease (
    lease_key VARCHAR(160) PRIMARY KEY, owner_job_id BIGINT NULL, worker_id VARCHAR(128) NULL,
    lease_expires_at TIMESTAMP NOT NULL, lock_version INTEGER NOT NULL DEFAULT 0,
    updated_at TIMESTAMP NOT NULL
);

-- 版本标记必须最后写入，且重复执行旧脚本不得覆盖未来更高版本。
CREATE TABLE IF NOT EXISTS s2_forecast_schema_version (
    component VARCHAR(64) PRIMARY KEY,
    version INTEGER NOT NULL,
    installed_at TIMESTAMP NOT NULL
);
INSERT INTO s2_forecast_schema_version (component, version, installed_at)
VALUES ('forecast_meta', 2, CURRENT_TIMESTAMP)
ON CONFLICT (component) DO NOTHING;
