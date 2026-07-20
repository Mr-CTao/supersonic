-- 20260720 Forecast 首次同步激活并发保护回滚（PostgreSQL，forecast_meta 2 -> 1）。
DROP INDEX IF EXISTS idx_forecast_job_stream_activation;
DROP INDEX IF EXISTS uk_forecast_job_active_concurrency;
ALTER TABLE s2_forecast_job DROP COLUMN IF EXISTS active_concurrency_key;

UPDATE s2_forecast_schema_version
SET version = 1, installed_at = CURRENT_TIMESTAMP
WHERE component = 'forecast_meta' AND version = 2;
