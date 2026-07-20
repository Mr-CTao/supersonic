package com.tencent.supersonic.forecast.server.rest;

import com.github.pagehelper.PageInfo;
import com.tencent.supersonic.auth.api.authentication.utils.UserHolder;
import com.tencent.supersonic.common.pojo.User;
import com.tencent.supersonic.common.pojo.exception.InvalidArgumentException;
import com.tencent.supersonic.forecast.api.enums.ForecastJobType;
import com.tencent.supersonic.forecast.api.model.ForecastMappingValidation;
import com.tencent.supersonic.forecast.api.model.ForecastMetadata;
import com.tencent.supersonic.forecast.api.request.ForecastJobReq;
import com.tencent.supersonic.forecast.api.request.ForecastMappingReq;
import com.tencent.supersonic.forecast.api.request.ForecastProfileReq;
import com.tencent.supersonic.forecast.api.request.ForecastStreamReq;
import com.tencent.supersonic.forecast.api.response.ForecastJobResp;
import com.tencent.supersonic.forecast.api.response.ForecastMappingResp;
import com.tencent.supersonic.forecast.api.response.ForecastProfileResp;
import com.tencent.supersonic.forecast.api.response.ForecastStreamResp;
import com.tencent.supersonic.forecast.server.service.ForecastJobService;
import com.tencent.supersonic.forecast.server.service.ForecastProfileService;
import com.tencent.supersonic.forecast.server.service.ForecastSemanticModelService;
import com.tencent.supersonic.headless.api.pojo.response.ModelResp;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.sql.SQLException;
import java.util.List;

/**
 * Forecast Profile、数据流、映射版本和源库元数据 REST API。
 *
 * <p>
 * 所有写接口要求 {@code Idempotency-Key}；更新同时使用 lockVersion 防止并发覆盖。响应继续由 SuperSonic 全局 ResponseAdvice
 * 包装为统一 code/msg/data 结构。
 * </p>
 */
@RestController
@RequestMapping("/api/forecast/profiles")
public class ForecastProfileController {

    private final ForecastProfileService profileService;
    private final ForecastJobService jobService;
    private final ForecastSemanticModelService semanticModelService;

    /**
     * 创建 Profile Controller。
     *
     * @param profileService Profile 服务。
     * @param jobService 任务服务。
     */
    public ForecastProfileController(ForecastProfileService profileService,
            ForecastJobService jobService, ForecastSemanticModelService semanticModelService) {
        this.profileService = profileService;
        this.jobService = jobService;
        this.semanticModelService = semanticModelService;
    }

    /** 分页查询当前用户可见 Profile。 */
    @GetMapping
    public PageInfo<ForecastProfileResp> list(@RequestParam(defaultValue = "1") int pageNum,
            @RequestParam(defaultValue = "20") int pageSize, HttpServletRequest request,
            HttpServletResponse response) {
        return profileService.listProfiles(pageNum, pageSize, user(request, response));
    }

    /** 查询 Profile 详情与数据流。 */
    @GetMapping("/{id}")
    public ForecastProfileResp get(@PathVariable Long id, HttpServletRequest request,
            HttpServletResponse response) {
        return profileService.getProfile(id, user(request, response));
    }

    /** 创建 Profile；按钮应在请求完成前保持 loading。 */
    @PostMapping
    public ForecastProfileResp create(@Valid @RequestBody ForecastProfileReq body,
            @RequestHeader("Idempotency-Key") String idempotencyKey, HttpServletRequest request,
            HttpServletResponse response) {
        requireIdempotencyKey(idempotencyKey);
        return profileService.createProfile(body, user(request, response));
    }

    /** 乐观锁更新 Profile。 */
    @PutMapping("/{id}")
    public ForecastProfileResp update(@PathVariable Long id,
            @Valid @RequestBody ForecastProfileReq body,
            @RequestHeader("Idempotency-Key") String idempotencyKey, HttpServletRequest request,
            HttpServletResponse response) {
        requireIdempotencyKey(idempotencyKey);
        return profileService.updateProfile(id, body, user(request, response));
    }

    /** 停用 Profile，保留历史任务与结果。 */
    @DeleteMapping("/{id}")
    public ForecastProfileResp disable(@PathVariable Long id, @RequestParam int lockVersion,
            @RequestHeader("Idempotency-Key") String idempotencyKey, HttpServletRequest request,
            HttpServletResponse response) {
        requireIdempotencyKey(idempotencyKey);
        return profileService.disableProfile(id, lockVersion, user(request, response));
    }

    /** 新建 Profile 下数据流。 */
    @PostMapping("/{profileId}/streams")
    public ForecastStreamResp createStream(@PathVariable Long profileId,
            @Valid @RequestBody ForecastStreamReq body,
            @RequestHeader("Idempotency-Key") String idempotencyKey, HttpServletRequest request,
            HttpServletResponse response) {
        requireIdempotencyKey(idempotencyKey);
        return profileService.createStream(profileId, body, user(request, response));
    }

