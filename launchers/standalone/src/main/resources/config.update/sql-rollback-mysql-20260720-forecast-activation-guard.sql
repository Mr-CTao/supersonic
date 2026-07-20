-- 20260720 Forecast 首次同步激活并发保护回滚（MySQL 8，forecast_meta 2 -> 1）。
SET @forecast_activation_ddl = IF(
    EXISTS(SELECT 1 FROM information_schema.statistics
           WHERE table_schema = DATABASE() AND table_name = 's2_forecast_job'
             AND index_name = 'idx_forecast_job_stream_activation'),
    'DROP INDEX `idx_forecast_job_stream_activation` ON `s2_forecast_job`',
    'DO 0'
);
PREPARE forecast_activation_stmt FROM @forecast_activation_ddl;
EXECUTE forecast_activation_stmt;
DEALLOCATE PREPARE forecast_activation_stmt;

SET @forecast_activation_ddl = IF(
    EXISTS(SELECT 1 FROM information_schema.statistics
           WHERE table_schema = DATABASE() AND table_name = 's2_forecast_job'
             AND index_name = 'uk_forecast_job_active_concurrency'),
    'DROP INDEX `uk_forecast_job_active_concurrency` ON `s2_forecast_job`',
    'DO 0'
);
PREPARE forecast_activation_stmt FROM @forecast_activation_ddl;
EXECUTE forecast_activation_stmt;
DEALLOCATE PREPARE forecast_activation_stmt;

SET @forecast_activation_ddl = IF(
    EXISTS(SELECT 1 FROM information_schema.columns
           WHERE table_schema = DATABASE() AND table_name = 's2_forecast_job'
             AND column_name = 'active_concurrency_key'),
    'ALTER TABLE `s2_forecast_job` DROP COLUMN `active_concurrency_key`',
    'DO 0'
);
PREPARE forecast_activation_stmt FROM @forecast_activation_ddl;
EXECUTE forecast_activation_stmt;
DEALLOCATE PREPARE forecast_activation_stmt;
SET @forecast_activation_ddl = NULL;

UPDATE `s2_forecast_schema_version`
SET `version` = 1, `installed_at` = CURRENT_TIMESTAMP(3)
WHERE `component` = 'forecast_meta' AND `version` = 2;
