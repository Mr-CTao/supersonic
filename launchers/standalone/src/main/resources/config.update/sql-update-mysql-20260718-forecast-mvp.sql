-- 20260718 Forecast MVP 元数据库正向迁移（MySQL 8）。
CREATE TABLE IF NOT EXISTS `s2_forecast_profile` (
    `id` BIGINT NOT NULL AUTO_INCREMENT, `name` VARCHAR(255) NOT NULL,
    `source_database_id` BIGINT NOT NULL, `decision_database_id` BIGINT NOT NULL,
    `time_zone` VARCHAR(64) NOT NULL, `sync_cron` VARCHAR(64) NOT NULL,
    `forecast_cron` VARCHAR(64) NOT NULL, `reconcile_cron` VARCHAR(64) NOT NULL,
    `history_days` INT NOT NULL, `enabled` TINYINT NOT NULL DEFAULT 1,
    `deleted` TINYINT NOT NULL DEFAULT 0, `lock_version` INT NOT NULL DEFAULT 0,
    `last_sync_at` DATETIME(3) NULL, `last_forecast_at` DATETIME(3) NULL,
    `created_by` VARCHAR(100) NOT NULL, `created_at` DATETIME(3) NOT NULL,
    `updated_by` VARCHAR(100) NOT NULL, `updated_at` DATETIME(3) NOT NULL,
    PRIMARY KEY (`id`), KEY `idx_forecast_profile_source` (`source_database_id`, `deleted`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS `s2_forecast_stream` (
    `id` BIGINT NOT NULL AUTO_INCREMENT, `profile_id` BIGINT NOT NULL,
    `name` VARCHAR(255) NOT NULL, `enabled` TINYINT NOT NULL DEFAULT 1,
    `deleted` TINYINT NOT NULL DEFAULT 0, `active_mapping_id` BIGINT NULL,
    `active_mapping_version` INT NULL, `lock_version` INT NOT NULL DEFAULT 0,
    `last_sync_at` DATETIME(3) NULL, `created_by` VARCHAR(100) NOT NULL,
    `created_at` DATETIME(3) NOT NULL, `updated_by` VARCHAR(100) NOT NULL,
    `updated_at` DATETIME(3) NOT NULL, PRIMARY KEY (`id`),
    KEY `idx_forecast_stream_profile` (`profile_id`, `deleted`, `id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS `s2_forecast_mapping` (
    `id` BIGINT NOT NULL AUTO_INCREMENT, `stream_id` BIGINT NOT NULL,
    `mapping_version` INT NOT NULL, `status` VARCHAR(32) NOT NULL,
    `config_json` LONGTEXT NOT NULL, `config_checksum` VARCHAR(64) NOT NULL,
    `valid` TINYINT NOT NULL DEFAULT 0, `validation_summary` TEXT NULL,
    `created_by` VARCHAR(100) NOT NULL, `created_at` DATETIME(3) NOT NULL,
    `published_at` DATETIME(3) NULL, PRIMARY KEY (`id`),
    UNIQUE KEY `uk_forecast_mapping_version` (`stream_id`, `mapping_version`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS `s2_forecast_job` (
    `id` BIGINT NOT NULL AUTO_INCREMENT, `parent_job_id` BIGINT NULL,
    `profile_id` BIGINT NOT NULL, `stream_id` BIGINT NULL, `mapping_id` BIGINT NULL,
    `job_type` VARCHAR(32) NOT NULL, `status` VARCHAR(32) NOT NULL,
    `active_concurrency_key` VARCHAR(160) NULL,
    `idempotency_key` VARCHAR(128) NOT NULL, `request_fingerprint` VARCHAR(64) NOT NULL,
    `parameters_json` LONGTEXT NOT NULL, `progress_percent` INT NOT NULL DEFAULT 0,
    `rows_read` BIGINT NOT NULL DEFAULT 0, `rows_written` BIGINT NOT NULL DEFAULT 0,
    `rows_aggregated` BIGINT NOT NULL DEFAULT 0, `checkpoint_updated_at` DATETIME(3) NULL,
    `checkpoint_record_id` VARCHAR(512) NULL, `retry_count` INT NOT NULL DEFAULT 0,
    `max_retries` INT NOT NULL DEFAULT 3, `worker_id` VARCHAR(128) NULL,
    `lease_expires_at` DATETIME(3) NULL, `heartbeat_at` DATETIME(3) NULL,
    `error_code` VARCHAR(64) NULL, `error_message` VARCHAR(1000) NULL,
    `lock_version` INT NOT NULL DEFAULT 0, `created_by` VARCHAR(100) NOT NULL,
    `created_at` DATETIME(3) NOT NULL, `started_at` DATETIME(3) NULL,
    `finished_at` DATETIME(3) NULL, `updated_at` DATETIME(3) NOT NULL,
    PRIMARY KEY (`id`), UNIQUE KEY `uk_forecast_job_idempotency` (`created_by`, `idempotency_key`),
    UNIQUE KEY `uk_forecast_job_active_concurrency` (`active_concurrency_key`),
    KEY `idx_forecast_job_queue` (`status`, `created_at`, `id`),
    KEY `idx_forecast_job_profile` (`profile_id`, `created_at`, `id`),
    KEY `idx_forecast_job_stream_activation` (`stream_id`, `job_type`, `id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS `s2_forecast_watermark` (
    `id` BIGINT NOT NULL AUTO_INCREMENT, `stream_id` BIGINT NOT NULL,
    `mapping_id` BIGINT NOT NULL, `watermark_updated_at` DATETIME(3) NULL,
    `watermark_record_id` VARCHAR(512) NULL, `checkpoint_updated_at` DATETIME(3) NULL,
    `checkpoint_record_id` VARCHAR(512) NULL, `last_batch_id` VARCHAR(64) NULL,
    `last_success_at` DATETIME(3) NULL, `lock_version` INT NOT NULL DEFAULT 0,
    `created_at` DATETIME(3) NOT NULL, `updated_at` DATETIME(3) NOT NULL,
    PRIMARY KEY (`id`), UNIQUE KEY `uk_forecast_watermark` (`stream_id`, `mapping_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS `s2_forecast_worker_node` (
    `worker_id` VARCHAR(128) NOT NULL, `worker_version` VARCHAR(64) NOT NULL,
    `active_jobs` INT NOT NULL DEFAULT 0, `started_at` DATETIME(3) NOT NULL,
    `heartbeat_at` DATETIME(3) NOT NULL, PRIMARY KEY (`worker_id`),
    KEY `idx_forecast_worker_heartbeat` (`heartbeat_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS `s2_forecast_resource_lease` (
    `lease_key` VARCHAR(160) NOT NULL, `owner_job_id` BIGINT NULL,
    `worker_id` VARCHAR(128) NULL, `lease_expires_at` DATETIME(3) NOT NULL,
    `lock_version` INT NOT NULL DEFAULT 0, `updated_at` DATETIME(3) NOT NULL,
    PRIMARY KEY (`lease_key`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- 版本标记必须最后写入，且重复执行旧脚本不得覆盖未来更高版本。
CREATE TABLE IF NOT EXISTS `s2_forecast_schema_version` (
    `component` VARCHAR(64) NOT NULL, `version` INT NOT NULL,
    `installed_at` DATETIME(3) NOT NULL, PRIMARY KEY (`component`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
INSERT IGNORE INTO `s2_forecast_schema_version` (`component`, `version`, `installed_at`)
VALUES ('forecast_meta', 2, CURRENT_TIMESTAMP(3));
