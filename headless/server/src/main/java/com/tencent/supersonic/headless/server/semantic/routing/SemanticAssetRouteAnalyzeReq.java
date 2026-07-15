package com.tencent.supersonic.headless.server.semantic.routing;

import com.fasterxml.jackson.annotation.JsonAlias;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 发起语义资产路由分析请求。
 *
 * <p>
 * 职责：只接收影响路由指纹的业务范围，不接收候选资产 ID、分数、SQL 或字段 Schema。所有事实和 ACL 均由服务端重新读取。DTO 为请求级对象，不存在共享状态。
 * </p>
 */
@Data
public class SemanticAssetRouteAnalyzeReq {
    @NotBlank
    @Size(max = 32)
    private String sourceType;
    private Long sourceId;
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
    private List<@NotBlank @Size(max = 255) String> selectedTables = new ArrayList<>();
    @NotNull
    private Integer chatModelId;
    private Boolean includeSampleData = false;
    @Size(max = 50)
    private Map<@NotBlank @Size(max = 64) String, Object> businessAnswers = new LinkedHashMap<>();
}
