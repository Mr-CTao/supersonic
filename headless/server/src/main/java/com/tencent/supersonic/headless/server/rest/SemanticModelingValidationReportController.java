package com.tencent.supersonic.headless.server.rest;

import com.tencent.supersonic.auth.api.authentication.utils.UserHolder;
import com.tencent.supersonic.common.pojo.User;
import com.tencent.supersonic.headless.server.semantic.modeling.ModelingDraftStage4Service;
import com.tencent.supersonic.headless.server.semantic.modeling.SemanticValidationReportResp;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 阶段 4 语义建模验证报告详情接口。
 *
 * <p>
 * 职责说明：按报告 ID 提供管理端只读详情，并由应用服务复核关联草稿、数据源和主题域管理权限。 Controller 不持有共享可变状态，也不提供批准、发布或回滚操作。
 * </p>
 */
@RestController
@RequestMapping("/api/semantic/modeling/validation-reports")
public class SemanticModelingValidationReportController {

    private final ModelingDraftStage4Service stage4Service;

    /**
     * 创建验证报告 Controller。
     *
     * @param stage4Service 多轮校准和验证门禁服务。
     */
    public SemanticModelingValidationReportController(ModelingDraftStage4Service stage4Service) {
        this.stage4Service = stage4Service;
    }

    /**
     * 查询单个验证报告详情。
     *
     * @param reportId 报告 ID。
     * @param servletRequest HTTP 请求。
     * @param servletResponse HTTP 响应。
     * @return 脱敏后的验证报告。
     */
    @GetMapping("/{reportId}")
    public SemanticValidationReportResp get(@PathVariable("reportId") Long reportId,
            HttpServletRequest servletRequest, HttpServletResponse servletResponse) {
        User user = UserHolder.findUser(servletRequest, servletResponse);
        return stage4Service.getReport(reportId, user);
    }
}
