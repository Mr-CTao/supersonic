package com.tencent.supersonic.headless.server.semantic.diagnostic;

import com.tencent.supersonic.common.pojo.User;
import com.tencent.supersonic.common.pojo.exception.InvalidPermissionException;
import com.tencent.supersonic.headless.api.pojo.ModelDetail;
import com.tencent.supersonic.headless.api.pojo.request.ModelReq;
import com.tencent.supersonic.headless.api.pojo.response.DatabaseResp;
import com.tencent.supersonic.headless.api.pojo.response.SemanticQueryResp;
import com.tencent.supersonic.headless.api.pojo.response.SemanticValidationCheckType;
import com.tencent.supersonic.headless.api.pojo.response.SemanticValidationStatus;
import com.tencent.supersonic.headless.server.semantic.modeling.ModelingSqlReadOnlyChecker;
import com.tencent.supersonic.headless.core.translator.parser.calcite.SemanticModelCompileException;
import com.tencent.supersonic.headless.server.service.DatabaseService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 模型 SQL 双重校验服务测试。
 *
 * <p>
 * 职责说明：验证数据源执行与 Calcite 编译独立报告、管理权限 fail-closed，以及网络失败不会 吞掉本地编译结果。所有依赖均为 mock，不执行真实
 * SQL、不写数据库，无并发共享状态。
 * </p>
 */
class SemanticModelValidationServiceTest {

    private DatabaseService databaseService;
    private ModelHealthService modelHealthService;
    private SemanticModelValidationService service;
    private User admin;

    @BeforeEach
    void setUp() {
        databaseService = mock(DatabaseService.class);
        modelHealthService = mock(ModelHealthService.class);
        service = new SemanticModelValidationService(databaseService,
                new ModelingSqlReadOnlyChecker(), modelHealthService);
        admin = User.getDefaultUser();
    }

    /** 数据源执行通过但 Calcite 拒绝 REGEXP 时必须保留两个独立结果。 */
    @Test
    void shouldKeepSourcePassWhenSemanticCompilerFails() {
        when(databaseService.getDatabase(7L)).thenReturn(database());
        when(databaseService.executeSql(any(), eq(admin))).thenReturn(new SemanticQueryResp());

        SemanticModelValidationReq request =
                request("SELECT * FROM inventory WHERE material_code REGEXP '^[A-Z]+$'");
        var result = service.validate(request, admin);

        assertEquals(SemanticValidationStatus.BLOCKING, result.getOverallStatus());
        assertEquals(SemanticValidationStatus.PASSED,
                status(result, SemanticValidationCheckType.SOURCE_DATABASE));
        assertEquals(SemanticValidationStatus.BLOCKING,
                status(result, SemanticValidationCheckType.SEMANTIC_COMPILER));
    }

    /** 数据源不可达时本地 Calcite 校验仍须执行并保留 PASSED。 */
    @Test
    void shouldKeepCompilerResultWhenSourceDatabaseIsUnavailable() {
        when(databaseService.getDatabase(7L)).thenReturn(database());
        when(databaseService.executeSql(any(), eq(admin)))
                .thenThrow(new RuntimeException("jdbc:mysql://host/db?password=secret"));

        var result = service.validate(request("SELECT * FROM inventory"), admin);

        assertEquals(SemanticValidationStatus.BLOCKING,
                status(result, SemanticValidationCheckType.SOURCE_DATABASE));
        assertEquals(SemanticValidationStatus.PASSED,
                status(result, SemanticValidationCheckType.SEMANTIC_COMPILER));
    }

    /** 非数据源管理员不得调用带远程执行能力的模型校验接口。 */
    @Test
    void shouldRejectViewerWithoutManagementPermission() {
        when(databaseService.getDatabase(7L)).thenReturn(database());

        assertThrows(InvalidPermissionException.class,
                () -> service.validate(request("SELECT 1"), User.getVisitUser()));
    }

    /** 保存门禁必须继续抛出结构化编译异常，供统一异常处理器返回诊断对象。 */
    @Test
    void shouldKeepStructuredDiagnosticAtPublicationGate() {
        when(databaseService.getDatabase(7L)).thenReturn(database());
        ModelDetail detail = new ModelDetail();
        detail.setSqlQuery("SELECT * FROM inventory WHERE material_code REGEXP '^[A-Z]+$'");
        ModelReq model = new ModelReq();
        model.setId(8L);
        model.setName("WMS库存明细");
        model.setDatabaseId(7L);
        model.setModelDetail(detail);

        SemanticModelCompileException exception = assertThrows(SemanticModelCompileException.class,
                () -> service.validateForPublication(model, admin));

        assertEquals("MODEL_SQL_PARSE_FAILED", exception.getDiagnostic().getCode().name());
        assertEquals("REGEXP", exception.getDiagnostic().getToken());
    }

    /** 查找指定检查项状态。 */
    private SemanticValidationStatus status(
            com.tencent.supersonic.headless.api.pojo.response.SemanticValidationResult result,
            SemanticValidationCheckType type) {
        return result.getChecks().stream().filter(check -> check.getType() == type).findFirst()
                .orElseThrow().getStatus();
    }

    /** 构造管理员可管理的 MySQL 数据源。 */
    private DatabaseResp database() {
        DatabaseResp database = DatabaseResp.builder().id(7L).name("WMS").type("MYSQL")
                .admins(List.of("admin")).build();
        database.setCreatedBy("owner");
        return database;
    }

    /** 构造 SQL 编辑阶段请求。 */
    private SemanticModelValidationReq request(String sql) {
        SemanticModelValidationReq request = new SemanticModelValidationReq();
        request.setDatabaseId(7L);
        request.setModelId(8L);
        request.setDataSetId(4L);
        request.setModelName("WMS库存明细");
        request.setSql(sql);
        return request;
    }
}