    /** 更新数据流名称或启用状态。 */
    @PutMapping("/{profileId}/streams/{streamId}")
    public ForecastStreamResp updateStream(@PathVariable Long profileId,
            @PathVariable Long streamId, @Valid @RequestBody ForecastStreamReq body,
            @RequestHeader("Idempotency-Key") String idempotencyKey, HttpServletRequest request,
            HttpServletResponse response) {
        requireIdempotencyKey(idempotencyKey);
        return profileService.updateStream(profileId, streamId, body, user(request, response));
    }

    /** 创建不可变映射草稿版本。 */
    @PostMapping("/{profileId}/mappings")
    public ForecastMappingResp createMapping(@PathVariable Long profileId,
            @RequestParam Long streamId, @Valid @RequestBody ForecastMappingReq body,
            @RequestHeader("Idempotency-Key") String idempotencyKey, HttpServletRequest request,
            HttpServletResponse response) {
        requireIdempotencyKey(idempotencyKey);
        return profileService.createMapping(profileId, streamId, body, user(request, response));
    }

    /** 查询数据流映射版本。 */
    @GetMapping("/{profileId}/mappings")
    public List<ForecastMappingResp> listMappings(@PathVariable Long profileId,
            @RequestParam Long streamId, HttpServletRequest request, HttpServletResponse response) {
        return profileService.listMappings(profileId, streamId, user(request, response));
    }

    /** 使用最多一百行源数据校验映射并返回标准化预览。 */
    @PostMapping("/{profileId}/mappings/{mappingId}/validate")
    public ForecastMappingValidation validateMapping(@PathVariable Long profileId,
            @PathVariable Long mappingId, @RequestParam Long streamId,
            @RequestParam(defaultValue = "100") int sampleLimit,
            @RequestHeader("Idempotency-Key") String idempotencyKey, HttpServletRequest request,
            HttpServletResponse response) {
        requireIdempotencyKey(idempotencyKey);
        try {
            return profileService.validateMapping(profileId, streamId, mappingId, sampleLimit,
                    user(request, response));
        } catch (SQLException exception) {
            throw new InvalidArgumentException("源数据库映射校验失败，请检查连接和字段配置");
        }
    }

    /** 发布已通过校验的映射，但尚不切换活动版本。 */
    @PostMapping("/{profileId}/mappings/{mappingId}/publish")
    public ForecastMappingResp publishMapping(@PathVariable Long profileId,
            @PathVariable Long mappingId, @RequestParam Long streamId,
            @RequestHeader("Idempotency-Key") String idempotencyKey, HttpServletRequest request,
            HttpServletResponse response) {
        requireIdempotencyKey(idempotencyKey);
        return profileService.publishMapping(profileId, streamId, mappingId,
                user(request, response));
    }

    /** 提交首次回填，成功后由 Worker 原子切换活动版本。 */
    @PostMapping("/{profileId}/mappings/{mappingId}/activate")
    public ForecastJobResp activateMapping(@PathVariable Long profileId,
            @PathVariable Long mappingId, @RequestParam Long streamId,
            @RequestHeader("Idempotency-Key") String idempotencyKey, HttpServletRequest request,
            HttpServletResponse response) {
        ForecastJobReq job = new ForecastJobReq();
        job.setProfileId(profileId);
        job.setStreamId(streamId);
        job.setMappingId(mappingId);
        job.setType(ForecastJobType.INITIAL_SYNC);
        return jobService.create(job, idempotencyKey, user(request, response));
    }

    /** 发现源库可见 catalog、schema、表、视图与列。 */
    @GetMapping("/{profileId}/metadata")
    public ForecastMetadata metadata(@PathVariable Long profileId,
            @RequestParam(required = false) String catalog,
            @RequestParam(required = false) String schema,
            @RequestParam(required = false) String tablePattern, HttpServletRequest request,
            HttpServletResponse response) {
        try {
            return profileService.discoverMetadata(profileId, catalog, schema, tablePattern,
                    user(request, response));
        } catch (SQLException exception) {
            throw new InvalidArgumentException("源数据库元数据读取失败，请检查连接状态");
        }
    }

    /** 通过现有 Headless 管理服务幂等创建稳定服务视图语义模型。 */
    @PostMapping("/{profileId}/semantic-model")
    public ModelResp provisionSemanticModel(@PathVariable Long profileId,
            @RequestParam Long domainId, @RequestHeader("Idempotency-Key") String idempotencyKey,
            HttpServletRequest request, HttpServletResponse response) {
        requireIdempotencyKey(idempotencyKey);
        return semanticModelService.provision(profileId, domainId, user(request, response));
    }

    /** 从现有认证策略读取当前用户。 */
    private User user(HttpServletRequest request, HttpServletResponse response) {
        return UserHolder.findUser(request, response);
    }

    /** Controller 层前置校验关键写接口幂等键。 */
    private void requireIdempotencyKey(String key) {
        if (key == null || key.isBlank() || key.length() > 128) {
            throw new InvalidArgumentException("Idempotency-Key 必填且不能超过 128 字符");
        }
    }
}
