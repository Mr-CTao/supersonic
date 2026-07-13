package com.tencent.supersonic.headless.server.rest;

import com.tencent.supersonic.headless.server.semantic.modeling.ModelingDraftExceptionHandler;
import com.tencent.supersonic.headless.server.semantic.modeling.ModelingDraftService;
import com.tencent.supersonic.headless.server.semantic.modeling.ModelingDraftStage4Service;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.hamcrest.Matchers.containsString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * AI 语义建模草稿 Controller 参数边界 MockMvc 测试。
 *
 * <p>
 * 职责说明：验证必填幂等请求头和创建 DTO 在进入鉴权/应用服务前即返回真实 HTTP 400，避免项目通用 异常处理把输入错误降为 200。测试不启动 LLM、数据源或任何正式语义写服务。
 * </p>
 */
class SemanticModelingDraftControllerTest {

    private ModelingDraftService modelingDraftService;
    private ModelingDraftStage4Service stage4Service;
    private MockMvc mockMvc;

    /** 初始化独立 Controller、专用异常处理器和 Mockito 服务。 */
    @BeforeEach
    void setUp() {
        modelingDraftService = mock(ModelingDraftService.class);
        stage4Service = mock(ModelingDraftStage4Service.class);
        mockMvc = MockMvcBuilders
                .standaloneSetup(
                        new SemanticModelingDraftController(modelingDraftService, stage4Service))
                .setControllerAdvice(new ModelingDraftExceptionHandler()).build();
    }

    /** 缺少 Idempotency-Key 时应返回 HTTP 400，且不进入应用服务。 */
    @Test
    void shouldRejectMissingIdempotencyHeaderBeforeService() throws Exception {
        mockMvc.perform(post("/api/semantic/modeling/drafts")
                .contentType(MediaType.APPLICATION_JSON).content(validRequest()))
                .andExpect(status().isBadRequest())
                .andExpect(content().string(containsString("INVALID_REQUEST")));
        verifyNoInteractions(modelingDraftService);
    }

    /** 选表为空等 DTO 约束失败时应返回字段级问题和 HTTP 400。 */
    @Test
    void shouldRejectInvalidBodyBeforeService() throws Exception {
        String invalid = """
                {
                  "sourceType":"DATA_SOURCE",
                  "businessGoal":"",
                  "dataSourceId":1,
                  "selectedTables":[],
                  "chatModelId":1,
                  "includeSampleData":false
                }
                """;
        mockMvc.perform(post("/api/semantic/modeling/drafts").header("Idempotency-Key", "test-key")
                .contentType(MediaType.APPLICATION_JSON).content(invalid))
                .andExpect(status().isBadRequest())
                .andExpect(content().string(containsString("issues")));
        verifyNoInteractions(modelingDraftService);
    }

    /** PostgreSQL 的 varchar(255) 边界应在入库前拦截，避免 256 字符触发数据库异常。 */
    @Test
    void shouldRejectTextLongerThanPostgresColumns() throws Exception {
        String invalid = """
                {
                  "sourceType":"DATA_SOURCE",
                  "title":"%s",
                  "businessGoal":"分析订单趋势",
                  "dataSourceId":1,
                  "databaseName":"demo",
                  "selectedTables":["orders"],
                  "chatModelId":1,
                  "includeSampleData":false
                }
                """.formatted("a".repeat(256));
        mockMvc.perform(post("/api/semantic/modeling/drafts")
                .header("Idempotency-Key", "postgres-column-boundary")
                .contentType(MediaType.APPLICATION_JSON).content(invalid))
                .andExpect(status().isBadRequest())
                .andExpect(content().string(containsString("title")));
        verifyNoInteractions(modelingDraftService);
    }

    /** 畸形 JSON 必须返回稳定 HTTP 400，不能落入全局 HTTP 200 系统错误。 */
    @Test
    void shouldRejectMalformedJsonWithoutEchoingParserDetails() throws Exception {
        mockMvc.perform(post("/api/semantic/modeling/drafts")
                .header("Idempotency-Key", "malformed-json").contentType(MediaType.APPLICATION_JSON)
                .content("{\"sourceType\":\"DATA_SOURCE\",broken}"))
                .andExpect(status().isBadRequest())
                .andExpect(content().string(containsString("MALFORMED_JSON")))
                .andExpect(content().string(containsString("请求体不是合法 JSON")));
        verifyNoInteractions(modelingDraftService);
    }

    /** AI 修订缺少幂等请求头时必须在进入会话和应用服务前返回 HTTP 400。 */
    @Test
    void shouldRejectAiRevisionWithoutIdempotencyHeader() throws Exception {
        mockMvc.perform(post("/api/semantic/modeling/drafts/1/ai-revise")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"instruction\":\"将模型改名为订单分析\",\"baseVersionNo\":1}"))
                .andExpect(status().isBadRequest())
                .andExpect(content().string(containsString("MISSING_HEADER")));
        verifyNoInteractions(stage4Service);
    }

    /** 验证请求必须绑定大于零的不可变版本号。 */
    @Test
    void shouldRejectInvalidValidationVersionBeforeService() throws Exception {
        mockMvc.perform(post("/api/semantic/modeling/drafts/1/validate")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"versionNo\":0,\"validationOptions\":{\"sqlPreviewLimit\":20}}"))
                .andExpect(status().isBadRequest())
                .andExpect(content().string(containsString("versionNo")));
        verifyNoInteractions(stage4Service);
    }

    /** 构造完整但不会进入应用服务的创建请求。 */
    private String validRequest() {
        return """
                {
                  "sourceType":"DATA_SOURCE",
                  "businessGoal":"分析订单趋势",
                  "dataSourceId":1,
                  "databaseName":"demo",
                  "selectedTables":["orders"],
                  "chatModelId":1,
                  "includeSampleData":false
                }
                """;
    }
}
