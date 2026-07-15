-- 语义诊断链路正向迁移：新增长度受限的可查询诊断列，不回填历史记录。
ALTER TABLE `s2_semantic_gap`
    ADD COLUMN `diagnostic_stage` varchar(64) DEFAULT NULL COMMENT '结构化诊断阶段',
    ADD COLUMN `error_code` varchar(64) DEFAULT NULL COMMENT '稳定错误码',
    ADD COLUMN `trace_id` varchar(64) DEFAULT NULL COMMENT '请求追踪ID',
    ADD COLUMN `error_line` int DEFAULT NULL COMMENT '错误行号',
    ADD COLUMN `error_column` int DEFAULT NULL COMMENT '错误列号',
    ADD COLUMN `error_token` varchar(128) DEFAULT NULL COMMENT '安全错误token',
    ADD COLUMN `suggestion` varchar(1000) DEFAULT NULL COMMENT '脱敏修复建议';
