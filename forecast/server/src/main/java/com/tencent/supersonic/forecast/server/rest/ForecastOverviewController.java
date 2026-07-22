package com.tencent.supersonic.forecast.server.rest;

import com.tencent.supersonic.auth.api.authentication.utils.UserHolder;
import com.tencent.supersonic.common.pojo.User;
import com.tencent.supersonic.common.pojo.exception.InvalidArgumentException;
import com.tencent.supersonic.forecast.api.enums.ForecastAnchorMode;
import com.tencent.supersonic.forecast.api.enums.ForecastDirection;
import com.tencent.supersonic.forecast.api.enums.ForecastMetric;
import com.tencent.supersonic.forecast.api.response.ForecastBreakdownResp;
import com.tencent.supersonic.forecast.api.response.ForecastHealthResp;
import com.tencent.supersonic.forecast.api.response.ForecastOverviewSnapshotResp;
import com.tencent.supersonic.forecast.api.response.ForecastOverviewSummaryResp;
import com.tencent.supersonic.forecast.api.response.ForecastSeriesPointResp;
import com.tencent.supersonic.forecast.server.service.ForecastOverviewService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.sql.SQLException;
import java.time.LocalDate;
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

    /**
     * 查询指定预测基准下的一致性看板快照。
     *
     * @param profileId Profile ID。
     * @param horizon 7、14 或 30 天。
     * @param metric 数量或任务数。
     * @param anchorMode 跟随数据、历史回测或今天。
     * @param forecastStartDate 历史回测首日；其他模式可为空。
     * @param direction 可选的出入库方向；为空表示全部方向。
     * @param trainingStartDate 可选的自定义训练起始日；必须与截止日同时提供。
     * @param trainingEndDate 可选的自定义训练截止日；必须与起始日同时提供。
     * @param request HTTP 请求。
     * @param response HTTP 响应。
     * @return 日期上下文、KPI、趋势和仓库拆分的一致性快照。
     * @throws InvalidArgumentException 查询失败或日期参数非法。
     */
    @GetMapping("/overview/snapshot")
    public ForecastOverviewSnapshotResp snapshot(@RequestParam Long profileId,
            @RequestParam(defaultValue = "7") int horizon,
            @RequestParam(defaultValue = "QUANTITY") ForecastMetric metric,
            @RequestParam(defaultValue = "LATEST_DATA") ForecastAnchorMode anchorMode,
            @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate forecastStartDate,
            @RequestParam(required = false) ForecastDirection direction,
            @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate trainingStartDate,
            @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate trainingEndDate,
            HttpServletRequest request, HttpServletResponse response) {
        try {
            return overviewService.snapshot(profileId, horizon, metric, anchorMode,
                    forecastStartDate, direction, trainingStartDate, trainingEndDate,
                    user(request, response));
        } catch (SQLException exception) {
            throw decisionQueryFailure();
        }
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
