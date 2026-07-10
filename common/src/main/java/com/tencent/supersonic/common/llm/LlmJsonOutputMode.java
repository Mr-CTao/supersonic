package com.tencent.supersonic.common.llm;

/**
 * Provider Adapter 的 JSON 输出协议能力。
 *
 * <p>
 * 职责说明：区分不支持结构化输出、仅保证合法 JSON 对象，以及由 Provider 原生严格执行 JSON Schema
 * 三种能力。Gateway 只依据 Adapter 显式声明选择协议，不根据模型名称猜测能力。枚举为不可变值，天然线程安全。
 * </p>
 */
public enum LlmJsonOutputMode {

    /** Adapter 不支持 JSON 输出协议。 */
    NONE,

    /** Provider 仅保证返回合法 JSON 对象，Schema 需要作为临时消息契约传递并由服务端校验。 */
    JSON_OBJECT,

    /** Provider 原生接收并严格执行 JSON Schema。 */
    JSON_SCHEMA_STRICT
}
