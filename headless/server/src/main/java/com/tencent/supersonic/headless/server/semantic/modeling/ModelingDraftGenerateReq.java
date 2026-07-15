package com.tencent.supersonic.headless.server.semantic.modeling;

import com.fasterxml.jackson.annotation.JsonAlias;
import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.util.ArrayList;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 创建 AI 语义建模草稿请求。
 *
 * <p>
 * 职责说明：接收业务目标、来源标识、选表意图和已确认路由 ID。客户端不得上传表字段 Schema、
 * 路由动作或目标资产；服务端会按路由快照与 ACL 重新填充只读上下文。并发说明：请求 DTO 不含共享状态，
 * 幂等性由请求头、路由指纹和数据库唯一键保证。
 * </p>
 */
@Data
public class ModelingDraftGenerateReq {

    /**
     * 统一草稿入口必填；Gap 专用入口会在 Bean Validation 后按认证路径补齐，业务层始终再次校验。
     */
    private String sourceType;

    /** Gap 来源时为缺口 ID；数据源来源可为空。 */
    private Long sourceId;

    /** 新入口必须消费的已确认资产路由分析 ID。 */
    private Long routeAnalysisId;

    /** 服务端根据已确认快照写入，客户端同名字段会被 Jackson 忽略。 */
    @JsonIgnore
    private String routeAction;

    /** 服务端生成的脱敏路由摘要，只用于 Prompt 和 v2 草稿头，不接受客户端赋值。 */
    @JsonIgnore
    private Map<String, Object> routeContext = new LinkedHashMap<>();

    /** 服务端持有的目标资产类型，只用于持久化和消费一致性校验。 */
    @JsonIgnore
    private String routeTargetAssetType;

    /** 服务端持有的正式目标资产 ID，永不进入 LLM Prompt。 */
    @JsonIgnore
    private Long routeTargetAssetId;

    /** 已确认目标资产的不可漂移版本。 */
    @JsonIgnore
    private Long routeTargetAssetVersion;

    @Size(max = 255)
    private String title;

    @NotBlank
    @Size(max = 4000)
    private String businessGoal;

    private Long domainId;

    @NotNull
    private Long dataSourceId;

    @JsonAlias("catalog")
    @Size(max = 255)
    private String catalogName;

    @JsonAlias({"db", "database"})
    @Size(max = 255)
    private String databaseName;

    @NotEmpty
    @Size(max = 10)
    private List<@NotBlank String> selectedTables = new ArrayList<>();

    @NotNull
    @JsonAlias("providerId")
    private Integer chatModelId;

    /** 默认关闭；只有管理员显式开启后才会尝试读取最多三行并脱敏。 */
    @JsonAlias("includeSample")
    private Boolean includeSampleData = false;
}
