-- Forecast 决策库 V2：使用 Profile 级发布指针原子切换一整批预测结果。
CREATE TABLE IF NOT EXISTS forecast.forecast_publication (
    profile_id BIGINT PRIMARY KEY,
    job_id BIGINT NOT NULL,
    published_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);

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
    JOIN forecast.forecast_publication p
      ON p.profile_id = m.profile_id
     AND p.job_id = m.job_id
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

COMMENT ON VIEW forecast.v_forecast_latest_result IS
    'WMS出入库预测：Profile 当前已发布任务的一致性三十天预测';

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

-- 版本标记最后写入；此前任一步失败时 Worker 仍会因版本不足而停止接单。
INSERT INTO forecast.forecast_schema_version(version)
VALUES (2)
ON CONFLICT (version) DO NOTHING;
