package com.tencent.supersonic.headless.server.semantic.diagnostic;

import com.tencent.supersonic.headless.api.pojo.response.ModelHealthResp;
import com.tencent.supersonic.headless.api.pojo.response.RefreshStatus;
import com.tencent.supersonic.headless.api.pojo.response.SemanticValidationResult;
import com.tencent.supersonic.headless.api.pojo.response.SemanticValidationStatus;
import org.junit.jupiter.api.Test;

import java.util.Collections;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * 验证模型健康状态的原子更新和快照隔离。
 */
class ModelHealthServiceTest {

    @Test
    void shouldExposeValidationAndRefreshStateWithoutLeakingMutableState() {
        ModelHealthService service = new ModelHealthService();
        SemanticValidationResult validation = SemanticValidationResult.builder()
                .overallStatus(SemanticValidationStatus.BLOCKING).contentDigest("digest").build();

        service.recordValidation(7L, validation);
        service.recordSchemaCache(Collections.singletonList(7L), RefreshStatus.SUCCEEDED);
        ModelHealthResp first = service.getHealth(7L);
        first.setLastErrorCode("tampered");

        ModelHealthResp second = service.getHealth(7L);
        assertEquals(SemanticValidationStatus.BLOCKING, second.getCompileStatus());
        assertEquals(RefreshStatus.SUCCEEDED, second.getSchemaCacheStatus());
        assertEquals("digest", second.getContentDigest());
        assertNull(second.getLastErrorCode());
    }
}
