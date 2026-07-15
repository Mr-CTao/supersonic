package com.tencent.supersonic.headless.core.translator.parser.calcite;

import com.tencent.supersonic.common.pojo.enums.EngineType;
import com.tencent.supersonic.headless.api.pojo.ModelDetail;
import com.tencent.supersonic.headless.api.pojo.response.DatabaseResp;
import com.tencent.supersonic.headless.api.pojo.response.ModelResp;
import com.tencent.supersonic.headless.api.pojo.response.SemanticDiagnosticCode;
import com.tencent.supersonic.headless.api.pojo.response.SemanticDiagnosticStage;
import org.apache.calcite.sql.validate.SqlValidatorScope;
import org.junit.jupiter.api.Test;

import java.util.Collections;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 语义模型编译校验器回归测试。
 *
 * <p>
 * 职责说明：稳定复现“MySQL 数据源接受 REGEXP，但 SuperSonic 当前 Calcite 配置拒绝该语法”的 真实根因，并验证运行时 SqlBuilder 不再返回缺少
 * table 的半成品对象。测试只执行内存解析， 不连接数据源、不写正式语义资产，因此无共享状态和并发保护需求。
 * </p>
 */
class SemanticModelCompilerTest {

    private static final String REGEXP_SQL =
            "SELECT * FROM inventory WHERE material_code REGEXP '^[A-Z]+$'";
    private static final String RLIKE_SQL =
            "SELECT * FROM inventory WHERE material_code RLIKE '^[A-Z]+$'";

    /** REGEXP 必须得到带位置和建议的稳定模型 SQL 解析诊断。 */
    @Test
    void shouldReportStructuredDiagnosticForUnsupportedRegexp() {
        SemanticModelCompileException exception =
                assertThrows(SemanticModelCompileException.class, () -> SemanticModelCompiler
                        .validateSql(REGEXP_SQL, EngineType.MYSQL, 8L, "WMS库存明细", 4L));

        assertNotNull(exception.getCause());
        assertEquals(SemanticDiagnosticCode.MODEL_SQL_PARSE_FAILED,
                exception.getDiagnostic().getCode());
        assertEquals(SemanticDiagnosticStage.MODEL_SQL_COMPILE,
                exception.getDiagnostic().getStage());
        assertEquals(8L, exception.getDiagnostic().getModelId());
        assertEquals("REGEXP", exception.getDiagnostic().getToken());
        assertTrue(exception.getDiagnostic().getLine() > 0);
        assertTrue(exception.getDiagnostic().getColumn() > 0);
        assertTrue(exception.getDiagnostic().getSuggestion().contains("RLIKE"));
    }

    /** 与现场等价的 RLIKE 写法必须沿用同一 Calcite parser config 并成功通过。 */
    @Test
    void shouldAcceptRlikeWithRuntimeMysqlParserConfig() {
        assertDoesNotThrow(() -> SemanticModelCompiler.validateSql(RLIKE_SQL, EngineType.MYSQL, 8L,
                "WMS库存明细", 4L));
    }

    /** SqlBuilder 遇到非法模型 SQL 时必须抛出领域异常，不能返回 table 为 null 的对象。 */
    @Test
    void shouldNotReturnIncompleteTableViewWhenModelSqlIsInvalid() {
        ModelResp model = model(REGEXP_SQL);
        SqlValidatorScope scope = mock(SqlValidatorScope.class);
        S2CalciteSchema schema = mock(S2CalciteSchema.class);
        com.tencent.supersonic.headless.core.pojo.Ontology ontology =
                mock(com.tencent.supersonic.headless.core.pojo.Ontology.class);
        when(schema.getOntology()).thenReturn(ontology);
        when(ontology.getDatabase()).thenReturn(DatabaseResp.builder().type("MYSQL").build());

        SemanticModelCompileException exception = assertThrows(SemanticModelCompileException.class,
                () -> SqlBuilder.renderOne(Collections.emptySet(), Collections.emptySet(), model,
                        scope, schema));

        assertEquals(SemanticDiagnosticCode.MODEL_SQL_PARSE_FAILED,
                exception.getDiagnostic().getCode());
        assertNotNull(exception.getCause());
    }

    /** 构造最小模型对象；空维度/指标集合避免测试引入无关字段表达式校验。 */
    private ModelResp model(String sql) {
        ModelDetail detail = new ModelDetail();
        detail.setDbType("MYSQL");
        detail.setSqlQuery(sql);
        ModelResp model = ModelResp.builder().modelDetail(detail).build();
        model.setId(8L);
        model.setName("WMS库存明细");
        return model;
    }
}
