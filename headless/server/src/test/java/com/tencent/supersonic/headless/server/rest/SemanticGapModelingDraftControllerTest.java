package com.tencent.supersonic.headless.server.rest;

import com.github.pagehelper.PageInfo;
import com.tencent.supersonic.auth.api.authentication.utils.UserHolder;
import com.tencent.supersonic.common.pojo.User;
import com.tencent.supersonic.headless.server.persistence.dataobject.SemanticGapDO;
import com.tencent.supersonic.headless.server.semantic.gap.SemanticGapQueryReq;
import com.tencent.supersonic.headless.server.semantic.gap.SemanticGapService;
import com.tencent.supersonic.headless.server.semantic.modeling.ModelingDraftConstants;
import com.tencent.supersonic.headless.server.semantic.modeling.ModelingDraftExceptionHandler;
import com.tencent.supersonic.headless.server.semantic.modeling.ModelingDraftGenerateReq;
import com.tencent.supersonic.headless.server.semantic.modeling.ModelingDraftResp;
import com.tencent.supersonic.headless.server.semantic.modeling.ModelingDraftService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.MockedStatic;
import org.mockito.Mockito;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Gap 专用建模草稿入口的路由与服务端来源补全测试。
 *
 * <p>
 * 职责说明：证明阶段 2 OpenAPI 列表兼容路径仍存在，同时阶段 3 草稿写入口只暴露在认证 API； 请求体无需重复提交 sourceType/sourceId。测试不连接
 * LLM、数据源或正式语义写服务。
 * </p>
 */
class SemanticGapModelingDraftControllerTest {

    private ModelingDraftService modelingDraftService;
    private SemanticGapService semanticGapService;
    private MockMvc mockMvc;

    /** 初始化两个独立 Controller，以真实 Spring 路由规则验证 API 与 OpenAPI 的边界。 */
    @BeforeEach
    void setUp() {
        modelingDraftService = mock(ModelingDraftService.class);
        semanticGapService = mock(SemanticGapService.class);
        mockMvc = MockMvcBuilders
                .standaloneSetup(new SemanticGapModelingDraftController(modelingDraftService),
                        new SemanticGapController(semanticGapService))
                .setControllerAdvice(new ModelingDraftExceptionHandler()).build();
    }

    /** Gap 专用 API 应由路径补齐来源并返回 202，调用方无需在请求体重复传 sourceType。 */
    @Test
    void shouldCompleteGapSourceFromAuthenticatedApiPath() throws Exception {
        User user = User.getDefaultUser();
        ModelingDraftResp accepted = ModelingDraftResp.builder().id(31L)
                .status(ModelingDraftConstants.STATUS_GENERATING).build();
        when(modelingDraftService.create(any(ModelingDraftGenerateReq.class), eq("gap-key"),
                eq(user))).thenReturn(accepted);

        try (MockedStatic<UserHolder> userHolder = Mockito.mockStatic(UserHolder.class)) {
            userHolder.when(() -> UserHolder.findUser(any(HttpServletRequest.class),
                    any(HttpServletResponse.class))).thenReturn(user);
            mockMvc.perform(post("/api/semantic/gaps/12/drafts")
                    .header("Idempotency-Key", "gap-key").contentType(MediaType.APPLICATION_JSON)
                    .content(validBodyWithoutSource())).andExpect(status().isAccepted());
        }

        ArgumentCaptor<ModelingDraftGenerateReq> requestCaptor =
                ArgumentCaptor.forClass(ModelingDraftGenerateReq.class);
        verify(modelingDraftService).create(requestCaptor.capture(), eq("gap-key"), eq(user));
        assertEquals(ModelingDraftConstants.SOURCE_SEMANTIC_GAP,
                requestCaptor.getValue().getSourceType());
        assertEquals(12L, requestCaptor.getValue().getSourceId());
    }

    /** OpenAPI 不得映射阶段 3 写入口，避免匿名调用绕过认证过滤器。 */
    @Test
    void shouldNotExposeDraftCreationOnOpenApiAlias() throws Exception {
        mockMvc.perform(
                post("/openapi/semantic/gaps/12/drafts").header("Idempotency-Key", "anonymous-key")
                        .contentType(MediaType.APPLICATION_JSON).content(validBodyWithoutSource()))
                .andExpect(status().isNotFound());
        verifyNoMoreInteractions(modelingDraftService);
    }

    /** 阶段 2 既有 OpenAPI 列表路径必须继续可用。 */
    @Test
    void shouldKeepExistingGapOpenApiListRoute() throws Exception {
        when(semanticGapService.query(any(SemanticGapQueryReq.class)))
                .thenReturn(new PageInfo<SemanticGapDO>());
        mockMvc.perform(get("/openapi/semantic/gaps")).andExpect(status().isOk());
        verify(semanticGapService).query(any(SemanticGapQueryReq.class));
    }

    /** 构造不含来源字段、但其余 Bean Validation 约束完整的 Gap 创建请求。 */
    private String validBodyWithoutSource() {
        return """
                {
                  "businessGoal":"分析缺口中的订单趋势",
                  "dataSourceId":1,
                  "databaseName":"demo",
                  "selectedTables":["orders"],
                  "chatModelId":1,
                  "includeSampleData":false
                }
                """;
    }
}
