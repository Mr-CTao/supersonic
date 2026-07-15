package com.tencent.supersonic.headless.server.semantic.diagnostic;

import com.tencent.supersonic.common.pojo.User;
import com.tencent.supersonic.common.pojo.enums.EngineType;
import com.tencent.supersonic.common.pojo.exception.InvalidArgumentException;
import com.tencent.supersonic.common.pojo.exception.InvalidPermissionException;
import com.tencent.supersonic.headless.api.pojo.Dimension;
import com.tencent.supersonic.headless.api.pojo.Measure;
import com.tencent.supersonic.headless.api.pojo.ModelDetail;
import com.tencent.supersonic.headless.api.pojo.request.ModelReq;
import com.tencent.supersonic.headless.api.pojo.request.SqlExecuteReq;
import com.tencent.supersonic.headless.api.pojo.response.DatabaseResp;
import com.tencent.supersonic.headless.api.pojo.response.ModelResp;
import com.tencent.supersonic.headless.api.pojo.response.SemanticDiagnostic;
import com.tencent.supersonic.headless.api.pojo.response.SemanticDiagnosticCode;
import com.tencent.supersonic.headless.api.pojo.response.SemanticDiagnosticSeverity;
import com.tencent.supersonic.headless.api.pojo.response.SemanticDiagnosticStage;
import com.tencent.supersonic.headless.api.pojo.response.SemanticValidationCheck;
import com.tencent.supersonic.headless.api.pojo.response.SemanticValidationCheckType;
import com.tencent.supersonic.headless.api.pojo.response.SemanticValidationResult;
import com.tencent.supersonic.headless.api.pojo.response.SemanticValidationStatus;
import com.tencent.supersonic.headless.core.translator.parser.calcite.SemanticModelCompileException;
import com.tencent.supersonic.headless.core.translator.parser.calcite.SemanticModelCompiler;
import com.tencent.supersonic.headless.server.semantic.modeling.ModelingSqlReadOnlyChecker;
import com.tencent.supersonic.headless.server.service.DatabaseService;
import org.apache.commons.lang3.StringUtils;
import org.slf4j.MDC;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * 语义模型双重校验服务。
 *
 * <p>
 * 职责说明：复用现有数据源 ACL/执行能力和只读门禁，同时调用 headless-core 的运行时 Calcite
 * 编译器，独立报告每项结果。远程执行发生在任何元数据事务之外；服务无共享可变状态，线程安全。
 * </p>
 */
@Service
public class SemanticModelValidationService {

    private final DatabaseService databaseService;
    private final ModelingSqlReadOnlyChecker sqlReadOnlyChecker;
    private final ModelHealthService modelHealthService;

    /**
     * 创建校验服务。
     *
     * @param databaseService 现有数据源服务。
     * @param sqlReadOnlyChecker 阶段 4 已有只读 AST 门禁。
     * @param modelHealthService 当前节点模型健康状态服务。
     */
    public SemanticModelValidationService(DatabaseService databaseService,
            ModelingSqlReadOnlyChecker sqlReadOnlyChecker, ModelHealthService modelHealthService) {
        this.databaseService = databaseService;
        this.sqlReadOnlyChecker = sqlReadOnlyChecker;
        this.modelHealthService = modelHealthService;
    }

