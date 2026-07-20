-- Forecast MVP PostgreSQL 决策库正向脚本。
-- 若 forecast.decision.schema 使用非默认值，请在受控发布流程中替换所有 forecast Schema 引用。
CREATE SCHEMA IF NOT EXISTS forecast;

CREATE TABLE IF NOT EXISTS forecast.forecast_schema_version (
    version INTEGER PRIMARY KEY,
    installed_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);

INSERT INTO forecast.forecast_schema_version(version)
VALUES (1)
ON CONFLICT (version) DO NOTHING;

CREATE TABLE IF NOT EXISTS forecast.sync_batch (
    batch_id VARCHAR(64) PRIMARY KEY,
    job_id BIGINT NOT NULL,
    profile_id BIGINT NOT NULL,
    stream_id BIGINT NOT NULL,
    mapping_id BIGINT NOT NULL,
    status VARCHAR(24) NOT NULL,
    rows_read BIGINT NOT NULL DEFAULT 0,
    rows_written BIGINT NOT NULL DEFAULT 0,
    rows_aggregated BIGINT NOT NULL DEFAULT 0,
    started_at TIMESTAMPTZ NOT NULL,
    completed_at TIMESTAMPTZ
);

CREATE INDEX IF NOT EXISTS idx_forecast_sync_batch_job
    ON forecast.sync_batch(job_id, started_at DESC);

CREATE TABLE IF NOT EXISTS forecast.event_stage (
    batch_id VARCHAR(64) NOT NULL,
    profile_id BIGINT NOT NULL,
    stream_id BIGINT NOT NULL,
    mapping_id BIGINT NOT NULL,
    source_record_id VARCHAR(512) NOT NULL,
    task_id VARCHAR(512),
    direction VARCHAR(16) NOT NULL,
    warehouse_code VARCHAR(255) NOT NULL,
    quantity NUMERIC(28, 8) NOT NULL,
    occurred_at TIMESTAMPTZ NOT NULL,
    business_date DATE NOT NULL,
    source_status VARCHAR(255),
    canonical_status VARCHAR(24) NOT NULL,
    source_updated_at TIMESTAMPTZ,
    deleted BOOLEAN NOT NULL DEFAULT FALSE,
    PRIMARY KEY(batch_id, source_record_id)
);

CREATE TABLE IF NOT EXISTS forecast.flow_event (
    profile_id BIGINT NOT NULL,
    stream_id BIGINT NOT NULL,
    mapping_id BIGINT NOT NULL,
    source_record_id VARCHAR(512) NOT NULL,
    task_id VARCHAR(512),
    direction VARCHAR(16) NOT NULL,
    warehouse_code VARCHAR(255) NOT NULL,
    quantity NUMERIC(28, 8) NOT NULL,
    occurred_at TIMESTAMPTZ NOT NULL,
    business_date DATE NOT NULL,
    source_status VARCHAR(255),
    canonical_status VARCHAR(24) NOT NULL,
    source_updated_at TIMESTAMPTZ,
    deleted BOOLEAN NOT NULL DEFAULT FALSE,
    ingested_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY(profile_id, stream_id, mapping_id, source_record_id)
);

CREATE INDEX IF NOT EXISTS idx_forecast_flow_event_daily
    ON forecast.flow_event(profile_id, stream_id, mapping_id, business_date, warehouse_code, direction);
CREATE INDEX IF NOT EXISTS idx_forecast_flow_event_updated
    ON forecast.flow_event(profile_id, stream_id, mapping_id, source_updated_at, source_record_id);

CREATE TABLE IF NOT EXISTS forecast.aggregate_dirty (
    batch_id VARCHAR(64) NOT NULL,
    profile_id BIGINT NOT NULL,
    stream_id BIGINT NOT NULL,
    mapping_id BIGINT NOT NULL,
    business_date DATE NOT NULL,
    warehouse_code VARCHAR(255) NOT NULL,
    direction VARCHAR(16) NOT NULL,
    PRIMARY KEY(batch_id, profile_id, stream_id, mapping_id, business_date, warehouse_code, direction)
);

CREATE TABLE IF NOT EXISTS forecast.daily_fact (
    profile_id BIGINT NOT NULL,
    stream_id BIGINT NOT NULL,
    mapping_id BIGINT NOT NULL,
    business_date DATE NOT NULL,
    warehouse_code VARCHAR(255) NOT NULL,
    direction VARCHAR(16) NOT NULL,
    quantity_sum NUMERIC(28, 8) NOT NULL DEFAULT 0,
    task_count BIGINT NOT NULL DEFAULT 0,
    updated_at TIMESTAMPTZ NOT NULL,
    PRIMARY KEY(profile_id, stream_id, mapping_id, business_date, warehouse_code, direction)
);

