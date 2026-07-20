-- 20260720 Forecast 首次同步激活并发保护（H2，forecast_meta 1 -> 2）。
-- 可空唯一键只由非终态首次同步持有；历史任务保留 NULL，升级不会取消或删除现场任务。
ALTER TABLE s2_forecast_job
    ADD COLUMN IF NOT EXISTS active_concurrency_key VARCHAR(160) DEFAULT NULL;

CREATE UNIQUE INDEX IF NOT EXISTS uk_forecast_job_active_concurrency
    ON s2_forecast_job(active_concurrency_key);
CREATE INDEX IF NOT EXISTS idx_forecast_job_stream_activation
    ON s2_forecast_job(stream_id, job_type, id);

-- 版本标记最后推进，保证任何 DDL 失败时启动门禁仍会拒绝版本 1。
UPDATE s2_forecast_schema_version
SET version = 2, installed_at = CURRENT_TIMESTAMP
WHERE component = 'forecast_meta' AND version = 1;
