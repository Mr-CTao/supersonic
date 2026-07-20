package com.tencent.supersonic.forecast.server.rest;

import com.github.pagehelper.PageInfo;
import com.tencent.supersonic.auth.api.authentication.utils.UserHolder;
import com.tencent.supersonic.common.pojo.User;
import com.tencent.supersonic.forecast.api.request.ForecastJobReq;
import com.tencent.supersonic.forecast.api.response.ForecastJobResp;
import com.tencent.supersonic.forecast.server.service.ForecastJobService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Forecast 任务队列和运行中心 REST API。
 */
@RestController
@RequestMapping("/api/forecast/jobs")
public class ForecastJobController {

    private final ForecastJobService jobService;

    /** @param jobService 任务服务。 */
    public ForecastJobController(ForecastJobService jobService) {
        this.jobService = jobService;
    }

    /** 分页查询用户可见任务。 */
    @GetMapping
    public PageInfo<ForecastJobResp> list(@RequestParam(required = false) Long profileId,
            @RequestParam(defaultValue = "1") int pageNum,
            @RequestParam(defaultValue = "20") int pageSize, HttpServletRequest request,
            HttpServletResponse response) {
        return jobService.list(profileId, pageNum, pageSize, user(request, response));
    }

    /** 幂等提交初始同步、增量、对账或预测任务。 */
    @PostMapping
    public ForecastJobResp create(@Valid @RequestBody ForecastJobReq body,
            @RequestHeader("Idempotency-Key") String idempotencyKey, HttpServletRequest request,
            HttpServletResponse response) {
        return jobService.create(body, idempotencyKey, user(request, response));
    }

    /** 请求运行任务在安全页边界取消。 */
    @PostMapping("/{id}/cancel")
    public ForecastJobResp cancel(@PathVariable Long id,
            @RequestHeader("Idempotency-Key") String idempotencyKey, HttpServletRequest request,
            HttpServletResponse response) {
        return jobService.cancel(id, idempotencyKey, user(request, response));
    }

    /** 用新任务重试失败或已取消任务。 */
    @PostMapping("/{id}/retry")
    public ForecastJobResp retry(@PathVariable Long id,
            @RequestHeader("Idempotency-Key") String idempotencyKey, HttpServletRequest request,
            HttpServletResponse response) {
        return jobService.retry(id, idempotencyKey, user(request, response));
    }

    /** 从现有认证策略读取当前用户。 */
    private User user(HttpServletRequest request, HttpServletResponse response) {
        return UserHolder.findUser(request, response);
    }
}
