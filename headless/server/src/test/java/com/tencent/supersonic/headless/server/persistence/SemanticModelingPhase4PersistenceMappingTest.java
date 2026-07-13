package com.tencent.supersonic.headless.server.persistence;

import com.baomidou.mybatisplus.annotation.TableName;
import com.tencent.supersonic.headless.server.persistence.dataobject.SemanticModelingDraftDO;
import com.tencent.supersonic.headless.server.persistence.dataobject.SemanticModelingDraftVersionDO;
import com.tencent.supersonic.headless.server.persistence.dataobject.SemanticModelingValidationReportDO;
import com.tencent.supersonic.headless.server.persistence.mapper.SemanticModelingDraftMapper;
import com.tencent.supersonic.headless.server.persistence.mapper.SemanticModelingDraftVersionMapper;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.Date;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 阶段 4 草稿持久化映射契约测试。
 *
 * <p>
 * 职责说明：以轻量反射检查验证报告表名、新增审计字段和参数化原子 SQL 的关键并发条件，避免后续重构静默丢失版本或状态门禁。本测试不连接正式数据库， 也不会写入正式语义资产。
 * </p>
 */
class SemanticModelingPhase4PersistenceMappingTest {

    /** 验证新增 DO 与阶段 4 Schema 字段保持一致。 */
    @Test
    void shouldExposePhase4PersistenceFields() throws Exception {
        TableName reportTable =
                SemanticModelingValidationReportDO.class.getAnnotation(TableName.class);
        assertNotNull(reportTable);
        assertEquals("s2_semantic_validation_report", reportTable.value());

        assertEquals(Long.class, SemanticModelingDraftDO.class
                .getDeclaredField("submittedValidationReportId").getType());
        assertEquals(String.class, SemanticModelingDraftDO.class
                .getDeclaredField("submissionIdempotencyKey").getType());
        assertEquals(String.class, SemanticModelingDraftVersionDO.class
                .getDeclaredField("requestIdempotencyKey").getType());
        assertEquals(Integer.class, SemanticModelingDraftVersionDO.class
                .getDeclaredField("resultLockVersion").getType());
        assertEquals(Integer.class, SemanticModelingValidationReportDO.class
                .getDeclaredField("activeMarker").getType());
    }

    /** 验证 AI 修订和提交待审批均使用数据库状态与版本条件更新。 */
    @Test
    void shouldKeepAtomicVersionAndSubmissionConditions() throws Exception {
        Method revise = SemanticModelingDraftMapper.class.getMethod("updateDraftWithAiVersion",
                Long.class, Integer.class, String.class, Integer.class, Long.class, String.class,
                Date.class);
        String reviseSql = annotationSql(revise.getAnnotation(Update.class).value());
        assertTrue(reviseSql.contains("status = 'DRAFT'"));
        assertTrue(reviseSql.contains("current_version_no = #{expectedVersionNo}"));
        assertTrue(reviseSql.contains("lock_version = lock_version + 1"));

        Method submit = SemanticModelingDraftMapper.class.getMethod("submitForApproval", Long.class,
                Integer.class, Long.class, String.class, String.class, Date.class);
        String submitSql = annotationSql(submit.getAnnotation(Update.class).value());
        assertTrue(submitSql.contains("status = 'PENDING_APPROVAL'"));
        assertTrue(submitSql.contains("status = 'DRAFT'"));
        assertTrue(submitSql.contains("current_version_no = #{expectedVersionNo}"));
    }

    /** 验证版本幂等查询同时限定草稿 ID 和请求幂等键。 */
    @Test
    void shouldScopeRevisionReplayToDraft() throws Exception {
        Method method = SemanticModelingDraftVersionMapper.class
                .getMethod("selectByDraftIdAndRequestIdempotencyKey", Long.class, String.class);
        String selectSql = annotationSql(method.getAnnotation(Select.class).value());
        assertTrue(selectSql.contains("draft_id = #{draftId}"));
        assertTrue(selectSql.contains("request_idempotency_key = #{requestIdempotencyKey}"));
    }

    /** 合并 MyBatis 注解中的 SQL 片段，便于稳定断言关键条件。 */
    private String annotationSql(String[] fragments) {
        return String.join(" ", Arrays.asList(fragments));
    }
}
