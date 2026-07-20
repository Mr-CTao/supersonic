-- 20260720 Forecast 首次同步激活并发保护（MySQL 8，forecast_meta 1 -> 2）。
-- 动态 DDL 允许脚本在部分升级现场安全重放；历史任务保留 NULL，不取消或删除现场任务。
SET @forecast_activation_ddl = IF(
    EXISTS(SELECT 1 FROM information_schema.columns
           WHERE table_schema = DATABASE() AND table_name = 's2_forecast_job'
             AND column_name = 'active_concurrency_key'),
    'DO 0',
    'ALTER TABLE `s2_forecast_job` ADD COLUMN `active_concurrency_key` VARCHAR(160) NULL AFTER `status`'
);
PREPARE forecast_activation_stmt FROM @forecast_activation_ddl;
EXECUTE forecast_activation_stmt;
DEALLOCATE PREPARE forecast_activation_stmt;

SET @forecast_activation_ddl = IF(
    EXISTS(SELECT 1 FROM information_schema.statistics
           WHERE table_schema = DATABASE() AND table_name = 's2_forecast_job'
             AND index_name = 'uk_forecast_job_active_concurrency'),
    'DO 0',
    'CREATE UNIQUE INDEX `uk_forecast_job_active_concurrency` ON `s2_forecast_job` (`active_concurrency_key`)'
);
PREPARE forecast_activation_stmt FROM @forecast_activation_ddl;
EXECUTE forecast_activation_stmt;
DEALLOCATE PREPARE forecast_activation_stmt;

SET @forecast_activation_ddl = IF(
    EXISTS(SELECT 1 FROM information_schema.statistics
           WHERE table_schema = DATABASE() AND table_name = 's2_forecast_job'
             AND index_name = 'idx_forecast_job_stream_activation'),
    'DO 0',
    'CREATE INDEX `idx_forecast_job_stream_activation` ON `s2_forecast_job` (`stream_id`, `job_type`, `id`)'
);
PREPARE forecast_activation_stmt FROM @forecast_activation_ddl;
EXECUTE forecast_activation_stmt;
DEALLOCATE PREPARE forecast_activation_stmt;
SET @forecast_activation_ddl = NULL;

-- 版本标记最后推进，保证任何 DDL 失败时启动门禁仍会拒绝版本 1。
UPDATE `s2_forecast_schema_version`
SET `version` = 2, `installed_at` = CURRENT_TIMESTAMP(3)
WHERE `component` = 'forecast_meta' AND `version` = 1;