    /**
     * 执行 SQL 编辑阶段双重校验。
     *
     * @param request 校验请求。
     * @param user 当前用户。
     * @return 各检查项独立呈现的结果。
     * @throws InvalidArgumentException 请求缺少数据库或 SQL 时抛出。
     * @throws InvalidPermissionException 当前用户无管理权限时抛出。
     */
    public SemanticValidationResult validate(SemanticModelValidationReq request, User user) {
        if (request == null || request.getDatabaseId() == null
                || StringUtils.isBlank(effectiveSql(request))) {
            throw new InvalidArgumentException("数据库和模型 SQL 不能为空");
        }
        DatabaseResp database = databaseService.getDatabase(request.getDatabaseId());
        requireManagementPermission(database, user);
        String sql = effectiveSql(request);
        EngineType engineType = EngineType.fromString(database.getType());
        String traceId = traceId();
        List<SemanticValidationCheck> checks = new ArrayList<>();

        checks.add(validateSource(request, user, database, sql, traceId));
        checks.add(validateCompiler(request, engineType, sql));
        checks.add(validateExpressions(request, engineType));
        checks.add(SemanticValidationCheck.builder()
                .type(SemanticValidationCheckType.SEMANTIC_QUERY_SMOKE)
                .status(SemanticValidationStatus.SKIPPED).message("保存前仅校验模型编译；语义问法冒烟在完整模型验证阶段执行")
                .build());

        SemanticValidationStatus overall = checks.stream()
                .anyMatch(check -> check.getStatus() == SemanticValidationStatus.BLOCKING)
                        ? SemanticValidationStatus.BLOCKING
                        : SemanticValidationStatus.PASSED;
        SemanticValidationResult result = SemanticValidationResult.builder().overallStatus(overall)
                .checks(checks).contentDigest(contentDigest(request, sql)).traceId(traceId).build();
        modelHealthService.recordValidation(request.getModelId(), result);
        return result;
    }

    /**
     * 对已保存模型重新执行确定性编译校验并刷新健康摘要。
     *
     * @param model 已保存模型。
     * @param user 当前管理员。
     * @return 独立校验项结果。
     * @throws InvalidArgumentException 模型为空或不包含可校验 SQL 时抛出。
     */
    public SemanticValidationResult revalidate(ModelResp model, User user) {
        if (model == null || model.getModelDetail() == null
                || StringUtils.isBlank(model.getModelDetail().getSqlQuery())) {
            throw new InvalidArgumentException("当前模型不包含可校验的模型 SQL");
        }
        ModelReq modelReq = new ModelReq();
        modelReq.setId(model.getId());
        modelReq.setName(model.getName());
        modelReq.setDatabaseId(model.getDatabaseId());
        modelReq.setModelDetail(model.getModelDetail());
        SemanticModelValidationReq request = fromModel(modelReq);
        request.setExecuteSource(false);
        return validate(request, user);
    }

    /**
     * 保存/发布前执行不访问远程数据库的确定性模型门禁。
     *
     * @param modelReq 待保存模型。
     * @param user 当前管理员。
     * @throws SemanticModelCompileException 编译不通过且存在结构化诊断时抛出。
     * @throws InvalidArgumentException 编译不通过但没有可用诊断时抛出。
     */
    public void validateForPublication(ModelReq modelReq, User user) {
        if (modelReq == null || modelReq.getModelDetail() == null
                || StringUtils.isBlank(modelReq.getModelDetail().getSqlQuery())) {
            // 物理表模型不包含用户 SQL；其字段合法性沿用现有元数据构建链路校验。
            return;
        }
        SemanticModelValidationReq request = fromModel(modelReq);
        request.setExecuteSource(false);
        SemanticValidationResult result = validate(request, user);
        if (result.getOverallStatus() == SemanticValidationStatus.BLOCKING) {
            SemanticDiagnostic diagnostic =
                    result.getChecks().stream().map(SemanticValidationCheck::getDiagnostic)
                            .filter(Objects::nonNull).findFirst().orElse(null);
            if (diagnostic != null) {
                // 保存门禁必须保留同一结构化根因，不能在事务入口退化成普通字符串异常。
                throw new SemanticModelCompileException(diagnostic, null);
            }
            throw new InvalidArgumentException("模型编译校验未通过");
        }
    }

    /**
     * 已落库模型上线前重新编译，防止历史非法 SQL 被发布。
     *
     * @param model 已存在模型。
     * @param user 当前管理员。
     */
    public void validateForPublication(ModelResp model, User user) {
        if (model == null || model.getModelDetail() == null
                || StringUtils.isBlank(model.getModelDetail().getSqlQuery())) {
            return;
        }
        ModelReq request = new ModelReq();
        request.setId(model.getId());
        request.setName(model.getName());
        request.setDatabaseId(model.getDatabaseId());
        request.setModelDetail(model.getModelDetail());
        validateForPublication(request, user);
    }

