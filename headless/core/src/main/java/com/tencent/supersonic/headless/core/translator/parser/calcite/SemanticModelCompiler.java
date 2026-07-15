package com.tencent.supersonic.headless.core.translator.parser.calcite;

import com.tencent.supersonic.common.calcite.Configuration;
import com.tencent.supersonic.common.pojo.enums.EngineType;
import com.tencent.supersonic.headless.api.pojo.response.ModelResp;
import com.tencent.supersonic.headless.api.pojo.response.SemanticDiagnostic;
import com.tencent.supersonic.headless.api.pojo.response.SemanticDiagnosticCode;
import com.tencent.supersonic.headless.api.pojo.response.SemanticDiagnosticSeverity;
import com.tencent.supersonic.headless.api.pojo.response.SemanticDiagnosticStage;
import org.apache.calcite.runtime.CalciteContextException;
import org.apache.calcite.sql.SqlNode;
import org.apache.calcite.sql.parser.SqlParseException;
import org.apache.calcite.sql.parser.SqlParser;
import org.apache.calcite.sql.parser.SqlParserPos;
import org.apache.calcite.sql.validate.SqlValidatorException;
import org.apache.calcite.sql.validate.SqlValidatorScope;
import org.slf4j.MDC;

import java.util.Locale;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 无副作用的语义模型编译校验器。
 *
 * <p>
 * 职责说明：SQL 编辑校验与运行时模型构建复用 {@link Configuration#getParserConfig(EngineType)} 和
 * {@link DataModelNode#build(ModelResp, SqlValidatorScope)}，避免维护一套会漂移的关键字白名单。
 * 本类不持有状态，所有方法仅使用调用参数和局部变量，天然线程安全。
 * </p>
 */
public final class SemanticModelCompiler {

    private static final Pattern ENCOUNTERED_TOKEN =
            Pattern.compile("Encountered\\s+\\\"([^\\\"]+)\\\"");
    private static final int TOKEN_MAX_LENGTH = 64;

    private SemanticModelCompiler() {}

    /**
     * 使用运行时相同的 Calcite 方言配置校验数据源 SQL 语法。
     *
     * @param sql 待校验只读 SQL。
     * @param engineType 数据库方言。
     * @param modelId 模型 ID，可为空。
     * @param modelName 模型名称，可为空。
     * @param dataSetId 数据集 ID，可为空。
     * @throws SemanticModelCompileException SQL 无法解析时抛出，cause 保留原始异常。
     */
    public static void validateSql(String sql, EngineType engineType, Long modelId,
            String modelName, Long dataSetId) {
        try {
            SqlParser.create(sql, Configuration.getParserConfig(engineType)).parseQuery();
        } catch (Exception exception) {
            throw wrap(exception, SemanticDiagnosticStage.MODEL_SQL_COMPILE,
                    SemanticDiagnosticCode.MODEL_SQL_PARSE_FAILED, engineType, modelId, modelName,
                    dataSetId);
        }
    }

    /**
     * 使用运行时相同方言配置校验维度或指标表达式语法。
     *
     * @param expression 待校验表达式。
     * @param engineType 数据库方言。
     * @param modelId 模型 ID。
     * @param modelName 模型名称。
     * @param dataSetId 数据集 ID。
     * @throws SemanticModelCompileException 表达式无法解析时抛出。
     */
    public static void validateExpression(String expression, EngineType engineType, Long modelId,
            String modelName, Long dataSetId) {
        try {
            SqlParser.create(expression, Configuration.getParserConfig(engineType))
                    .parseExpression();
        } catch (Exception exception) {
            throw wrap(exception, SemanticDiagnosticStage.METRIC_EXPRESSION_COMPILE,
                    SemanticDiagnosticCode.METRIC_EXPRESSION_INVALID, engineType, modelId,
                    modelName, dataSetId);
        }
    }

    /**
     * 通过运行时 DataModelNode 完整编译模型 SQL 与字段结构。
     *
     * @param model 模型定义。
     * @param scope 当前 Calcite 校验作用域。
     * @param dataSetId 数据集 ID，可为空。
     * @return 编译完成的模型 SqlNode。
     * @throws SemanticModelCompileException 模型 SQL 解析或校验失败时抛出。
     */
    public static SqlNode compileModel(ModelResp model, SqlValidatorScope scope, Long dataSetId) {
        EngineType engineType = EngineType.fromString(model.getModelDetail().getDbType());
        try {
            return DataModelNode.build(model, scope);
        } catch (Exception exception) {
            SemanticDiagnosticCode code = isValidationFailure(exception)
                    ? SemanticDiagnosticCode.MODEL_SQL_VALIDATION_FAILED
                    : SemanticDiagnosticCode.MODEL_SQL_PARSE_FAILED;
            throw wrap(exception, SemanticDiagnosticStage.MODEL_SQL_COMPILE, code, engineType,
                    model.getId(), model.getName(), dataSetId);
        }
    }

    /**
     * 将任意 Calcite 编译异常转换为稳定领域异常。
     *
     * @param exception 原始异常。
     * @param stage 失败阶段。
     * @param code 稳定错误码。
     * @param engineType 方言。
     * @param modelId 模型 ID。
     * @param modelName 模型名称。
     * @param dataSetId 数据集 ID。
     * @return 保留 cause 的领域异常。
     */
    public static SemanticModelCompileException wrap(Exception exception,
            SemanticDiagnosticStage stage, SemanticDiagnosticCode code, EngineType engineType,
            Long modelId, String modelName, Long dataSetId) {
        SqlParserPos position = findParserPosition(exception);
        String token = extractToken(exception);
        SemanticDiagnostic diagnostic = SemanticDiagnostic.builder().code(code).stage(stage)
                .severity(SemanticDiagnosticSeverity.BLOCKING).modelId(modelId).modelName(modelName)
                .dataSetId(dataSetId).engineType(engineType == null ? null : engineType.name())
                .line(position == null ? null : position.getLineNum())
                .column(position == null ? null : position.getColumnNum()).token(token)
                .userMessage("模型 SQL 无法被语义解析器解析")
                .developerMessage("Calcite 无法解析或校验模型 SQL 中的运算符、函数、字段引用或表达式")
                .suggestion(resolveSuggestion(engineType, token)).traceId(traceId()).build();
        return new SemanticModelCompileException(diagnostic, exception);
    }

    /** 判断异常链中是否包含 Calcite validator 异常。 */
    private static boolean isValidationFailure(Throwable throwable) {
        for (Throwable current = throwable; current != null; current = current.getCause()) {
            if (current instanceof SqlValidatorException
                    || current instanceof CalciteContextException) {
                return true;
            }
        }
        return false;
    }

    /** 优先从 Calcite 异常结构读取行列号，避免依赖完整异常文本。 */
    private static SqlParserPos findParserPosition(Throwable throwable) {
        for (Throwable current = throwable; current != null; current = current.getCause()) {
            if (current instanceof SqlParseException parseException) {
                return parseException.getPos();
            }
            if (current instanceof CalciteContextException contextException
                    && contextException.getPosLine() > 0) {
                return new SqlParserPos(contextException.getPosLine(),
                        Math.max(1, contextException.getPosColumn()));
            }
        }
        return null;
    }

    /**
     * Calcite 未提供实际 token getter，使用集中且长度受限的保守适配器提取 Encountered token。
     */
    private static String extractToken(Throwable throwable) {
        for (Throwable current = throwable; current != null; current = current.getCause()) {
            Matcher matcher = ENCOUNTERED_TOKEN.matcher(String.valueOf(current.getMessage()));
            if (matcher.find()) {
                String token = matcher.group(1).replace("\\n", " ").trim().toUpperCase(Locale.ROOT);
                return token.substring(0, Math.min(token.length(), TOKEN_MAX_LENGTH));
            }
        }
        return null;
    }

    /** 仅对已确认等价的 MySQL REGEXP 场景返回确定性建议。 */
    private static String resolveSuggestion(EngineType engineType, String token) {
        if (engineType == EngineType.MYSQL && "REGEXP".equals(token)) {
            return "当前语义解析器不支持 REGEXP，请改为 RLIKE 后重新校验";
        }
        return "当前语义解析器不支持该语法，请改写后重新校验";
    }

    /** 复用请求 traceId；无请求上下文时生成最小本地追踪标识。 */
    private static String traceId() {
        String traceId = MDC.get("traceId");
        return traceId == null || traceId.isBlank() ? UUID.randomUUID().toString().replace("-", "")
                : traceId;
    }
}
