-- Roll back only the untouched seed connection. Once an administrator fills the API Key, the
-- record is treated as user-owned configuration and must not be deleted automatically.
DELETE FROM s2_llm_model_capability
WHERE chat_model_id IN (
    SELECT id
    FROM s2_chat_model
    WHERE name = 'Kimi-K3'
      AND config LIKE '%"provider":"KIMI"%'
      AND config LIKE '%"apiKey":""%'
);

DELETE FROM s2_chat_model
WHERE name = 'Kimi-K3'
  AND config LIKE '%"provider":"KIMI"%'
  AND config LIKE '%"apiKey":""%';
