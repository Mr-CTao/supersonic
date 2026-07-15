package com.tencent.supersonic.headless.server.semantic.modeling;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tencent.supersonic.headless.server.persistence.dataobject.SemanticModelingDraftDO;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.Objects;

/**
 * 路由感知草稿的不可漂移边界校验器。
 *
 * <p>职责：阻止 LLM、人工保存或 AI 修订更改已确认的 routeAnalysisId、动作、业务目标、路由摘要和
 * 目标资产快照。该类只比较请求内 JSON，不访问数据库；目标权限与当前版本由路由服务在创建前复核，
 * 草稿消费由数据库条件更新保证。组件无共享可变状态，可并发复用。</p>
 */
@Component
public class ModelingDraftRouteGuard {

    private final ObjectMapper objectMapper;

    /**
     * 创建路由草稿守卫。
     *
     * @param objectMapper 项目 JSON 映射器。
     */
    public ModelingDraftRouteGuard(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    /**
     * 校验首次 LLM 输出与服务端确认路由完全一致。
     *
     * @param payload 已通过 JSON Schema 和字段校验的草稿。
     * @param request 含服务端路由上下文的生成请求快照。
     * @throws ModelingDraftException LLM 改写路由事实或输出错误动作时抛出 422，允许固定修复轮处理。
     */
    public void validateGenerated(ModelingDraftPayload payload, ModelingDraftGenerateReq request) {
        if (request.getRouteAnalysisId() == null) {
            // 历史调用方没有已确认路由快照可供比对，继续由 1.0 Schema 与既有校验器负责兼容校验。
            return;
        }
        Map<String, Object> context = request.getRouteContext();
        if (!ModelingDraftConstants.SCHEMA_VERSION_ROUTED.equals(payload.getSchemaVersion())
                || !Objects.equals(request.getRouteAction(), payload.getAction())
                || !Objects.equals(request.getBusinessGoal(), payload.getBusinessGoal())
                || payload.getTargetDomain() != null
                || payload.getRouteSummary() == null
                || !Objects.equals(request.getRouteAnalysisId(),
                        payload.getRouteSummary().getRouteAnalysisId())
                || !sameJson(context.get("routeSummary"), payload.getRouteSummary())
                || !sameJson(context.get("targetAsset"), payload.getTargetAsset())) {
            throw routeMismatch("模型输出改写了已确认路由快照");
        }
        if (ModelingDraftConstants.ACTION_EXTEND_EXISTING.equals(request.getRouteAction())
                && payload.getTargetAsset() == null) {
            throw routeMismatch("增强草稿缺少已确认目标资产");
        }
        if (ModelingDraftConstants.ACTION_CREATE_NEW.equals(request.getRouteAction())
                && payload.getTargetAsset() != null) {
            throw routeMismatch("新建草稿不得绑定未确认目标资产");
        }
    }

    /**
     * 校验人工保存或 AI 修订未改变既有路由快照。
     *
     * @param draft 草稿主记录中的服务端路由字段。
     * @param baselineJson 当前不可变版本的 JSON。
     * @param candidate 待保存的新草稿。
     * @throws ModelingDraftException 路由字段、业务目标或目标资产发生变化时抛出 409。
     */
    public void validateMutation(SemanticModelingDraftDO draft, String baselineJson,
            ModelingDraftPayload candidate) {
        if (draft.getRouteAnalysisId() == null) {
            // 历史 1.0 草稿没有路由快照，继续沿用原阶段 4 兼容行为。
            return;
        }
        JsonNode baseline = readTree(baselineJson);
        JsonNode candidateNode = objectMapper.valueToTree(candidate);
        boolean unchanged = Objects.equals(draft.getRouteAction(), candidate.getAction())
                && candidate.getRouteSummary() != null
                && Objects.equals(draft.getRouteAnalysisId(),
                        candidate.getRouteSummary().getRouteAnalysisId())
                && Objects.equals(baseline.path("businessGoal"),
                        candidateNode.path("businessGoal"))
                && Objects.equals(baseline.path("targetDomain"),
                        candidateNode.path("targetDomain"))
                && Objects.equals(baseline.path("routeSummary"),
                        candidateNode.path("routeSummary"))
                && Objects.equals(baseline.path("targetAsset"),
                        candidateNode.path("targetAsset"));
        if (!unchanged) {
            throw new ModelingDraftException(HttpStatus.CONFLICT,
                    ModelingDraftConstants.ERROR_CONFLICT,
                    "已确认的路由、业务目标或目标资产快照不可修改，请重新分析");
        }
    }

    /** 比较服务端 Map/POJO 与类型化输出的标准 JSON 树。 */
    private boolean sameJson(Object expected, Object actual) {
        if (expected == null || actual == null) {
            return expected == actual;
        }
        return Objects.equals(objectMapper.valueToTree(expected), objectMapper.valueToTree(actual));
    }

    /** 读取既有草稿 JSON；持久化损坏时 fail-closed。 */
    private JsonNode readTree(String json) {
        try {
            return objectMapper.readTree(json);
        } catch (JsonProcessingException exception) {
            throw new ModelingDraftException(HttpStatus.CONFLICT,
                    ModelingDraftConstants.ERROR_CONFLICT,
                    "既有草稿路由快照无法读取，请重新生成");
        }
    }

    /** 构造可触发一次结构修复、但不回显 Prompt 或正式资产信息的错误。 */
    private ModelingDraftException routeMismatch(String message) {
        return new ModelingDraftException(HttpStatus.UNPROCESSABLE_ENTITY,
                ModelingDraftConstants.ERROR_OUTPUT_INVALID, message);
    }
}