CREATE INDEX IF NOT EXISTS idx_forecast_daily_profile_date
    ON forecast.daily_fact(profile_id, business_date, warehouse_code, direction);

CREATE TABLE IF NOT EXISTS forecast.stream_activation (
    profile_id BIGINT NOT NULL,
    stream_id BIGINT NOT NULL,
    mapping_id BIGINT NOT NULL,
    activated_at TIMESTAMPTZ NOT NULL,
    PRIMARY KEY(profile_id, stream_id)
);

CREATE TABLE IF NOT EXISTS forecast.model_run (
    id BIGSERIAL PRIMARY KEY,
    job_id BIGINT NOT NULL,
    profile_id BIGINT NOT NULL,
    warehouse_code VARCHAR(255) NOT NULL,
    direction VARCHAR(16) NOT NULL,
    metric VARCHAR(24) NOT NULL,
    algorithm VARCHAR(64),
    data_status VARCHAR(32) NOT NULL,
    training_start DATE NOT NULL,
    training_end DATE NOT NULL,
    training_size INTEGER NOT NULL,
    validation_size INTEGER NOT NULL,
    wape NUMERIC(18, 8),
    mae NUMERIC(28, 8),
    bias NUMERIC(18, 8),
    created_at TIMESTAMPTZ NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_forecast_model_latest
    ON forecast.model_run(profile_id, warehouse_code, direction, metric, created_at DESC, id DESC);

CREATE TABLE IF NOT EXISTS forecast.forecast_result (
    model_run_id BIGINT NOT NULL REFERENCES forecast.model_run(id) ON DELETE CASCADE,
    profile_id BIGINT NOT NULL,
    warehouse_code VARCHAR(255) NOT NULL,
    direction VARCHAR(16) NOT NULL,
    metric VARCHAR(24) NOT NULL,
    forecast_date DATE NOT NULL,
    horizon_day INTEGER NOT NULL,
    forecast_value NUMERIC(28, 8) NOT NULL,
    lower_value NUMERIC(28, 8),
    upper_value NUMERIC(28, 8),
    created_at TIMESTAMPTZ NOT NULL,
    PRIMARY KEY(model_run_id, forecast_date)
);

CREATE INDEX IF NOT EXISTS idx_forecast_result_profile_date
    ON forecast.forecast_result(profile_id, metric, forecast_date, warehouse_code, direction);

CREATE OR REPLACE VIEW forecast.v_forecast_daily_fact AS
SELECT f.profile_id,
       f.stream_id,
       f.mapping_id,
       f.business_date,
       f.warehouse_code,
       f.direction,
       f.quantity_sum,
       f.task_count,
       f.updated_at
FROM forecast.daily_fact f
JOIN forecast.stream_activation a
  ON a.profile_id = f.profile_id
 AND a.stream_id = f.stream_id
 AND a.mapping_id = f.mapping_id;

CREATE OR REPLACE VIEW forecast.v_forecast_latest_result AS
WITH ranked_models AS (
    SELECT m.id,
           m.profile_id,
           m.warehouse_code,
           m.direction,
           m.metric,
           m.algorithm,
           m.data_status,
           m.wape,
           m.mae,
           m.bias,
           m.created_at,
           ROW_NUMBER() OVER (
               PARTITION BY m.profile_id, m.warehouse_code, m.direction, m.metric
               ORDER BY m.created_at DESC, m.id DESC
           ) AS rn
    FROM forecast.model_run m
)
SELECT r.model_run_id,
       r.profile_id,
       r.warehouse_code,
       r.direction,
       r.metric,
       r.forecast_date,
       r.horizon_day,
       r.forecast_value,
       r.lower_value,
       r.upper_value,
       m.algorithm,
       m.data_status,
       m.wape,
       m.mae,
       m.bias,
       m.created_at AS model_created_at
FROM forecast.forecast_result r
JOIN ranked_models m ON m.id = r.model_run_id AND m.rn = 1;

CREATE OR REPLACE VIEW forecast.v_forecast_overview AS
SELECT profile_id,
       forecast_date,
       metric,
       direction,
       SUM(forecast_value) AS forecast_value,
       CASE WHEN COUNT(lower_value) = COUNT(*) THEN SUM(lower_value) END AS lower_value,
       CASE WHEN COUNT(upper_value) = COUNT(*) THEN SUM(upper_value) END AS upper_value
FROM forecast.v_forecast_latest_result
GROUP BY profile_id, forecast_date, metric, direction;

COMMENT ON VIEW forecast.v_forecast_daily_fact IS 'WMS出入库预测：当前激活映射的日实际事实';
COMMENT ON VIEW forecast.v_forecast_latest_result IS 'WMS出入库预测：每个仓库方向指标的最新三十天预测';
COMMENT ON VIEW forecast.v_forecast_overview IS 'WMS出入库预测：稳定看板服务视图';