    /** 数据源试运行与编译检查必须相互独立。 */
    private SemanticValidationCheck validateSource(SemanticModelValidationReq request, User user,
            DatabaseResp database, String sql, String traceId) {
        if (!request.isExecuteSource()) {
            return SemanticValidationCheck.builder()
                    .type(SemanticValidationCheckType.SOURCE_DATABASE)
                    .status(SemanticValidationStatus.SKIPPED).message("发布门禁不在事务内执行远程查询").build();
        }
        ModelingSqlReadOnlyChecker.CheckResult readOnly =
                sqlReadOnlyChecker.validate(sql, database.getType());
        if (!readOnly.readOnly()) {
            return SemanticValidationCheck.builder()
                    .type(SemanticValidationCheckType.SOURCE_DATABASE)
                    .status(SemanticValidationStatus.BLOCKING).message(readOnly.message())
                    .diagnostic(sourceDiagnostic(traceId)).build();
        }
        try {
            SqlExecuteReq executeReq = new SqlExecuteReq();
            executeReq.setId(request.getDatabaseId());
            executeReq.setSql(sql);
            executeReq.setSqlVariables(request.getSqlVariables());
            databaseService.executeSql(executeReq, user);
            return SemanticValidationCheck.builder()
                    .type(SemanticValidationCheckType.SOURCE_DATABASE)
                    .status(SemanticValidationStatus.PASSED).message("数据源执行通过").build();
        } catch (RuntimeException exception) {
            // 异常文本可能含 JDBC URL/凭证，只返回固定脱敏文案；原异常由既有数据源层记录。
            return SemanticValidationCheck.builder()
                    .type(SemanticValidationCheckType.SOURCE_DATABASE)
                    .status(SemanticValidationStatus.BLOCKING).message("数据源执行失败，请检查连接或 SQL")
                    .diagnostic(sourceDiagnostic(traceId)).build();
        }
    }

    /** 使用 headless-core 的运行时 parser config 校验模型 SQL。 */
    private SemanticValidationCheck validateCompiler(SemanticModelValidationReq request,
            EngineType engineType, String sql) {
        try {
            SemanticModelCompiler.validateSql(sql, engineType, request.getModelId(),
                    request.getModelName(), request.getDataSetId());
            return SemanticValidationCheck.builder()
                    .type(SemanticValidationCheckType.SEMANTIC_COMPILER)
                    .status(SemanticValidationStatus.PASSED).message("SuperSonic 语义解析通过").build();
        } catch (SemanticModelCompileException exception) {
            return SemanticValidationCheck.builder()
                    .type(SemanticValidationCheckType.SEMANTIC_COMPILER)
                    .status(SemanticValidationStatus.BLOCKING)
                    .message(exception.getDiagnostic().getUserMessage())
                    .diagnostic(exception.getDiagnostic()).build();
        }
    }

