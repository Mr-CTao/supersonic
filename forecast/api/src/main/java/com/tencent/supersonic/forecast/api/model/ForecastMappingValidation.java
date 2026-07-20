package com.tencent.supersonic.forecast.api.model;

import java.util.List;
import java.util.Map;

/**
 * 映射结构校验和受控抽样结果。
 *
 * @param valid 是否允许发布。
 * @param errors 阻断错误。
 * @param warnings 非阻断能力提示。
 * @param samples 最多一百条标准事件预览。
 */
public record ForecastMappingValidation(boolean valid, List<String> errors,
        List<String> warnings, List<Map<String, Object>> samples) {
}
