-- Forecast MVP 元数据库回滚（H2）。执行前须停止 Worker；决策库使用单独 U1 脚本回滚。
DROP TABLE IF EXISTS s2_forecast_schema_version;
DROP TABLE IF EXISTS s2_forecast_resource_lease;
DROP TABLE IF EXISTS s2_forecast_worker_node;
DROP TABLE IF EXISTS s2_forecast_watermark;
DROP TABLE IF EXISTS s2_forecast_job;
DROP TABLE IF EXISTS s2_forecast_mapping;
DROP TABLE IF EXISTS s2_forecast_stream;
DROP TABLE IF EXISTS s2_forecast_profile;
