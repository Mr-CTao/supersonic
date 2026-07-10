package com.tencent.supersonic.headless.server.semantic.modeling;

import com.fasterxml.jackson.annotation.JsonAlias;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.util.ArrayList;
import java.util.List;

/**
 * 创建 AI 语义建模草稿请求。
 *
 * <p>
 * 职责说明：只接收业务目标、来源标识和选表意图。客户端不得上传表字段 Schema；服务端会按数据源 ACL 重新读取表和列元数据。并发说明：请求 DTO
 * 不含共享状态，幂等性由请求头和数据库唯一键保证。
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
