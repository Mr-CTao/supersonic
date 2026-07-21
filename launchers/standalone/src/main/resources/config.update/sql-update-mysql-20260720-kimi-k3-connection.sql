-- 20260720 Seed an editable Kimi K3 connection with no API Key.
-- The NOT EXISTS guards make the migration idempotent and preserve administrator-managed data.
INSERT INTO `s2_chat_model` (
    `name`, `description`, `config`, `created_at`, `created_by`, `updated_at`, `updated_by`,
    `admin`, `viewer`, `is_open`
)
SELECT
    'Kimi-K3',
    'Kimi K3 默认连接；请在大模型管理页面填写 API Key 后执行连接测试。',
    '{"provider":"KIMI","baseUrl":"https://api.moonshot.cn/v1","apiKey":"","modelName":"kimi-k3","apiVersion":"","temperature":1.0,"timeOut":180,"topP":null,"maxRetries":3,"logRequests":false,"logResponses":false,"enableSearch":false,"jsonFormat":false,"jsonFormatType":"json_schema"}',
    CURRENT_TIMESTAMP,
    'admin',
    CURRENT_TIMESTAMP,
    'admin',
    'admin',
    '[]',
    0
FROM DUAL
WHERE NOT EXISTS (
    SELECT 1 FROM `s2_chat_model` WHERE `name` = 'Kimi-K3'
);

INSERT INTO `s2_llm_model_capability` (
    `chat_model_id`, `provider_type`, `model_name`, `max_context_tokens`,
    `support_stream`, `support_json_mode`, `support_tool_calling`, `support_thinking`,
    `support_chat_prefix_completion`, `support_fim_completion`, `support_context_cache`,
    `support_system_prompt`, `recommended_temperature`, `usage_scene`, `enabled`,
    `created_at`, `updated_at`
)
SELECT
    model.`id`, 'KIMI', 'kimi-k3', 1000000,
    1, 1, 1, 1,
    1, 0, 1,
    1, 1.0, 'semantic_modeling', 1,
    CURRENT_TIMESTAMP, CURRENT_TIMESTAMP
FROM `s2_chat_model` model
WHERE model.`name` = 'Kimi-K3'
  AND model.`config` LIKE '%"provider":"KIMI"%'
  AND NOT EXISTS (
      SELECT 1
      FROM `s2_llm_model_capability` capability
      WHERE capability.`chat_model_id` = model.`id`
        AND capability.`model_name` = 'kimi-k3'
  );