    /** 可选完整模型请求会额外检查维度和指标表达式。 */
    private SemanticValidationCheck validateExpressions(SemanticModelValidationReq request,
            EngineType engineType) {
        ModelReq model = request.getModel();
        if (model == null || model.getModelDetail() == null) {
            return SemanticValidationCheck.builder()
                    .type(SemanticValidationCheckType.FIELD_EXPRESSION)
                    .status(SemanticValidationStatus.SKIPPED).message("SQL 编辑阶段未提交完整字段定义").build();
        }
        try {
            for (Dimension dimension : model.getModelDetail().getDimensions()) {
                if (StringUtils.isNotBlank(dimension.getExpr())) {
                    SemanticModelCompiler.validateExpression(dimension.getExpr(), engineType,
                            request.getModelId(), request.getModelName(), request.getDataSetId());
                }
            }
            for (Measure measure : model.getModelDetail().getMeasures()) {
                if (StringUtils.isNotBlank(measure.getExpr())) {
                    SemanticModelCompiler.validateExpression(measure.getExpr(), engineType,
                            request.getModelId(), request.getModelName(), request.getDataSetId());
                }
            }
            return SemanticValidationCheck.builder()
                    .type(SemanticValidationCheckType.FIELD_EXPRESSION)
                    .status(SemanticValidationStatus.PASSED).message("字段与表达式检查通过").build();
        } catch (SemanticModelCompileException exception) {
            return SemanticValidationCheck.builder()
                    .type(SemanticValidationCheckType.FIELD_EXPRESSION)
                    .status(SemanticValidationStatus.BLOCKING)
                    .message(exception.getDiagnostic().getUserMessage())
                    .diagnostic(exception.getDiagnostic()).build();
        }
    }

    /** 从正式模型请求构建无远程执行的校验请求。 */
    private SemanticModelValidationReq fromModel(ModelReq modelReq) {
        if (modelReq == null || modelReq.getModelDetail() == null) {
            throw new InvalidArgumentException("模型定义不能为空");
        }
        SemanticModelValidationReq request = new SemanticModelValidationReq();
        request.setDatabaseId(modelReq.getDatabaseId());
        request.setModelId(modelReq.getId());
        request.setModelName(modelReq.getName());
        request.setSql(modelReq.getModelDetail().getSqlQuery());
        request.setSqlVariables(modelReq.getModelDetail().getSqlVariables());
        request.setModel(modelReq);
        return request;
    }

    /** 优先使用完整模型中的 SQL，避免前端两个字段不一致。 */
    private String effectiveSql(SemanticModelValidationReq request) {
        ModelDetail detail =
                request.getModel() == null ? null : request.getModel().getModelDetail();
        return detail != null && StringUtils.isNotBlank(detail.getSqlQuery()) ? detail.getSqlQuery()
                : request.getSql();
    }

    /** 远程执行失败使用固定诊断，禁止把 JDBC 异常原文回传。 */
    private SemanticDiagnostic sourceDiagnostic(String traceId) {
        return SemanticDiagnostic.builder().code(SemanticDiagnosticCode.SOURCE_SQL_EXECUTION_FAILED)
                .stage(SemanticDiagnosticStage.PHYSICAL_SQL_EXECUTION)
                .severity(SemanticDiagnosticSeverity.BLOCKING).userMessage("数据源 SQL 执行失败")
                .developerMessage("请通过服务端日志和 traceId 排查").suggestion("请检查数据源连接、只读权限和 SQL 方言")
                .traceId(traceId).build();
    }

    /** 管理型校验接口只允许数据源创建人、管理员或超级管理员调用。 */
    private void requireManagementPermission(DatabaseResp database, User user) {
        if (database == null) {
            throw new InvalidArgumentException("数据库不存在");
        }
        List<String> admins = database.getAdmins() == null ? List.of() : database.getAdmins();
        if (user != null && (user.isSuperAdmin() || admins.contains(user.getName())
                || StringUtils.equalsIgnoreCase(database.getCreatedBy(), user.getName()))) {
            return;
        }
        throw new InvalidPermissionException("您暂无模型校验管理权限");
    }

    /** 内容摘要绑定 SQL、模型和变量，禁止复用旧校验结果。 */
    private String contentDigest(SemanticModelValidationReq request, String sql) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            String input = request.getModelId() + "|" + request.getDatabaseId() + "|" + sql + "|"
                    + String.valueOf(request.getSqlVariables());
            return HexFormat.of().formatHex(digest.digest(input.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 algorithm is unavailable", exception);
        }
    }

    /** 复用请求 traceId；没有 MDC 时生成请求级标识。 */
    private String traceId() {
        String traceId = MDC.get("traceId");
        return StringUtils.isBlank(traceId) ? UUID.randomUUID().toString().replace("-", "")
                : traceId;
    }
}
