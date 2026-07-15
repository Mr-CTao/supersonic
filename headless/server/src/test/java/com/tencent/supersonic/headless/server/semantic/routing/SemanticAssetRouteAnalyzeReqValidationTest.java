package com.tencent.supersonic.headless.server.semantic.routing;

import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 路由分析请求 Bean Validation 测试。
 *
 * <p>
 * 职责：固定 selectedTables 集合元素的长度边界，防止单个超长标识进入指纹、审计 JSON 和候选查询。 Validator 仅在单个测试作用域创建并关闭，不共享线程状态。
 * </p>
 */
class SemanticAssetRouteAnalyzeReqValidationTest {

    /** 单个表名超过服务端标识长度上限时请求必须校验失败。 */
    @Test
    void shouldRejectOversizedSelectedTableName() {
        SemanticAssetRouteAnalyzeReq request = new SemanticAssetRouteAnalyzeReq();
        request.setSourceType("DATA_SOURCE");
        request.setBusinessGoal("统计库存");
        request.setDataSourceId(1L);
        request.setSelectedTables(List.of("t".repeat(256)));
        request.setChatModelId(2);

        try (ValidatorFactory factory = Validation.buildDefaultValidatorFactory()) {
            Validator validator = factory.getValidator();
            assertFalse(validator.validate(request).isEmpty());
        }
    }

    /** 路由分析依赖语义建议模型，未提供 chatModelId 时必须在 Controller 入参阶段失败。 */
    @Test
    void shouldRejectMissingChatModelId() {
        SemanticAssetRouteAnalyzeReq request = new SemanticAssetRouteAnalyzeReq();
        request.setSourceType("DATA_SOURCE");
        request.setBusinessGoal("统计库存");
        request.setDataSourceId(1L);
        request.setSelectedTables(List.of("stock_summary"));

        try (ValidatorFactory factory = Validation.buildDefaultValidatorFactory()) {
            Validator validator = factory.getValidator();
            assertTrue(validator.validate(request).stream()
                    .anyMatch(violation -> "chatModelId".equals(
                            violation.getPropertyPath().toString())));
        }
    }
}
