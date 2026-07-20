package com.tencent.supersonic.forecast.server.rest;

import com.tencent.supersonic.auth.api.authentication.utils.UserHolder;
import com.tencent.supersonic.common.pojo.User;
import com.tencent.supersonic.common.pojo.exception.InvalidArgumentException;
import com.tencent.supersonic.forecast.api.enums.ForecastMetric;
import com.tencent.supersonic.forecast.api.response.ForecastBreakdownResp;
import com.tencent.supersonic.forecast.api.response.ForecastHealthResp;
import com.tencent.supersonic.forecast.api.response.ForecastOverviewSummaryResp;
import com.tencent.supersonic.forecast.api.response.ForecastSeriesPointResp;
import com.tencent.supersonic.forecast.server.service.ForecastOverviewService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.sql.SQLException;
import java.util.List;

/**
 * Forecast 看板汇总、曲线、拆分和健康状态 REST API。
 */
@RestController
@RequestMapping("/api/forecast")
public class ForecastOverviewController {

    private final ForecastOverviewService overviewService;

    /** @param overviewService 看板服务。 */
    public ForecastOverviewController(ForecastOverviewService overviewService) {
        this.overviewService = overviewService;
    }

    /** 查询 7/14/30 天 KPI 汇总。 */
    @GetMapping("/overview/summary")
    public ForecastOverviewSummaryResp summary(@RequestParam Long profileId,
            @RequestParam(defaultValue = "7") int horizon,
            @RequestParam(defaultValue = "QUANTITY") ForecastMetric metric,
            HttpServletRequest request, HttpServletResponse response) {
        try {
            return overviewService.summary(profileId, horizon, metric, user(request, response));
        } catch (SQLException exception) {
            throw decisionQueryFailure();
        }
    }

    /** 查询最近实际和未来预测/经验区间曲线。 */
    @GetMapping("/overview/series")
    public List<ForecastSeriesPointResp> series(@RequestParam Long profileId,
            @RequestParam(defaultValue = "7") int horizon,
            @RequestParam(defaultValue = "QUANTITY") ForecastMetric metric,
            HttpServletRequest request, HttpServletResponse response) {
        try {
            return overviewService.series(profileId, horizon, metric, user(request, response));
        } catch (SQLException exception) {
            throw decisionQueryFailure();
        }
    }

    /** 查询仓库/方向预测拆分与模型质量。 */
    @GetMapping("/overview/breakdown")
    public List<ForecastBreakdownResp> breakdown(@RequestParam Long profileId,
            @RequestParam(defaultValue = "7") int horizon,
            @RequestParam(defaultValue = "QUANTITY") ForecastMetric metric,
            HttpServletRequest request, HttpServletResponse response) {
        try {
            return overviewService.breakdown(profileId, horizon, metric, user(request, response));
        } catch (SQLException exception) {
            throw decisionQueryFailure();
        }
    }

    /** 查询 Worker 心跳和数据新鲜度。 */
    @GetMapping("/health")
    public ForecastHealthResp health(@RequestParam Long profileId, HttpServletRequest request,
            HttpServletResponse response) {
        return overviewService.health(profileId, user(request, response));
    }

    /** 从现有认证策略读取当前用户。 */
    private User user(HttpServletRequest request, HttpServletResponse response) {
        return UserHolder.findUser(request, response);
    }

    /** 对外隐藏决策库连接、SQL 和驱动异常。 */
    private InvalidArgumentException decisionQueryFailure() {
        return new InvalidArgumentException("预测决策库查询失败，请检查 Worker 与 Schema 状态");
    }
}
