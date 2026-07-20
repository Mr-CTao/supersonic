-- Forecast 决策库 V2 回滚：先恢复 V1 视图，再删除发布指针。
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

DROP TABLE IF EXISTS forecast.forecast_publication;
DELETE FROM forecast.forecast_schema_version WHERE version = 2;
